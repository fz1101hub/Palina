package it.palinaroma.dati

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * I treni Cotral (Metromare e Roma–Viterbo) in tempo reale, dal servizio orari
 * di ASTRAL, la societa' della Regione che gestisce le due ferrovie. Risponde
 * senza password ma non e' documentato: se cambia o non risponde, la stazione
 * mostra l'orario programmato di Transitous.
 *
 * Due richieste, entrambe POST con un corpo JSON:
 *  - fermate/<percorso>: le stazioni di un percorso (nome, codice, ordine);
 *  - transit {percorso, fermata}: i treni del giorno a quella stazione, con
 *    l'ora ("H:MM"), il ritardo in minuti (vuoto se non ancora noto), soppressi
 *    e bus sostitutivi.
 */
object Astral {
    const val API = "https://gestionecorse.astralspa.it/api"

    /** Un percorso di ASTRAL: una linea in una direzione. [linea] e' il nome nel GTFS ferroviario di Cotral. */
    data class Percorso(val codice: String, val linea: String, val direzione: Int, val capolinea: String)

    val PERCORSI = listOf(
        Percorso("RL_PSP-CC", "ROMALIDO", 0, "Cristoforo Colombo"),
        Percorso("RL_CC-PSP", "ROMALIDO", 1, "Porta San Paolo"),
        Percorso("RN_RMMON", "RVURB", 0, "Montebello"),
        Percorso("RN_MONRM", "RVURB", 1, "Flaminio"),
        Percorso("RV_CATVIT", "RVEXT", 0, "Viterbo"),
        Percorso("RV_CATMOR", "RVEXT", 0, "Morlupo"),
        Percorso("RV_VITCAT", "RVEXT", 1, "Catalano"),
        Percorso("RV_MORCAT", "RVEXT", 1, "Catalano"),
    )

    /** Le stazioni di ogni percorso cambiano di rado: si leggono una volta per avvio dell'app. */
    private val stazioni = MappaCondivisa<String, List<StazioneAstral>>()

    private suspend fun chiedi(percorso: String, corpo: String): String {
        val risposta = Collegamento.http.richiedi(
            "$API/$percorso",
            mapOf("User-Agent" to Collegamento.agenteUtente, "Accept" to "application/json"),
            Attesa.BREVE,
            corpo.encodeToByteArray(),
            "application/json; charset=utf-8",
        )
        val testo = risposta.testo()
        if (!risposta.riuscita) throw IllegalStateException("ASTRAL ha risposto ${risposta.codice}")
        return testo
    }

    private suspend fun stazioniDi(percorso: String): List<StazioneAstral> =
        stazioni[percorso] ?: leggiStazioni(chiedi("fermate/$percorso", "{}")).also { stazioni[percorso] = it }

    /**
     * I treni dei prossimi [orizzonteMinuti] alla stazione [nome], per le linee
     * Cotral che vi passano ([lineeGtfs]: "ROMALIDO", "RVURB", "RVEXT", associate
     * all'identificativo della linea nell'app). Null se ASTRAL non risponde.
     */
    suspend fun passaggi(
        nome: String,
        lineeGtfs: Map<String, String>,
        adessoMs: Long,
        orizzonteMinuti: Int = 90,
    ): List<PassaggioLinea>? = withContext(dispatcherDati) {
        val percorsi = PERCORSI.filter { it.linea in lineeGtfs }
        if (percorsi.isEmpty()) return@withContext null
        val risultati = coroutineScope {
            percorsi.map { p ->
                async {
                    // null: la stazione non e' di questo percorso (o il nome non si trova), oppure
                    // ASTRAL non ha nessun treno per tutto il giorno (di notte, o se qualcosa non va)
                    runCatching {
                        val codice = codiceStazione(stazioniDi(p.codice), nome) ?: return@runCatching null
                        val voci = leggiTransiti(chiedi("transit", "{\"percorso\":\"${p.codice}\",\"fermata\":\"$codice\"}"))
                        if (voci.isEmpty()) return@runCatching null
                        passaggiDaTransiti(voci, p, lineeGtfs.getValue(p.linea), adessoMs, orizzonteMinuti)
                    }
                }
            }.awaitAll()
        }
        // nessun percorso ha risposto per questa stazione: si passa all'orario di Transitous
        val risposte = risultati.mapNotNull { it.getOrNull() }
        if (risposte.isEmpty()) null else risposte.flatten()
    }
}

data class StazioneAstral(val nome: String, val codice: String, val ordine: Int)

/** Un treno a una stazione: ora programmata ("H:MM"), ritardo in minuti se noto. */
data class TransitoAstral(
    val corsa: String,
    val ora: String,
    val ritardo: Int?,
    val soppresso: Boolean,
    val busSostitutivo: Boolean,
)

/** Le risposte di ASTRAL sono oggetti (o elenchi) di righe: qui le righe. */
private fun righe(testo: String): List<Map<String, Any?>> {
    val dati = Json.leggi(testo)
    val valori = when (dati) {
        is Map<*, *> -> dati.values
        is List<*> -> dati
        else -> emptyList()
    }
    return valori.mapNotNull { v -> (v as? Map<*, *>)?.comeOggetto() }
}

/** Un valore qualsiasi come testo: i numeri senza ".0". */
private fun testoDi(v: Any?): String = when (v) {
    null -> ""
    is String -> v.trim()
    is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    is Number -> v.toString()
    is Boolean -> if (v) "Y" else ""
    else -> ""
}

fun leggiStazioni(testo: String): List<StazioneAstral> =
    righe(testo).mapNotNull { r ->
        val nome = testoDi(r["nomeFermata"]).ifEmpty { return@mapNotNull null }
        val codice = testoDi(r["codice"]).ifEmpty { return@mapNotNull null }
        StazioneAstral(nome, codice, testoDi(r["ordine"]).toIntOrNull() ?: 0)
    }.sortedBy { it.ordine }

fun leggiTransiti(testo: String): List<TransitoAstral> =
    righe(testo).mapNotNull { r ->
        val ora = testoDi(r["orario"])
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(ora)) return@mapNotNull null
        TransitoAstral(
            corsa = testoDi(r["corsa"]),
            ora = ora,
            ritardo = testoDi(r["ritardo"]).toIntOrNull(),
            soppresso = testoDi(r["soppressa"]).equals("Y", ignoreCase = true),
            busSostitutivo = testoDi(r["busSostitutivo"]).equals("Y", ignoreCase = true),
        )
    }

/**
 * Il codice ASTRAL di una stazione dal nome: prima uguale, poi uno dentro
 * l'altro ("Lido Centro" e "Lido di Ostia Centro" no, "Acilia" e "Acilia Sud" no:
 * prima si cerca l'uguale). Null se non si trova.
 */
fun codiceStazione(stazioni: List<StazioneAstral>, nome: String): String? {
    val cercato = normalizza(nome)
    if (cercato.isEmpty()) return null
    stazioni.firstOrNull { normalizza(it.nome) == cercato }?.let { return it.codice }
    stazioni.firstOrNull { s ->
        val n = normalizza(s.nome)
        n.isNotEmpty() && (n.contains(cercato) || cercato.contains(n))
    }?.let { return it.codice }
    // le abbreviazioni: "Basilica S. Paolo" e "Basilica San Paolo", "P.le Flaminio" e "Piazzale Flaminio"
    val parole = senzaPunti(nome)
    return stazioni.firstOrNull { s -> abbreviatoUguale(senzaPunti(s.nome), parole) }?.codice
}

/** "P.le Flaminio" -> ["ple", "flaminio"]: i punti delle abbreviazioni non spezzano le parole. */
private fun senzaPunti(nome: String): List<String> =
    normalizza(nome.replace(".", "")).split(' ').filter { it.isNotEmpty() }

/**
 * Stesse parole, ammesse le abbreviazioni: a coppie, stessa iniziale e le lettere
 * della piu' corta, in ordine, dentro la piu' lunga ("s" e "san", "ple" e "piazzale").
 */
private fun abbreviatoUguale(a: List<String>, b: List<String>): Boolean {
    if (a.isEmpty() || a.size != b.size) return false
    return a.zip(b).all { (x, y) ->
        val (corta, lunga) = if (x.length <= y.length) x to y else y to x
        corta[0] == lunga[0] && sottosequenza(corta, lunga)
    }
}

private fun sottosequenza(corta: String, lunga: String): Boolean {
    var i = 0
    for (c in lunga) if (i < corta.length && corta[i] == c) i++
    return i == corta.length
}


/**
 * I transiti del giorno trasformati nei passaggi dei prossimi [orizzonteMinuti]:
 * l'ora programmata piu' il ritardo, "in tempo reale" quando il ritardo e' noto.
 * Le ore molto prima di adesso sono del giorno dopo (i treni dopo mezzanotte).
 */
fun passaggiDaTransiti(
    voci: List<TransitoAstral>,
    percorso: Astral.Percorso,
    lineaId: String,
    adessoMs: Long,
    orizzonteMinuti: Int,
): List<PassaggioLinea> {
    val oggi = giornoARoma(adessoMs)
    val adessoS = adessoMs / 1000
    return voci.mapNotNull { v ->
        val (h, m) = v.ora.split(':').map { it.toInt() }
        if (h > 23 || m > 59) return@mapNotNull null
        var previsto = secondiARoma(oggi, h, m)
        if (previsto < adessoS - 6 * 3600) previsto = secondiARoma(oggi, h, m, giorniDopo = 1)
        val atteso = previsto + (v.ritardo ?: 0) * 60L
        if (atteso - adessoS !in -30..(orizzonteMinuti * 60L)) return@mapNotNull null
        val avviso = when {
            v.soppresso -> "soppresso"
            v.busSostitutivo -> "bus sostitutivo"
            else -> null
        }
        PassaggioLinea(
            lineaId = lineaId,
            nomeLinea = nomeLineaTreniCotral(percorso.linea),
            direzione = percorso.direzione,
            capolinea = percorso.capolinea,
            passaggio = Passaggio(
                atteso, "astral:" + percorso.codice + ":" + v.corsa, v.ritardo != null, avviso,
                ritardo = v.ritardo?.let { it * 60 },
            ),
        )
    }
}
