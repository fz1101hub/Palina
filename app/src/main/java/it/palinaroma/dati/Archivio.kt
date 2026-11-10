package it.palinaroma.dati

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import it.palinaroma.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

sealed interface StatoAggiornamento {
    data object Fermo : StatoAggiornamento
    data class InCorso(val fase: String, val frazione: Float) : StatoAggiornamento
    data object Completato : StatoAggiornamento
    data class Errore(val messaggio: String) : StatoAggiornamento
}

/** Tiene l'orario aperto, lo aggiorna e conserva i preferiti. */
object Archivio {
    const val URL_ORARIO = "https://romamobilita.it/sites/default/files/rome_static_gtfs.zip"
    private const val GIORNI_VALIDITA = 7

    private lateinit var app: Context
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val agenteUtente = "PalinaRoma-Android/" + BuildConfig.VERSION_NAME

    private val _orario = MutableStateFlow<Orario?>(null)
    val orario: StateFlow<Orario?> = _orario.asStateFlow()

    /** Diventa true quando l'orario salvato (o quello incluso nell'APK) e' stato aperto. */
    private val _pronto = MutableStateFlow(false)
    val pronto: StateFlow<Boolean> = _pronto.asStateFlow()

    private val _aggiornamento = MutableStateFlow<StatoAggiornamento>(StatoAggiornamento.Fermo)
    val aggiornamento: StateFlow<StatoAggiornamento> = _aggiornamento.asStateFlow()

    private val _preferiti = MutableStateFlow<Set<String>>(emptySet())
    val preferiti: StateFlow<Set<String>> = _preferiti.asStateFlow()

    private val impostazioni by lazy { app.getSharedPreferences("palina", Context.MODE_PRIVATE) }

    fun inizializza(context: Context) {
        app = context.applicationContext
        _preferiti.value = impostazioni.getStringSet("preferiti", emptySet())?.toSet() ?: emptySet()
        ambito.launch {
            try {
                installaOrarioIncluso()
            } catch (e: Exception) {
                Log.e("Archivio", "Orario incluso non installato", e)
            }
            apri()
            _pronto.value = true
            aggiornaInWiFi()
        }
    }

    private fun fileDb() = File(app.filesDir, "orario.db")

    /** Quando e' stato creato l'orario incluso nell'APK, o null se l'APK non lo contiene. */
    private fun dataOrarioIncluso(): Long? =
        runCatching {
            app.assets.open("orario-incluso.txt").bufferedReader().use { it.readText().trim().toLong() }
        }.getOrNull()

    /**
     * Al primo avvio (o se l'APK ha un orario piu' recente di quello salvato)
     * copia l'orario incluso nell'APK: l'app funziona subito, senza scaricare nulla.
     */
    private fun installaOrarioIncluso() {
        val data = dataOrarioIncluso() ?: return
        if (fileDb().exists() && ultimoAggiornamento() >= data) return
        val copia = File(app.filesDir, "orario-incluso.tmp")
        try {
            app.assets.open("orario.db").use { ingresso ->
                copia.outputStream().use { ingresso.copyTo(it, 1 shl 16) }
            }
            // prima di sostituire quello salvato controlla che si apra
            SQLiteDatabase.openDatabase(copia.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM fermate", null).use { c ->
                    if (!c.moveToFirst() || c.getInt(0) == 0) throw IOException("Orario incluso vuoto")
                }
            }
            if (!copia.renameTo(fileDb())) throw IOException("Impossibile salvare l'orario incluso")
        } finally {
            copia.delete()
        }
        impostazioni.edit().putLong("orario_aggiornato", data).apply()
    }

    /** Senza costi: rete Wi-Fi o comunque non a consumo. */
    private fun reteSenzaCosti(): Boolean = runCatching {
        val gestore = app.getSystemService(ConnectivityManager::class.java)
        val rete = gestore?.getNetworkCapabilities(gestore.activeNetwork)
        rete != null && rete.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && gestore?.isActiveNetworkMetered == false
    }.getOrDefault(false)

    /** Se l'orario ha piu' di una settimana lo aggiorna da solo, ma solo in Wi-Fi. */
    private fun aggiornaInWiFi() {
        if (_orario.value != null && daAggiornare() && reteSenzaCosti()) aggiorna()
    }

    private fun apri() {
        val file = fileDb()
        if (!file.exists()) return
        try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val vecchio = _orario.value
            _orario.value = Orario(db)
            // chiude il vecchio quando le schermate hanno finito di usarlo
            if (vecchio != null) ambito.launch { delay(10_000); vecchio.chiudi() }
        } catch (e: Exception) {
            Log.e("Archivio", "Orario non leggibile", e)
            file.delete()
        }
    }

    fun ultimoAggiornamento(): Long = impostazioni.getLong("orario_aggiornato", 0L)

    fun daAggiornare(): Boolean =
        System.currentTimeMillis() - ultimoAggiornamento() > GIORNI_VALIDITA * 24L * 3600 * 1000

    fun aggiorna() {
        if (_aggiornamento.value is StatoAggiornamento.InCorso) return
        _aggiornamento.value = StatoAggiornamento.InCorso("Avvio", 0f)
        ambito.launch {
            try {
                esegui()
                _aggiornamento.value = StatoAggiornamento.Completato
            } catch (e: Throwable) {
                Log.e("Archivio", "Aggiornamento fallito", e)
                _aggiornamento.value = StatoAggiornamento.Errore(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun stato(fase: String, frazione: Float) {
        _aggiornamento.value = StatoAggiornamento.InCorso(fase, frazione.coerceIn(0f, 1f))
    }

    private fun esegui() {
        val zip = File(app.cacheDir, "orario.zip")
        scarica(zip) { stato("Scarico l'orario di Roma Mobilità", 0.4f * it) }

        val nuovo = File(app.filesDir, "orario-nuovo.db")
        nuovo.delete()
        File(nuovo.path + "-journal").delete()
        val scrittore = ScrittoreDb(nuovo)
        try {
            ZipFile(zip).use { z ->
                CostruttoreOrario(z, scrittore) { fase, p -> stato(fase, 0.4f + 0.6f * p) }.costruisci()
            }
            scrittore.completa()
        } catch (e: Throwable) {
            scrittore.annulla()
            nuovo.delete()
            throw e
        }
        if (!nuovo.renameTo(fileDb())) throw IOException("Impossibile salvare l'orario")
        zip.delete()
        impostazioni.edit().putLong("orario_aggiornato", System.currentTimeMillis()).apply()
        apri()
    }

    private fun scarica(destinazione: File, progresso: (Float) -> Unit) {
        val richiesta = Request.Builder().url(URL_ORARIO).header("User-Agent", agenteUtente).build()
        http.newCall(richiesta).execute().use { risposta ->
            if (!risposta.isSuccessful) throw IOException("Il server ha risposto ${risposta.code}")
            val corpo = risposta.body ?: throw IOException("Risposta vuota")
            val totale = corpo.contentLength()
            corpo.byteStream().use { ingresso ->
                destinazione.outputStream().use { uscita ->
                    val buffer = ByteArray(1 shl 16)
                    var letti = 0L
                    var ultimo = 0L
                    while (true) {
                        val n = ingresso.read(buffer)
                        if (n < 0) break
                        uscita.write(buffer, 0, n)
                        letti += n
                        if (totale > 0 && letti - ultimo > 500_000) {
                            ultimo = letti
                            progresso(letti.toFloat() / totale)
                        }
                    }
                }
            }
        }
    }

    fun alternaPreferito(fermataId: String) {
        val nuovi = _preferiti.value.toMutableSet()
        if (!nuovi.add(fermataId)) nuovi.remove(fermataId)
        _preferiti.value = nuovi
        impostazioni.edit().putStringSet("preferiti", nuovi).apply()
    }

    /** Ultimi luoghi scelti per i tragitti, dal piu' recente. */
    fun luoghiRecenti(): List<Luogo> =
        impostazioni.getString("luoghi_recenti", "").orEmpty().lines().mapNotNull { luogoDaRiga(it) }

    fun ricordaLuogo(luogo: Luogo) {
        if (luogo.tipo == TipoLuogo.POSIZIONE || !luogo.haCoordinate) return
        val elenco = listOf(luogo) + luoghiRecenti().filter { it.nome != luogo.nome || it.dettaglio != luogo.dettaglio }
        impostazioni.edit().putString("luoghi_recenti", elenco.take(8).joinToString("\n") { it.inRiga() }).apply()
    }
}
