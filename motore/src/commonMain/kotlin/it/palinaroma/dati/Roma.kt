package it.palinaroma.dati

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** L'ora di Roma (con l'ora legale), per gli orari dei treni che arrivano come "07:42". */
private val FUSO_ROMA = TimeZone.of("Europe/Rome")

/** Il giorno a Roma nell'istante [millisecondi]. */
@OptIn(ExperimentalTime::class)
fun giornoARoma(millisecondi: Long): LocalDate =
    Instant.fromEpochMilliseconds(millisecondi).toLocalDateTime(FUSO_ROMA).date

/** I secondi dal 1970 dell'ora [ore]:[minuti] di Roma nel [giorno] (piu' [giorniDopo]). */
@OptIn(ExperimentalTime::class)
fun secondiARoma(giorno: LocalDate, ore: Int, minuti: Int, giorniDopo: Int = 0): Long {
    val data = if (giorniDopo == 0) giorno else giorno.plus(DatePeriod(days = giorniDopo))
    return LocalDateTime(data, LocalTime(ore, minuti)).toInstant(FUSO_ROMA).epochSeconds
}

/** L'ora (0-23) di Roma nell'istante [millisecondi]. */
@OptIn(ExperimentalTime::class)
fun oraDelGiornoARoma(millisecondi: Long): Int = Instant.fromEpochMilliseconds(millisecondi).toLocalDateTime(FUSO_ROMA).hour

/** "07:42": l'ora di Roma di un istante in millisecondi. */
@OptIn(ExperimentalTime::class)
fun oraARoma(millisecondi: Long): String {
    val t = Instant.fromEpochMilliseconds(millisecondi).toLocalDateTime(FUSO_ROMA)
    return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}
