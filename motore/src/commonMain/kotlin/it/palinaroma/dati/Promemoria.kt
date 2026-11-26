package it.palinaroma.dati

import it.palinaroma.ponte.QuandoAvvisare
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/*
 * I promemoria ricorrenti: "ogni giorno feriale alle 8:10 avvisami per il 64 a
 * Lavoro", o "esci tra" per un tragitto salvato. All'ora scelta arriva una
 * notifica con i tempi; da li' si attiva "Avvisami". Uguali su Android e
 * iPhone: qui le regole e i testi, le app programmano le notifiche.
 */

data class Promemoria(
    val id: String,
    /** "linea" (una linea a una fermata) o "tragitto" (un tragitto salvato). */
    val tipo: String,
    /** Giorni della settimana: 1 = lunedi' ... 7 = domenica. */
    val giorni: Set<Int>,
    /** Minuti dalla mezzanotte, ora di Roma (8:10 = 490). */
    val minuti: Int,
    val fermataId: String = "",
    val lineaId: String = "",
    val capolinea: String = "",
    /** "64" (per i testi, anche senza l'orario). */
    val nomeLinea: String = "",
    /** Come per "Avvisami": "uscire", "cinque", "due". */
    val quando: String = QuandoAvvisare.CINQUE.codice,
    val tragittoId: String = "",
    val attivo: Boolean = true,
) {
    val perLinea: Boolean get() = tipo == TIPO_LINEA

    companion object {
        const val TIPO_LINEA = "linea"
        const val TIPO_TRAGITTO = "tragitto"
        val FERIALI = setOf(1, 2, 3, 4, 5)
        val TUTTI = setOf(1, 2, 3, 4, 5, 6, 7)
    }
}

object CodificaPromemoria {
    fun scrivi(elenco: List<Promemoria>): String = ScriviJson.testo(
        elenco.map {
            mapOf(
                "id" to it.id, "tipo" to it.tipo, "giorni" to it.giorni.sorted(), "minuti" to it.minuti,
                "fermataId" to it.fermataId, "lineaId" to it.lineaId, "capolinea" to it.capolinea, "nomeLinea" to it.nomeLinea,
                "quando" to it.quando, "tragittoId" to it.tragittoId, "attivo" to it.attivo,
            )
        },
    )

    fun leggi(json: String?): List<Promemoria> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            (Json.leggi(json) as? List<*>).orEmpty().mapNotNull { v ->
                val o = v.comeOggetto()
                Promemoria(
                    id = o.testo("id") ?: return@mapNotNull null,
                    tipo = o.testo("tipo") ?: return@mapNotNull null,
                    giorni = (o["giorni"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.filter { it in 1..7 }.toSet(),
                    minuti = o.numero("minuti")?.toInt()?.coerceIn(0, 24 * 60 - 1) ?: return@mapNotNull null,
                    fermataId = o.testo("fermataId").orEmpty(),
                    lineaId = o.testo("lineaId").orEmpty(),
                    capolinea = o.testo("capolinea").orEmpty(),
                    nomeLinea = o.testo("nomeLinea").orEmpty(),
                    quando = o.testo("quando") ?: QuandoAvvisare.CINQUE.codice,
                    tragittoId = o.testo("tragittoId").orEmpty(),
                    attivo = o["attivo"] != false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Aggiunge [p], o lo sostituisce se c'e' gia' uno con lo stesso id. */
fun conPromemoria(elenco: List<Promemoria>, p: Promemoria): List<Promemoria> =
    if (elenco.any { it.id == p.id }) elenco.map { if (it.id == p.id) p else it } else elenco + p

fun senzaPromemoria(elenco: List<Promemoria>, id: String): List<Promemoria> = elenco.filter { it.id != id }

/** Toglie i promemoria dei tragitti che non ci sono piu'. */
fun promemoriaValidi(elenco: List<Promemoria>, salvati: List<TragittoSalvato>): List<Promemoria> =
    elenco.filter { !(it.tipo == Promemoria.TIPO_TRAGITTO && salvati.none { s -> s.id == it.tragittoId }) }

/** Il giorno della settimana di una data: 1 = lunedi' ... 7 = domenica. */
private fun giornoSettimana(d: LocalDate): Int = d.dayOfWeek.isoDayNumber

/** Le prossime [quante] volte in cui scatta [p] dopo [adessoMs] (al massimo nei prossimi 15 giorni). */
fun prossimeVolte(p: Promemoria, adessoMs: Long, quante: Int = 1): List<Long> {
    if (!p.attivo || p.giorni.isEmpty()) return emptyList()
    val oggi = giornoARoma(adessoMs)
    val volte = ArrayList<Long>()
    for (dopo in 0..15) {
        val giorno = oggi.plus(DatePeriod(days = dopo))
        if (giornoSettimana(giorno) !in p.giorni) continue
        val ms = secondiARoma(giorno, p.minuti / 60, p.minuti % 60) * 1000
        if (ms > adessoMs) volte += ms
        if (volte.size >= quante) break
    }
    return volte
}

/** La prossima volta, fra tutti i promemoria attivi (null se nessuno). */
fun prossimoPromemoria(elenco: List<Promemoria>, adessoMs: Long): Long? =
    elenco.mapNotNull { prossimeVolte(it, adessoMs).firstOrNull() }.minOrNull()

/** I promemoria che scattano adesso (entro [tolleranzaMs] dall'ora scelta, anche se in ritardo). */
fun promemoriaDaMostrare(elenco: List<Promemoria>, adessoMs: Long, tolleranzaMs: Long = 10 * 60_000): List<Promemoria> =
    elenco.filter { p ->
        if (!p.attivo || p.giorni.isEmpty()) return@filter false
        val oggi = giornoARoma(adessoMs)
        if (giornoSettimana(oggi) !in p.giorni) return@filter false
        val alle = secondiARoma(oggi, p.minuti / 60, p.minuti % 60) * 1000
        adessoMs - alle in -60_000..tolleranzaMs
    }

/** Le scelte di "Avvisami" per un promemoria: codice -> etichetta (come nel foglio "Avvisami"). */
fun sceltePromemoria(): List<Pair<String, String>> = listOf(
    QuandoAvvisare.USCIRE.codice to tx("Ora di uscire"),
    QuandoAvvisare.CINQUE.codice to tx("A 5 minuti"),
    QuandoAvvisare.DUE.codice to tx("A 2 minuti"),
)

/** Per l'iPhone: i codici e le etichette delle scelte, in due elenchi. */
fun codiciScelteePromemoria(): List<String> = sceltePromemoria().map { it.first }

fun etichetteScelteePromemoria(): List<String> = sceltePromemoria().map { it.second }

/** "L M M G V S D": le iniziali dei giorni, da lunedi'. */
fun inizialiGiorni(): List<String> =
    listOf(tx("L§lunedì"), tx("M§martedì"), tx("M§mercoledì"), tx("G§giovedì"), tx("V§venerdì"), tx("S§sabato"), tx("D§domenica"))

private fun nomeBreveGiorno(g: Int): String = when (g) {
    1 -> tx("lun")
    2 -> tx("mar")
    3 -> tx("mer")
    4 -> tx("gio")
    5 -> tx("ven")
    6 -> tx("sab")
    else -> tx("dom")
}

/** "tutti i giorni", "nei giorni feriali", "sabato e domenica", "lun, mer, ven". */
fun testoGiorni(giorni: Set<Int>): String = when {
    giorni == Promemoria.TUTTI -> tx("tutti i giorni")
    giorni == Promemoria.FERIALI -> tx("nei giorni feriali")
    giorni == setOf(6, 7) -> tx("sabato e domenica")
    giorni.isEmpty() -> tx("mai")
    else -> giorni.sorted().joinToString(", ") { nomeBreveGiorno(it) }
}

/** "8:10" come le ore dell'app ("08:10"). */
fun testoMinutiDelGiorno(minuti: Int): String =
    (minuti / 60).toString().padStart(2, '0') + ":" + (minuti % 60).toString().padStart(2, '0')

/** "nei giorni feriali alle 08:10". */
fun testoQuandoPromemoria(p: Promemoria): String = tx("{0} alle {1}", testoGiorni(p.giorni), testoMinutiDelGiorno(p.minuti))

/**
 * Il titolo del promemoria: "Lavoro · 64 → Stazione S. Pietro" (con il nome
 * dato alla fermata, o quello della fermata), o il nome del tragitto.
 */
fun titoloPromemoria(p: Promemoria, nomeFermata: String?, salvati: List<TragittoSalvato>): String =
    if (p.perLinea) {
        listOfNotNull(nomeFermata?.takeIf { it.isNotBlank() }, p.nomeLinea + (if (p.capolinea.isNotEmpty()) " → " + p.capolinea else ""))
            .joinToString(" · ")
    } else {
        salvati.firstOrNull { it.id == p.tragittoId }?.nome ?: tx("Tragitto salvato")
    }

/** Una notifica di promemoria, pronta: cosa dire e cosa aprire. */
data class NotificaPromemoria(
    val id: String,
    /** Quando mostrarla (per l'iPhone, che le programma in anticipo). */
    val alleMs: Long,
    val titolo: String,
    val testo: String,
    /** Cosa apre il tocco: "fermata:<id>" o "tragitto:<id>". */
    val apri: String,
    /** Il pulsante "Avvisami": "avvisami:<fermata>|<linea>|<capolinea>|<quando>", o vuoto. */
    val avvisami: String,
)

private fun apriPromemoria(p: Promemoria) = if (p.perLinea) "fermata:" + p.fermataId else "tragitto:" + p.tragittoId

private fun avvisamiPromemoria(p: Promemoria) =
    if (p.perLinea) "avvisami:" + listOf(p.fermataId, p.lineaId, p.capolinea, p.quando).joinToString("|") else ""

/** "avvisami:70078|64|Stazione S. Pietro|cinque" -> le quattro parti (null se non e' un avviso). */
fun leggiRichiestaAvvisami(testo: String): List<String>? {
    if (!testo.startsWith("avvisami:")) return null
    val parti = testo.removePrefix("avvisami:").split("|")
    return if (parti.size == 4 && parti[0].isNotEmpty() && parti[1].isNotEmpty()) parti else null
}

/** Il testo quando non si sanno ancora i tempi. */
private fun testoSenzaTempi(p: Promemoria): String =
    if (p.perLinea) tx("Tocca per vedere quando passa il {0} e attivare l'avviso.", p.nomeLinea)
    else tx("Tocca per sapere quando uscire.")

/**
 * La notifica di [p] adesso, con i tempi veri: per una linea quando passa (tempo
 * reale e orario), per un tragitto quando uscire. Senza rete resta l'invito a
 * toccarla.
 */
suspend fun notificaPromemoria(
    p: Promemoria,
    orario: Orario,
    nomi: Map<String, String>,
    salvati: List<TragittoSalvato>,
    posizione: Punto?,
    adessoMs: Long = adessoMs(),
): NotificaPromemoria {
    val fermata = if (p.perLinea) orario.fermata(p.fermataId) else null
    val titolo = titoloPromemoria(p, nomi[p.fermataId] ?: fermata?.nome, salvati)
    val testo = try {
        if (p.perLinea) {
            fermata?.let { f ->
                val arrivi = CaricatoreArrivi().aggiorna(orario, listOf(f), emptyList(), ArriviFermate())
                val adesso = adessoMs / 1000
                arrivi.perFermata[f.id].orEmpty()
                    .filter { it.lineaId == p.lineaId && (p.capolinea.isEmpty() || it.capolinea == p.capolinea) }
                    .firstOrNull { it.passaggi.isNotEmpty() }
                    ?.let { fraseArrivo(it, adesso) }
                    ?: tx("Il {0} non passa nei prossimi 90 minuti.", p.nomeLinea)
            }
        } else {
            salvati.firstOrNull { it.id == p.tragittoId }?.let { t ->
                val partenze = partenzeTragittoSalvato(t, posizione)
                val carta = cartaTragittoSalvato(t, partenze, adessoMs)
                if (carta.numero.isNotEmpty() || carta.esci.isNotEmpty()) {
                    listOf(
                        (if (carta.numero.isEmpty()) carta.esci.lowercase().replaceFirstChar { it.uppercase() } else tx("Esci tra {0} min", carta.numero)) + ".",
                        carta.sotto,
                        carta.ultima,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                } else {
                    carta.sotto
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return NotificaPromemoria(p.id, adessoMs, titolo, testo ?: testoSenzaTempi(p), apriPromemoria(p), avvisamiPromemoria(p))
}

/**
 * Per l'iPhone, che non puo' chiedere i tempi all'ora del promemoria: le
 * prossime notifiche di tutti i promemoria (al massimo [massimo]), con un
 * invito a toccarle. Per quelle delle prossime 24 ore, quando si sa, l'orario
 * di Transitous ("da orario: 08:16, poi 08:28").
 */
suspend fun notifichePromemoria(
    elenco: List<Promemoria>,
    orario: Orario,
    nomi: Map<String, String>,
    salvati: List<TragittoSalvato>,
    adessoMs: Long = adessoMs(),
    massimo: Int = 48,
): List<NotificaPromemoria> {
    val attivi = promemoriaValidi(elenco, salvati).filter { it.attivo && it.giorni.isNotEmpty() }
    if (attivi.isEmpty()) return emptyList()
    val perOgnuno = (massimo / attivi.size).coerceIn(1, 14)
    val esito = ArrayList<NotificaPromemoria>()
    for (p in attivi) {
        val fermata = if (p.perLinea) orario.fermata(p.fermataId) else null
        val titolo = titoloPromemoria(p, nomi[p.fermataId] ?: fermata?.nome, salvati)
        prossimeVolte(p, adessoMs, perOgnuno).forEachIndexed { i, alle ->
            val daOrario = if (i == 0 && fermata != null && alle - adessoMs < 24 * 3600_000L) {
                try {
                    val partenze = Transitous.partenzeFinoA(fermata.id, fermata.lat, fermata.lon, alle, alle + 60 * 60_000)
                    val ore = passaggiProgrammati(partenze, orario, alle / 1000, orizzonteMinuti = 60)
                        .filter { it.lineaId == p.lineaId && (p.capolinea.isEmpty() || it.capolinea == p.capolinea) }
                        .map { it.passaggio.orario }.sorted().take(2)
                    if (ore.isEmpty()) null
                    else tx("Il {0} da orario: {1}.", p.nomeLinea, ore.joinToString(", ") { oraARoma(it * 1000) }) + " " + testoSenzaTempi(p)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            esito += NotificaPromemoria(p.id + "@" + alle, alle, titolo, daOrario ?: testoSenzaTempi(p), apriPromemoria(p), avvisamiPromemoria(p))
        }
    }
    return esito.sortedBy { it.alleMs }.take(massimo)
}

/** Un id nuovo per un promemoria. */
fun nuovoIdPromemoria(adessoMs: Long = adessoMs()): String = "p" + adessoMs.toString(36)

/** Il promemoria proposto per una linea a una fermata: nei giorni feriali, all'ora [minuti]. */
fun promemoriaPerLinea(fermataId: String, lineaId: String, capolinea: String, nomeLinea: String, quando: String, minuti: Int): Promemoria =
    Promemoria(nuovoIdPromemoria(), Promemoria.TIPO_LINEA, Promemoria.FERIALI, minuti, fermataId, lineaId, capolinea, nomeLinea, quando)

fun promemoriaPerTragitto(tragittoId: String, minuti: Int): Promemoria =
    Promemoria(nuovoIdPromemoria(), Promemoria.TIPO_TRAGITTO, Promemoria.FERIALI, minuti, tragittoId = tragittoId)

/** L'ora proposta per un promemoria nuovo: adesso, arrotondata ai 5 minuti. */
fun minutiProposti(adessoMs: Long = adessoMs()): Int {
    val ora = oraDelGiornoARoma(adessoMs)
    val minuto = ((adessoMs / 60_000) % 60).toInt()
    return (ora * 60 + (minuto / 5) * 5) % (24 * 60)
}

/** La riga nell'elenco dei promemoria: "Lavoro · 64 → S. Pietro" / "nei giorni feriali alle 08:10". */
data class VocePromemoria(val id: String, val titolo: String, val sotto: String, val attivo: Boolean)

fun vociPromemoria(elenco: List<Promemoria>, orario: Orario?, nomi: Map<String, String>, salvati: List<TragittoSalvato>): List<VocePromemoria> =
    promemoriaValidi(elenco, salvati).map { p ->
        val nomeFermata = nomi[p.fermataId] ?: if (p.perLinea) orario?.fermata(p.fermataId)?.nome else null
        VocePromemoria(p.id, titoloPromemoria(p, nomeFermata, salvati), testoQuandoPromemoria(p), p.attivo)
    }

/** Per l'iPhone: il promemoria [p] con giorni, ora, scelta e stato nuovi. */
fun promemoriaModificato(p: Promemoria, giorni: List<Int>, minuti: Int, quando: String, attivo: Boolean): Promemoria =
    p.copy(giorni = giorni.filter { it in 1..7 }.toSet(), minuti = minuti.coerceIn(0, 24 * 60 - 1), quando = quando, attivo = attivo)

/** Per l'iPhone: i giorni in ordine (1 = lunedi'). */
fun giorniDelPromemoria(p: Promemoria): List<Int> = p.giorni.sorted()
