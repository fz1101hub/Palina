package it.palinaroma.dati

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

fun haPermessoPosizione(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Con questa precisione (in metri) si possono gia' cercare fermate e tragitti. */
const val PRECISIONE_SUFFICIENTE = 100f

/** Con questa precisione si smette di cercare. */
private const val PRECISIONE_OTTIMA = 25f

/** Le posizioni piu' vecchie di cosi' non si mostrano nemmeno come prima stima. */
private const val ETA_MASSIMA_MS = 10 * 60_000L

/** L'ultima posizione trovata dall'app, riusata subito finche' e' recente. */
@Volatile
private var memoria: Location? = null

/** Da quanti millisecondi e' stata rilevata la posizione. */
fun Location.etaMs(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

/** Precisione in metri (5 km se il telefono non la indica). */
fun Location.precisione(): Float = if (hasAccuracy()) accuracy else 5_000f

/** L'ultima posizione nota all'app, per esempio per cercare indirizzi vicini. */
fun ultimaPosizioneNota(): Location? = memoria?.takeIf { it.etaMs() < 30 * 60_000L }

private fun meglioDi(nuova: Location, vecchia: Location?): Boolean {
    if (vecchia == null) return true
    val differenza = vecchia.etaMs() - nuova.etaMs() // positiva se la nuova e' piu' recente
    if (differenza > 60_000) return true
    if (differenza < -60_000) return false
    return nuova.precisione() < vecchia.precisione()
}

private fun serviziGoogle(context: Context): Boolean =
    runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

/**
 * Posizioni sempre migliori, appena arrivano: subito l'ultima nota (se recente),
 * in uno o due secondi quella da Wi-Fi e rete, poi il GPS. Il flusso finisce
 * quando la precisione e' di pochi metri, quando tutte le richieste hanno
 * risposto o dopo [durataMs].
 */
@SuppressLint("MissingPermission")
fun posizioni(context: Context, durataMs: Long = 15_000): Flow<Location> = callbackFlow {
    if (!haPermessoPosizione(context)) {
        close()
        awaitClose { }
        return@callbackFlow
    }
    val app = context.applicationContext
    val principale = ContextCompat.getMainExecutor(app)
    var migliore: Location? = null
    var inCorso = 0
    val annullamenti = ArrayList<() -> Unit>()

    fun proponi(posizione: Location?) {
        if (posizione == null || (posizione.latitude == 0.0 && posizione.longitude == 0.0)) return
        if (posizione.etaMs() > ETA_MASSIMA_MS || !meglioDi(posizione, migliore)) return
        migliore = posizione
        memoria = posizione
        trySend(posizione)
        if (posizione.precisione() <= PRECISIONE_OTTIMA && posizione.etaMs() < 30_000) close()
    }

    fun finita() {
        inCorso--
        if (inCorso <= 0) close()
    }

    // 1. subito: la posizione gia' trovata dall'app e le ultime note del telefono
    proponi(memoria)
    val gestore = app.getSystemService(LocationManager::class.java)
    val fornitori = runCatching { gestore?.getProviders(true).orEmpty() }.getOrDefault(emptyList())
    fornitori.forEach { f -> proponi(runCatching { gestore?.getLastKnownLocation(f) }.getOrNull()) }

    if (serviziGoogle(app)) {
        // 2. servizi di localizzazione di Google: Wi-Fi e rete in pochi istanti, poi il GPS
        val cliente = LocationServices.getFusedLocationProviderClient(app)
        inCorso++
        runCatching {
            cliente.lastLocation.addOnCompleteListener { esito ->
                if (esito.isSuccessful) proponi(esito.result)
                finita()
            }
        }.onFailure { finita() }
        listOf(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY to 60_000L,
            Priority.PRIORITY_HIGH_ACCURACY to 15_000L,
        ).forEach { (priorita, etaAccettata) ->
            val gettone = CancellationTokenSource()
            annullamenti += { gettone.cancel() }
            val richiesta = CurrentLocationRequest.Builder()
                .setPriority(priorita)
                .setMaxUpdateAgeMillis(etaAccettata)
                .setDurationMillis(durataMs)
                .build()
            inCorso++
            runCatching {
                cliente.getCurrentLocation(richiesta, gettone.token).addOnCompleteListener { esito ->
                    if (esito.isSuccessful) proponi(esito.result)
                    finita()
                }
            }.onFailure { finita() }
        }
    } else if (gestore != null) {
        // 3. telefoni senza servizi Google: rete e GPS in parallelo
        val richiesti = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { it in fornitori }
        richiesti.forEach { f ->
            val segnale = CancellationSignal()
            annullamenti += { segnale.cancel() }
            inCorso++
            runCatching {
                LocationManagerCompat.getCurrentLocation(gestore, f, segnale, principale) { posizione ->
                    proponi(posizione)
                    finita()
                }
            }.onFailure { finita() }
        }
    }

    if (inCorso == 0) close()
    launch {
        delay(durataMs)
        close()
    }
    awaitClose { annullamenti.forEach { runCatching { it() } } }
}

/**
 * Una posizione utilizzabile il prima possibile: quella gia' nota se recente e
 * abbastanza precisa, altrimenti la prima entro [PRECISIONE_SUFFICIENTE] metri,
 * altrimenti la migliore trovata entro [attesaMs].
 */
suspend fun posizioneAttuale(context: Context, attesaMs: Long = 8_000): Location? {
    memoria?.let { if (it.etaMs() < 60_000 && it.precisione() <= PRECISIONE_SUFFICIENTE) return it }
    var migliore: Location? = null
    withTimeoutOrNull(attesaMs) {
        posizioni(context, attesaMs)
            .takeWhile { posizione ->
                migliore = posizione
                !(posizione.precisione() <= PRECISIONE_SUFFICIENTE && posizione.etaMs() < 60_000)
            }
            .collect()
    }
    return migliore ?: memoria
}

/**
 * La posizione aggiornata di continuo, ogni [intervalloMs] circa, finche' il
 * flusso e' raccolto: per il segnalino "sei qui" sulle mappe. Parte subito con
 * l'ultima posizione nota, se recente.
 */
@SuppressLint("MissingPermission")
fun aggiornamentiPosizione(context: Context, intervalloMs: Long = 4_000): Flow<Location> = callbackFlow {
    if (!haPermessoPosizione(context)) {
        close()
        awaitClose { }
        return@callbackFlow
    }
    val app = context.applicationContext
    fun nuova(posizione: Location?) {
        if (posizione == null || (posizione.latitude == 0.0 && posizione.longitude == 0.0)) return
        memoria = posizione
        trySend(posizione)
    }
    memoria?.takeIf { it.etaMs() < 2 * 60_000L }?.let { trySend(it) }

    if (serviziGoogle(app)) {
        val cliente = LocationServices.getFusedLocationProviderClient(app)
        val richiesta = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalloMs)
            .setMinUpdateIntervalMillis(intervalloMs / 2)
            .build()
        val ascoltatore = object : LocationCallback() {
            override fun onLocationResult(risultato: LocationResult) {
                risultato.locations.forEach { nuova(it) }
            }
        }
        val avviata = runCatching { cliente.requestLocationUpdates(richiesta, ascoltatore, Looper.getMainLooper()) }.isSuccess
        if (!avviata) close()
        awaitClose { runCatching { cliente.removeLocationUpdates(ascoltatore) } }
    } else {
        val gestore = app.getSystemService(LocationManager::class.java)
        // tutti i metodi implementati: su Android 8 e 9 non hanno ancora un'implementazione predefinita
        val ascoltatore = object : LocationListener {
            override fun onLocationChanged(posizione: Location) = nuova(posizione)

            @Deprecated("Solo per Android 8 e 9")
            override fun onStatusChanged(fornitore: String?, stato: Int, extra: android.os.Bundle?) = Unit

            override fun onProviderEnabled(fornitore: String) = Unit

            override fun onProviderDisabled(fornitore: String) = Unit
        }
        val fornitori = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { f ->
            runCatching { gestore?.isProviderEnabled(f) == true }.getOrDefault(false)
        }
        var avviati = 0
        fornitori.forEach { f ->
            runCatching {
                gestore?.requestLocationUpdates(f, intervalloMs, 0f, ascoltatore, Looper.getMainLooper())
                avviati++
            }
        }
        if (avviati == 0) close()
        awaitClose { runCatching { gestore?.removeUpdates(ascoltatore) } }
    }
}
