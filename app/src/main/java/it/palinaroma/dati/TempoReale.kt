package it.palinaroma.dati

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Un passaggio previsto. [tracciato] vale true se la corsa ha una vettura seguita
 * via GPS; altrimenti il tempo e' calcolato sull'orario programmato.
 */
data class Passaggio(val orario: Long, val corsaId: String, val tracciato: Boolean)

/** Un gruppo di passaggi della stessa linea e direzione a una fermata. */
data class Arrivo(
    val lineaId: String,
    val nomeLinea: String,
    val direzione: Int?,
    val capolinea: String,
    val passaggi: List<Passaggio>,
) {
    val orari: List<Long> get() = passaggi.map { it.orario }
}

/** Feed in tempo reale di Roma Mobilita', con una piccola cache. */
object TempoReale {
    const val URL_CORSE = "https://romamobilita.it/sites/default/files/rome_rtgtfs_trip_updates_feed.pb"
    const val URL_MEZZI = "https://romamobilita.it/sites/default/files/rome_rtgtfs_vehicle_positions_feed.pb"

    private val mutexCorse = Mutex()
    private val mutexMezzi = Mutex()
    private var corse: List<CorsaRT> = emptyList()
    private var quandoCorse = 0L
    private var mezzi: List<MezzoRT> = emptyList()
    private var quandoMezzi = 0L

    suspend fun corse(forza: Boolean = false): List<CorsaRT> = mutexCorse.withLock {
        val adesso = System.currentTimeMillis()
        if (forza || adesso - quandoCorse > 20_000 || corse.isEmpty()) {
            corse = FeedTempoReale.corse(scarica(URL_CORSE))
            quandoCorse = adesso
        }
        corse
    }

    suspend fun mezzi(forza: Boolean = false): List<MezzoRT> = mutexMezzi.withLock {
        val adesso = System.currentTimeMillis()
        if (forza || adesso - quandoMezzi > 15_000 || mezzi.isEmpty()) {
            mezzi = FeedTempoReale.mezzi(scarica(URL_MEZZI))
            quandoMezzi = adesso
        }
        mezzi
    }

    private suspend fun scarica(url: String): ByteArray = withContext(Dispatchers.IO) {
        val richiesta = Request.Builder().url(url).header("User-Agent", Archivio.agenteUtente).build()
        Archivio.http.newCall(richiesta).execute().use { risposta ->
            if (!risposta.isSuccessful) throw IOException("Il server ha risposto ${risposta.code}")
            risposta.body?.bytes() ?: throw IOException("Risposta vuota")
        }
    }
}

/**
 * I prossimi passaggi alla fermata, raggruppati per linea e direzione.
 * La direzione viene dal feed, se c'e', altrimenti dall'orario.
 */
fun arriviAllaFermata(
    corse: List<CorsaRT>,
    fermataId: String,
    orario: Orario?,
    adesso: Long,
    orizzonteMinuti: Int = 90,
    tracciate: Set<String> = emptySet(),
): List<Arrivo> {
    val passaggi = ArrayList<Pair<CorsaRT, Long>>()
    for (corsa in corse) {
        if (corsa.cancellata) continue
        for (f in corsa.fermate) {
            if (f.saltata || !stessoCodice(f.fermataId, fermataId)) continue
            val secondi = f.orario - adesso
            if (secondi in -30..(orizzonteMinuti * 60L)) passaggi.add(corsa to f.orario)
        }
    }
    if (passaggi.isEmpty()) return emptyList()

    val info = orario?.corse(passaggi.map { it.first.corsaId }) ?: emptyMap()
    val lineeFermata = orario?.lineeAllaFermata(fermataId) ?: emptyList()

    val gruppi = LinkedHashMap<Triple<String, Int?, String>, MutableList<Passaggio>>()
    for ((corsa, quando) in passaggi) {
        val dati = info[corsa.corsaId]
        val direzione = corsa.direzione ?: dati?.direzione
        val capolinea = corsa.capolinea.let { bellaScrittura(it) }.ifEmpty { dati?.capolinea ?: "" }.ifEmpty {
            lineeFermata.firstOrNull {
                it.linea.id == corsa.lineaId && (direzione == null || it.direzione == direzione)
            }?.capolinea ?: ""
        }
        val tracciato = corsa.veicolo.isNotEmpty() || corsa.corsaId in tracciate
        gruppi.getOrPut(Triple(corsa.lineaId, direzione, capolinea)) { ArrayList() }
            .add(Passaggio(quando, corsa.corsaId, tracciato))
    }
    return gruppi.map { (chiave, elenco) ->
        Arrivo(
            lineaId = chiave.first,
            nomeLinea = orario?.linea(chiave.first)?.nome ?: chiave.first,
            direzione = chiave.second,
            capolinea = chiave.third,
            passaggi = elenco.sortedBy { it.orario }.distinctBy { it.orario },
        )
    }.sortedBy { it.orari.first() }
}

/**
 * La vettura che sta facendo una corsa. Va bene anche un identificativo di
 * Transitous, che finisce con "_" + l'identificativo di Roma Mobilita'.
 */
fun mezzoDellaCorsa(mezzi: List<MezzoRT>, corsaId: String): MezzoRT? {
    if (corsaId.isEmpty()) return null
    return mezzi.firstOrNull { it.corsaId == corsaId } ?: mezzi.firstOrNull {
        it.corsaId.length >= 3 && corsaId.endsWith("_" + it.corsaId)
    }
}

/** "adesso", "40 s fa", "3 min fa"; null se l'ora del rilevamento non e' nota. */
fun etaRilevamento(rilevato: Long, adesso: Long): String? {
    if (rilevato <= 0) return null
    val secondi = (adesso - rilevato).coerceAtLeast(0)
    return when {
        secondi < 15 -> "adesso"
        secondi < 60 -> "${secondi / 5 * 5} s fa"
        secondi < 3600 -> "${secondi / 60} min fa"
        else -> "oltre un'ora fa"
    }
}

/** I mezzi della linea; se la direzione e' nota, solo quelli in quella direzione. */
fun mezziDellaLinea(mezzi: List<MezzoRT>, lineaId: String, direzione: Int?, orario: Orario?): List<MezzoRT> {
    val dellaLinea = mezzi.filter { it.lineaId == lineaId }
    if (direzione == null) return dellaLinea
    val info = orario?.corse(dellaLinea.filter { it.direzione == null }.map { it.corsaId }) ?: emptyMap()
    return dellaLinea.filter { (it.direzione ?: info[it.corsaId]?.direzione ?: direzione) == direzione }
}
