package it.palinaroma

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.tx
import it.palinaroma.dati.Punto
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.posizioneAttuale
import it.palinaroma.dati.ultimaPosizioneNota
import it.palinaroma.ponte.CodificaCorsa
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.CorsaOrologio
import it.palinaroma.ponte.LineaSeguita
import it.palinaroma.ponte.Ponte
import it.palinaroma.ponte.RichiestaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.TrenoOrologio
import it.palinaroma.ponte.calcolaCorsaOrologio
import it.palinaroma.ponte.calcolaTabelloneOrologio
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il servizio che risponde all'orologio: quando l'app Wear OS chiede un
 * tabellone ([Ponte.RICHIESTA]), lo calcola qui con le stesse fonti della Home
 * e lo pubblica come dato condiviso ([Ponte.TABELLONE]), che arriva all'orologio
 * anche per il riquadro e la complicazione.
 */
class ServizioOrologio : WearableListenerService() {
    override fun onMessageReceived(evento: MessageEvent) {
        // qui siamo gia' fuori dal thread principale: si puo' aspettare il calcolo
        when (evento.path) {
            Ponte.RICHIESTA -> runBlocking {
                val seguite = RichiestaOrologio.leggi(evento.data)
                val tabellone = withTimeoutOrNull(40_000) { tabelloneOrologio(applicationContext, seguite = seguite) } ?: return@runBlocking
                pubblica(Ponte.TABELLONE, CodificaOrologio.scrivi(tabellone))
            }
            Ponte.RICHIESTA_CORSA -> runBlocking {
                val treno = TrenoOrologio.leggi(String(evento.data, Charsets.UTF_8)) ?: return@runBlocking
                val corsa = withTimeoutOrNull(40_000) { corsaOrologio(treno) }
                    ?: CorsaOrologio(treno.chiave, System.currentTimeMillis(), tx("Treno"), null, null, emptyList(), null, tx("Il telefono non ha trovato il treno in tempo: riprova."))
                pubblica(Ponte.CORSA, CodificaCorsa.scrivi(corsa))
            }
        }
    }

    private suspend fun pubblica(percorso: String, json: String) {
        val dato = PutDataMapRequest.create(percorso).apply {
            dataMap.putString(Ponte.CAMPO_JSON, json)
            // il dato cambia sempre, cosi' l'orologio lo riceve anche se il contenuto e' uguale
            dataMap.putLong("chiesto", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { withTimeoutOrNull(15_000) { Wearable.getDataClient(applicationContext).putDataItem(dato).await() } }
    }
}

/** Le fermate del [treno] chiesto dall'orologio, come nel dettaglio della corsa sul telefono (calcolo nel motore). */
suspend fun corsaOrologio(treno: TrenoOrologio): CorsaOrologio {
    val orario = withTimeoutOrNull(20_000) { Archivio.orario.filterNotNull().first() }
    return calcolaCorsaOrologio(orario, treno)
}

/**
 * Il tabellone per l'orologio: le fermate preferite (dalla piu' vicina) e
 * qualche fermata vicina, ognuna con le prossime linee (prima quelle fissate).
 * Il calcolo e' nel motore, lo stesso che l'iPhone fa per l'Apple Watch.
 */
suspend fun tabelloneOrologio(
    context: Context,
    adessoMs: Long = System.currentTimeMillis(),
    seguite: List<LineaSeguita> = emptyList(),
): TabelloneOrologio? {
    val orario = withTimeoutOrNull(20_000) { Archivio.orario.filterNotNull().first() } ?: return null
    val posizione = ultimaPosizioneNota()
        ?: if (haPermessoPosizione(context)) runCatching { posizioneAttuale(context, 5_000) }.getOrNull() else null
    return calcolaTabelloneOrologio(
        orario,
        Archivio.preferiti.value,
        Archivio.fissate.value,
        posizione?.let { Punto(it.latitude, it.longitude) },
        adessoMs,
        seguite,
    )
}
