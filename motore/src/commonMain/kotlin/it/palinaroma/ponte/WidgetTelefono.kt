package it.palinaroma.ponte

import it.palinaroma.dati.CartaSalvato
import it.palinaroma.dati.CodificaPartenze
import it.palinaroma.dati.Json
import it.palinaroma.dati.Orario
import it.palinaroma.dati.PartenzeTragitto
import it.palinaroma.dati.Punto
import it.palinaroma.dati.ScriviJson
import it.palinaroma.dati.TragittoSalvato
import it.palinaroma.dati.adessoMs
import it.palinaroma.dati.cartaTragittoSalvato
import it.palinaroma.dati.comeOggetto
import it.palinaroma.dati.lineeFissate
import it.palinaroma.dati.luogoDaJson
import it.palinaroma.dati.luogoInJson
import it.palinaroma.dati.oggetto
import it.palinaroma.dati.oraARoma
import it.palinaroma.dati.partenzeTragittoSalvato
import it.palinaroma.dati.tragittoPerWidget
import it.palinaroma.dati.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * I widget del telefono (Android e iPhone): il tabellone di una fermata (quella
 * scelta, o la preferita a cui sei vicino) con le linee fissate, la fermata piu'
 * vicina e "esci tra" per un tragitto salvato (quello scelto, o quello che
 * parte piu' vicino), con mezzi, cambi e tempo di percorrenza. L'app calcola i dati (stesso calcolo del tabellone per l'orologio)
 * e li salva; i widget li ridisegnano minuto per minuto senza rete.
 */

/**
 * Tutto quello che serve ai widget, con l'ora in cui e' stato calcolato: il
 * tabellone (preferite, vicine e le fermate scelte nei widget) e le partenze
 * dei tragitti salvati.
 */
data class DatiWidget(
    val creato: Long,
    val tabellone: TabelloneOrologio?,
    /** Le linee fissate ("fermata|linea"): nel widget della fermata solo quelle. */
    val fissate: Set<String>,
    val salvati: List<TragittoSalvato>,
    /** Le partenze per tragitto salvato (id -> partenze). */
    val partenze: Map<String, PartenzeTragitto>,
    /** Il tragitto del widget "automatico": quello che parte piu' vicino a dove sei. */
    val automatico: String?,
    /** I nomi dati alle fermate ("Lavoro"): l'etichetta in cima al widget. */
    val nomi: Map<String, String> = emptyMap(),
)

object CodificaWidget {
    private const val VERSIONE = 2

    fun scrivi(d: DatiWidget): String = ScriviJson.testo(
        mapOf(
            "v" to VERSIONE,
            "creato" to d.creato,
            "tabellone" to d.tabellone?.let { CodificaOrologio.scrivi(it) },
            "fissate" to d.fissate.toList(),
            "salvati" to d.salvati.map {
                mapOf("id" to it.id, "nome" to it.nome, "da" to luogoInJson(it.partenza), "a" to luogoInJson(it.arrivo))
            },
            "partenze" to d.partenze.mapValues { CodificaPartenze.inJson(it.value) },
            "automatico" to d.automatico,
            "nomi" to d.nomi,
        ),
    )

    fun leggi(json: String?): DatiWidget? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = Json.leggi(json).comeOggetto()
            if ((o["v"] as? Number)?.toInt() != VERSIONE) return null
            DatiWidget(
                creato = (o["creato"] as? Number)?.toLong() ?: 0L,
                tabellone = CodificaOrologio.leggi(o["tabellone"] as? String),
                fissate = (o["fissate"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet(),
                salvati = (o["salvati"] as? List<*>).orEmpty().mapNotNull { v ->
                    val t = v.comeOggetto()
                    val da = t.oggetto("da")?.let { l -> luogoDaJson(l) } ?: return@mapNotNull null
                    val a = t.oggetto("a")?.let { l -> luogoDaJson(l) } ?: return@mapNotNull null
                    TragittoSalvato(t["id"] as? String ?: return@mapNotNull null, t["nome"] as? String ?: "", da, a)
                },
                partenze = o.oggetto("partenze").orEmpty().mapNotNull { (id, v) ->
                    CodificaPartenze.daJson(v.comeOggetto())?.let { id to it }
                }.toMap(),
                automatico = o["automatico"] as? String,
                nomi = o.oggetto("nomi").orEmpty().mapNotNull { (id, v) -> (v as? String)?.let { id to it } }.toMap(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Calcola i dati dei widget: il tabellone delle preferite, delle fermate
 * vicine e di quelle scelte nei widget ([fermateWidget]), e le partenze dei
 * tragitti salvati ([tragittiWidget]: quelli scelti nei widget; null = tutti).
 * Le partenze gia' calcolate ([partenzePrima]) si richiedono a Transitous solo
 * dopo un quarto d'ora o quando sono finite ([forza]: sempre).
 */
suspend fun calcolaDatiWidget(
    orario: Orario,
    preferiti: Set<String>,
    fissate: Set<String>,
    posizione: Punto?,
    salvati: List<TragittoSalvato>,
    partenzePrima: Map<String, PartenzeTragitto>,
    forza: Boolean,
    fermateWidget: Collection<String> = emptyList(),
    tragittiWidget: Collection<String>? = null,
    nomi: Map<String, String> = emptyMap(),
): DatiWidget = coroutineScope {
    val adesso = adessoMs()
    val automatico = tragittoPerWidget(salvati, posizione)?.id
    val quali = salvati.filter { tragittiWidget == null || it.id in tragittiWidget || it.id == automatico }.take(8)
    val partenze = quali.map { t ->
        async {
            val prima = partenzePrima[t.id]
            t.id to when {
                !forza && prima != null && adesso - prima.calcolato < 15 * 60_000 &&
                    prima.partenze.any { it.partenza > adesso + 60_000 } -> prima
                else -> try {
                    partenzeTragittoSalvato(t, posizione).let { nuove ->
                        // senza rete restano quelle di prima, se ce ne sono ancora
                        if (nuove.partenze.isEmpty() && prima != null && prima.partenze.any { it.partenza > adesso }) prima else nuove
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    prima
                }
            }
        }
    }
    val tabellone = try {
        calcolaTabelloneOrologio(orario, preferiti, fissate, posizione, adesso, sempre = fermateWidget)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    DatiWidget(
        creato = adesso,
        tabellone = tabellone,
        fissate = fissate,
        salvati = salvati,
        partenze = partenze.awaitAll().mapNotNull { (id, p) -> p?.let { id to it } }.toMap(),
        automatico = automatico,
        nomi = nomi,
    )
}

/** Una riga di un widget: "64 · Stazione S. Pietro · poi 9 · 1'". */
data class RigaWidgetTelefono(
    val linea: String,
    val colore: Long,
    val capolinea: String,
    /** "poi 9'" o vuoto. */
    val poi: String,
    /** "1'", "ora". */
    val tra: String,
    val tempoReale: Boolean,
    val perVoce: String,
)

/** Il widget grande: la preferita a cui sei vicino con le linee fissate. */
data class WidgetTabellone(
    /** "PREFERITA", "VICINO A TE". */
    val etichetta: String,
    val nome: String,
    val fermataId: String,
    /** L'ora dei tempi: "18:34". */
    val ora: String,
    val righe: List<RigaWidgetTelefono>,
    val nota: String,
)

/** Il widget piccolo: la fermata piu' vicina e le prime due linee. */
data class WidgetVicina(
    val nome: String,
    val fermataId: String,
    /** "121 m". */
    val distanza: String,
    val righe: List<RigaWidgetTelefono>,
    val nota: String?,
)

private fun riga(r: RigaOrologio, adesso: Long): RigaWidgetTelefono? {
    val prossimi = r.prossimi(adesso)
    val primo = prossimi.firstOrNull() ?: return null
    // "poi 9" come nel disegno (senza il segno dei minuti)
    val poi = prossimi.getOrNull(1)?.let { tx("poi {0}", testoMinuti(it, adesso).removeSuffix("'")) }.orEmpty()
    return RigaWidgetTelefono(
        linea = r.linea.take(8),
        colore = r.colore.toLong() and 0xFFFFFFFFL,
        capolinea = r.capolinea,
        poi = poi,
        tra = conPrimo(testoMinuti(primo, adesso)),
        tempoReale = r.tempoReale,
        perVoce = perVoceRigaOrologio(r, adesso),
    )
}

/**
 * Il widget della fermata: quella scelta ([fermataId]) o, se non se n'e'
 * scelta una, la preferita a cui sei vicino. [adesso] in secondi.
 */
fun widgetTabellone(d: DatiWidget?, adesso: Long, quante: Int = 3, fermataId: String? = null): WidgetTabellone {
    val t = d?.tabellone
    val scelta = !fermataId.isNullOrEmpty()
    val f = if (scelta) {
        t?.fermate?.firstOrNull { it.id == fermataId }
    } else {
        t?.fermate?.firstOrNull { it.preferita } ?: t?.fermate?.firstOrNull()
    }
    if (d == null || t == null || f == null) {
        return WidgetTabellone(
            "PALINA", "Palina Roma", fermataId.orEmpty(), "", emptyList(),
            when {
                scelta && d != null -> tx("Apri Palina per aggiornare")
                else -> t?.nota ?: tx("Aggiungi una fermata ai preferiti, o apri Palina per i tempi delle fermate vicine.")
            },
        )
    }
    val fisse = lineeFissate(f.id, d.fissate)
    val scelte = if (fisse.isNotEmpty()) f.righe.filter { it.lineaId in fisse } else f.righe
    val righe = scelte.mapNotNull { riga(it, adesso) }.take(quante)
    val nota = when {
        adesso * 1000 - d.creato > 60 * 60_000L -> tx("Tempi di più di un'ora fa: tocca per aggiornare")
        righe.isEmpty() && fisse.isNotEmpty() -> tx("Le linee che hai fissato non passano nei prossimi 90 minuti.")
        righe.isEmpty() -> tx("Nessun passaggio a breve")
        fisse.isNotEmpty() -> tx("Solo le linee che hai fissato · tocca per aggiornare")
        else -> tx("Fissa le linee che usi con la puntina · tocca per aggiornare")
    }
    return WidgetTabellone(
        // come nel disegno: il nome che hai dato alla fermata ("LAVORO")
        etichetta = when {
            !d.nomi[f.id].isNullOrBlank() -> d.nomi.getValue(f.id).uppercase()
            scelta -> tx("FERMATA")
            f.preferita -> tx("PREFERITA")
            else -> tx("VICINO A TE")
        },
        nome = f.nome,
        fermataId = f.id,
        ora = oraARoma(d.creato),
        righe = righe,
        nota = nota,
    )
}

/** "4'" -> "4′": il segno dei minuti delle paline (come nel disegno e nell'app). */
private fun conPrimo(minuti: String): String = if (minuti.endsWith("'")) minuti.dropLast(1) + "′" else minuti

/** Il widget della fermata piu' vicina. [adesso] in secondi. */
fun widgetVicina(d: DatiWidget?, adesso: Long): WidgetVicina {
    val vicine = d?.tabellone?.fermate?.filter { it.distanza != null }?.sortedBy { it.distanza!! }.orEmpty()
    // la piu' vicina, o una poco piu' in la' se quella non ha passaggi a breve
    val f = vicine.firstOrNull { v -> v.distanza!! <= vicine.first().distanza!! + 150 && v.righe.any { it.prossimi(adesso).isNotEmpty() } }
        ?: vicine.firstOrNull()
        ?: return WidgetVicina("Palina", "", "", emptyList(), tx("Apri Palina con la posizione attiva"))
    val righe = f.righe.mapNotNull { riga(it, adesso) }.sortedBy { r -> if (r.tra.endsWith("′")) r.tra.dropLast(1).toIntOrNull() ?: 0 else 0 }.take(2)
    return WidgetVicina(
        nome = f.nome,
        fermataId = f.id,
        distanza = testoDistanza(f.distanza!!),
        righe = righe,
        nota = if (righe.isEmpty()) tx("Nessun passaggio a breve") else null,
    )
}

/**
 * Il widget "esci tra": il tragitto salvato scelto ([id]) o quello che parte
 * piu' vicino a dove sei. [adessoMs] in millisecondi.
 */
fun widgetTragitto(d: DatiWidget?, adessoMs: Long, id: String? = null): CartaSalvato? {
    val salvati = d?.salvati ?: return null
    val scelto = if (id.isNullOrEmpty()) d.automatico else id
    val t = salvati.firstOrNull { it.id == scelto } ?: return null
    return cartaTragittoSalvato(t, d.partenze[t.id], adessoMs)
}

/**
 * I widget minuto per minuto per la prossima ora, in JSON per WidgetKit
 * sull'iPhone. Ogni voce: "alle" (ms), "tabellone", "vicina", "tragitto".
 */
fun vociWidget(d: DatiWidget?, adessoMs: Long, minuti: Int = 60): String {
    val inizio = adessoMs / 60_000 * 60_000
    // nei widget si scelgono le fermate preferite (e quelle gia' scelte, che l'app aggiunge)
    val fermate = d?.tabellone?.fermate.orEmpty().filter { it.preferita }
    val voci = (0 until minuti).map { i ->
        val alle = inizio + i * 60_000L
        val adesso = alle / 1000
        val tab = widgetTabellone(d, adesso, quante = 4)
        val vic = widgetVicina(d, adesso)
        val tra = widgetTragitto(d, alle)
        fun righe(r: List<RigaWidgetTelefono>) = r.map {
            mapOf(
                "linea" to it.linea, "colore" to it.colore, "capolinea" to it.capolinea, "poi" to it.poi,
                "tra" to it.tra, "tempoReale" to it.tempoReale,
            )
        }
        fun tabellone(w: WidgetTabellone) = mapOf(
            "etichetta" to w.etichetta, "nome" to w.nome, "fermataId" to w.fermataId, "ora" to w.ora,
            "righe" to righe(w.righe), "nota" to w.nota,
        )
        fun carta(c: CartaSalvato) = mapOf(
            "id" to c.id, "nome" to c.nome, "esci" to c.esci, "numero" to c.numero, "unita" to c.unita,
            "sotto" to c.sotto, "linea" to c.linea, "colore" to c.colore, "poi" to c.poi, "viaggio" to c.viaggio,
        )
        mapOf(
            "alle" to alle,
            "tabellone" to tabellone(tab),
            // le fermate e i tragitti che si possono scegliere nei widget
            "fermate" to fermate.associate { it.id to tabellone(widgetTabellone(d, adesso, quante = 4, fermataId = it.id)) },
            "tragitti" to d?.salvati.orEmpty().associate { it.id to carta(widgetTragitto(d, alle, it.id)!!) },
            "vicina" to mapOf(
                "nome" to vic.nome, "fermataId" to vic.fermataId, "distanza" to vic.distanza, "righe" to righe(vic.righe),
                "nota" to vic.nota, "etichetta" to tx("PIÙ VICINA"),
            ),
            "tragitto" to tra?.let { carta(it) },
        )
    }
    return ScriviJson.testo(
        mapOf(
            "voci" to voci,
            "elenco" to mapOf(
                "fermate" to fermate.map {
                    val nome = d?.nomi?.get(it.id)
                    mapOf("id" to it.id, "nome" to (nome ?: it.nome), "sotto" to if (nome != null) it.nome else it.sotto)
                },
                "tragitti" to d?.salvati.orEmpty().map { mapOf("id" to it.id, "nome" to it.nome) },
            ),
            "vuoto" to mapOf(
                "tragitto" to tx("Salva un tragitto in «Come arrivo» per vedere quando uscire."),
                "vecchio" to tx("Apri Palina per aggiornare"),
            ),
        ),
    )
}

/** Una scorciatoia dell'icona (tenendola premuta). */
data class Scorciatoia(val id: String, val titolo: String, val breve: String)

/** Le scorciatoie: fermata piu' vicina, i primi tragitti salvati, numero della palina. */
fun scorciatoie(salvati: List<TragittoSalvato>): List<Scorciatoia> {
    val vicina = Scorciatoia("vicina", tx("Fermata più vicina"), tx("Più vicina"))
    val palina = Scorciatoia("palina", tx("Numero della palina"), tx("Palina"))
    val tragitti = salvati.take(2).map { Scorciatoia("tragitto:" + it.id, it.nome, it.nome.take(24)) }
    return listOfNotNull(vicina, tragitti.getOrNull(0), palina, tragitti.getOrNull(1))
}
