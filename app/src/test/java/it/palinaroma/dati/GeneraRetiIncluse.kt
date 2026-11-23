package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Passa a [dentro] l'orario di un'altra rete con i codici di fermate, linee e
 * tracciati preceduti dal prefisso della rete ("C:", "F:", "T:"): nel database
 * delle reti stanno insieme e non si devono confondere tra loro ne' con Roma.
 *
 * Se c'e' [area], tiene solo le fermate al suo interno (le stazioni Trenitalia
 * del Lazio, non di tutta Italia) e solo linee, percorsi e tracciati che vi
 * passano. Le corse non servono: per queste reti linea, direzione e capolinea
 * li dice gia' Transitous. Linee, percorsi e tracciati si scrivono in [completa].
 */
class FiltroRete(
    private val rete: Rete,
    private val dentro: DestinazioneOrario,
    private val area: ((Double, Double) -> Boolean)? = null,
) : DestinazioneOrario {
    private data class DatiLinea(val nome: String, val descrizione: String, val tipo: Int, val colore: String, val coloreTesto: String)
    private data class DatiPercorso(val linea: String, val direzione: Int, val capolinea: String, val forma: String, val fermate: List<String>)

    private val linee = LinkedHashMap<String, DatiLinea>()
    private val percorsi = ArrayList<DatiPercorso>()
    private val forme = HashMap<String, List<Punto>>()
    private val fermateTenute = HashSet<String>()
    private val lineeUsate = HashSet<String>()

    var fermate = 0
        private set

    private fun p(id: String) = if (id.isEmpty()) id else rete.prefisso + id

    override fun linea(id: String, nome: String, descrizione: String, tipo: Int, colore: String, coloreTesto: String) {
        // i treni Cotral: "ROMALIDO" diventa "Metromare"; il nome del GTFS resta nella descrizione,
        // perche' e' quello che serve per chiedere ad ASTRAL ritardi e soppressioni
        linee[id] = if (rete == Rete.COTRAL_TRENI) {
            DatiLinea(nomeLineaTreniCotral(nome), nome.trim(), tipo, colore, coloreTesto)
        } else {
            DatiLinea(nome, descrizione, tipo, colore, coloreTesto)
        }
    }

    override fun fermata(id: String, codice: String, nome: String, lat: Double, lon: Double) {
        if (area != null && !area.invoke(lat, lon)) return
        fermateTenute.add(id)
        dentro.fermata(p(id), codice, nome, lat, lon)
        fermate++
    }

    override fun corsa(id: String, linea: String, direzione: Int, capolinea: String) = Unit

    override fun percorso(linea: String, direzione: Int, capolinea: String, forma: String, fermate: List<String>) {
        percorsi.add(DatiPercorso(linea, direzione, capolinea, forma, fermate))
    }

    override fun fermataLinea(fermata: String, linea: String, direzione: Int, capolinea: String) {
        if (fermata !in fermateTenute) return
        lineeUsate.add(linea)
        dentro.fermataLinea(p(fermata), p(linea), direzione, capolinea)
    }

    override fun forma(id: String, punti: List<Punto>) {
        forme[id] = punti
    }

    fun completa() {
        val usate = if (area == null) linee.keys else lineeUsate
        for (id in linee.keys) {
            if (id !in usate) continue
            val l = linee.getValue(id)
            dentro.linea(p(id), l.nome, l.descrizione, l.tipo, l.colore, l.coloreTesto)
        }
        val formeUsate = HashSet<String>()
        for (pc in percorsi) {
            if (pc.linea !in usate) continue
            val tenute = pc.fermate.filter { it in fermateTenute }
            if (tenute.isEmpty()) continue
            dentro.percorso(p(pc.linea), pc.direzione, pc.capolinea, p(pc.forma), tenute.map { p(it) })
            if (pc.forma.isNotEmpty()) formeUsate.add(pc.forma)
        }
        for (id in formeUsate) forme[id]?.let { dentro.forma(p(id), it) }
    }
}

/** Il Lazio, con un po' di margine: le stazioni Trenitalia che servono. */
fun nelLazio(lat: Double, lon: Double): Boolean = lat in 40.75..42.90 && lon in 11.40..14.05

class GeneraRetiIncluse {

    /**
     * Gira solo nella compilazione su GitHub: RETI_COTRAL, RETI_FERRO e
     * RETI_TRENITALIA sono gli zip GTFS (ognuno puo' mancare), RETI_SQL dove
     * scrivere le istruzioni per strumenti/crea_reti.py.
     */
    @Test
    fun generaDaGtfs() {
        val sql = System.getenv("RETI_SQL").orEmpty()
        val fonti = listOf(
            Triple(Rete.COTRAL, System.getenv("RETI_COTRAL").orEmpty(), null),
            Triple(Rete.COTRAL_TRENI, System.getenv("RETI_FERRO").orEmpty(), null),
            Triple(Rete.TRENITALIA, System.getenv("RETI_TRENITALIA").orEmpty(), ::nelLazio),
        ).filter { it.second.isNotEmpty() && File(it.second).exists() }
        assumeTrue(sql.isNotEmpty() && fonti.isNotEmpty())
        val uscita = File(sql)
        uscita.absoluteFile.parentFile?.mkdirs()
        var totale = 0
        uscita.bufferedWriter(Charsets.UTF_8).use { w ->
            val scrittore = ScrittoreSql(w)
            for ((rete, zip, area) in fonti) {
                // una rete che non si legge non ferma le altre
                try {
                    val filtro = FiltroRete(rete, scrittore, area)
                    ZipFile(zip).use { z -> CostruttoreOrario(z, filtro).costruisci() }
                    filtro.completa()
                    totale += filtro.fermate
                    println("reti incluse: ${rete.etichetta}, ${filtro.fermate} fermate")
                } catch (e: Exception) {
                    println("reti incluse: ${rete.etichetta} non letta ($e)")
                }
            }
            scrittore.completa(System.currentTimeMillis())
        }
        assertTrue("nessuna fermata delle altre reti", totale > 0)
    }

    @Test
    fun prefissiEAreaDelleAltreReti() {
        val file = File.createTempFile("rete", ".zip")
        ZipOutputStream(file.outputStream()).use { z ->
            mapOf(
                "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nL1,ROMALIDO,,2\nL2,ALTRA,,2\n",
                "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon\n" +
                    "S1,,PORTA SAN PAOLO,41.875,12.481\nS2,,OSTIA,41.73,12.28\nS3,,MILANO,45.48,9.20\n",
                "trips.txt" to "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\n" +
                    "L1,X,T1,,0,F1\nL2,X,T2,,0,F2\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,08:00:00,08:00:00,S1,1\nT1,08:30:00,08:30:00,S2,2\nT2,09:00:00,09:00:00,S3,1\n",
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n" +
                    "F1,41.875,12.481,1\nF1,41.73,12.28,2\nF2,45.48,9.20,1\nF2,45.5,9.3,2\n",
            ).forEach { (nome, testo) ->
                z.putNextEntry(ZipEntry(nome))
                z.write(testo.toByteArray())
                z.closeEntry()
            }
        }
        val sql = StringBuilder()
        val scrittore = ScrittoreSql(sql)
        val filtro = FiltroRete(Rete.COTRAL_TRENI, scrittore, ::nelLazio)
        ZipFile(file).use { CostruttoreOrario(it, filtro).costruisci() }
        filtro.completa()
        scrittore.completa(1_700_000_000_000)
        file.delete()

        val testo = sql.toString()
        assertEquals(2, filtro.fermate)
        assertTrue(testo.contains("INSERT OR REPLACE INTO fermate VALUES('F:S1','S1','Porta San Paolo'"))
        assertFalse("Milano non e' nel Lazio", testo.contains("'F:S3'"))
        // il nome che si legge alle stazioni, quello del GTFS per ASTRAL
        assertTrue(testo.contains("INSERT OR REPLACE INTO linee VALUES('F:L1','Metromare','ROMALIDO',2,'','');"))
        assertFalse("la linea di Milano non serve", testo.contains("'F:L2'"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO fermata_linee VALUES('F:S1','F:L1',0,"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO percorso_fermate VALUES('F:L1',0,1,'F:S2');"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO forme VALUES('F:F1',"))
        assertFalse(testo.contains("INTO forme VALUES('F:F2'"))
        assertFalse("niente corse", testo.contains("INTO corse"))
    }
}
