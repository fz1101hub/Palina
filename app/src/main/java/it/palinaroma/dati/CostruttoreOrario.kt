package it.palinaroma.dati

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipFile

data class Punto(val lat: Double, val lon: Double)

/** Dove finisce l'orario letto dal file GTFS (nell'app: il database SQLite). */
interface DestinazioneOrario {
    fun linea(id: String, nome: String, descrizione: String, tipo: Int, colore: String, coloreTesto: String)
    fun fermata(id: String, codice: String, nome: String, lat: Double, lon: Double)
    fun corsa(id: String, linea: String, direzione: Int, capolinea: String)
    fun percorso(linea: String, direzione: Int, capolinea: String, forma: String, fermate: List<String>)
    fun fermataLinea(fermata: String, linea: String, direzione: Int, capolinea: String)
    fun forma(id: String, punti: List<Punto>)
}

/**
 * Trasforma l'orario statico GTFS di Roma Mobilita' (circa 280 MB scompattato)
 * in poche tabelle compatte: linee, fermate, capolinea di ogni corsa, il
 * percorso tipico di ogni linea in ogni direzione e il suo tracciato.
 *
 * La direzione viene da direction_id; se manca o e' sempre la stessa, si ricava
 * dal percorso: le corse che vanno verso il capolinea piu' frequente della linea
 * sono la direzione 0, quelle che se ne allontanano la direzione 1.
 *
 * Il percorso tipico e' quello della corsa con piu' fermate tra quelle della
 * stessa linea e direzione. stop_times.txt viene letto due volte in streaming
 * per non tenerlo in memoria.
 */
class CostruttoreOrario(
    private val zip: ZipFile,
    private val destinazione: DestinazioneOrario,
    private val avanzamento: (String, Float) -> Unit = { _, _ -> },
) {
    private val indiceCorse = HashMap<String, Int>(1 shl 19)
    private val corsaId = ArrayList<String>()
    private val corsaLinea = ArrayList<String>()
    private val corsaDirezioneGrezza = ArrayList<Int>()
    private val corsaForma = ArrayList<String>()
    private val corsaCapolinea = ArrayList<String>()
    private val testiUnici = HashMap<String, String>()
    private val nomiFermate = HashMap<String, String>()
    private val posizioniFermate = HashMap<String, Punto>()

    fun costruisci() {
        avanzamento("Linee", 0f)
        leggiLinee()
        avanzamento("Fermate", 0.03f)
        leggiFermate()
        avanzamento("Corse", 0.08f)
        leggiCorse()

        // 1. quante fermate ha ogni corsa, dove parte e dove arriva
        avanzamento("Fermate delle linee (1/2)", 0.25f)
        val n = corsaId.size
        val conteggi = IntArray(n)
        val primaPosizione = IntArray(n) { Int.MAX_VALUE }
        val ultimaPosizione = IntArray(n) { Int.MIN_VALUE }
        val primaFermata = arrayOfNulls<String>(n)
        val ultimaFermata = arrayOfNulls<String>(n)
        leggiOrari(0.25f, 0.55f) { corsa, fermata, posizione ->
            conteggi[corsa]++
            val p = posizione ?: conteggi[corsa]
            if (p < primaPosizione[corsa]) {
                primaPosizione[corsa] = p
                primaFermata[corsa] = unico(fermata)
            }
            if (p > ultimaPosizione[corsa]) {
                ultimaPosizione[corsa] = p
                ultimaFermata[corsa] = unico(fermata)
            }
        }

        // 2. la direzione di ogni corsa e lo schema (linea + direzione)
        val direzioni = assegnaDirezioni(primaFermata, ultimaFermata)
        val schemi = HashMap<String, Int>()
        val schemaLinea = ArrayList<String>()
        val schemaDirezione = ArrayList<Int>()
        val corsaSchema = IntArray(n)
        val capolinea = Array(n) { c ->
            corsaCapolinea[c].ifEmpty { ultimaFermata[c]?.let { nomiFermate[it] }.orEmpty() }
        }
        for (c in 0 until n) {
            corsaSchema[c] = schemi.getOrPut(corsaLinea[c] + '\u0000' + direzioni[c]) {
                schemaLinea.add(corsaLinea[c])
                schemaDirezione.add(direzioni[c])
                schemaLinea.size - 1
            }
            destinazione.corsa(corsaId[c], corsaLinea[c], direzioni[c], capolinea[c])
        }

        val migliore = IntArray(schemaLinea.size) { -1 }
        for (corsa in 0 until n) {
            val schema = corsaSchema[corsa]
            if (conteggi[corsa] > 0 && (migliore[schema] < 0 || conteggi[corsa] > conteggi[migliore[schema]])) {
                migliore[schema] = corsa
            }
        }

        // 3. le fermate di ogni linea e la sequenza del percorso tipico
        avanzamento("Fermate delle linee (2/2)", 0.55f)
        val fermateSchemi = HashMap<String, HashSet<Int>>()
        val sequenze = HashMap<Int, ArrayList<Pair<Int, String>>>()
        for (corsa in migliore) if (corsa >= 0) sequenze[corsa] = ArrayList()
        leggiOrari(0.55f, 0.85f) { corsa, fermata, posizione ->
            fermateSchemi.getOrPut(unico(fermata)) { HashSet() }.add(corsaSchema[corsa])
            sequenze[corsa]?.let { it.add((posizione ?: it.size) to fermata) }
        }

        for (schema in migliore.indices) {
            val corsa = migliore[schema]
            if (corsa < 0) continue
            val fermate = sequenze[corsa].orEmpty().sortedBy { it.first }.map { it.second }
            destinazione.percorso(
                schemaLinea[schema], schemaDirezione[schema],
                capolinea[corsa], corsaForma[corsa], fermate,
            )
        }
        for ((fermata, schemiFermata) in fermateSchemi) {
            for (schema in schemiFermata) {
                val corsa = migliore[schema]
                val testo = if (corsa >= 0) capolinea[corsa] else ""
                destinazione.fermataLinea(fermata, schemaLinea[schema], schemaDirezione[schema], testo)
            }
        }

        avanzamento("Tracciati", 0.85f)
        val forme = migliore.filter { it >= 0 }.map { corsaForma[it] }.filter { it.isNotEmpty() }.toHashSet()
        leggiForme(forme)
        avanzamento("Fatto", 1f)
    }

    /**
     * La direzione di ogni corsa. Se una linea ha almeno due valori di
     * direction_id si usano quelli; altrimenti 0 per le corse che finiscono piu'
     * vicine al capolinea piu' frequente di quanto partano, 1 per le altre.
     */
    private fun assegnaDirezioni(prima: Array<String?>, ultima: Array<String?>): IntArray {
        val direzioni = IntArray(corsaId.size)
        val perLinea = HashMap<String, ArrayList<Int>>()
        for (c in corsaId.indices) perLinea.getOrPut(corsaLinea[c]) { ArrayList() }.add(c)
        for (corse in perLinea.values) {
            val valori = corse.map { corsaDirezioneGrezza[it] }.filter { it >= 0 }.toSet()
            if (valori.size >= 2) {
                corse.forEach { direzioni[it] = corsaDirezioneGrezza[it].coerceAtLeast(0) }
                continue
            }
            val capolinea = corse.mapNotNull { ultima[it] }.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key?.let { posizioniFermate[it] }
            for (c in corse) {
                val da = prima[c]?.let { posizioniFermate[it] }
                val a = ultima[c]?.let { posizioniFermate[it] }
                direzioni[c] = if (capolinea == null || da == null || a == null) {
                    0
                } else {
                    val partenza = distanzaMetri(da.lat, da.lon, capolinea.lat, capolinea.lon)
                    val arrivo = distanzaMetri(a.lat, a.lon, capolinea.lat, capolinea.lon)
                    if (arrivo <= partenza) 0 else 1
                }
            }
        }
        return direzioni
    }

    private fun apri(nome: String): LettoreCsv? {
        val voce = zip.entries().asSequence()
            .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == nome } ?: return null
        return LettoreCsv(BufferedReader(InputStreamReader(zip.getInputStream(voce), Charsets.UTF_8), 1 shl 16))
    }

    private fun unico(testo: String): String = testiUnici.getOrPut(testo) { testo }

    private fun leggiLinee() {
        apri("routes.txt")?.use { csv ->
            val iId = csv.indice("route_id")
            val iNome = csv.indice("route_short_name")
            val iDescrizione = csv.indice("route_long_name")
            val iTipo = csv.indice("route_type")
            val iColore = csv.indice("route_color")
            val iTesto = csv.indice("route_text_color")
            while (true) {
                val r = csv.prossima() ?: break
                val id = r.campo(iId)
                if (id.isEmpty()) continue
                destinazione.linea(
                    id, r.campo(iNome).ifEmpty { id }, r.campo(iDescrizione),
                    r.campo(iTipo).toIntOrNull() ?: 3, r.campo(iColore), r.campo(iTesto),
                )
            }
        } ?: error("Nell'orario manca routes.txt")
    }

    private fun leggiFermate() {
        apri("stops.txt")?.use { csv ->
            val iId = csv.indice("stop_id")
            val iCodice = csv.indice("stop_code")
            val iNome = csv.indice("stop_name")
            val iLat = csv.indice("stop_lat")
            val iLon = csv.indice("stop_lon")
            val iTipo = csv.indice("location_type")
            while (true) {
                val r = csv.prossima() ?: break
                val tipo = r.campo(iTipo)
                if (tipo.isNotEmpty() && tipo != "0") continue  // stazioni e ingressi
                val id = r.campo(iId)
                val lat = r.campo(iLat).toDoubleOrNull() ?: continue
                val lon = r.campo(iLon).toDoubleOrNull() ?: continue
                if (id.isEmpty()) continue
                val nome = r.campo(iNome)
                nomiFermate[id] = nome
                posizioniFermate[id] = Punto(lat, lon)
                destinazione.fermata(id, r.campo(iCodice).ifEmpty { id }, nome, lat, lon)
            }
        } ?: error("Nell'orario manca stops.txt")
    }

    private fun leggiCorse() {
        apri("trips.txt")?.use { csv ->
            val iId = csv.indice("trip_id")
            val iLinea = csv.indice("route_id")
            val iCapolinea = csv.indice("trip_headsign")
            val iDirezione = csv.indice("direction_id")
            val iForma = csv.indice("shape_id")
            while (true) {
                val r = csv.prossima() ?: break
                val id = r.campo(iId)
                val linea = r.campo(iLinea)
                if (id.isEmpty() || linea.isEmpty() || id in indiceCorse) continue
                indiceCorse[id] = corsaId.size
                corsaId.add(id)
                corsaLinea.add(unico(linea))
                corsaDirezioneGrezza.add(r.campo(iDirezione).toIntOrNull() ?: -1)
                corsaForma.add(unico(r.campo(iForma)))
                corsaCapolinea.add(unico(r.campo(iCapolinea)))
            }
        } ?: error("Nell'orario manca trips.txt")
    }

    /** Scorre stop_times.txt chiamando [azione] con (indice corsa, fermata, stop_sequence). */
    private inline fun leggiOrari(da: Float, a: Float, azione: (Int, String, Int?) -> Unit) {
        apri("stop_times.txt")?.use { csv ->
            val iCorsa = csv.indice("trip_id")
            val iFermata = csv.indice("stop_id")
            val iPosizione = csv.indice("stop_sequence")
            var righe = 0L
            while (true) {
                val r = csv.prossima() ?: break
                righe++
                if (righe % 250_000 == 0L) {
                    // stop_times di Roma ha alcuni milioni di righe: stima prudente
                    avanzamento("Fermate delle linee", da + (a - da) * minOf(0.95f, righe / 9_000_000f))
                }
                val corsa = indiceCorse[r.campo(iCorsa)] ?: continue
                azione(corsa, r.campo(iFermata), r.campo(iPosizione).toIntOrNull())
            }
        } ?: error("Nell'orario manca stop_times.txt")
    }

    private fun leggiForme(necessarie: Set<String>) {
        if (necessarie.isEmpty()) return
        val punti = HashMap<String, ArrayList<Triple<Int, Double, Double>>>()
        apri("shapes.txt")?.use { csv ->
            val iId = csv.indice("shape_id")
            val iLat = csv.indice("shape_pt_lat")
            val iLon = csv.indice("shape_pt_lon")
            val iPosizione = csv.indice("shape_pt_sequence")
            while (true) {
                val r = csv.prossima() ?: break
                val id = r.campo(iId)
                if (id !in necessarie) continue
                val lat = r.campo(iLat).toDoubleOrNull() ?: continue
                val lon = r.campo(iLon).toDoubleOrNull() ?: continue
                val lista = punti.getOrPut(id) { ArrayList() }
                lista.add(Triple(r.campo(iPosizione).toIntOrNull() ?: lista.size, lat, lon))
            }
        } ?: return  // senza tracciati la mappa unira' le fermate
        for ((id, lista) in punti) {
            destinazione.forma(id, semplifica(lista.sortedBy { it.first }.map { Punto(it.second, it.third) }))
        }
    }

    /** Toglie i punti a meno di 8 metri dal precedente: il tracciato resta uguale a vista. */
    private fun semplifica(punti: List<Punto>): List<Punto> {
        if (punti.size < 3) return punti
        val risultato = ArrayList<Punto>(punti.size / 2)
        risultato.add(punti.first())
        for (i in 1 until punti.size - 1) {
            val ultimo = risultato.last()
            if (distanzaMetri(ultimo.lat, ultimo.lon, punti[i].lat, punti[i].lon) >= 8.0) risultato.add(punti[i])
        }
        risultato.add(punti.last())
        return risultato
    }
}
