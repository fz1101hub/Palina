package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * L'affidabilita' delle linee di Roma: ogni ora, su GitHub, il tempo reale di
 * Roma Mobilita' si confronta con l'orario (strumenti/affidabilita.py). L'app
 * scarica il riassunto delle ultime 4 settimane e dice, per la linea e l'ora
 * di adesso: "di solito in orario 8 corse su 10, ritardo medio 3 minuti".
 * Le fasce e i tipi di giorno sono gli stessi dello script.
 */

/** Per linea e fascia ("F05": feriale dalle 5 alle 9): corse viste, ritardo medio (s), % in orario, % oltre 5 minuti. */
data class DatiAffidabilita(val aggiornato: Long, val giorni: Int, val linee: Map<String, Map<String, List<Int>>>)

fun leggiAffidabilita(json: String): DatiAffidabilita? = try {
    val o = Json.leggi(json).comeOggetto()
    DatiAffidabilita(
        aggiornato = o.numero("aggiornato")?.toLong() ?: 0L,
        giorni = o.numero("giorni")?.toInt() ?: 0,
        linee = o.oggetto("linee").orEmpty().mapValues { (_, fasce) ->
            fasce.comeOggetto().mapValues { (_, v) -> (v as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() } }
                .filterValues { it.size >= 4 }
        },
    )
} catch (_: Exception) {
    null
}

private fun pasqua(anno: Int): LocalDate {
    val a = anno % 19
    val b = anno / 100
    val c = anno % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val mese = (h + l - 7 * m + 114) / 31
    val giorno = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate(anno, mese, giorno)
}

private val FESTE = setOf(1 to 1, 1 to 6, 4 to 25, 5 to 1, 6 to 2, 6 to 29, 8 to 15, 11 to 1, 12 to 8, 12 to 25, 12 to 26)

/** "F" feriale, "S" sabato, "D" domenica e festivi (come lo script). */
fun tipoGiorno(d: LocalDate): String = when {
    (d.month.number to d.day) in FESTE || d == pasqua(d.year).plus(DatePeriod(days = 1)) || d.dayOfWeek == DayOfWeek.SUNDAY -> "D"
    d.dayOfWeek == DayOfWeek.SATURDAY -> "S"
    else -> "F"
}

/** La fascia di adesso: "F05", "S17", "D21"... (dopo mezzanotte e fino all'una conta il giorno prima). */
fun chiaveFascia(istanteMs: Long): String {
    val ora = oraDelGiornoARoma(istanteMs)
    val giorno = if (ora < 1) giornoARoma(istanteMs).minus(DatePeriod(days = 1)) else giornoARoma(istanteMs)
    val fascia = when {
        ora >= 21 || ora < 1 -> "21"
        ora >= 17 -> "17"
        ora >= 13 -> "13"
        ora >= 9 -> "09"
        ora >= 5 -> "05"
        else -> "01"
    }
    return tipoGiorno(giorno) + fascia
}

/**
 * "Di solito a quest'ora: in orario 8 corse su 10, ritardo medio 3 min" per la
 * linea [lineaId] adesso; null se non ci sono abbastanza corse viste.
 */
fun testoAffidabilita(d: DatiAffidabilita?, lineaId: String, istanteMs: Long): String? {
    val v = d?.linee?.get(idOriginale(lineaId))?.get(chiaveFascia(istanteMs)) ?: return null
    val (_, ritardo, puntuali, _) = v
    val dieci = (puntuali / 10.0).roundToInt().coerceIn(0, 10)
    val minuti = (abs(ritardo) / 60.0).roundToInt()
    val medio = when {
        minuti < 1 -> tx("quasi sempre puntuale")
        ritardo > 0 -> tx("ritardo medio {0} min", minuti)
        else -> tx("anticipo medio {0} min", minuti)
    }
    return tx("Di solito a quest'ora: in orario {0} corse su 10, {1}", dieci, medio)
}

/** Da dove viene: "ultime 4 settimane, dal tempo reale di Roma Mobilità". */
fun fonteAffidabilita(d: DatiAffidabilita): String =
    txn("ultimo giorno, dal tempo reale di Roma Mobilità", "ultimi {0} giorni, dal tempo reale di Roma Mobilità", d.giorni)

object Affidabilita {
    const val URL = "https://github.com/fz1101hub/Palina/releases/download/ultima/affidabilita.json"

    private val feed = FeedInMemoria(
        validitaMs = 6 * 3600_000L,
        tolleranzaMs = 7 * 24 * 3600_000L,
        scarica = { etag, modificato -> scaricaFeed(URL, etag, modificato) },
        decodifica = { listOfNotNull(leggiAffidabilita(it.decodeToString())) },
    )

    /** I dati (null senza rete o se non ci sono ancora). */
    suspend fun dati(): DatiAffidabilita? = try {
        feed.ottieni().firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Per la schermata della linea: il testo di adesso e la fonte, o null. */
data class AffidabilitaLinea(val testo: String, val fonte: String)

suspend fun affidabilitaLinea(lineaId: String, adessoMs: Long = adessoMs()): AffidabilitaLinea? {
    if (Rete.di(lineaId) != Rete.ROMA) return null
    val d = Affidabilita.dati() ?: return null
    val testo = testoAffidabilita(d, lineaId, adessoMs) ?: return null
    return AffidabilitaLinea(testo, fonteAffidabilita(d))
}
