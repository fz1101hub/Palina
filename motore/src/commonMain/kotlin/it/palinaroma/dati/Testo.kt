package it.palinaroma.dati

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val NON_ALFANUMERICI = Regex("[^a-z0-9]+")
private val SPAZI = Regex("\\s+")

/** Testo per la ricerca: minuscolo, senza accenti e punteggiatura. */
fun normalizza(testo: String): String =
    senzaAccenti(testo)
        .lowercase()
        .replace(NON_ALFANUMERICI, " ")
        .trim()

private val MINUSCOLE = setOf(
    "di", "del", "dello", "della", "dei", "degli", "delle", "da", "dal", "dalla",
    "e", "a", "al", "alla", "in", "per", "su", "sul",
)
private val ELISE = setOf("dell", "dall", "nell", "sull", "all", "l", "d")
private val VOCALI = Regex("[aeiouy]")

/** "(ma-mb-fs)": solo sigle brevi tra parentesi. */
private fun sigleTraParentesi(p: String): Boolean =
    p.length > 2 && p.substring(1, p.length - 1).split('-', '/').all { it.length in 1..3 && it.all(Char::isLetterOrDigit) }

/** Una parola in minuscolo scritta per bene; [prima] se apre il nome (allora mai minuscola). */
private fun parolaScritta(p: String, prima: Boolean): String = when {
    (p.startsWith("(") && p.endsWith(")") && (p.length <= 5 || sigleTraParentesi(p))) ||
        (p.isNotEmpty() && p.all { it.isLetter() } && p.length <= 3 && !VOCALI.containsMatchIn(p)) -> p.uppercase()
    !prima && p in MINUSCOLE -> p
    '\'' in p -> {
        val inizio = p.substringBefore('\'')
        val dopo = p.substringAfter('\'')
        val testa = if (!prima && inizio in ELISE) inizio else inizio.replaceFirstChar { it.uppercase() }
        testa + "'" + dopo.replaceFirstChar { it.uppercase() }
    }
    else -> p.replaceFirstChar { it.uppercase() }
}

/**
 * "STAZIONE TERMINI" -> "Stazione Termini"; i testi gia' scritti bene restano
 * uguali. Dopo la barra comincia un altro nome: "NAZIONALE/TORINO" -> "Nazionale/Torino".
 */
fun bellaScrittura(testo: String): String {
    val pulito = testo.trim().split(SPAZI).joinToString(" ")
    if (pulito.isEmpty() || pulito != pulito.uppercase()) return pulito
    return pulito.lowercase().split(" ").mapIndexed { i, p ->
        if ('/' in p && !p.startsWith("(")) {
            p.split('/').mapIndexed { j, pezzo -> parolaScritta(pezzo, prima = i == 0 || j > 0) }.joinToString("/")
        } else {
            parolaScritta(p, prima = i == 0)
        }
    }.joinToString(" ")
}

/** Distanza in metri tra due punti (formula dell'emisenoverso). */
fun distanzaMetri(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = radianti(lat2 - lat1)
    val dLon = radianti(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(radianti(lat1)) * cos(radianti(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

/** Stesso codice fermata, anche se uno dei due ha zeri iniziali. */
fun stessoCodice(a: String, b: String): Boolean =
    a == b || (a.trimStart('0') == b.trimStart('0') && a.trimStart('0').isNotEmpty())

/** Ordina le linee come le leggerebbe una persona: 8, 16, 64, 714, H, N1... */
val ordineLinee: Comparator<String> = Comparator { a, b ->
    val na = a.toIntOrNull()
    val nb = b.toIntOrNull()
    when {
        na != null && nb != null -> if (na != nb) na.compareTo(nb) else a.compareTo(b)
        na != null -> -1
        nb != null -> 1
        else -> a.compareTo(b, ignoreCase = true)
    }
}

/** Gradi in radianti, come Math.toRadians (stessa costante, stessi risultati). */
fun radianti(gradi: Double): Double = gradi * GRADI_IN_RADIANTI

private const val GRADI_IN_RADIANTI = PI / 180.0
