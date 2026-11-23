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

/** Chiaro o scuro: automatico segue il telefono. */
enum class Aspetto { AUTOMATICO, CHIARO, SCURO }

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

    /** Per le risposte piccole (partenze di una fermata, ricerca di indirizzi): al massimo 20 secondi in tutto. */
    val httpBreve: OkHttpClient by lazy { http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

    /** Per i feed in tempo reale (alcuni MB) e il calcolo dei tragitti: al massimo 45 secondi in tutto. */
    val httpMedio: OkHttpClient by lazy { http.newBuilder().callTimeout(45, TimeUnit.SECONDS).build() }

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

    /** Linee fissate in cima alle fermate: chiavi "fermata|linea" (vedi [chiaveFissata]). */
    private val _fissate = MutableStateFlow<Set<String>>(emptySet())
    val fissate: StateFlow<Set<String>> = _fissate.asStateFlow()

    private val _aspetto = MutableStateFlow(Aspetto.AUTOMATICO)
    val aspetto: StateFlow<Aspetto> = _aspetto.asStateFlow()

    /** Cifre dei tempi con il carattere a punti delle paline elettroniche. */
    private val _cifreLed = MutableStateFlow(true)
    val cifreLed: StateFlow<Boolean> = _cifreLed.asStateFlow()

    private val impostazioni by lazy { app.getSharedPreferences("palina", Context.MODE_PRIVATE) }

    fun inizializza(context: Context) {
        app = context.applicationContext
        _preferiti.value = impostazioni.getStringSet("preferiti", emptySet())?.toSet() ?: emptySet()
        _fissate.value = impostazioni.getStringSet("linee_fissate", emptySet())?.toSet() ?: emptySet()
        _aspetto.value = impostazioni.getString("aspetto", null)
            ?.let { nome -> Aspetto.entries.firstOrNull { it.name == nome } } ?: Aspetto.AUTOMATICO
        _cifreLed.value = impostazioni.getBoolean("cifre_led", true)
        ambito.launch {
            pulisciAvanzi()
            try {
                installaOrarioIncluso()
            } catch (e: Exception) {
                Log.e("Archivio", "Orario incluso non installato", e)
            }
            try {
                installaReti()
            } catch (e: Exception) {
                Log.e("Archivio", "Reti Cotral e Trenitalia non installate", e)
            }
            apri()
            _pronto.value = true
            aggiornaInWiFi()
        }
    }

    private fun fileDb() = File(app.filesDir, "orario.db")

    /** File lasciati a meta' da un aggiornamento interrotto (app chiusa mentre lavorava). */
    private fun pulisciAvanzi() {
        listOf(
            File(app.filesDir, "orario-nuovo.db"),
            File(app.filesDir, "orario-nuovo.db-journal"),
            File(app.filesDir, "orario-nuovo.db-wal"),
            File(app.filesDir, "orario-nuovo.db-shm"),
            File(app.filesDir, "orario-incluso.tmp"),
            File(app.filesDir, "reti-incluse.tmp"),
            File(app.cacheDir, "orario.zip"),
        ).forEach { runCatching { it.delete() } }
    }

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

    private fun fileReti() = File(app.filesDir, "reti.db")

    /**
     * Fermate e linee di Cotral (bus e treni) e le stazioni Trenitalia del Lazio:
     * sono nell'APK (reti.db) e si aggiornano con l'app, non dal telefono. Si
     * copiano al primo avvio e quando l'APK ne porta una versione diversa.
     */
    private fun installaReti() {
        val creato = runCatching {
            app.assets.open("reti-incluse.txt").bufferedReader().use { it.readText().trim().toLong() }
        }.getOrNull() ?: return
        if (fileReti().exists() && impostazioni.getLong("reti_create", 0L) == creato) return
        val copia = File(app.filesDir, "reti-incluse.tmp")
        try {
            app.assets.open("reti.db").use { ingresso ->
                copia.outputStream().use { ingresso.copyTo(it, 1 shl 16) }
            }
            SQLiteDatabase.openDatabase(copia.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM fermate", null).use { c ->
                    if (!c.moveToFirst() || c.getInt(0) == 0) throw IOException("Reti incluse vuote")
                }
            }
            if (!copia.renameTo(fileReti())) throw IOException("Impossibile salvare le reti incluse")
        } finally {
            copia.delete()
        }
        impostazioni.edit().putLong("reti_create", creato).apply()
    }

    /** Senza costi: rete Wi-Fi o comunque non a consumo. */
    private fun reteSenzaCosti(): Boolean = runCatching {
        // runCatching copre anche i telefoni senza il servizio di rete
        val gestore = app.getSystemService(ConnectivityManager::class.java)
        val rete = gestore.getNetworkCapabilities(gestore.activeNetwork)
        rete != null && rete.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !gestore.isActiveNetworkMetered
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
            // le altre reti sono un di piu': se mancano o non si aprono l'app funziona con Roma
            val reti = fileReti().takeIf { it.exists() }?.let { f ->
                runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }
                    .onFailure { Log.e("Archivio", "Reti non leggibili", it) }
                    .getOrNull()
            }
            val vecchio = _orario.value
            _orario.value = Orario(db, reti)
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

    /** Scarica e prepara l'orario nuovo. Una sola volta per volta, e solo dopo l'avvio. */
    @Synchronized
    fun aggiorna() {
        if (!_pronto.value || _aggiornamento.value is StatoAggiornamento.InCorso) return
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
        val nuovo = File(app.filesDir, "orario-nuovo.db")
        try {
            scarica(zip) { stato("Scarico l'orario di Roma Mobilità", 0.4f * it) }
            nuovo.delete()
            File(nuovo.path + "-journal").delete()
            val scrittore = ScrittoreDb(nuovo)
            try {
                ZipFile(zip).use { z ->
                    CostruttoreOrario(z, scrittore) { fase, p -> stato(fase, 0.4f + 0.6f * p) }.costruisci()
                }
                scrittore.completa()
            } catch (e: Throwable) {
                runCatching { scrittore.annulla() }
                throw e
            }
            if (!nuovo.renameTo(fileDb())) throw IOException("Impossibile salvare l'orario")
        } finally {
            // riuscito o no, non restano 45 MB di zip ne' un database a meta'
            zip.delete()
            nuovo.delete()
        }
        impostazioni.edit().putLong("orario_aggiornato", System.currentTimeMillis()).apply()
        apri()
    }

    private fun scarica(destinazione: File, progresso: (Float) -> Unit) {
        val richiesta = Request.Builder().url(URL_ORARIO).header("User-Agent", agenteUtente).build()
        http.newCall(richiesta).execute().use { risposta ->
            if (!risposta.isSuccessful) throw IOException("Il server ha risposto ${risposta.code}")
            val corpo = risposta.body
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

    fun alternaLineaFissata(fermataId: String, lineaId: String) {
        val chiave = chiaveFissata(fermataId, lineaId)
        val nuove = _fissate.value.toMutableSet()
        if (!nuove.add(chiave)) nuove.remove(chiave)
        _fissate.value = nuove
        impostazioni.edit().putStringSet("linee_fissate", nuove).apply()
    }

    fun scegliAspetto(aspetto: Aspetto) {
        _aspetto.value = aspetto
        impostazioni.edit().putString("aspetto", aspetto.name).apply()
    }

    fun scegliCifreLed(attive: Boolean) {
        _cifreLed.value = attive
        impostazioni.edit().putBoolean("cifre_led", attive).apply()
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
