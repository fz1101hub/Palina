package it.palinaroma

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.BusOne
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.Rete
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.arriviStazione
import it.palinaroma.dati.caricaFonti
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.dividiArrivi
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.lineeFissate
import it.palinaroma.dati.posizioneAttuale
import it.palinaroma.dati.ultimaPosizioneNota
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.Ponte
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ui.coloreRete
import it.palinaroma.ui.descrizioneFermata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Quante fermate preferite e vicine vanno all'orologio, e quante righe per fermata. */
private const val PREFERITE_OROLOGIO = 6
private const val VICINE_OROLOGIO = 3
private const val RIGHE_OROLOGIO = 6

/**
 * Il servizio che risponde all'orologio: quando l'app Wear OS chiede un
 * tabellone ([Ponte.RICHIESTA]), lo calcola qui con le stesse fonti della Home
 * e lo pubblica come dato condiviso ([Ponte.TABELLONE]), che arriva all'orologio
 * anche per il riquadro e la complicazione.
 */
class ServizioOrologio : WearableListenerService() {
    override fun onMessageReceived(evento: MessageEvent) {
        if (evento.path != Ponte.RICHIESTA) return
        // qui siamo gia' fuori dal thread principale: si puo' aspettare il calcolo
        runBlocking {
            val tabellone = withTimeoutOrNull(40_000) { tabelloneOrologio(applicationContext) } ?: return@runBlocking
            val dato = PutDataMapRequest.create(Ponte.TABELLONE).apply {
                dataMap.putString(Ponte.CAMPO_JSON, CodificaOrologio.scrivi(tabellone))
                // il dato cambia sempre, cosi' l'orologio lo riceve anche se il tabellone e' uguale
                dataMap.putLong("chiesto", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            runCatching { withTimeoutOrNull(15_000) { Wearable.getDataClient(applicationContext).putDataItem(dato).await() } }
        }
    }
}

/**
 * Il tabellone per l'orologio: le fermate preferite (dalla piu' vicina) e
 * qualche fermata vicina, ognuna con le prossime linee (prima quelle fissate).
 */
suspend fun tabelloneOrologio(context: Context, adessoMs: Long = System.currentTimeMillis()): TabelloneOrologio? {
    val orario = withTimeoutOrNull(20_000) { Archivio.orario.filterNotNull().first() } ?: return null
    val adesso = adessoMs / 1000
    val posizione = ultimaPosizioneNota()
        ?: if (haPermessoPosizione(context)) runCatching { posizioneAttuale(context, 5_000) }.getOrNull() else null

    // le fermate: preferite, poi vicine che non lo sono gia'
    val scelte: List<Triple<Fermata, Double?, Boolean>> = withContext(Dispatchers.IO) {
        val preferiti = Archivio.preferiti.value.toList()
        val trovate = orario.fermate(preferiti)
        val preferite = preferiti.mapNotNull { id -> trovate[id]?.let { f -> orario.fermataUnita(id)?.copy(id = id) ?: f } }
            .map { f -> Triple(f, posizione?.let { distanzaMetri(it.latitude, it.longitude, f.lat, f.lon) }, true) }
            .sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.first.nome }))
            .take(PREFERITE_OROLOGIO)
        val giaScelte = preferite.flatMap { orario.stazioneUnita(it.first.id) }.toSet()
        val vicine = posizione?.let { p ->
            orario.fermateVicineUnite(p.latitude, p.longitude, 500.0)
                .filter { (f, _) -> orario.stazioneUnita(f.id).none { it in giaScelte } }
                .take(VICINE_OROLOGIO)
                .map { (f, d) -> Triple(f, d, false) }
        }.orEmpty()
        preferite + vicine
    }
    if (scelte.isEmpty()) {
        return TabelloneOrologio(adessoMs, emptyList(), "Aggiungi una fermata ai preferiti sul telefono, o attiva la posizione.")
    }

    // le fonti di tutte le fermate (anche quelle delle stazioni di scambio), in parallelo
    val membriPer = scelte.associate { it.first.id to orario.stazioneUnita(it.first.id) }
    val tutte = membriPer.values.flatten().distinct()
    val conRoma = tutte.any { Rete.di(it) == Rete.ROMA }
    val conCotral = tutte.any { Rete.di(it) == Rete.COTRAL }
    val (corse, mezzi, fonti) = coroutineScope {
        val c = async { if (conRoma) provando { TempoReale.corse() } else null }
        val m = async {
            val roma = if (conRoma) provando { TempoReale.mezzi() }.orEmpty() else emptyList()
            val cotral = if (conCotral) provando { BusOne.mezzi() }.orEmpty() else emptyList()
            roma + cotral
        }
        val fermate = withContext(Dispatchers.IO) { orario.fermate(tutte) }
        val f = tutte.mapNotNull { fermate[it] }
            .map { fermata -> async { fermata.id to provando { caricaFonti(fermata, orario, adessoMs) } } }
            .awaitAll()
            .mapNotNull { (id, fonti) -> fonti?.let { id to it } }
            .toMap()
        Triple(c.await(), m.await(), f)
    }

    val fissate = Archivio.fissate.value
    val fermate = withContext(Dispatchers.Default) {
        scelte.map { (f, distanza, preferita) ->
            val membri = membriPer.getValue(f.id)
            val arrivi = arriviStazione(membri, orario, corse, mezzi, membri.associateWith { fonti[it] }, adesso)
            FermataOrologio(
                id = f.id,
                nome = f.nome,
                sotto = descrizioneFermata(f),
                preferita = preferita,
                distanza = distanza?.toInt(),
                righe = righeOrologio(arrivi, lineeFissate(f.id, fissate), adesso),
            )
        }
    }
    val nota = if (conRoma && TempoReale.fermo(corse)) "Roma Mobilità ora non pubblica il tempo reale: tempi da orario." else null
    return TabelloneOrologio(adessoMs, fermate, nota)
}

/** Le righe per l'orologio: prima le linee fissate, poi le altre; al massimo [quante], tre passaggi l'una. */
fun righeOrologio(arrivi: List<Arrivo>, fissate: Set<String>, adesso: Long, quante: Int = RIGHE_OROLOGIO): List<RigaOrologio> {
    val divisi = dividiArrivi(arrivi, fissate)
    return (divisi.fissate + divisi.altre)
        .filter { a -> a.passaggi.any { it.orario >= adesso - 30 } }
        .take(quante)
        .map { a ->
            val prossimi = a.passaggi.filter { it.orario >= adesso - 30 }
            RigaOrologio(
                linea = a.nomeLinea,
                colore = coloreRete(a.lineaId).toArgb(),
                capolinea = a.capolinea,
                orari = prossimi.take(3).map { it.orario },
                tempoReale = prossimi.first().tracciato,
                avviso = prossimi.first().avviso,
            )
        }
}

private suspend fun <T> provando(blocco: suspend () -> T): T? =
    try {
        blocco()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
