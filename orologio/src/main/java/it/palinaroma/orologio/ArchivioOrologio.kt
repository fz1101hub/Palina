package it.palinaroma.orologio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.Ponte
import it.palinaroma.ponte.TabelloneOrologio
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

    /** Ridisegna riquadro e complicazione con i dati nuovi. */
    fun aggiornaSuperfici(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(Riquadro::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, Complicazione::class.java))
                .requestUpdateAll()
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
        return try {
            withTimeout(15_000) {
                val conPalina = Wearable.getCapabilityClient(context)
                    .getCapability(Ponte.CAPACITA_TELEFONO, CapabilityClient.FILTER_REACHABLE).await().nodes
                val nodi = conPalina.ifEmpty { Wearable.getNodeClient(context).connectedNodes.await().toSet() }
                if (nodi.isEmpty()) {
                    _telefono.value = StatoTelefono.NON_RAGGIUNGIBILE
                    false
                } else {
                    val messaggi = Wearable.getMessageClient(context)
                    nodi.forEach { messaggi.sendMessage(it.id, Ponte.RICHIESTA, ByteArray(0)).await() }
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _telefono.value = StatoTelefono.NON_RAGGIUNGIBILE
            false
        }
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

/** Riceve i tabelloni pubblicati dal telefono, anche ad app chiusa. */
class Ricevitore : WearableListenerService() {
    override fun onDataChanged(eventi: DataEventBuffer) {
        eventi.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == Ponte.TABELLONE) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString(Ponte.CAMPO_JSON)?.let {
                    ArchivioOrologio.salva(applicationContext, it)
                }
            }
        }
    }
}
