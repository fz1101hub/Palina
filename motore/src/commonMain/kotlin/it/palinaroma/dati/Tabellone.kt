package it.palinaroma.dati

/**
 * Le regole del "tabellone": come si mettono insieme i passaggi di una fermata,
 * quali linee vanno in cima, come si dicono i tempi a parole. Niente Android qui
 * dentro, cosi' si provano con i test di unita'.
 */

/** Mette insieme le fonti di una fermata; ognuna puo' mancare. [adesso] in secondi; [treni] da ASTRAL. */
fun arriviFermata(
    fermataId: String,
    orario: Orario?,
    corse: List<CorsaRT>?,
    mezzi: List<MezzoRT>,
    partenze: List<PartenzaTransitous>?,
    adesso: Long,
    treni: List<PassaggioLinea>? = null,
): List<Arrivo> {
    val tracciate = mezzi.mapNotNull { it.corsaId.ifEmpty { null } }.toSet()
    val inTempoReale = corse?.let { passaggiTempoReale(it, fermataId, orario, adesso, tracciate = tracciate) }.orEmpty()
    val programmati = partenze?.let { passaggiProgrammati(it, orario, adesso, mezzi = mezzi) }.orEmpty() + treni.orEmpty()
    return raggruppa(uniscPassaggi(inTempoReale, programmati), orario)
}

/** La chiave con cui si salva una linea fissata a una fermata. */
fun chiaveFissata(fermataId: String, lineaId: String): String = "$fermataId|$lineaId"

/** Le linee fissate a una fermata, tra tutte quelle salvate. */
fun lineeFissate(fermataId: String, chiavi: Set<String>): Set<String> {
    val prefisso = "$fermataId|"
    return chiavi.filter { it.startsWith(prefisso) }.map { it.removePrefix(prefisso) }.toSet()
}

/**
 * Gli arrivi divisi tra le linee fissate (in cima) e le altre. [senzaPassaggi]
 * sono le linee fissate che nei prossimi 90 minuti non passano: restano in cima
 * con una riga che lo dice, invece di sparire.
 */
data class ArriviDivisi(val fissate: List<Arrivo>, val altre: List<Arrivo>, val senzaPassaggi: List<String>)

fun dividiArrivi(arrivi: List<Arrivo>, fissate: Set<String>): ArriviDivisi {
    val (sopra, sotto) = arrivi.partition { it.lineaId in fissate }
    val presenti = sopra.map { it.lineaId }.toSet()
    return ArriviDivisi(sopra, sotto, fissate.filter { it !in presenti }.sorted())
}

/** Minuti interi che mancano; 0 se e' gia' in arrivo. Tempi in secondi. */
fun minutiAttesa(orario: Long, adesso: Long): Int = ((orario - adesso) / 60).toInt().coerceAtLeast(0)

/** "adesso", "20 s fa", "3 min fa": da quanto e' arrivato l'ultimo dato. Tempi in millisecondi. */
fun testoAggiornato(aggiornatoMs: Long, adessoMs: Long): String {
    val secondi = ((adessoMs - aggiornatoMs) / 1000).coerceAtLeast(0)
    return when {
        secondi < 10 -> tx("adesso")
        secondi < 60 -> tx("{0} s fa", secondi / 10 * 10)
        else -> tx("{0} min fa", secondi / 60)
    }
}

/** Le abbreviazioni dei nomi delle fermate, sciolte per il lettore dello schermo. */
private val ABBREVIAZIONI = listOf(
    Regex("\\bP\\.za\\b\\s*", RegexOption.IGNORE_CASE) to "Piazza ",
    Regex("\\bP\\.le\\b\\s*", RegexOption.IGNORE_CASE) to "Piazzale ",
    Regex("\\bL\\.go\\b\\s*", RegexOption.IGNORE_CASE) to "Largo ",
    Regex("\\bV\\.le\\b\\s*", RegexOption.IGNORE_CASE) to "Viale ",
    Regex("\\bC\\.so\\b\\s*", RegexOption.IGNORE_CASE) to "Corso ",
    Regex("\\bStaz\\.ne\\b\\s*", RegexOption.IGNORE_CASE) to "Stazione ",
    Regex("\\bStaz\\.\\s*", RegexOption.IGNORE_CASE) to "Stazione ",
)

/** Un nome scritto come si legge: "P.za Venezia" -> "Piazza Venezia", "Nazionale/Torino" -> "Nazionale, Torino". */
fun daLeggere(nome: String): String {
    var t = nome
    for ((regola, sostituto) in ABBREVIAZIONI) t = regola.replace(t, sostituto)
    return t.replace(Regex("\\s*/\\s*"), ", ").replace(Regex("\\s+"), " ").trim()
}


/**
 * La riga di un arrivo come una frase sola, per il lettore dello schermo:
 * "64 per Stazione San Pietro. Tra 1 minuto, in tempo reale. Poi tra 9 e 17 minuti."
 */
fun fraseArrivo(arrivo: Arrivo, adesso: Long): String {
    val primo = arrivo.passaggi.first()
    val minuti = minutiAttesa(primo.orario, adesso)
    val linea = if (arrivo.capolinea.isEmpty()) arrivo.nomeLinea else tx("{0} per {1}", arrivo.nomeLinea, daLeggere(arrivo.capolinea))
    val fonte = primo.avviso?.let { testoAvviso(it) } ?: if (primo.tracciato) tx("in tempo reale") else tx("secondo l'orario")
    val quando = if (minuti < 1) tx("In arrivo, {0}.", fonte) else txn("Tra {0} minuto, {1}.", "Tra {0} minuti, {1}.", minuti, fonte)
    val successivi = arrivo.passaggi.drop(1).take(2).map { minutiAttesa(it.orario, adesso) }
    val poi = when {
        successivi.isEmpty() -> ""
        successivi.size == 1 && successivi[0] < 1 -> " " + tx("Poi un altro in arrivo.")
        successivi.size == 1 -> " " + txn("Poi tra {0} minuto.", "Poi tra {0} minuti.", successivi[0])
        successivi[0] < 1 -> " " + txn("Poi un altro in arrivo e uno tra {0} minuto.", "Poi un altro in arrivo e uno tra {0} minuti.", successivi[1])
        else -> " " + tx("Poi tra {0} e {1} minuti.", successivi[0], successivi[1])
    }
    return "$linea. $quando$poi"
}

/** I passaggi dopo il primo, in breve: "poi 9 · 17 min"; "<1" se arrivano quasi insieme. */
fun testoPoi(minuti: List<Int>): String =
    if (minuti.isEmpty()) "" else tx("poi {0} min", minuti.joinToString(" · ") { if (it < 1) "<1" else it.toString() })

/**
 * La fermata preferita a cui sei vicino: la piu' vicina entro [raggio] metri.
 * Null se non c'e' o se la posizione non e' nota.
 */
fun preferitaVicina(
    preferite: List<Fermata>,
    lat: Double?,
    lon: Double?,
    raggio: Double = 400.0,
): Pair<Fermata, Double>? {
    if (lat == null || lon == null) return null
    return preferite
        .map { it to distanzaMetri(lat, lon, it.lat, it.lon) }
        .filter { it.second <= raggio }
        .minByOrNull { it.second }
}

/**
 * Il tabellone di una stazione fatta di piu' fermate (le stazioni di scambio,
 * vedi [StazioneUnita]): quelli di ognuna, insieme, in ordine di passaggio. Con
 * una fermata sola e' [arriviFermata]. [fonti] per fermata; il tempo reale di
 * Roma Mobilita' vale solo per le sue fermate.
 */
fun arriviStazione(
    membri: List<String>,
    orario: Orario?,
    corse: List<CorsaRT>?,
    mezzi: List<MezzoRT>,
    fonti: Map<String, FontiFermata?>,
    adesso: Long,
): List<Arrivo> {
    val perFermata = membri.distinct().map { id ->
        val f = fonti[id]
        arriviFermata(id, orario, corse.takeIf { Rete.di(id) == Rete.ROMA }, mezzi, f?.partenze, adesso, f?.treni)
    }
    return if (perFermata.size == 1) perFermata[0] else perFermata.flatten().sortedBy { it.orari.first() }
}
