package it.palinaroma.dati

import java.io.BufferedReader
import java.io.Closeable
import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Lettore CSV per i file GTFS: veloce sulle righe senza virgolette. */
class LettoreCsv(private val lettore: BufferedReader) : Closeable {
    val intestazione: List<String>

    override fun close() = lettore.close()

    init {
        val prima = lettore.readLine() ?: ""
        intestazione = dividi(prima.removePrefix("\uFEFF")).map { it.trim() }
    }

    fun indice(nome: String): Int = intestazione.indexOf(nome)

    /** La prossima riga non vuota, oppure null alla fine del file. */
    fun prossima(): List<String>? {
        while (true) {
            val riga = lettore.readLine() ?: return null
            if (riga.isNotBlank()) return dividi(riga)
        }
    }

    companion object {
        fun dividi(riga: String): List<String> {
            if (riga.indexOf('"') < 0) return riga.split(',')
            val campi = ArrayList<String>()
            val campo = StringBuilder()
            var tra = false
            var i = 0
            while (i < riga.length) {
                val c = riga[i]
                when {
                    tra && c == '"' && i + 1 < riga.length && riga[i + 1] == '"' -> {
                        campo.append('"'); i++
                    }
                    c == '"' -> tra = !tra
                    c == ',' && !tra -> {
                        campi.add(campo.toString()); campo.setLength(0)
                    }
                    else -> campo.append(c)
                }
                i++
            }
            campi.add(campo.toString())
            return campi
        }
    }
}

/** Valore della colonna, stringa vuota se manca. */
fun List<String>.campo(indice: Int): String =
    if (indice in indices) this[indice].trim() else ""

private val ACCENTI = Regex("\\p{M}+")
private val NON_ALFANUMERICI = Regex("[^a-z0-9]+")
private val SPAZI = Regex("\\s+")

/** Testo per la ricerca: minuscolo, senza accenti e punteggiatura. */
fun normalizza(testo: String): String =
    Normalizer.normalize(testo, Normalizer.Form.NFD)
        .replace(ACCENTI, "")
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
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
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
