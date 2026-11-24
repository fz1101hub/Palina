package it.palinaroma.dati

import kotlin.math.cos

/**
 * Lettura dell'orario gia' trasformato: [db] e' quello di Roma (aggiornabile dal
 * telefono), [reti] quello delle altre reti incluso nell'APK (Cotral, Trenitalia),
 * con le stesse tabelle e i codici con il prefisso della rete (vedi [Rete]).
 */
class Orario(private val db: BaseDati, private val reti: BaseDati? = null) {

    private val basi = listOfNotNull(db, reti)

    /** Il database che contiene una fermata, una linea o un tracciato, dal prefisso del codice. */
    private fun baseDi(id: String): BaseDati = if (reti != null && Rete.di(id) != Rete.ROMA) reti else db

    private val linee: Map<String, Linea> by lazy {
        val mappa = HashMap<String, Linea>()
        for (base in basi) {
            base.elenco("SELECT id, nome, descrizione, tipo, colore, colore_testo FROM linee", emptyList()) { c ->
                Linea(c.testo(0), c.testo(1), c.testo(2), c.intero(3), c.testo(4), c.testo(5))
            }.forEach { mappa[it.id] = it }
        }
        mappa
    }

    /** Ci sono le fermate di Cotral e Trenitalia (l'APK le include). */
    val conAltreReti: Boolean get() = reti != null

    /**
     * Le stazioni di scambio (vedi [StazioneUnita]), per ognuna delle fermate che
     * le compongono: le stazioni dei treni Cotral con le banchine della metro di
     * Roma entro [METRI_SCAMBIO] metri.
     */
    private val scambi: Map<String, StazioneUnita> by lazy {
        val altre = reti ?: return@lazy emptyMap()
        val mappa = HashMap<String, StazioneUnita>()
        val stazioni = fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id LIKE 'F:%'", emptyList(), altre)
        for (s in stazioni) {
            val metro = vicineIn(db, s.lat, s.lon, METRI_SCAMBIO)
                .filter { (f, _) -> lineeAllaFermata(f.id).any { it.linea.tipo == 1 } }
            if (metro.isEmpty()) continue
            val fermate = listOf(s) + metro.map { it.first }
            val unita = StazioneUnita(
                membri = fermate.map { it.id },
                nome = nomeStazioneUnita(fermate.map { it.nome }),
                linee = lineeStazioneUnita(fermate.flatMap { f -> lineeAllaFermata(f.id).map { it.linea.nome } }),
            )
            fermate.forEach { if (it.id !in mappa) mappa[it.id] = unita }
        }
        mappa
    }

    /** Prepara le stazioni di scambio (da chiamare fuori dal thread dell'interfaccia). */
    fun preparaScambi() {
        scambi.size
    }

    /** Le fermate della stazione di cui [id] fa parte (la stazione Cotral per prima), o solo [id]. */
    fun stazioneUnita(id: String): List<String> = scambi[id]?.membri ?: listOf(id)

    /** La stazione unita di cui fa parte [id], con il suo nome; null se [id] non e' uno scambio. */
    fun fermataUnita(id: String): Fermata? {
        val unita = scambi[id] ?: return null
        return fermata(unita.membri.first())?.copy(nome = unita.nome)
    }

    /** "Metro B/B1 · Metromare" se [id] fa parte di una stazione di scambio. */
    fun lineeStazioneUnita(id: String): String? = scambi[id]?.linee

    /** Le fermate di una stazione di scambio diventano una sola, al posto della prima che compare. */
    private fun unisciScambi(fermate: Collection<Fermata>): List<Fermata> {
        if (scambi.isEmpty()) return fermate.toList()
        val viste = HashSet<String>()
        return fermate.mapNotNull { f ->
            val unita = scambi[f.id] ?: return@mapNotNull f.takeIf { viste.add(it.id) }
            if (viste.add(unita.membri.first())) fermataUnita(f.id) else null
        }
    }

    fun info(chiave: String): String? =
        db.elenco("SELECT valore FROM info WHERE chiave = ?", listOf(chiave)) { it.testo(0) }.firstOrNull()

    val numeroFermate: Int get() = db.elenco("SELECT COUNT(*) FROM fermate", emptyList()) { it.intero(0) }.firstOrNull() ?: 0

    fun linea(id: String): Linea? = linee[id]

    fun cercaLinee(testo: String): List<Linea> {
        val t = testo.trim().uppercase()
        if (t.isEmpty()) return emptyList()
        // le "linee" Trenitalia sono categorie di treni (REG, RV): non si cercano
        return linee.values
            .filter { Rete.di(it.id) != Rete.TRENITALIA }
            .filter { it.nome.uppercase().startsWith(t) || it.id.uppercase() == t }
            .sortedWith(compareBy(ordineLinee) { it.nome })
            .take(20)
    }

    private fun fermateDa(sql: String, argomenti: List<String>, base: BaseDati = db): List<Fermata> =
        base.elenco(sql, argomenti) { c -> Fermata(c.testo(0), c.testo(1), c.testo(2), c.decimale(3), c.decimale(4)) }

    fun fermata(id: String): Fermata? =
        fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id = ?", listOf(id), baseDi(id)).firstOrNull()

    fun fermate(ids: Collection<String>): Map<String, Fermata> {
        val risultato = HashMap<String, Fermata>()
        ids.distinct().groupBy { baseDi(it) }.forEach { (base, elenco) ->
            elenco.chunked(400).forEach { blocco ->
                val segnaposto = blocco.joinToString(",") { "?" }
                fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id IN ($segnaposto)", blocco, base)
                    .forEach { risultato[it.id] = it }
            }
        }
        return risultato
    }

    /** Prima le fermate di Roma, poi quelle delle altre reti. */
    fun cercaFermate(testo: String): List<Fermata> {
        val t = testo.trim()
        if (t.isEmpty()) return emptyList()
        val risultati = LinkedHashMap<String, Fermata>()
        for (base in basi) {
            if (t.all { it.isDigit() }) {
                fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE codice = ? OR id = ?", listOf(t, t), base)
                    .forEach { risultati[it.id] = it }
            }
            val parole = normalizza(t).split(" ").filter { it.isNotEmpty() }
            if (parole.isNotEmpty() && parole.joinToString("").length >= 2) {
                val condizioni = parole.joinToString(" AND ") { "nome_norm LIKE ?" }
                val frase = parole.joinToString(" ")
                // prima i nomi che cominciano con il testo cercato ("Termini..."), poi quelli
                // in cui e' una parola intera ("Siculiana/Termini Imerese"), poi gli altri
                fermateDa(
                    "SELECT id, codice, nome, lat, lon FROM fermate WHERE $condizioni " +
                        "ORDER BY CASE WHEN nome_norm LIKE ? THEN 0 WHEN nome_norm LIKE ? THEN 1 ELSE 2 END, " +
                        "length(nome), nome LIMIT 60",
                    parole.map { "%$it%" } + listOf("$frase%", "% $frase%"),
                    base,
                ).forEach { if (it.id !in risultati) risultati[it.id] = it }
            }
        }
        return unisciScambi(risultati.values)
    }

    /** Fermate entro [raggio] metri, dalla piu' vicina. */
    fun fermateVicine(lat: Double, lon: Double, raggio: Double = 700.0): List<Pair<Fermata, Double>> =
        basi.flatMap { vicineIn(it, lat, lon, raggio) }.sortedBy { it.second }.take(40)

    /** Come [fermateVicine], ma le fermate di una stazione di scambio diventano una sola. */
    fun fermateVicineUnite(lat: Double, lon: Double, raggio: Double = 700.0): List<Pair<Fermata, Double>> {
        val vicine = fermateVicine(lat, lon, raggio)
        val distanze = vicine.associate { it.first.id to it.second }
        return unisciScambi(vicine.map { it.first }).map { f ->
            f to (scambi[f.id]?.membri?.mapNotNull { distanze[it] }?.minOrNull() ?: distanze[f.id] ?: 0.0)
        }
    }

    private fun vicineIn(base: BaseDati, lat: Double, lon: Double, raggio: Double): List<Pair<Fermata, Double>> {
        val dLat = raggio / 111_320.0
        val dLon = raggio / (111_320.0 * cos(radianti(lat)))
        val limiti = listOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())
        return fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", limiti, base)
            .map { it to distanzaMetri(lat, lon, it.lat, it.lon) }
            .filter { it.second <= raggio }
            .sortedBy { it.second }
    }

    fun lineeAllaFermata(fermataId: String): List<LineaFermata> =
        baseDi(fermataId).elenco(
            "SELECT linea, direzione, capolinea FROM fermata_linee WHERE fermata = ?", listOf(fermataId),
        ) { c ->
            val linea = linee[c.testo(0)] ?: Linea(c.testo(0), c.testo(0), "", 3, "", "")
            LineaFermata(linea, c.intero(1), bellaScrittura(c.testo(2)))
        }.sortedWith(compareBy(ordineLinee) { it.linea.nome })

    fun percorsi(lineaId: String): List<Percorso> =
        baseDi(lineaId).elenco(
            "SELECT linea, direzione, capolinea, forma FROM percorsi WHERE linea = ? ORDER BY direzione", listOf(lineaId),
        ) { c -> Percorso(c.testo(0), c.intero(1), bellaScrittura(c.testo(2)), c.testo(3)) }

    fun fermateDelPercorso(lineaId: String, direzione: Int): List<Fermata> =
        fermateDa(
            "SELECT f.id, f.codice, f.nome, f.lat, f.lon FROM percorso_fermate p JOIN fermate f ON f.id = p.fermata " +
                "WHERE p.linea = ? AND p.direzione = ? ORDER BY p.posizione",
            listOf(lineaId, direzione.toString()),
            baseDi(lineaId),
        )

    fun forma(id: String): List<Punto> {
        if (id.isEmpty()) return emptyList()
        val testo = baseDi(id).elenco("SELECT punti FROM forme WHERE id = ?", listOf(id)) { it.testo(0) }.firstOrNull()
            ?: return emptyList()
        return testo.split(';').mapNotNull { coppia ->
            val lat = coppia.substringBefore(',').toDoubleOrNull()
            val lon = coppia.substringAfter(',').toDoubleOrNull()
            if (lat != null && lon != null) Punto(lat, lon) else null
        }
    }

    fun corse(ids: Collection<String>): Map<String, InfoCorsa> {
        val risultato = HashMap<String, InfoCorsa>()
        ids.filter { it.isNotEmpty() }.distinct().chunked(400).forEach { blocco ->
            val segnaposto = blocco.joinToString(",") { "?" }
            db.elenco(
                "SELECT id, linea, direzione, capolinea FROM corse WHERE id IN ($segnaposto)", blocco,
            ) { c -> c.testo(0) to InfoCorsa(c.testo(1), c.intero(2), bellaScrittura(c.testo(3))) }
                .forEach { (id, info) -> risultato[id] = info }
        }
        return risultato
    }

    fun chiudi() {
        db.chiudi()
        reti?.chiudi()
    }
}
