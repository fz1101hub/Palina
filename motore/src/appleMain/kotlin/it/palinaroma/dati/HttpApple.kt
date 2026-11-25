package it.palinaroma.dati

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual val dispatcherDati: CoroutineDispatcher = Dispatchers.IO

/** Le richieste HTTP su iPhone e Apple Watch, con NSURLSession. */
object HttpApple : Http {
    override suspend fun richiedi(
        url: String,
        intestazioni: Map<String, String>,
        attesa: Attesa,
        corpo: ByteArray?,
        tipoCorpo: String?,
    ): RispostaHttp = suspendCancellableCoroutine { continua ->
        val indirizzo = NSURL.URLWithString(url)
        if (indirizzo == null) {
            continua.resumeWithException(ErroreRete(tx("Indirizzo non valido")))
            return@suspendCancellableCoroutine
        }
        val richiesta = NSMutableURLRequest(indirizzo, NSURLRequestReloadIgnoringLocalCacheData, secondi(attesa))
        intestazioni.forEach { (nome, valore) -> richiesta.setValue(valore, forHTTPHeaderField = nome) }
        if (corpo != null) {
            richiesta.setHTTPMethod("POST")
            richiesta.setHTTPBody(corpo.comeNSData())
            if (tipoCorpo != null) richiesta.setValue(tipoCorpo, forHTTPHeaderField = "Content-Type")
        }
        val compito = NSURLSession.sharedSession.dataTaskWithRequest(richiesta) { dati, risposta, errore ->
            val http = risposta as? NSHTTPURLResponse
            when {
                errore != null -> continua.resumeWithException(ErroreRete(errore.localizedDescription))
                http == null -> continua.resumeWithException(ErroreRete(tx("Nessuna risposta")))
                else -> {
                    val campi = HashMap<String, String>()
                    http.allHeaderFields.forEach { (k, v) -> if (k != null && v != null) campi[k.toString()] = v.toString() }
                    continua.resume(RispostaHttp(http.statusCode.toInt(), dati?.comeByteArray() ?: ByteArray(0), campi))
                }
            }
        }
        continua.invokeOnCancellation { compito.cancel() }
        compito.resume()
    }

    private fun secondi(attesa: Attesa): Double = when (attesa) {
        Attesa.BREVE -> 20.0
        Attesa.MEDIA -> 45.0
        Attesa.LUNGA -> 600.0
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.comeNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.comeByteArray(): ByteArray {
    val n = length.toInt()
    val risultato = ByteArray(n)
    if (n > 0) risultato.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return risultato
}
