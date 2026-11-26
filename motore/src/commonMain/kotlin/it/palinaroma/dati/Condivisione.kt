package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Monopattini, bici e scooter in condivisione vicino a te, dai dati aperti
 * (GBFS) degli operatori di Roma che li pubblicano: per ora Bird e Cooltra.
 * Lime e Dott a Roma non pubblicano dati aperti. Nei tragitti: se il primo
 * tratto a piedi e' lungo e c'e' un monopattino vicino, quanto si risparmia.
 */

/** Un operatore con il suo indirizzo GBFS ("gbfs.json"). */
data class FonteCondivisione(val nome: String, val url: String)

val FONTI_CONDIVISIONE = listOf(
    FonteCondivisione("Bird", "https://mds.bird.co/gbfs/v2/public/rome/gbfs.json"),
    FonteCondivisione("Cooltra", "https://maas.zeus.cooltra.com/gbfs/rome/3.0/en/gbfs.json"),
)

enum class TipoMezzoCondiviso { MONOPATTINO, BICI, SCOOTER, ALTRO }

/** Un mezzo libero. [autonomia] in metri (null se non si sa); [apri] il link per noleggiarlo, se c'e'. */
data class MezzoCondiviso(
    val id: String,
    val operatore: String,
    val tipo: TipoMezzoCondiviso,
    val lat: Double,
    val lon: Double,
    val autonomia: Int?,
    val apri: String?,
)

private fun tipoDaGbfs(fattore: String?): TipoMezzoCondiviso = when (fattore?.lowercase()) {
    "scooter", "scooter_standing" -> TipoMezzoCondiviso.MONOPATTINO
    "bicycle", "cargo_bicycle" -> TipoMezzoCondiviso.BICI
    "moped", "scooter_seated" -> TipoMezzoCondiviso.SCOOTER
    null -> TipoMezzoCondiviso.MONOPATTINO
    else -> TipoMezzoCondiviso.ALTRO
}

/** Gli indirizzi dei feed dentro gbfs.json (versione 2: per lingua; versione 3: diretti). */
fun feedGbfs(json: String): Map<String, String> {
    val dati = Json.leggi(json).comeOggetto().oggetto("data") ?: return emptyMap()
    val elenco = dati.oggetti("feeds").ifEmpty {
        val lingua = dati.oggetto("it") ?: dati.oggetto("en") ?: dati.values.firstOrNull()?.comeOggetto()
        lingua?.oggetti("feeds").orEmpty()
    }
    return elenco.mapNotNull { f -> f.testo("name")?.let { n -> f.testo("url")?.let { n to it } } }.toMap()
}

/** I tipi di mezzo: id -> tipo (da form_factor). */
fun tipiGbfs(json: String): Map<String, TipoMezzoCondiviso> =
    Json.leggi(json).comeOggetto().oggetto("data")?.oggetti("vehicle_types").orEmpty()
        .mapNotNull { t -> t.testo("vehicle_type_id")?.let { it to tipoDaGbfs(t.testo("form_factor")) } }.toMap()

/** I mezzi liberi (free_bike_status v2 o vehicle_status v3), senza quelli prenotati o guasti. */
fun mezziGbfs(json: String, operatore: String, tipi: Map<String, TipoMezzoCondiviso>): List<MezzoCondiviso> {
    val dati = Json.leggi(json).comeOggetto().oggetto("data") ?: return emptyList()
    return (dati.oggetti("bikes") + dati.oggetti("vehicles")).mapNotNull { v ->
        if (v.vero("is_reserved") || v.vero("is_disabled")) return@mapNotNull null
        val lat = v.numero("lat") ?: return@mapNotNull null
        val lon = v.numero("lon") ?: return@mapNotNull null
        val tipo = v.testo("vehicle_type_id")?.let { tipi[it] } ?: if (tipi.isEmpty()) TipoMezzoCondiviso.MONOPATTINO else TipoMezzoCondiviso.ALTRO
        val uri = v.oggetto("rental_uris")
        MezzoCondiviso(
            id = v.testo("bike_id") ?: v.testo("vehicle_id") ?: "$lat,$lon",
            operatore = operatore,
            tipo = tipo,
            lat = lat,
            lon = lon,
            autonomia = v.numero("current_range_meters")?.toInt(),
            apri = uri?.testo("android") ?: uri?.testo("ios") ?: uri?.testo("web"),
        )
    }
}

private class MemoriaCondivisione(val quando: Long, val mezzi: List<MezzoCondiviso>)

private val memoriaMezzi = HashMap<String, MemoriaCondivisione>()
private val memoriaTipi = HashMap<String, Map<String, TipoMezzoCondiviso>>()
private val memoriaFeed = HashMap<String, Map<String, String>>()
private val chiaveCondivisione = Mutex()

private suspend fun scaricaTesto(url: String): String {
    val r = Collegamento.http.richiedi(url, mapOf("User-Agent" to Collegamento.agenteUtente, "Accept" to "application/json"), Attesa.MEDIA)
    if (!r.riuscita) throw ErroreRete(tx("Il server ha risposto {0}", r.codice))
    return r.testo()
}

/** I mezzi liberi di un operatore (tenuti 2 minuti; i tipi e gli indirizzi per tutta la sessione). */
private suspend fun mezziDi(fonte: FonteCondivisione, adessoMs: Long): List<MezzoCondiviso> {
    chiaveCondivisione.withLock { memoriaMezzi[fonte.nome] }?.let { if (adessoMs - it.quando < 120_000) return it.mezzi }
    val feed = chiaveCondivisione.withLock { memoriaFeed[fonte.nome] } ?: withContext(Dispatchers.Default) { feedGbfs(scaricaTesto(fonte.url)) }
        .also { f -> chiaveCondivisione.withLock { memoriaFeed[fonte.nome] = f } }
    val tipi = chiaveCondivisione.withLock { memoriaTipi[fonte.nome] }
        ?: (feed["vehicle_types"]?.let { url -> runCatching { withContext(Dispatchers.Default) { tipiGbfs(scaricaTesto(url)) } }.getOrNull() } ?: emptyMap())
            .also { t -> chiaveCondivisione.withLock { memoriaTipi[fonte.nome] = t } }
    val url = feed["vehicle_status"] ?: feed["free_bike_status"] ?: return emptyList()
    val mezzi = withContext(Dispatchers.Default) { mezziGbfs(scaricaTesto(url), fonte.nome, tipi) }
    chiaveCondivisione.withLock { memoriaMezzi[fonte.nome] = MemoriaCondivisione(adessoMs, mezzi) }
    return mezzi
}

/** I mezzi liberi entro [raggio] metri da [punto], dal piu' vicino (vuoto senza rete). */
suspend fun mezziCondivisiVicini(punto: Punto, raggio: Double = 400.0, adessoMs: Long = adessoMs()): List<Pair<MezzoCondiviso, Double>> = coroutineScope {
    FONTI_CONDIVISIONE.map { fonte ->
        async {
            try {
                mezziDi(fonte, adessoMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.awaitAll().flatten()
        .map { it to distanzaMetri(punto.lat, punto.lon, it.lat, it.lon) }
        .filter { it.second <= raggio }
        .sortedBy { it.second }
}

fun nomeTipoCondiviso(t: TipoMezzoCondiviso, quanti: Int): String = when (t) {
    TipoMezzoCondiviso.MONOPATTINO -> txn("{0} monopattino", "{0} monopattini", quanti)
    TipoMezzoCondiviso.BICI -> txn("{0} bici", "{0} bici§plurale", quanti)
    TipoMezzoCondiviso.SCOOTER -> txn("{0} scooter", "{0} scooter§plurale", quanti)
    TipoMezzoCondiviso.ALTRO -> txn("{0} mezzo", "{0} mezzi", quanti)
}

/** Una riga dell'elenco: "Monopattino Bird · 60 m · 18 km di autonomia". */
data class VoceCondivisione(val id: String, val titolo: String, val sotto: String, val metri: Int, val apri: String?, val perVoce: String)

/** Il riquadro "In condivisione vicino a te": il riassunto e i mezzi piu' vicini. */
data class RiquadroCondivisione(val riassunto: String, val voci: List<VoceCondivisione>, val fonti: String)

fun riquadroCondivisione(vicini: List<Pair<MezzoCondiviso, Double>>, quante: Int = 5): RiquadroCondivisione? {
    if (vicini.isEmpty()) return null
    val perTipo = vicini.groupBy { it.first.tipo }.entries.sortedByDescending { it.value.size }
    val riassunto = perTipo.joinToString(" · ") { (tipo, elenco) -> nomeTipoCondiviso(tipo, elenco.size) } + " " +
        tx("entro {0}", testoMetri(vicini.maxOf { it.second }.coerceAtLeast(100.0)))
    val voci = vicini.take(quante).map { (m, d) ->
        val nome = when (m.tipo) {
            TipoMezzoCondiviso.MONOPATTINO -> tx("Monopattino {0}", m.operatore)
            TipoMezzoCondiviso.BICI -> tx("Bici {0}", m.operatore)
            TipoMezzoCondiviso.SCOOTER -> tx("Scooter {0}", m.operatore)
            TipoMezzoCondiviso.ALTRO -> m.operatore
        }
        val autonomia = m.autonomia?.takeIf { it > 0 }?.let { tx("{0} km di autonomia", (it / 1000.0).roundToInt()) }
        val sotto = listOfNotNull(testoMetri(d), autonomia).joinToString(" · ")
        VoceCondivisione(m.id, nome, sotto, d.toInt(), m.apri, "$nome, $sotto")
    }
    return RiquadroCondivisione(riassunto, voci, tx("Dati aperti di {0}", vicini.map { it.first.operatore }.distinct().joinToString(", ")))
}

/** Il riquadro per la posizione [punto] (null se non c'e' niente vicino o senza rete). */
suspend fun condivisioneVicino(lat: Double, lon: Double): RiquadroCondivisione? =
    riquadroCondivisione(mezziCondivisiVicini(Punto(lat, lon)))

/**
 * Nei tragitti: se il primo tratto e' a piedi e lungo (almeno 700 m) e c'e' un
 * monopattino o una bici a meno di 150 m dalla partenza, quanto si risparmia
 * ("Monopattino Bird a 60 m: 4 min invece di 12 a piedi"). Null se non conviene.
 */
fun suggerimentoCondivisione(t: Tragitto, vicini: List<Pair<MezzoCondiviso, Double>>): String? {
    val primo = t.tratti.firstOrNull() ?: return null
    if (primo.modo != "WALK") return null
    val metri = primo.metri ?: return null
    if (metri < 700) return null
    val (mezzo, distanza) = vicini.firstOrNull { (m, d) ->
        d <= 150 && (m.tipo == TipoMezzoCondiviso.MONOPATTINO || m.tipo == TipoMezzoCondiviso.BICI) &&
            distanzaMetri(m.lat, m.lon, primo.da.lat, primo.da.lon) <= 150
    } ?: return null
    // 15 km/h in monopattino, 13 in bici, piu' un minuto per sbloccarlo
    val velocita = if (mezzo.tipo == TipoMezzoCondiviso.BICI) 13.0 else 15.0
    val conMezzo = (metri / (velocita * 1000 / 60)).roundToInt() + 1
    val aPiedi = (primo.durata + 59) / 60
    if (aPiedi - conMezzo < 4) return null
    val nome = if (mezzo.tipo == TipoMezzoCondiviso.BICI) tx("Bici {0}", mezzo.operatore) else tx("Monopattino {0}", mezzo.operatore)
    return tx("{0} a {1}: {2} min invece di {3} a piedi", nome, testoMetri(distanza), conMezzo, aPiedi)
}

/** Per i risultati di una ricerca: chiave del tragitto -> suggerimento (vuoto se niente o senza rete). */
suspend fun suggerimentiCondivisione(tragitti: List<Tragitto>): Map<String, String> {
    val partenza = tragitti.firstOrNull()?.tratti?.firstOrNull()?.da ?: return emptyMap()
    if (tragitti.none { (it.tratti.firstOrNull()?.metri ?: 0.0) >= 700 && it.tratti.first().modo == "WALK" }) return emptyMap()
    val vicini = mezziCondivisiVicini(Punto(partenza.lat, partenza.lon), 200.0)
    if (vicini.isEmpty()) return emptyMap()
    return tragitti.mapNotNull { t -> suggerimentoCondivisione(t, vicini)?.let { t.chiave to it } }.toMap()
}
