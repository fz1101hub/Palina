package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** I passaggi delle fermate mostrate (Home dell'app), e di quando sono. */
data class ArriviFermate(
    val perFermata: Map<String, List<Arrivo>> = emptyMap(),
    val aggiornatoAlle: Long = 0L,
    val errore: Boolean = false,
    /** Il feed di Roma Mobilita' si legge ma non ha nessuna corsa (o e' fermo). */
    val romaFerma: Boolean = false,
)

private suspend fun <T> seNonFallisce(blocco: suspend () -> T): T? =
    try {
        blocco()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * Tiene i passaggi di un gruppo di fermate, da chiedere ogni 30 secondi. Per
 * tutte: il feed in tempo reale di Roma Mobilita' (uno solo per tutta la citta').
 * Per le [complete] anche gli orari di Transitous o di ASTRAL, al massimo una
 * volta al minuto per fermata. Lo usano la Home di Android e quella dell'iPhone.
 */
class CaricatoreArrivi {
    private val fonti = HashMap<String, Pair<Long, FontiFermata>>()

    suspend fun aggiorna(
        orario: Orario,
        complete: List<Fermata>,
        soloTempoReale: List<String>,
        precedente: ArriviFermate,
    ): ArriviFermate {
        val idComplete = complete.map { it.id }
        val adessoMs = adessoMs()
        // i bus Cotral col GPS servono solo se c'e' una fermata Cotral
        val conCotral = (idComplete + soloTempoReale).any { Rete.di(it) == Rete.COTRAL }
        val (corse, mezzi) = coroutineScope {
            val c = async { seNonFallisce { TempoReale.corse() } }
            val m = async { seNonFallisce { TempoReale.mezzi() } }
            val mc = async { if (conCotral) seNonFallisce { BusOne.mezzi() } else null }
            // per le stazioni di scambio (Metromare e Metro B...) le fonti di ogni loro fermata
            val daCaricare = withContext(dispatcherDati) {
                complete.flatMap { f ->
                    val membri = orario.stazioneUnita(f.id)
                    if (membri.size == 1) listOf(f) else orario.fermate(membri).values.toList()
                }.distinctBy { it.id }
            }
            daCaricare.map { f ->
                async {
                    val gia = fonti[f.id]
                    if (gia == null || adessoMs - gia.first > 60_000) {
                        caricaFonti(f, orario, adessoMs).takeIf { !it.vuote }?.let { fonti[f.id] = adessoMs to it }
                    }
                }
            }.awaitAll()
            // i codici Cotral hanno il prefisso "C:": non si confondono con quelli di Roma
            c.await() to (m.await().orEmpty() + mc.await().orEmpty())
        }
        val fresche = fonti.values.any { it.first == adessoMs }
        if (corse == null && !fresche) {
            // senza rete restano i tempi gia' letti, che si consumano da soli
            return precedente.copy(errore = true)
        }
        val ids = (idComplete + soloTempoReale).distinct()
        val adesso = adessoMs() / 1000
        val calcolati = withContext(dispatcherDati) {
            ids.associateWith { id ->
                // il tempo reale di Roma Mobilita' vale solo per le sue fermate (lo filtra arriviStazione)
                val membri = orario.stazioneUnita(id)
                arriviStazione(membri, orario, corse, mezzi, membri.associateWith { fonti[it]?.second }, adesso)
            }
        }
        return ArriviFermate(calcolati, adessoMs(), errore = false, romaFerma = TempoReale.fermo(corse))
    }
}
