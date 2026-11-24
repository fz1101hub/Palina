package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Le partenze programmate di una fermata, ognuna puo' mancare:
 * [partenze] da Transitous (orario, con il tempo reale quando c'e': per Roma,
 * i bus Cotral e i treni Trenitalia), [treni] da ASTRAL per le stazioni dei
 * treni Cotral (orario e ritardi). Quando ASTRAL risponde, per quella stazione
 * Transitous non serve: sarebbero gli stessi treni, senza ritardi.
 */
data class FontiFermata(val partenze: List<PartenzaTransitous>?, val treni: List<PassaggioLinea>?) {
    val vuote: Boolean get() = partenze == null && treni == null
}

private suspend fun <T> provando(blocco: suspend () -> T): T? =
    try {
        blocco()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/** Le linee ferroviarie Cotral di una stazione: nome nel GTFS ("ROMALIDO") -> linea dell'app. */
fun lineeGtfsTreniCotral(linee: List<LineaFermata>): Map<String, String> =
    linee.filter { Rete.di(it.linea.id) == Rete.COTRAL_TRENI }
        .associate { lf -> lf.linea.descrizione.ifEmpty { idOriginale(lf.linea.id) }.trim().uppercase() to lf.linea.id }

suspend fun caricaFonti(fermata: Fermata, orario: Orario?, adessoMs: Long = adessoMs()): FontiFermata {
    if (Rete.di(fermata.id) == Rete.COTRAL_TRENI) {
        // prima ASTRAL; Transitous solo se non risponde (altrimenti si aspetterebbe anche lui)
        val linee = withContext(dispatcherDati) { lineeGtfsTreniCotral(orario?.lineeAllaFermata(fermata.id).orEmpty()) }
        provando { Astral.passaggi(fermata.nome, linee, adessoMs) }?.let { return FontiFermata(null, it) }
    }
    return FontiFermata(provando { Transitous.partenze(fermata.id, fermata.lat, fermata.lon) }, null)
}
