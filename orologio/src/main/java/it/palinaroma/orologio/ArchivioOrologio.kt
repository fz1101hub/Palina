package it.palinaroma.orologio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.palinaroma.ponte.CodificaCorsa
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.CorsaOrologio
import it.palinaroma.ponte.Ponte
import it.palinaroma.ponte.RichiestaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.TrenoOrologio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Se il telefono risponde. */
enum class StatoTelefono { SCONOSCIUTO, RAGGIUNTO, NON_RAGGIUNGIBILE }

/**
 * L'ultimo tabellone ricevuto dal telefono, salvato sull'orologio (per il
 * riquadro e la complicazione, e per mostrare subito qualcosa all'apertura), e
 * la richiesta di uno nuovo.
 */
object ArchivioOrologio {
    private const val PREFERENZE = "orologio"
    private const val CHIAVE = "tabellone"

    /** Un tabellone piu' vecchio di cosi' si chiede di nuovo al telefono. */
    const val VECCHIO_DOPO_MS = 60_000L

    private val _tabellone = MutableStateFlow<TabelloneOrologio?>(null)
    val tabellone: StateFlow<TabelloneOrologio?> = _tabellone.asStateFlow()

    private val _telefono = MutableStateFlow(StatoTelefono.SCONOSCIUTO)
    val telefono: StateFlow<StatoTelefono> = _telefono.asStateFlow()

    /** Le fermate dell'ultimo treno chiesto (vedi [richiediCorsa]). */
    private val _corsa = MutableStateFlow<CorsaOrologio?>(null)
    val corsa: StateFlow<CorsaOrologio?> = _corsa.asStateFlow()

    @Volatile
    private var caricato = false

    @Volatile
    private var ultimaRichiesta = 0L

    /** Per le richieste lanciate dal riquadro e dalla complicazione, che non aspettano la risposta. */
    private val sfondo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun preferenze(context: Context) = context.getSharedPreferences(PREFERENZE, Context.MODE_PRIVATE)

    /** Il tabellone salvato (letto una volta sola). */
    fun carica(context: Context): TabelloneOrologio? {
        if (!caricato) {
            caricato = true
            if (_tabellone.value == null) {
                _tabellone.value = CodificaOrologio.leggi(preferenze(context).getString(CHIAVE, null))
            }
        }
        return _tabellone.value
    }

    /** Un tabellone arrivato dal telefono: si salva e si aggiornano riquadro e complicazione. */
    fun salva(context: Context, json: String) {
        val nuovo = CodificaOrologio.leggi(json) ?: return
        carica(context)
        val attuale = _tabellone.value
        if (attuale != null && attuale.creato > nuovo.creato) return
        preferenze(context).edit().putString(CHIAVE, json).apply()
        _tabellone.value = nuovo
        _telefono.value = StatoTelefono.RAGGIUNTO
        aggiornaSuperfici(context)
    }

    /** Le fermate di un treno arrivate dal telefono. */
    fun salvaCorsa(json: String) {
        val nuova = CodificaCorsa.leggi(json) ?: return
        val attuale = _corsa.value
        if (attuale != null && attuale.chiave == nuova.chiave && attuale.creato > nuova.creato) return
        _corsa.value = nuova
        _telefono.value = StatoTelefono.RAGGIUNTO
    }

    /** Ridisegna riquadro, widget e complicazione con i dati nuovi. */
    fun aggiornaSuperfici(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(Riquadro::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, Complicazione::class.java))
                .requestUpdateAll()
        }
        // i widget ci sono da Wear OS 7 (Android 17): prima le loro classi non esistono sull'orologio
        if (Build.VERSION.SDK_INT >= 37) {
            sfondo.launch {
                try {
                    WidgetPalina().triggerUpdateAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // nessun widget aggiunto, o widget non supportati da questo orologio
                }
            }
        }
    }

    fun vecchio(tabellone: TabelloneOrologio?, adessoMs: Long = System.currentTimeMillis()): Boolean =
        tabellone == null || adessoMs - tabellone.creato > VECCHIO_DOPO_MS

    /**
     * Chiede al telefono un tabellone nuovo: arriva poi come dato condiviso
     * (vedi [Ricevitore]). Al massimo una richiesta ogni 20 secondi, salvo [forza].
     * Falso se nessun telefono con Palina e' raggiungibile.
     */
    suspend fun richiedi(context: Context, forza: Boolean = false): Boolean {
        val adesso = System.currentTimeMillis()
        if (!forza && adesso - ultimaRichiesta < 20_000) return _telefono.value != StatoTelefono.NON_RAGGIUNGIBILE
        ultimaRichiesta = adesso
        // con le linee seguite: il telefono le tiene nel tabellone anche lontano dalla fermata
        return manda(context, Ponte.RICHIESTA, RichiestaOrologio.scrivi(listOfNotNull(Seguito.stato.value?.lineaSeguita)))
    }

    /**
     * Chiede al telefono le fermate del [treno]: arrivano poi in [corsa]
     * (vedi [Ricevitore]). Falso se il telefono non e' raggiungibile.
     */
    suspend fun richiediCorsa(context: Context, treno: TrenoOrologio): Boolean =
        manda(context, Ponte.RICHIESTA_CORSA, treno.testoJson().toByteArray(Charsets.UTF_8))

    /** Un messaggio ai telefoni con Palina (o, se non si trovano, a quelli collegati). */
    private suspend fun manda(context: Context, percorso: String, dati: ByteArray): Boolean =
        try {
            withTimeout(15_000) {
                val nodi = telefoni(context)
                if (nodi.isEmpty()) {
                    _telefono.value = StatoTelefono.NON_RAGGIUNGIBILE
                    false
                } else {
                    val messaggi = Wearable.getMessageClient(context)
                    nodi.forEach { messaggi.sendMessage(it.id, percorso, dati).await() }
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _telefono.value = StatoTelefono.NON_RAGGIUNGIBILE
            false
        }

    private suspend fun telefoni(context: Context): Set<Node> {
        val conPalina = Wearable.getCapabilityClient(context)
            .getCapability(Ponte.CAPACITA_TELEFONO, CapabilityClient.FILTER_REACHABLE).await().nodes
        return conPalina.ifEmpty { Wearable.getNodeClient(context).connectedNodes.await().toSet() }
    }

    /** Come [richiedi], senza aspettare: per riquadro e complicazione. */
    fun richiediInSfondo(context: Context) {
        val app = context.applicationContext
        sfondo.launch { richiedi(app) }
    }

    /** All'apertura: il dato condiviso c'e' gia' se il telefono l'ha pubblicato mentre l'app era chiusa. */
    suspend fun leggiDatoCondiviso(context: Context) {
        try {
            val elementi = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*" + Ponte.TABELLONE)).await()
            try {
                elementi.forEach { e -> DataMapItem.fromDataItem(e).dataMap.getString(Ponte.CAMPO_JSON)?.let { salva(context, it) } }
            } finally {
                elementi.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // senza servizi Google o senza telefono: resta il tabellone salvato
        }
    }
}

/** Riceve i tabelloni e le fermate dei treni pubblicati dal telefono, anche ad app chiusa. */
class Ricevitore : WearableListenerService() {
    override fun onDataChanged(eventi: DataEventBuffer) {
        eventi.forEach { e ->
            if (e.type != DataEvent.TYPE_CHANGED) return@forEach
            val json = DataMapItem.fromDataItem(e.dataItem).dataMap.getString(Ponte.CAMPO_JSON) ?: return@forEach
            when (e.dataItem.uri.path) {
                Ponte.TABELLONE -> ArchivioOrologio.salva(applicationContext, json)
                Ponte.CORSA -> ArchivioOrologio.salvaCorsa(json)
            }
        }
    }
}
