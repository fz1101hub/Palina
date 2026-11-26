package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/*
 * Gli avvisi di Roma Mobilita' che ti riguardano, anche ad app chiusa: le
 * linee fissate, quelle dei tragitti salvati e dei promemoria, le fermate
 * preferite. E gli scioperi, con le fasce garantite: in Home e con un avviso
 * la sera prima. Le app controllano ogni tanto in sottofondo; qui le regole.
 */

/** Le linee e le fermate (dell'app) che ti interessano. */
data class LeTueLinee(val linee: Set<String>, val fermate: Set<String>) {
    val vuote: Boolean get() = linee.isEmpty() && fermate.isEmpty()
}

/**
 * Le linee fissate, quelle dei promemoria e dei tragitti salvati (dalle ultime
 * partenze calcolate, per nome), e le fermate preferite e dei promemoria.
 */
fun leTueLinee(
    orario: Orario?,
    preferiti: Set<String>,
    fissate: Set<String>,
    partenze: List<PartenzeTragitto>,
    promemoria: List<Promemoria>,
): LeTueLinee {
    val linee = HashSet<String>()
    fissate.forEach { chiave -> chiave.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { linee += it } }
    promemoria.filter { it.attivo && it.perLinea }.forEach { linee += it.lineaId }
    val nomi = partenze.flatMap { p -> p.partenze.take(2).flatMap { it.linee } }.filter { it.isNotBlank() }.toSet()
    if (orario != null) {
        for (nome in nomi) {
            orario.cercaLinee(nome).filter { it.nome.equals(nome, ignoreCase = true) && Rete.di(it.id) == Rete.ROMA }.forEach { linee += it.id }
        }
    }
    val fermate = preferiti + promemoria.filter { it.attivo && it.perLinea }.map { it.fermataId }
    return LeTueLinee(linee.filter { Rete.di(it) == Rete.ROMA }.toSet(), fermate.filter { Rete.di(it) == Rete.ROMA }.toSet())
}

/** Una notifica di un avviso: cosa dire e cosa aprire ("linea:<id>", "fermata:<id>", "home"). */
data class NotificaAvviso(val id: String, val titolo: String, val testo: String, val apri: String)

/** Le notifiche da mostrare e gli avvisi ormai visti (da ricordare fino al prossimo controllo). */
data class EsitoAvvisi(val notifiche: List<NotificaAvviso>, val visti: Set<String>)

object CodificaVisti {
    fun scrivi(visti: Set<String>): String = ScriviJson.testo(visti.sorted())

    /** Null se non si e' mai controllato (la prima volta non si avvisa di tutto quello che c'e' gia'). */
    fun leggi(json: String?): Set<String>? =
        if (json.isNullOrBlank()) null else (runCatching { Json.leggi(json) as? List<*> }.getOrNull())?.mapNotNull { it as? String }?.toSet()
}

/** Un avviso va detto se vale adesso o entro due giorni. */
private fun daDire(a: AvvisoServizio, adesso: Long): Boolean =
    a.attivo(adesso) || a.periodi.any { it.first in (adesso + 1)..(adesso + 48 * 3600) }

/**
 * Gli avvisi nuovi sulle tue linee e fermate: uno per notifica (al massimo 3,
 * poi uno che li riassume). [visti] null: prima volta, si ricordano tutti
 * senza avvisare. [adesso] in secondi.
 */
fun nuoviAvvisiPerTe(
    avvisi: List<AvvisoServizio>,
    tue: LeTueLinee,
    nomiLinee: Map<String, String>,
    visti: Set<String>?,
    adesso: Long,
): EsitoAvvisi {
    val presenti = avvisi.map { it.id }.toSet()
    // si ricordano solo gli avvisi ancora nel feed (e gli scioperi gia' detti), cosi' l'elenco non cresce
    val ricordati = visti.orEmpty().filter { it in presenti || it.startsWith("sciopero:") }.toMutableSet()
    if (visti == null) return EsitoAvvisi(emptyList(), ricordati + presenti)
    val nuovi = avvisi.filter { a ->
        a.id !in ricordati && daDire(a, adesso) &&
            (a.linee.any { it in tue.linee } || a.fermate.any { it in tue.fermate })
    }
    val notifiche = nuovi.take(3).map { a ->
        val mie = a.linee.filter { it in tue.linee }.map { nomiLinee[it] ?: it }.distinct().sorted()
        val scheda = schedaAvviso(a, adesso, mie)
        val sopra = listOfNotNull(mie.takeIf { it.isNotEmpty() }?.joinToString(", "), scheda.tipo).joinToString(" · ")
        NotificaAvviso(
            id = "avviso:" + a.id,
            titolo = sopra.ifEmpty { tx("Avviso di Roma Mobilità") },
            testo = listOfNotNull(scheda.titolo, scheda.periodo).joinToString(" · "),
            apri = a.linee.firstOrNull { it in tue.linee }?.let { "linea:$it" } ?: a.fermate.firstOrNull { it in tue.fermate }?.let { "fermata:$it" } ?: "home",
        )
    }.toMutableList()
    if (nuovi.size > 3) {
        notifiche += NotificaAvviso(
            "avvisi:altri", tx("Avvisi di Roma Mobilità"),
            txn("Ancora {0} avviso sulle tue linee: aprili da Palina.", "Ancora {0} avvisi sulle tue linee: aprili da Palina.", nuovi.size - 3),
            "home",
        )
    }
    return EsitoAvvisi(notifiche, ricordati + nuovi.map { it.id })
}

// --- gli scioperi

/** Uno sciopero: il giorno, cosa dice l'avviso e le fasce garantite (se l'avviso le dice). */
data class Sciopero(
    val id: String,
    /** "venerdì 28 novembre". */
    val giorno: String,
    /** "Sciopero di 24 ore", il titolo dell'avviso. */
    val titolo: String,
    /** "Servizio garantito dall'inizio alle 8:30 e dalle 17 alle 20", o null. */
    val fasce: String?,
    val testo: String,
    val url: String?,
    val oggi: Boolean,
    val domani: Boolean,
    /** Secondi: quando comincia. */
    val inizio: Long,
    val perVoce: String,
)

private val PAROLA_SCIOPERO = Regex("sciopero|scioperi|strike", RegexOption.IGNORE_CASE)
private val PAROLA_GARANZIA = Regex("garantit|fasce|fascia", RegexOption.IGNORE_CASE)

/** Le frasi dell'avviso che dicono le fasce garantite (le frasi finiscono con ". " o a capo: "8.30" resta intero). */
fun fasceGarantite(testo: String): String? =
    testo.split(Regex("\\.\\s+|\\n")).map { it.trim().trimEnd('.') }
        .filter { PAROLA_GARANZIA.containsMatchIn(it) && it.length in 8..220 }
        .distinct().take(2).joinToString(". ").ifEmpty { null }

fun eUnoSciopero(a: AvvisoServizio): Boolean =
    a.causa == 4 || PAROLA_SCIOPERO.containsMatchIn(testoInLingua(a.titolo, "it")) || PAROLA_SCIOPERO.containsMatchIn(testoInLingua(a.titolo))

private fun nomeGiornoSettimana(n: Int): String = when (n) {
    1 -> tx("lunedì")
    2 -> tx("martedì")
    3 -> tx("mercoledì")
    4 -> tx("giovedì")
    5 -> tx("venerdì")
    6 -> tx("sabato")
    else -> tx("domenica")
}

/** "venerdì 28 novembre". */
fun giornoDellaSettimana(secondi: Long): String {
    val g = giornoARoma(secondi * 1000)
    return tx("{0} {1} {2}§giorno", nomeGiornoSettimana(g.dayOfWeek.isoDayNumber), g.day, nomeMese(g.month.number))
}

/**
 * Gli scioperi nei prossimi [giorni] giorni (anche quello in corso), dal piu'
 * vicino. Le fasce garantite si cercano nel testo dell'avviso.
 */
fun scioperi(avvisi: List<AvvisoServizio>, adesso: Long, giorni: Int = 3): List<Sciopero> {
    val oggi = giornoARoma(adesso * 1000)
    val domani = giornoARoma((adesso + 24 * 3600) * 1000)
    return avvisi.filter { eUnoSciopero(it) }.mapNotNull { a ->
        val inizio = when {
            a.attivo(adesso) -> a.periodi.filter { (i, f) -> (i == 0L || i <= adesso) && (f == 0L || adesso <= f) }.minOfOrNull { it.first }?.takeIf { it > 0 } ?: adesso
            else -> a.periodi.map { it.first }.filter { it > adesso }.minOrNull() ?: return@mapNotNull null
        }
        if (inizio - adesso > giorni * 24 * 3600L) return@mapNotNull null
        val scheda = schedaAvviso(a, adesso)
        val tutto = pulisciTestoAvviso(testoInLingua(a.testo))
        val fasce = fasceGarantite(tutto)
        val giorno = giornoARoma(maxOf(inizio, adesso) * 1000)
        val nomeGiorno = giornoDellaSettimana(maxOf(inizio, adesso))
        Sciopero(
            id = a.id,
            giorno = nomeGiorno,
            titolo = scheda.titolo,
            fasce = fasce,
            testo = scheda.testo,
            url = a.url,
            oggi = giorno == oggi,
            domani = giorno == domani,
            inizio = inizio,
            perVoce = listOfNotNull(tx("Sciopero {0}", nomeGiorno), scheda.titolo, fasce).joinToString(". ") + ".",
        )
    }.distinctBy { it.giorno + it.titolo }.sortedBy { it.inizio }
}

/** Il titolo del riquadro in Home: "Sciopero oggi", "Sciopero domani", "Sciopero venerdì 28 novembre". */
fun titoloSciopero(s: Sciopero): String = when {
    s.oggi -> tx("Sciopero oggi")
    s.domani -> tx("Sciopero domani")
    else -> tx("Sciopero {0}", s.giorno)
}

/**
 * La notifica dello sciopero: la sera prima (dalle 17) o la mattina stessa,
 * una volta sola. Null se non e' il momento o e' gia' stata data.
 */
fun notificaSciopero(elenco: List<Sciopero>, visti: Set<String>, adesso: Long): NotificaAvviso? {
    val ora = oraDelGiornoARoma(adesso * 1000)
    val s = elenco.firstOrNull { (it.domani && ora >= 17) || (it.oggi && ora in 5..10) } ?: return null
    val id = "sciopero:" + s.id
    if (id in visti) return null
    return NotificaAvviso(id, titoloSciopero(s), listOfNotNull(s.titolo, s.fasce).joinToString(" · "), "home")
}

/**
 * Il controllo in sottofondo: legge gli avvisi e dice quali notifiche mostrare
 * (avvisi nuovi sulle tue linee, lo sciopero di domani). [visti] e' quello
 * salvato l'ultima volta (null se mai).
 */
suspend fun controllaAvvisiPerTe(
    orario: Orario?,
    tue: LeTueLinee,
    visti: Set<String>?,
    adessoMs: Long = adessoMs(),
): EsitoAvvisi? {
    val avvisi = try {
        AvvisiRoma.tutti()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    if (avvisi.isEmpty()) return null
    val adesso = adessoMs / 1000
    val nomi = tue.linee.associateWith { id -> orario?.linea(id)?.nome ?: id }
    val esito = nuoviAvvisiPerTe(avvisi, tue, nomi, visti, adesso)
    val sciopero = if (visti == null) null else notificaSciopero(scioperi(avvisi, adesso), esito.visti, adesso)
    return if (sciopero == null) esito else EsitoAvvisi(listOf(sciopero) + esito.notifiche, esito.visti + sciopero.id)
}

/** Lo sciopero per il riquadro in Home (il piu' vicino nei prossimi 3 giorni), o null. Senza rete null. */
suspend fun scioperoInVista(adessoMs: Long = adessoMs()): Sciopero? = try {
    scioperi(AvvisiRoma.tutti(), adessoMs / 1000).firstOrNull()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
