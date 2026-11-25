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
import kotlinx.coroutines.coroutineScope

/*
 * I widget del telefono (Android e iPhone): il tabellone della preferita con
 * le linee fissate, la fermata piu' vicina e "esci tra" per un tragitto
 * salvato. L'app calcola i dati (stesso calcolo del tabellone per l'orologio)
 * e li salva; i widget li ridisegnano minuto per minuto senza rete.
 */

/** Tutto quello che serve ai widget, con l'ora in cui e' stato calcolato. */
data class DatiWidget(
    val creato: Long,
    val tabellone: TabelloneOrologio?,
    /** Le linee fissate ("fermata|linea"): nel widget della preferita solo quelle. */
    val fissate: Set<String>,
    val tragitto: TragittoSalvato?,
    val partenze: PartenzeTragitto?,
)

object CodificaWidget {
    fun scrivi(d: DatiWidget): String = ScriviJson.testo(
        mapOf(
            "v" to 1,
            "creato" to d.creato,
            "tabellone" to d.tabellone?.let { CodificaOrologio.scrivi(it) },
            "fissate" to d.fissate.toList(),
            "tragitto" to d.tragitto?.let {
                mapOf("id" to it.id, "nome" to it.nome, "da" to luogoInJson(it.partenza), "a" to luogoInJson(it.arrivo))
            },
            "partenze" to d.partenze?.let { CodificaPartenze.inJson(it) },
        ),
    )

    fun leggi(json: String?): DatiWidget? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = Json.leggi(json).comeOggetto()
            val t = o.oggetto("tragitto")
            DatiWidget(
                creato = (o["creato"] as? Number)?.toLong() ?: 0L,
                tabellone = CodificaOrologio.leggi(o["tabellone"] as? String),
                fissate = (o["fissate"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet(),
                tragitto = t?.let {
                    val da = it.oggetto("da")?.let { l -> luogoDaJson(l) } ?: return@let null
                    val a = it.oggetto("a")?.let { l -> luogoDaJson(l) } ?: return@let null
                    TragittoSalvato(it["id"] as? String ?: return@let null, it["nome"] as? String ?: "", da, a)
                },
                partenze = CodificaPartenze.daJson(o.oggetto("partenze")),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Calcola i dati dei widget: il tabellone delle preferite e delle fermate
 * vicine, e le partenze del tragitto salvato che parte piu' vicino.
 * [partenzePrima] sono quelle gia' calcolate: si richiedono a Transitous solo
 * se e' passato un quarto d'ora o e' cambiato il tragitto ([forza] sempre).
 */
suspend fun calcolaDatiWidget(
    orario: Orario,
    preferiti: Set<String>,
    fissate: Set<String>,
    posizione: Punto?,
    salvati: List<TragittoSalvato>,
    partenzePrima: PartenzeTragitto?,
    forza: Boolean,
): DatiWidget = coroutineScope {
    val adesso = adessoMs()
    val tragitto = tragittoPerWidget(salvati, posizione)
    val partenze = async {
        when {
            tragitto == null -> null
            !forza && partenzePrima != null && partenzePrima.id == tragitto.id &&
                adesso - partenzePrima.calcolato < 15 * 60_000 &&
                partenzePrima.partenze.any { it.partenza > adesso + 60_000 } -> partenzePrima
            else -> try {
                partenzeTragittoSalvato(tragitto, posizione)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                partenzePrima?.takeIf { it.id == tragitto.id }
            }
        }
    }
    val tabellone = try {
        calcolaTabelloneOrologio(orario, preferiti, fissate, posizione, adesso)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    DatiWidget(adesso, tabellone, fissate, tragitto, partenze.await())
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
    val poi = prossimi.getOrNull(1)?.let { tx("poi {0}", testoMinuti(it, adesso)) }.orEmpty()
    return RigaWidgetTelefono(
        linea = r.linea.take(8),
        colore = r.colore.toLong() and 0xFFFFFFFFL,
        capolinea = r.capolinea,
        poi = poi,
        tra = testoMinuti(primo, adesso),
        tempoReale = r.tempoReale,
        perVoce = perVoceRigaOrologio(r, adesso),
    )
}

/** Il widget della preferita. [adesso] in secondi. */
fun widgetTabellone(d: DatiWidget?, adesso: Long, quante: Int = 3): WidgetTabellone {
    val t = d?.tabellone
    val f = t?.fermate?.firstOrNull { it.preferita } ?: t?.fermate?.firstOrNull()
    if (d == null || t == null || f == null) {
        return WidgetTabellone(
            "PALINA", "Palina Roma", "", "", emptyList(),
            t?.nota ?: tx("Aggiungi una fermata ai preferiti, o apri Palina per i tempi delle fermate vicine."),
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
        etichetta = if (f.preferita) tx("PREFERITA") else tx("VICINO A TE"),
        nome = f.nome,
        fermataId = f.id,
        ora = oraARoma(d.creato),
        righe = righe,
        nota = nota,
    )
}

/** Il widget della fermata piu' vicina. [adesso] in secondi. */
fun widgetVicina(d: DatiWidget?, adesso: Long): WidgetVicina {
    val vicine = d?.tabellone?.fermate?.filter { it.distanza != null }?.sortedBy { it.distanza!! }.orEmpty()
    // la piu' vicina, o una poco piu' in la' se quella non ha passaggi a breve
    val f = vicine.firstOrNull { v -> v.distanza!! <= vicine.first().distanza!! + 150 && v.righe.any { it.prossimi(adesso).isNotEmpty() } }
        ?: vicine.firstOrNull()
        ?: return WidgetVicina("Palina", "", "", emptyList(), tx("Apri Palina con la posizione attiva"))
    val righe = f.righe.mapNotNull { riga(it, adesso) }.sortedBy { r -> if (r.tra.endsWith("'")) r.tra.dropLast(1).toIntOrNull() ?: 0 else 0 }.take(2)
    return WidgetVicina(
        nome = f.nome,
        fermataId = f.id,
        distanza = testoDistanza(f.distanza!!),
        righe = righe,
        nota = if (righe.isEmpty()) tx("Nessun passaggio a breve") else null,
    )
}

/** Il widget "esci tra" del tragitto salvato. [adessoMs] in millisecondi. */
fun widgetTragitto(d: DatiWidget?, adessoMs: Long): CartaSalvato? {
    val t = d?.tragitto ?: return null
    return cartaTragittoSalvato(t, d.partenze, adessoMs)
}

/**
 * I widget minuto per minuto per la prossima ora, in JSON per WidgetKit
 * sull'iPhone. Ogni voce: "alle" (ms), "tabellone", "vicina", "tragitto".
 */
fun vociWidget(d: DatiWidget?, adessoMs: Long, minuti: Int = 60): String {
    val inizio = adessoMs / 60_000 * 60_000
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
        mapOf(
            "alle" to alle,
            "tabellone" to mapOf(
                "etichetta" to tab.etichetta, "nome" to tab.nome, "fermataId" to tab.fermataId, "ora" to tab.ora,
                "righe" to righe(tab.righe), "nota" to tab.nota,
            ),
            "vicina" to mapOf(
                "nome" to vic.nome, "fermataId" to vic.fermataId, "distanza" to vic.distanza, "righe" to righe(vic.righe),
                "nota" to vic.nota, "etichetta" to tx("PIÙ VICINA"),
            ),
            "tragitto" to tra?.let {
                mapOf(
                    "id" to it.id, "nome" to it.nome, "esci" to it.esci, "numero" to it.numero, "unita" to it.unita,
                    "sotto" to it.sotto, "linea" to it.linea, "colore" to it.colore, "poi" to it.poi,
                )
            },
        )
    }
    return ScriviJson.testo(
        mapOf(
            "voci" to voci,
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
