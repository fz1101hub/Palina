package it.palinaroma.dati

import kotlinx.coroutines.CoroutineDispatcher

/** Una risposta HTTP: codice, corpo e intestazioni (i nomi senza distinzione di maiuscole). */
class RispostaHttp(val codice: Int, val corpo: ByteArray, private val intestazioni: Map<String, String>) {
    val riuscita: Boolean get() = codice in 200..299
    fun intestazione(nome: String): String? = intestazioni.entries.firstOrNull { it.key.equals(nome, ignoreCase = true) }?.value
    fun testo(): String = corpo.decodeToString()
}

/** Quanto si aspetta una risposta: 20 secondi (risposte piccole), 45 (feed e tragitti), di piu' (l'orario). */
enum class Attesa { BREVE, MEDIA, LUNGA }

/**
 * Chi fa le richieste HTTP: OkHttp su Android, NSURLSession su iPhone e Apple
 * Watch. Lancia un'eccezione se la rete non risponde; i codici di errore HTTP
 * arrivano nella risposta.
 */
interface Http {
    suspend fun richiedi(
        url: String,
        intestazioni: Map<String, String>,
        attesa: Attesa,
        corpo: ByteArray? = null,
        tipoCorpo: String? = null,
    ): RispostaHttp
}

/** Rete, nome dell'app e thread per i dati: li sceglie ogni piattaforma all'avvio. */
object Collegamento {
    lateinit var http: Http

    /** "PalinaRoma-Android/0.20", "PalinaRoma-iOS/0.1": il contatto e' l'indirizzo del progetto, mai una email. */
    var agenteUtente: String = "PalinaRoma"
}

/** Un errore di rete o una risposta che non si puo' usare. */
class ErroreRete(messaggio: String) : Exception(messaggio)

/** Il thread per leggere i file e la rete (Dispatchers.IO). */
expect val dispatcherDati: CoroutineDispatcher
