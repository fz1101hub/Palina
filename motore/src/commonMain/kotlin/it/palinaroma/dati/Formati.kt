package it.palinaroma.dati

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Un numero con [cifre] decimali e il [separatore] scelto, come
 * String.format(Locale.ROOT, "%.6f"): arrotondato a meta' per eccesso.
 */
fun decimali(valore: Double, cifre: Int, separatore: Char = '.'): String {
    if (valore.isNaN()) return "NaN"
    var scala = 1L
    repeat(cifre) { scala *= 10 }
    val intero = (abs(valore) * scala).roundToLong()
    val parteIntera = intero / scala
    val parteDecimale = (intero % scala).toString().padStart(cifre, '0')
    val segno = if (valore < 0 && intero != 0L) "-" else ""
    return if (cifre == 0) "$segno$parteIntera" else "$segno$parteIntera$separatore$parteDecimale"
}

/** Il testo per un indirizzo web, come URLEncoder.encode(testo, "UTF-8"). */
fun codificaUrl(valore: String): String = buildString {
    for (b in valore.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> append(c.toChar())
            c == ' '.code -> append('+')
            else -> {
                append('%')
                append(ESADECIMALI[c shr 4])
                append(ESADECIMALI[c and 0x0F])
            }
        }
    }
}

private const val ESADECIMALI = "0123456789ABCDEF"

/** Un istante in millisecondi come testo ISO 8601 in UTC ("2026-11-24T05:00:00Z"), come Instant.toString(). */
@OptIn(ExperimentalTime::class)
fun testoIstante(millisecondi: Long): String = Instant.fromEpochMilliseconds(millisecondi).toString()

/** Data e ora ISO 8601 (con "Z" o con lo scostamento, "+01:00") in millisecondi, o null. */
@OptIn(ExperimentalTime::class)
fun istanteDaTesto(testo: String): Long? =
    runCatching { Instant.parse(testo).toEpochMilliseconds() }.getOrNull()
