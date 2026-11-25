package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.number

/*
 * Gli avvisi di servizio di Roma Mobilita' (deviazioni, lavori, fermate
 * spostate, scioperi): dal feed GTFS-Realtime "service alerts", uguali su
 * Android e iPhone. Si mostrano sulla linea, sulla fermata e sul tragitto che
 * riguardano.
 */

/** Un avviso del feed: a quali linee e fermate si riferisce, quando vale e cosa dice (per lingua). */
data class AvvisoServizio(
    val id: String,
    /** Periodi in cui vale, in secondi epoch (0 = senza inizio o senza fine). Vuoto: sempre. */
    val periodi: List<Pair<Long, Long>>,
    val linee: Set<String>,
    val fermate: Set<String>,
    /** Riguarda tutta la rete (solo l'azienda, senza linee ne' fermate). */
    val generale: Boolean,
    /** Effect di GTFS-Realtime (1 = servizio sospeso, 4 = deviazione...), 0 se manca. */
    val effetto: Int,
    /** Titolo e testo per lingua ("" quando il feed non la dice). */
    val titolo: Map<String, String>,
    val testo: Map<String, String>,
    val url: String?,
) {
    fun attivo(adesso: Long): Boolean =
        periodi.isEmpty() || periodi.any { (inizio, fine) -> (inizio == 0L || inizio <= adesso) && (fine == 0L || adesso <= fine) }

    /** Comincia piu' avanti (da [adesso] in secondi): un avviso per lavori dei prossimi giorni. */
    fun futuro(adesso: Long): Boolean = !attivo(adesso) && periodi.any { it.first > adesso }
}

/** Lettura degli avvisi da un feed GTFS-Realtime (FeedEntity.alert = 5). */
object FeedAvvisi {
    private const val VARINT = 0
    private const val LUNGHEZZA = 2
    private fun tag(campo: Int, tipo: Int) = (campo shl 3) or tipo

    fun leggi(dati: ByteArray, prefisso: String = ""): List<AvvisoServizio> {
        val avvisi = ArrayList<AvvisoServizio>()
        val feed = LettoreProto(dati)
        while (!feed.finito()) {
            val t = feed.leggiTag()
            if (t != tag(2, LUNGHEZZA)) {
                feed.salta(t and 7)
                continue
            }
            val entita = feed.leggiMessaggio()
            var id = ""
            var avviso: AvvisoServizio? = null
            var cancellato = false
            while (!entita.finito()) {
                val te = entita.leggiTag()
                when (te) {
                    tag(1, LUNGHEZZA) -> id = entita.leggiStringa()
                    tag(2, VARINT) -> cancellato = entita.leggiVarint() != 0L
                    tag(5, LUNGHEZZA) -> avviso = leggiAvviso(entita.leggiMessaggio(), prefisso)
                    else -> entita.salta(te and 7)
                }
            }
            val a = avviso ?: continue
            if (cancellato) continue
            if (a.titolo.isEmpty() && a.testo.isEmpty()) continue
            avvisi.add(a.copy(id = id.ifEmpty { a.titolo.values.firstOrNull().orEmpty() + a.periodi.firstOrNull()?.first }))
        }
        return avvisi
    }

    private fun leggiAvviso(m: LettoreProto, prefisso: String): AvvisoServizio {
        val periodi = ArrayList<Pair<Long, Long>>()
        val linee = HashSet<String>()
        val fermate = HashSet<String>()
        var generale = false
        var effetto = 0
        var titolo: Map<String, String> = emptyMap()
        var testo: Map<String, String> = emptyMap()
        var url: String? = null
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(1, LUNGHEZZA) -> periodi.add(leggiPeriodo(m.leggiMessaggio()))
                tag(5, LUNGHEZZA) -> {
                    val e = m.leggiMessaggio()
                    var linea = ""
                    var fermata = ""
                    var azienda = ""
                    var tipo = false
                    while (!e.finito()) {
                        val tt = e.leggiTag()
                        when (tt) {
                            tag(1, LUNGHEZZA) -> azienda = e.leggiStringa()
                            tag(2, LUNGHEZZA) -> linea = e.leggiStringa()
                            tag(3, VARINT) -> {
                                e.leggiVarint()
                                tipo = true
                            }
                            tag(4, LUNGHEZZA) -> {
                                // TripDescriptor: route_id (5)
                                val c = e.leggiMessaggio()
                                while (!c.finito()) {
                                    val tc = c.leggiTag()
                                    if (tc == tag(5, LUNGHEZZA)) linea = c.leggiStringa() else c.salta(tc and 7)
                                }
                            }
                            tag(5, LUNGHEZZA) -> fermata = e.leggiStringa()
                            else -> e.salta(tt and 7)
                        }
                    }
                    if (linea.isNotBlank()) linee.add(prefisso + linea.trim())
                    if (fermata.isNotBlank()) fermate.add(prefisso + fermata.trim())
                    if (linea.isBlank() && fermata.isBlank() && (azienda.isNotBlank() || tipo)) generale = true
                }
                tag(7, VARINT) -> effetto = m.leggiVarint().toInt()
                tag(8, LUNGHEZZA) -> url = leggiTradotto(m.leggiMessaggio()).values.firstOrNull { it.isNotBlank() }
                tag(10, LUNGHEZZA) -> titolo = leggiTradotto(m.leggiMessaggio())
                tag(11, LUNGHEZZA) -> testo = leggiTradotto(m.leggiMessaggio())
                else -> m.salta(t and 7)
            }
        }
        return AvvisoServizio("", periodi, linee, fermate, generale, effetto, titolo, testo, url)
    }

    private fun leggiPeriodo(m: LettoreProto): Pair<Long, Long> {
        var inizio = 0L
        var fine = 0L
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(1, VARINT) -> inizio = m.leggiVarint()
                tag(2, VARINT) -> fine = m.leggiVarint()
                else -> m.salta(t and 7)
            }
        }
        return inizio to fine
    }

    /** TranslatedString: lingua -> testo. */
    private fun leggiTradotto(m: LettoreProto): Map<String, String> {
        val testi = LinkedHashMap<String, String>()
        while (!m.finito()) {
            val t = m.leggiTag()
            if (t != tag(1, LUNGHEZZA)) {
                m.salta(t and 7)
                continue
            }
            val tr = m.leggiMessaggio()
            var testo = ""
            var lingua = ""
            while (!tr.finito()) {
                val tt = tr.leggiTag()
                when (tt) {
                    tag(1, LUNGHEZZA) -> testo = tr.leggiStringa()
                    tag(2, LUNGHEZZA) -> lingua = tr.leggiStringa()
                    else -> tr.salta(tt and 7)
                }
            }
            val chiave = lingua.trim().lowercase().take(2)
            if (testo.isNotBlank() && chiave !in testi) testi[chiave] = testo
        }
        return testi
    }
}

/** Gli avvisi di Roma Mobilita', tenuti in memoria qualche minuto. */
object AvvisiRoma {
    const val URL = "https://romamobilita.it/sites/default/files/rome_rtgtfs_service_alerts_feed.pb"

    private val feed = FeedInMemoria(
        validitaMs = 5 * 60_000L,
        tolleranzaMs = 60 * 60_000L,
        scarica = { etag, modificato -> scaricaFeed(URL, etag, modificato) },
        decodifica = { FeedAvvisi.leggi(it) },
    )

    /** Gli avvisi (vuoto se il feed non si legge: gli avvisi non devono fermare nient'altro). */
    suspend fun tutti(): List<AvvisoServizio> = try {
        feed.ottieni()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}

// --- cosa mostrare

/** Un avviso come si mostra: titolo, testo, tipo ("Deviazione") e periodo ("fino al 30/11"). */
data class SchedaAvviso(
    val id: String,
    val titolo: String,
    val testo: String,
    /** "Deviazione", "Fermata spostata"... o null. */
    val tipo: String?,
    /** "fino al 30/11 alle 20:00", "dal 2/12", o null. */
    val periodo: String?,
    val url: String?,
    /** I nomi delle linee coinvolte, se si sanno (per la fermata e il tragitto). */
    val linee: List<String>,
    /** Comincia piu' avanti: si mostra piu' in piccolo. */
    val futuro: Boolean,
    val perVoce: String,
)

fun testoEffetto(effetto: Int): String? = when (effetto) {
    1 -> tx("Servizio sospeso")
    2 -> tx("Servizio ridotto")
    3 -> tx("Ritardi")
    4 -> tx("Deviazione")
    5 -> tx("Servizio aggiuntivo")
    6 -> tx("Servizio modificato")
    9 -> tx("Fermata spostata")
    11 -> tx("Accessibilità")
    else -> null
}

/** Il testo nella lingua dell'app, o in italiano, o il primo che c'e'. */
fun testoInLingua(testi: Map<String, String>, lingua: String = Lingue.attuale.codice): String =
    (testi[lingua] ?: testi[""] ?: testi["it"] ?: testi.values.firstOrNull()).orEmpty()

/** Toglie i tag HTML e gli spazi in piu' (i testi di Roma Mobilita' a volte li hanno). */
fun pulisciTestoAvviso(testo: String): String =
    testo.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .lines().map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

private fun giornoEOraBreve(secondi: Long): String {
    val ms = secondi * 1000
    val g = giornoARoma(ms)
    val ora = oraARoma(ms)
    return if (ora == "0:00" || ora == "00:00") "${g.day}/${g.month.number}" else tx("{0} alle {1}", "${g.day}/${g.month.number}", ora)
}

fun schedaAvviso(a: AvvisoServizio, adesso: Long, nomiLinee: List<String> = emptyList()): SchedaAvviso {
    val titolo = pulisciTestoAvviso(testoInLingua(a.titolo))
    val testo = pulisciTestoAvviso(testoInLingua(a.testo)).takeIf { it != titolo }.orEmpty()
    val futuro = a.futuro(adesso)
    val periodo = if (futuro) {
        a.periodi.filter { it.first > adesso }.minOfOrNull { it.first }?.let { tx("dal {0}", giornoEOraBreve(it)) }
    } else {
        a.periodi.filter { (i, f) -> (i == 0L || i <= adesso) && (f == 0L || adesso <= f) }
            .map { it.second }.filter { it > 0 }.maxOrNull()
            ?.takeIf { it - adesso < 400L * 24 * 3600 }
            ?.let { tx("fino al {0}", giornoEOraBreve(it)) }
    }
    val tipo = testoEffetto(a.effetto)
    return SchedaAvviso(
        id = a.id,
        titolo = titolo.ifEmpty { testo.lineSequence().firstOrNull().orEmpty() },
        testo = if (titolo.isEmpty()) testo.lines().drop(1).joinToString("\n") else testo,
        tipo = tipo,
        periodo = periodo,
        url = a.url,
        linee = nomiLinee,
        futuro = futuro,
        perVoce = listOfNotNull(tx("Avviso"), tipo, nomiLinee.takeIf { it.isNotEmpty() }?.joinToString(", "), titolo, periodo)
            .joinToString(". ") + ".",
    )
}

/** Ordine: prima quelli in corso, poi quelli che cominciano piu' avanti; senza doppioni di testo. */
private fun ordina(elenco: List<Pair<AvvisoServizio, List<String>>>, adesso: Long): List<SchedaAvviso> =
    elenco.filter { (a, _) -> a.attivo(adesso) || a.futuro(adesso) }
        .map { (a, linee) -> schedaAvviso(a, adesso, linee) }
        .distinctBy { it.titolo + "|" + it.testo }
        .sortedBy { it.futuro }

/** Gli avvisi di una linea (anche quelli di una sola sua fermata). [adesso] in secondi. */
fun avvisiDellaLinea(avvisi: List<AvvisoServizio>, lineaId: String, adesso: Long): List<SchedaAvviso> =
    ordina(avvisi.filter { lineaId in it.linee }.map { it to emptyList() }, adesso)

/**
 * Gli avvisi per una fermata: quelli sulla fermata e quelli sulle linee che ci
 * passano ([linee]: id -> nome), con i nomi delle linee coinvolte.
 */
fun avvisiDellaFermata(
    avvisi: List<AvvisoServizio>,
    fermate: Collection<String>,
    linee: Map<String, String>,
    adesso: Long,
): List<SchedaAvviso> {
    val qui = fermate.toSet()
    return ordina(
        avvisi.mapNotNull { a ->
            val coinvolte = a.linee.mapNotNull { linee[it] }.distinct().sortedWith(compareBy({ it.length }, { it }))
            if (a.fermate.any { it in qui } || coinvolte.isNotEmpty()) a to coinvolte else null
        },
        adesso,
    )
}

/** Gli avvisi delle linee di un tragitto ([linee]: id -> nome, dei mezzi del tragitto). */
fun avvisiDelTragitto(avvisi: List<AvvisoServizio>, linee: Map<String, String>, fermate: Collection<String>, adesso: Long): List<SchedaAvviso> =
    avvisiDellaFermata(avvisi, fermate, linee, adesso)

/** Le linee dell'app dei mezzi di un tragitto di Transitous (id -> nome), per trovarne gli avvisi. */
fun lineeDelTragitto(orario: Orario, t: Tragitto): Map<String, String> {
    val corse = t.mezzi.mapNotNull { corsaApp(it.corsaId) }
    if (corse.isEmpty()) return emptyMap()
    val info = orario.corse(corse)
    return info.values.associate { it.lineaId to (orario.linea(it.lineaId)?.nome ?: it.lineaId) }
}

/** Le fermate dell'app dove si sale e si scende in un tragitto, per gli avvisi sulle fermate. */
fun fermateDelTragitto(orario: Orario, t: Tragitto): List<String> =
    t.mezzi.flatMap { listOfNotNull(fermataLocale(orario, it.da)?.id, fermataLocale(orario, it.a)?.id) }.distinct()

/** "1 avviso", "3 avvisi" (sulla riga che apre l'elenco). */
fun testoQuantiAvvisi(n: Int): String = txn("{0} avviso", "{0} avvisi", n)

// --- per le schermate (Android e iPhone)

/** Gli avvisi di Roma Mobilita' sulla linea [lineaId] (vuoto per le altre reti o senza rete). */
suspend fun avvisiPerLinea(lineaId: String): List<SchedaAvviso> {
    if (Rete.di(lineaId) != Rete.ROMA) return emptyList()
    return avvisiDellaLinea(AvvisiRoma.tutti(), lineaId, adessoMs() / 1000)
}

/** Gli avvisi per la fermata [fermataId] e le linee di Roma che ci passano. */
suspend fun avvisiPerFermata(orario: Orario, fermataId: String): List<SchedaAvviso> {
    val membri = orario.stazioneUnita(fermataId).filter { Rete.di(it) == Rete.ROMA }
    if (membri.isEmpty()) return emptyList()
    val avvisi = AvvisiRoma.tutti()
    if (avvisi.isEmpty()) return emptyList()
    val linee = kotlinx.coroutines.withContext(dispatcherDati) {
        membri.flatMap { orario.lineeAllaFermata(it) }.associate { it.linea.id to it.linea.nome }
    }
    return avvisiDellaFermata(avvisi, membri, linee, adessoMs() / 1000)
}

/** Gli avvisi delle linee e delle fermate di un tragitto. */
suspend fun avvisiPerTragitto(orario: Orario, t: Tragitto): List<SchedaAvviso> {
    val avvisi = AvvisiRoma.tutti()
    if (avvisi.isEmpty()) return emptyList()
    val (linee, fermate) = kotlinx.coroutines.withContext(dispatcherDati) { lineeDelTragitto(orario, t) to fermateDelTragitto(orario, t) }
    return avvisiDelTragitto(avvisi, linee, fermate, adessoMs() / 1000)
}
