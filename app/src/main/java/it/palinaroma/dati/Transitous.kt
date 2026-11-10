package it.palinaroma.dati

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.pow

/*
 * Tragitti da A a B con Transitous (https://transitous.org), servizio gratuito
 * basato su MOTIS che include ATAC, Roma TPL, Cotral e Trenitalia con il tempo reale.
 * Regole d'uso: app open source e non commerciali, User-Agent con un contatto,
 * attribuzione visibile e poche richieste.
 */

enum class TipoLuogo { INDIRIZZO, LUOGO, FERMATA, POSIZIONE }

data class Luogo(
    val nome: String,
    val dettaglio: String,
    val lat: Double,
    val lon: Double,
    val tipo: TipoLuogo,
) {
    val coordinate: String get() = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
    val haCoordinate: Boolean get() = !lat.isNaN() && !lon.isNaN()

    companion object {
        /** "La mia posizione": le coordinate si leggono al momento della ricerca. */
        fun posizione() = Luogo("La mia posizione", "", Double.NaN, Double.NaN, TipoLuogo.POSIZIONE)
    }
}

/** Una fermata o un punto lungo il tragitto. */
data class Tappa(
    val nome: String,
    val lat: Double,
    val lon: Double,
    val fermataId: String?,
    val codice: String?,
    val binario: String?,
    val orario: Long?,
    val orarioPrevisto: Long?,
)

data class Passo(val svolta: String, val strada: String, val metri: Double) {
    /** "Gira a destra in Via Nazionale". */
    val testo: String
        get() {
            val azione = when (svolta) {
                "DEPART" -> "Parti"
                "LEFT" -> "Gira a sinistra"
                "RIGHT" -> "Gira a destra"
                "SLIGHTLY_LEFT" -> "Tieni la sinistra"
                "SLIGHTLY_RIGHT" -> "Tieni la destra"
                "HARD_LEFT" -> "Svolta stretta a sinistra"
                "HARD_RIGHT" -> "Svolta stretta a destra"
                "UTURN_LEFT", "UTURN_RIGHT" -> "Torna indietro"
                "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> "Alla rotonda prosegui"
                "STAIRS" -> "Prendi le scale"
                "ELEVATOR" -> "Prendi l'ascensore"
                else -> "Prosegui"
            }
            return if (strada.isBlank()) azione else "$azione in $strada"
        }
}

data class Tratto(
    val modo: String,
    val partenza: Long,
    val arrivo: Long,
    val partenzaPrevista: Long,
    val arrivoPrevisto: Long,
    val tempoReale: Boolean,
    val durata: Int,
    val metri: Double?,
    val da: Tappa,
    val a: Tappa,
    val linea: String,
    val direzione: String,
    val colore: Int?,
    val gestore: String,
    val intermedie: List<Tappa>,
    val forma: List<Punto>,
    val passi: List<Passo>,
    val cancellato: Boolean,
    val avvisi: List<String>,
    /** Identificativo della corsa per Transitous (finisce con quello di Roma Mobilita'). */
    val corsaId: String = "",
) {
    val aPiedi: Boolean get() = modo in MODI_STRADALI

    /** Minuti di ritardo (positivi) o di anticipo (negativi) alla partenza. */
    val ritardoMinuti: Int get() = ((partenza - partenzaPrevista) / 60_000).toInt()

    val nomeMezzo: String
        get() = when (modo) {
            "TRAM" -> "Tram"
            "SUBWAY", "METRO" -> "Metro"
            "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN", "HIGHSPEED_RAIL",
            "LONG_DISTANCE", "NIGHT_RAIL" -> "Treno"
            "FERRY" -> "Traghetto"
            "COACH" -> "Pullman"
            "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> "Funivia"
            "WALK" -> "A piedi"
            else -> "Bus"
        }

    companion object {
        val MODI_STRADALI = setOf("WALK", "BIKE", "CAR", "RENTAL", "CAR_PARKING", "CAR_DROPOFF", "ODM", "RIDE_SHARING")
    }
}

data class Tragitto(
    val partenza: Long,
    val arrivo: Long,
    val durata: Int,
    val cambi: Int,
    val tratti: List<Tratto>,
) {
    val mezzi: List<Tratto> get() = tratti.filter { !it.aPiedi }
    val minutiAPiedi: Int get() = tratti.filter { it.aPiedi }.sumOf { it.durata } / 60
    val metriAPiedi: Double get() = tratti.filter { it.aPiedi }.sumOf { it.metri ?: 0.0 }

    /** Chiave per non mostrare due volte la stessa soluzione. */
    val chiave: String get() = "$partenza|$arrivo|" + mezzi.joinToString(",") { it.linea + "@" + it.da.nome }
}

data class RisultatoTragitti(
    val tragitti: List<Tragitto>,
    val soloAPiedi: Tragitto?,
    val cursorePrima: String?,
    val cursoreDopo: String?,
)

class ErroreTransitous(messaggio: String) : Exception(messaggio)

/** Una partenza da una fermata: orario programmato, aggiornato col tempo reale se c'e'. Orari in ms. */
data class PartenzaTransitous(
    val corsaId: String,
    val lineaId: String,
    val linea: String,
    val direzione: Int?,
    val capolinea: String,
    val orario: Long,
    val previsto: Long,
    val tempoReale: Boolean,
    val cancellata: Boolean,
    val fermataId: String,
)

object Transitous {
    const val API = "https://api.transitous.org/api"
    const val FONTI = "https://transitous.org/sources/"
    const val SITO_APP = "https://github.com/fz1101hub/Palina"

    /** Nome con cui Transitous identifica l'orario di Roma Mobilita'. */
    const val DATI_ROMA = "it-Lazio-Rome"

    /** Centro di Roma, per favorire i risultati in citta'. */
    val ROMA = Punto(41.9028, 12.4964)

    private val agente: String get() = Archivio.agenteUtente + " (+" + SITO_APP + ")"

    suspend fun cerca(testo: String, vicino: Punto?): List<Luogo> = withContext(Dispatchers.IO) {
        leggiLuoghi(scarica(urlLuoghi(testo, vicino ?: ROMA), Archivio.httpBreve))
    }

    suspend fun tragitti(
        da: Luogo,
        a: Luogo,
        istante: Long?,
        arrivaEntro: Boolean,
        cursore: String?,
    ): RisultatoTragitti = withContext(Dispatchers.IO) {
        leggiTragitti(scarica(urlTragitti(da, a, istante, arrivaEntro, cursore), Archivio.httpMedio), da.nome, a.nome)
    }

    /** Le prossime partenze (90 minuti) dalla fermata dell'app [fermataId]. */
    suspend fun partenze(fermataId: String, lat: Double, lon: Double): List<PartenzaTransitous> =
        withContext(Dispatchers.IO) {
            leggiPartenze(scarica(urlPartenze(fermataId, lat, lon), Archivio.httpBreve), fermataId, lat, lon)
        }

    private fun scarica(url: String, http: OkHttpClient): String {
        val richiesta = Request.Builder()
            .url(url)
            .header("User-Agent", agente)
            .header("Accept", "application/json")
            .build()
        http.newCall(richiesta).execute().use { risposta ->
            val corpo = risposta.body?.string().orEmpty()
            if (!risposta.isSuccessful) throw ErroreTransitous(messaggioErrore(risposta.code, corpo))
            return corpo
        }
    }
}

private fun codifica(valore: String): String = URLEncoder.encode(valore, "UTF-8")

/**
 * Ricerca di indirizzi e luoghi. [vicino] serve solo a preferire i risultati in
 * zona: si manda arrotondato a circa un chilometro, non la posizione esatta.
 */
fun urlLuoghi(testo: String, vicino: Punto): String =
    Transitous.API + "/v1/geocode?text=" + codifica(testo.trim()) +
        "&language=it&numResults=8&place=" +
        codifica(String.format(Locale.ROOT, "%.2f,%.2f", vicino.lat, vicino.lon))

fun urlTragitti(da: Luogo, a: Luogo, istante: Long?, arrivaEntro: Boolean, cursore: String?): String {
    val parametri = mutableListOf(
        "fromPlace" to da.coordinate,
        "toPlace" to a.coordinate,
        "numItineraries" to "5",
        "language" to "it",
    )
    if (istante != null) parametri += "time" to Instant.ofEpochSecond(istante / 1000).toString()
    if (arrivaEntro) parametri += "arriveBy" to "true"
    if (cursore != null) parametri += "pageCursor" to cursore
    return Transitous.API + "/v5/plan?" + parametri.joinToString("&") { (k, v) -> k + "=" + codifica(v) }
}

/**
 * Partenze da una fermata. Se Transitous non riconosce il codice della fermata
 * usa le coordinate: solo le fermate entro 30 metri.
 */
fun urlPartenze(fermataId: String, lat: Double, lon: Double): String =
    Transitous.API + "/v5/stoptimes?stopId=" + codifica(Transitous.DATI_ROMA + "_" + fermataId) +
        "&center=" + codifica(String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)) +
        "&radius=30&exactRadius=true&n=40&window=5400&language=it"

/** Le partenze con lo stesso codice di fermata valgono entro questa distanza (altri dati, stesso numero). */
private const val METRI_STESSO_CODICE = 150.0

/** Senza lo stesso codice valgono solo le fermate in questo raggio: lo stesso palo, non l'altro lato della strada. */
private const val METRI_STESSO_PUNTO = 12.0

/**
 * Risposta di /stoptimes: solo le partenze dalla fermata cercata. Transitous puo'
 * aggiungere fermate vicine (anche dall'altro lato della strada) e fermate con lo
 * stesso nome in altre zone: si tengono quelle con lo stesso codice a meno di
 * 150 m e, con un codice diverso, solo quelle nello stesso punto.
 */
fun leggiPartenze(json: String, fermataId: String, lat: Double, lon: Double): List<PartenzaTransitous> {
    val voci = Json.leggi(json).comeOggetto().oggetti("stopTimes")
    return voci.mapNotNull { m ->
        val luogo = m.oggetto("place") ?: return@mapNotNull null
        if (m.testo("pickupDropoffType") == "NOT_ALLOWED") return@mapNotNull null
        val codice = luogo.testo("stopId").orEmpty()
        val stessoCodice = codice == fermataId || codice.endsWith("_$fermataId")
        val latLuogo = luogo.numero("lat")
        val lonLuogo = luogo.numero("lon")
        val distanza = if (latLuogo != null && lonLuogo != null) distanzaMetri(lat, lon, latLuogo, lonLuogo) else null
        val valida = if (distanza == null) stessoCodice
        else (stessoCodice && distanza <= METRI_STESSO_CODICE) || distanza <= METRI_STESSO_PUNTO
        if (!valida) return@mapNotNull null
        val orario = leggiIstante(luogo.testo("departure")) ?: leggiIstante(luogo.testo("arrival")) ?: return@mapNotNull null
        val previsto = leggiIstante(luogo.testo("scheduledDeparture")) ?: leggiIstante(luogo.testo("scheduledArrival")) ?: orario
        val modo = m.testo("mode") ?: "BUS"
        PartenzaTransitous(
            corsaId = m.testo("tripId").orEmpty(),
            lineaId = m.testo("routeId").orEmpty(),
            linea = nomeLinea(m.testo("routeShortName") ?: m.testo("displayName"), m.testo("routeLongName"), modo),
            direzione = m.testo("directionId")?.toIntOrNull(),
            capolinea = m.testo("headsign")?.let { bellaScrittura(it) }.orEmpty(),
            orario = orario,
            previsto = previsto,
            tempoReale = m.vero("realTime"),
            cancellata = m.vero("cancelled") || m.vero("tripCancelled"),
            fermataId = codice,
        )
    }
}

fun messaggioErrore(codice: Int, corpo: String): String {
    val dettaglio = runCatching { Json.leggi(corpo).comeOggetto().testo("error") }.getOrNull()
    return when {
        codice == 429 -> "Transitous ha ricevuto troppe richieste: riprova tra qualche minuto."
        codice in 500..599 -> "Transitous non risponde in questo momento ($codice). Riprova più tardi."
        dettaglio != null -> "Transitous non può calcolare il tragitto: $dettaglio"
        else -> "Transitous ha risposto con un errore ($codice)."
    }
}

/** Data e ora ISO 8601 in millisecondi, o null. */
fun leggiIstante(testo: String?): Long? {
    if (testo.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(testo).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(testo).toEpochMilli() }
        .getOrNull()
}

/** Polilinea codificata alla Google con la precisione indicata (5, 6 o 7 decimali). */
fun decodificaPolilinea(codificata: String, precisione: Int): List<Punto> {
    val fattore = 10.0.pow(precisione)
    val punti = ArrayList<Punto>()
    var i = 0
    var lat = 0L
    var lon = 0L

    fun prossimo(): Long? {
        var risultato = 0L
        var spostamento = 0
        while (i < codificata.length) {
            val b = codificata[i++].code - 63
            risultato = risultato or ((b and 0x1f).toLong() shl spostamento)
            spostamento += 5
            if (b < 0x20) return if (risultato and 1L != 0L) (risultato shr 1).inv() else risultato shr 1
        }
        return null
    }

    while (i < codificata.length) {
        val dLat = prossimo() ?: break
        val dLon = prossimo() ?: break
        lat += dLat
        lon += dLon
        punti.add(Punto(lat / fattore, lon / fattore))
    }
    return punti
}

private fun leggiForma(oggetto: Map<String, Any?>?): List<Punto> {
    val punti = oggetto?.testo("points") ?: return emptyList()
    val precisione = oggetto.numero("precision")?.toInt() ?: 6
    return runCatching { decodificaPolilinea(punti, precisione) }.getOrDefault(emptyList())
}

/** Risultati della ricerca di indirizzi, luoghi e fermate. */
fun leggiLuoghi(json: String): List<Luogo> =
    (Json.leggi(json) as? List<*>).orEmpty().mapNotNull { voce ->
        val m = voce.comeOggetto()
        val lat = m.numero("lat") ?: return@mapNotNull null
        val lon = m.numero("lon") ?: return@mapNotNull null
        val tipo = when (m.testo("type")) {
            "STOP" -> TipoLuogo.FERMATA
            "ADDRESS" -> TipoLuogo.INDIRIZZO
            else -> TipoLuogo.LUOGO
        }
        var nome = bellaScrittura(m.testo("name") ?: return@mapNotNull null)
        val civico = m.testo("houseNumber")
        if (tipo == TipoLuogo.INDIRIZZO && civico != null && !nome.contains(civico)) nome = "$nome, $civico"
        val aree = m.oggetti("areas")
        val scelte = aree.filter { it.vero("default") || it.vero("unique") }.ifEmpty {
            aree.sortedBy { kotlin.math.abs((it.numero("adminLevel") ?: 99.0) - 8) }.take(1)
        }
        val dettaglio = scelte.mapNotNull { it.testo("name") }
            .filter { it != nome }
            .distinct()
            .joinToString(", ")
        Luogo(nome, dettaglio, lat, lon, tipo)
    }.distinctBy { Triple(it.nome, it.dettaglio, it.tipo) }

private fun leggiTappa(m: Map<String, Any?>?, nomeAlternativo: String?, arrivo: Boolean): Tappa {
    val mappa = m ?: emptyMap()
    val grezzo = mappa.testo("name")
    val nome = when {
        nomeAlternativo != null && (grezzo == null || grezzo == "START" || grezzo == "END") -> nomeAlternativo
        grezzo == null -> ""
        else -> bellaScrittura(grezzo)
    }
    val orario = leggiIstante(mappa.testo(if (arrivo) "arrival" else "departure"))
        ?: leggiIstante(mappa.testo(if (arrivo) "departure" else "arrival"))
    val previsto = leggiIstante(mappa.testo(if (arrivo) "scheduledArrival" else "scheduledDeparture"))
    return Tappa(
        nome = nome,
        lat = mappa.numero("lat") ?: Double.NaN,
        lon = mappa.numero("lon") ?: Double.NaN,
        fermataId = mappa.testo("stopId"),
        codice = mappa.testo("stopCode"),
        binario = mappa.testo("track"),
        orario = orario,
        orarioPrevisto = previsto ?: orario,
    )
}

private fun leggiColore(esadecimale: String?): Int? {
    val pulito = esadecimale?.trim()?.removePrefix("#") ?: return null
    if (pulito.length != 6) return null
    val valore = pulito.toLongOrNull(16) ?: return null
    return (0xFF000000L or valore).toInt()
}

private val SIGLA_METRO = Regex("^ME([A-C]\\d?)$")

/** "MEA" -> "Metro A" per le linee della metropolitana di Roma. */
fun nomeLinea(breve: String?, lungo: String?, modo: String): String {
    val nome = breve?.trim().orEmpty().ifEmpty { lungo?.trim().orEmpty() }
    val metro = SIGLA_METRO.find(nome)
    if (metro != null && (modo == "SUBWAY" || modo == "METRO")) return "Metro " + metro.groupValues[1]
    return nome
}

private fun leggiTratto(m: Map<String, Any?>, primo: Boolean, ultimo: Boolean, nomeDa: String?, nomeA: String?): Tratto {
    val modo = m.testo("mode") ?: "WALK"
    val partenza = leggiIstante(m.testo("startTime")) ?: 0L
    val arrivo = leggiIstante(m.testo("endTime")) ?: partenza
    return Tratto(
        modo = modo,
        partenza = partenza,
        arrivo = arrivo,
        partenzaPrevista = leggiIstante(m.testo("scheduledStartTime")) ?: partenza,
        arrivoPrevisto = leggiIstante(m.testo("scheduledEndTime")) ?: arrivo,
        tempoReale = m.vero("realTime"),
        durata = m.numero("duration")?.toInt() ?: ((arrivo - partenza) / 1000).toInt(),
        metri = m.numero("distance"),
        da = leggiTappa(m.oggetto("from"), if (primo) nomeDa else null, arrivo = false),
        a = leggiTappa(m.oggetto("to"), if (ultimo) nomeA else null, arrivo = true),
        linea = nomeLinea(m.testo("routeShortName") ?: m.testo("displayName"), m.testo("routeLongName"), modo),
        direzione = m.testo("headsign")?.let { bellaScrittura(it) }.orEmpty(),
        colore = leggiColore(m.testo("routeColor")),
        gestore = m.testo("agencyName").orEmpty(),
        intermedie = m.oggetti("intermediateStops").map { leggiTappa(it, null, arrivo = true) },
        forma = leggiForma(m.oggetto("legGeometry")),
        passi = m.oggetti("steps").map {
            Passo(it.testo("relativeDirection") ?: "CONTINUE", it.testo("streetName").orEmpty(), it.numero("distance") ?: 0.0)
        }.filter { it.metri > 0 || it.svolta == "STAIRS" || it.svolta == "ELEVATOR" },
        cancellato = m.vero("cancelled"),
        avvisi = m.oggetti("alerts").mapNotNull { it.testo("headerText") ?: it.testo("descriptionText") }.distinct(),
        corsaId = m.testo("tripId").orEmpty(),
    )
}

private fun leggiTragitto(m: Map<String, Any?>, nomeDa: String?, nomeA: String?): Tragitto? {
    val voci = m.oggetti("legs")
    if (voci.isEmpty()) return null
    val tratti = voci.mapIndexed { i, voce -> leggiTratto(voce, i == 0, i == voci.lastIndex, nomeDa, nomeA) }
    val partenza = leggiIstante(m.testo("startTime")) ?: tratti.first().partenza
    val arrivo = leggiIstante(m.testo("endTime")) ?: tratti.last().arrivo
    return Tragitto(
        partenza = partenza,
        arrivo = arrivo,
        durata = m.numero("duration")?.toInt() ?: ((arrivo - partenza) / 1000).toInt(),
        cambi = m.numero("transfers")?.toInt() ?: (tratti.count { !it.aPiedi } - 1).coerceAtLeast(0),
        tratti = tratti,
    )
}

/** Risposta di /plan: soluzioni con i mezzi, l'alternativa solo a piedi e i cursori per scorrere. */
fun leggiTragitti(json: String, nomeDa: String?, nomeA: String?): RisultatoTragitti {
    val radice = Json.leggi(json).comeOggetto()
    val tragitti = radice.oggetti("itineraries").mapNotNull { leggiTragitto(it, nomeDa, nomeA) }
        .filter { t -> t.mezzi.isNotEmpty() }
    val soloAPiedi = radice.oggetti("direct").mapNotNull { leggiTragitto(it, nomeDa, nomeA) }
        .firstOrNull { t -> t.tratti.all { it.modo == "WALK" } }
    return RisultatoTragitti(
        tragitti = tragitti,
        soloAPiedi = soloAPiedi,
        cursorePrima = radice.testo("previousPageCursor"),
        cursoreDopo = radice.testo("nextPageCursor"),
    )
}

/** Un luogo su una riga, per ricordare gli ultimi cercati. */
fun Luogo.inRiga(): String =
    listOf(tipo.name, lat.toString(), lon.toString(), nome.replace('|', '/'), dettaglio.replace('|', '/'))
        .joinToString("|")

fun luogoDaRiga(riga: String): Luogo? {
    val parti = riga.split('|')
    if (parti.size != 5) return null
    val tipo = runCatching { TipoLuogo.valueOf(parti[0]) }.getOrNull() ?: return null
    val lat = parti[1].toDoubleOrNull() ?: return null
    val lon = parti[2].toDoubleOrNull() ?: return null
    if (tipo == TipoLuogo.POSIZIONE) return null
    return Luogo(parti[3], parti[4], lat, lon, tipo)
}

/** "35 min", "1 h 05 min". */
fun testoDurata(secondi: Int): String {
    val minuti = (secondi + 59) / 60
    return if (minuti < 60) "$minuti min" else "${minuti / 60} h " + String.format(Locale.ROOT, "%02d", minuti % 60) + " min"
}

/** "meno di 10 m", "450 m", "3,2 km". */
fun testoMetri(metri: Double): String = when {
    metri < 10 -> "meno di 10 m"
    metri < 1000 -> "${(metri / 10).toInt() * 10} m"
    else -> String.format(Locale.ITALY, "%.1f km", metri / 1000)
}
