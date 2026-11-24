package it.palinaroma.dati

/**
 * Stazioni di scambio tra i treni Cotral e la metro di Roma: Metromare con la
 * Metro B (Piramide/Porta San Paolo, Basilica San Paolo, EUR Magliana), Roma
 * Nord con la Metro A (Flaminio). Nell'orario sono fermate diverse (la stazione
 * Cotral e una banchina della metro per direzione); nell'app sono una sola
 * stazione, con gli orari di tutte.
 */
data class StazioneUnita(
    /** La stazione Cotral per prima, poi le banchine della metro dalla piu' vicina. */
    val membri: List<String>,
    val nome: String,
    /** "Metro B/B1 · Metromare". */
    val linee: String,
)

/** Le banchine della metro valgono come la stessa stazione entro questa distanza dalla stazione Cotral. */
const val METRI_SCAMBIO = 150.0

/**
 * Il nome della stazione unita, dai nomi delle sue fermate (la stazione Cotral
 * per prima): quello che contiene tutti gli altri ("San Paolo" e "Basilica San
 * Paolo" -> "Basilica San Paolo"), altrimenti tutti, la metro per prima
 * ("Piramide / Porta San Paolo").
 */
fun nomeStazioneUnita(nomi: List<String>): String {
    val diversi = nomi.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { normalizza(it) }
    if (diversi.size <= 1) return diversi.firstOrNull().orEmpty()
    val piuLungo = diversi.maxBy { normalizza(it).length }
    val contieneTutti = diversi.all { normalizza(piuLungo).contains(normalizza(it)) }
    return if (contieneTutti) piuLungo else (diversi.drop(1) + diversi.first()).joinToString(" / ")
}

private val SIGLA_METRO_ORARIO = Regex("^ME([A-C]\\d?)$")

/** "MEB", "MEB1", "Metromare" -> "Metro B/B1 · Metromare". */
fun lineeStazioneUnita(nomiLinee: List<String>): String {
    val metro = nomiLinee.mapNotNull { SIGLA_METRO_ORARIO.find(it.trim().uppercase())?.groupValues?.get(1) }.distinct().sorted()
    val altre = nomiLinee.filter { SIGLA_METRO_ORARIO.find(it.trim().uppercase()) == null }.distinct()
    return (listOfNotNull(metro.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "Metro ")) + altre).joinToString(" · ")
}
