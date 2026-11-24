package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cosa serve per aprire il dettaglio di una corsa dal tabellone (vedi [CercaCorsa]). */
data class RichiestaCorsa(
    val corsaId: String?,
    val fermataId: String,
    val lineaId: String,
    val direzione: Int?,
    /** Orario programmato alla stazione, in secondi. */
    val previsto: Long,
    val corsaAstral: String?,
    val ritardo: Int?,
)

/**
 * Trova la corsa della [richiesta] e ne legge tutte le fermate. Si richiama per
 * aggiornare: la corsa trovata e l'ultimo ritardo di ASTRAL restano.
 *
 * Senza [RichiestaCorsa.corsaId] (treni Cotral visti da ASTRAL) la si cerca tra
 * le partenze di Transitous della stazione, per linea, direzione e orario
 * programmato; [RichiestaCorsa.corsaAstral] serve a portare il ritardo di ASTRAL
 * nel dettaglio. Lo usano il dettaglio sul telefono e l'orologio.
 */
class CercaCorsa(private val orario: Orario?, private val richiesta: RichiestaCorsa) {
    /** Il dettaglio letto, o perche' non c'e'. */
    data class Esito(val dettaglio: DettaglioCorsa?, val errore: String?)

    private var trovata = richiesta.corsaId
    private var ritardo = richiesta.ritardo
    private val stazione: Fermata? by lazy { orario?.fermata(richiesta.fermataId) }

    suspend fun aggiorna(): Esito = try {
        val f = withContext(Dispatchers.IO) { stazione }
        val r = richiesta
        if (trovata == null && f != null) {
            val partenze = Transitous.partenze(f.id, f.lat, f.lon, da = r.previsto * 1000 - 5 * 60_000)
            trovata = scegliCorsa(partenze, r.lineaId, r.direzione, r.previsto * 1000)?.corsaId
        }
        // il ritardo di ASTRAL si aggiorna come nel tabellone della stazione
        val o = orario
        if (r.corsaAstral != null && f != null && o != null) {
            val linee = withContext(Dispatchers.IO) { lineeGtfsTreniCotral(o.lineeAllaFermata(f.id)) }
            val treni = try {
                Astral.passaggi(f.nome, linee, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            treni?.firstOrNull { it.passaggio.corsaId == r.corsaAstral }?.passaggio?.ritardo?.let { ritardo = it }
        }
        val id = trovata
        if (id == null) {
            Esito(null, "Non trovo questo treno negli orari: torna indietro e riprova.")
        } else {
            val tratto = Transitous.corsa(id)
            if (tratto == null) {
                Esito(null, "Non trovo le fermate di questo treno.")
            } else {
                val d = withContext(Dispatchers.Default) {
                    dettaglioCorsa(tratto, r.fermataId, f?.lat ?: Double.NaN, f?.lon ?: Double.NaN, ritardo)
                }
                Esito(d, null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Esito(null, "Non riesco a leggere le fermate. Controlla la connessione e riprova.")
    }
}

/** Il nome della linea nel dettaglio: quello delle stazioni per i treni Cotral ("Metromare"). */
fun nomeLineaCorsa(d: DettaglioCorsa?, lineaId: String, orario: Orario?): String =
    d?.tratto?.linea?.let { if (Rete.di(lineaId) == Rete.COTRAL_TRENI) nomeLineaTreniCotral(it) else it }
        ?: orario?.linea(lineaId)?.nome.orEmpty()

/** Da dove vengono gli orari del dettaglio; [breve] per l'orologio. */
fun DettaglioCorsa.testoFonte(breve: Boolean = false): String {
    val quiFermata = qui?.let { fermate.getOrNull(it) }
    val minuti = (quiFermata ?: fermate.firstOrNull { it.ritardoMinuti != null })?.ritardoMinuti
    val luogo = quiFermata?.nome?.let { " a $it" }.orEmpty()
    val ritardo = when {
        minuti == null -> ""
        minuti == 0 -> ": in orario$luogo"
        minuti > 0 -> ": $minuti min di ritardo$luogo"
        else -> ": ${-minuti} min di anticipo$luogo"
    }
    return when {
        tratto.cancellato -> "Corsa soppressa."
        fonte == FonteCorsa.TEMPO_REALE ->
            "In tempo reale$ritardo." + if (breve) "" else " Le fermate sono solo quelle dove il treno ferma."
        fonte == FonteCorsa.ASTRAL ->
            "Ritardo da ASTRAL$ritardo" + if (breve) "." else "; per le stazioni dopo è una stima."
        breve -> "Orario programmato: ora non c'è il tempo reale."
        else -> "Orario programmato: per questo treno ora non c'è il tempo reale. Le fermate sono solo quelle dove ferma."
    }
}
