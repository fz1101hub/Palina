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

/** Un passaggio con la sua linea, prima di raggruppare per linea e direzione. */
data class PassaggioLinea(
    val lineaId: String,
    val nomeLinea: String?,
    val direzione: Int?,
    val capolinea: String,
    val passaggio: Passaggio,
)

/**
 * I prossimi passaggi alla fermata secondo il feed in tempo reale di Roma Mobilita',
 * raggruppati per linea e direzione.
 */
fun arriviAllaFermata(
    corse: List<CorsaRT>,
    fermataId: String,
    orario: Orario?,
    adesso: Long,
    orizzonteMinuti: Int = 90,
    tracciate: Set<String> = emptySet(),
): List<Arrivo> = raggruppa(passaggiTempoReale(corse, fermataId, orario, adesso, orizzonteMinuti, tracciate), orario)

/** I passaggi del feed in tempo reale; la direzione e' quella dell'orario, se nota. */
fun passaggiTempoReale(
    corse: List<CorsaRT>,
    fermataId: String,
    orario: Orario?,
    adesso: Long,
    orizzonteMinuti: Int = 90,
    tracciate: Set<String> = emptySet(),
): List<PassaggioLinea> {
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
    return passaggi.map { (corsa, quando) ->
        val dati = info[corsa.corsaId]
        val direzione = dati?.direzione ?: corsa.direzione
        val capolinea = bellaScrittura(corsa.capolinea).ifEmpty { dati?.capolinea ?: "" }.ifEmpty {
            lineeFermata.firstOrNull {
                it.linea.id == corsa.lineaId && (direzione == null || it.direzione == direzione)
            }?.capolinea ?: ""
        }
        val tracciato = corsa.veicolo.isNotEmpty() || corsa.corsaId in tracciate
        PassaggioLinea(corsa.lineaId, null, direzione, capolinea, Passaggio(quando, corsa.corsaId, tracciato))
    }
}

/** Possibili identificativi locali dentro un identificativo di Transitous ("data_ora_dati_id"). */
internal fun candidatiLocali(id: String): List<String> {
    val parti = id.split('_')
    return (1..parti.size).map { k -> parti.takeLast(k).joinToString("_") } + id
}

/**
 * I passaggi di Transitous (orario programmato, con il tempo reale quando c'e'),
 * riportati agli identificativi dell'orario dell'app. [adesso] in secondi.
 */
fun passaggiProgrammati(
    partenze: List<PartenzaTransitous>,
    orario: Orario?,
    adesso: Long,
    orizzonteMinuti: Int = 90,
    mezzi: List<MezzoRT> = emptyList(),
): List<PassaggioLinea> {
    val utili = partenze.filter { p ->
        !p.cancellata && (p.orario / 1000 - adesso) in -30..(orizzonteMinuti * 60L)
    }
    if (utili.isEmpty()) return emptyList()
    val candidati = utili.associateWith { candidatiLocali(it.corsaId) }
    val info = orario?.corse(candidati.values.flatten()) ?: emptyMap()
    return utili.map { p ->
        val locale = candidati.getValue(p).firstOrNull { it in info }
        val dati = locale?.let { info[it] }
        val lineaId = dati?.lineaId
            ?: candidatiLocali(p.lineaId).firstOrNull { orario?.linea(it) != null }
            ?: p.linea
        val corsa = locale ?: p.corsaId
        val tracciato = p.tempoReale || mezzoDellaCorsa(mezzi, corsa) != null
        PassaggioLinea(
            lineaId = lineaId,
            nomeLinea = p.linea.takeIf { orario?.linea(lineaId) == null },
            direzione = dati?.direzione ?: p.direzione,
            capolinea = dati?.capolinea?.takeIf { it.isNotEmpty() } ?: p.capolinea,
            passaggio = Passaggio(p.orario / 1000, corsa, tracciato),
        )
    }
}

/**
 * Unisce i due elenchi: per le corse presenti in entrambi vale il tempo reale di
 * Roma Mobilita'; si scarta anche un programmato della stessa linea a meno di
 * 90 secondi da un passaggio in tempo reale (la stessa corsa con un altro codice).
 */
fun uniscPassaggi(tempoReale: List<PassaggioLinea>, programmati: List<PassaggioLinea>): List<PassaggioLinea> {
    val corseRT = tempoReale.map { it.passaggio.corsaId }.toSet()
    val aggiunti = programmati.filter { p ->
        candidatiLocali(p.passaggio.corsaId).none { it in corseRT } && tempoReale.none { r ->
            r.lineaId == p.lineaId &&
                (r.direzione == null || p.direzione == null || r.direzione == p.direzione) &&
                kotlin.math.abs(r.passaggio.orario - p.passaggio.orario) < 90
        }
    }
    return tempoReale + aggiunti
}

/** Raggruppa per linea, direzione e capolinea, dal primo passaggio. */
fun raggruppa(elenco: List<PassaggioLinea>, orario: Orario?): List<Arrivo> {
    val gruppi = LinkedHashMap<Triple<String, Int?, String>, MutableList<PassaggioLinea>>()
    for (p in elenco) gruppi.getOrPut(Triple(p.lineaId, p.direzione, p.capolinea)) { ArrayList() }.add(p)
    return gruppi.map { (chiave, voci) ->
        Arrivo(
            lineaId = chiave.first,
            nomeLinea = orario?.linea(chiave.first)?.nome ?: voci.firstNotNullOfOrNull { it.nomeLinea } ?: chiave.first,
            direzione = chiave.second,
            capolinea = chiave.third,
            passaggi = voci.map { it.passaggio }.sortedBy { it.orario }.distinctBy { it.orario },
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

/** Le vetture di una linea: quelle nella direzione scelta e quelle nell'altra. */
data class VettureLinea(val inDirezione: List<MezzoRT>, val altre: List<MezzoRT>)

/**
 * Completa le vetture senza corsa o senza linea con i dati delle corse in tempo
 * reale. Con la corsa nota si usa quella; altrimenti il numero di vettura, ma
 * solo se compare in una sola corsa e in una sola vettura (aziende diverse
 * possono avere numeri uguali) e se la linea non contraddice quella del feed.
 */
fun completaMezzi(mezzi: List<MezzoRT>, corse: List<CorsaRT>): List<MezzoRT> {
    val perCorsa = corse.associateBy { it.corsaId }
    val perVettura = corse.filter { it.veicolo.isNotEmpty() }.groupBy { it.veicolo }
        .filterValues { it.size == 1 }.mapValues { it.value.single() }
    val vettureUguali = mezzi.groupingBy { it.etichetta }.eachCount()
    return mezzi.map { m ->
        if (m.corsaId.isNotEmpty() && m.lineaId.isNotEmpty()) return@map m
        val corsa = when {
            m.corsaId.isNotEmpty() -> perCorsa[m.corsaId]
            m.etichetta.isNotEmpty() && vettureUguali[m.etichetta] == 1 -> perVettura[m.etichetta]
            else -> null
        } ?: return@map m
        if (m.lineaId.isNotEmpty() && !m.lineaId.equals(corsa.lineaId, ignoreCase = true)) return@map m
        m.copy(
            corsaId = m.corsaId.ifEmpty { corsa.corsaId },
            lineaId = m.lineaId.ifEmpty { corsa.lineaId },
            direzione = m.direzione ?: corsa.direzione,
        )
    }
}

/**
 * Le vetture di una linea. La linea e' quella indicata dal feed; se manca, quella
 * della corsa nell'orario. Il codice si confronta anche con il nome della linea
 * e senza badare alle maiuscole ("nMA" e "NMA"). La direzione viene dall'orario
 * se la corsa e' nota, altrimenti dal feed; senza direzione la vettura conta
 * nella direzione scelta.
 */
fun vettureDellaLinea(
    mezzi: List<MezzoRT>,
    lineaId: String,
    nomeLinea: String?,
    direzione: Int?,
    orario: Orario?,
): VettureLinea {
    val info = orario?.corse(mezzi.map { it.corsaId }) ?: emptyMap()
    fun stessaLinea(id: String) =
        id.isNotEmpty() && (id.equals(lineaId, ignoreCase = true) || (nomeLinea != null && id.equals(nomeLinea, ignoreCase = true)))
    val dellaLinea = mezzi.filter { m ->
        if (m.lineaId.isNotEmpty()) stessaLinea(m.lineaId) else info[m.corsaId]?.let { stessaLinea(it.lineaId) } ?: false
    }
    if (direzione == null) return VettureLinea(dellaLinea, emptyList())
    val (dentro, fuori) = dellaLinea.partition { (info[it.corsaId]?.direzione ?: it.direzione ?: direzione) == direzione }
    return VettureLinea(dentro, fuori)
}

/**
 * Distanza in metri di un punto dal tracciato (la spezzata [forma]).
 * Infinita se il tracciato e' vuoto.
 */
fun distanzaDalTracciato(lat: Double, lon: Double, forma: List<Punto>): Double {
    if (forma.isEmpty()) return Double.POSITIVE_INFINITY
    if (forma.size == 1) return distanzaMetri(lat, lon, forma[0].lat, forma[0].lon)
    // piano locale in metri attorno al punto: basta per distanze di qualche chilometro
    val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
    val ky = 110_540.0
    var minimo = Double.POSITIVE_INFINITY
    for (i in 0 until forma.size - 1) {
        val ax = (forma[i].lon - lon) * kx
        val ay = (forma[i].lat - lat) * ky
        val bx = (forma[i + 1].lon - lon) * kx
        val by = (forma[i + 1].lat - lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val lunghezza = dx * dx + dy * dy
        val t = if (lunghezza == 0.0) 0.0 else (-(ax * dx + ay * dy) / lunghezza).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        minimo = minOf(minimo, kotlin.math.sqrt(px * px + py * py))
    }
    return minimo
}
