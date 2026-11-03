package it.palinaroma.dati

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun haPermessoPosizione(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** La posizione attuale (al massimo 10 secondi di attesa), o l'ultima nota. */
@SuppressLint("MissingPermission")
suspend fun posizioneAttuale(context: Context): Location? {
    if (!haPermessoPosizione(context)) return null
    val gestore = context.getSystemService(LocationManager::class.java) ?: return null
    val fornitori = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { gestore.isProviderEnabled(it) }.getOrDefault(false) }
    val ultima = fornitori.mapNotNull { runCatching { gestore.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    val fornitore = fornitori.firstOrNull() ?: return ultima
    val attuale = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { continuazione ->
            val segnale = CancellationSignal()
            continuazione.invokeOnCancellation { segnale.cancel() }
            LocationManagerCompat.getCurrentLocation(
                gestore, fornitore, segnale, ContextCompat.getMainExecutor(context),
            ) { posizione ->
                if (continuazione.isActive) continuazione.resume(posizione)
            }
        }
    }
    return attuale ?: ultima
}
