package it.palinaroma.ponte

import it.palinaroma.dati.Orario
import it.palinaroma.dati.adessoMs
import it.palinaroma.dati.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * "Segui" sull'iPhone: lo stesso calcolo dell'orologio Wear OS (Segui.kt).
 * L'iPhone lo mostra come Live Activity (schermata di blocco, Dynamic Island
 * e Smart Stack dell'Apple Watch) e manda un avviso 2 minuti prima.
 */

/** Cosa mostrare di una linea seguita. */
data class StatoSegui(
    val fermataId: String,
    val lineaId: String,
    val fermata: String,
    val linea: String,
    val capolinea: String,
    val colore: Long,
    /** Il passaggio seguito, in millisecondi. */
    val arrivoMs: Long,
    val tempoReale: Boolean,
    /** "64 → Stazione San Pietro". */
    val titolo: String,
    /** "Piazza Venezia · tra 4' (14:05) · tempo reale". */
    val testo: String,
    /** "tempo reale" o "da orario". */
    val fonte: String,
    /** L'avviso 2 minuti prima: quando (millisecondi), titolo e testo. */
    val avvisoAlleMs: Long,
    val avvisoTitolo: String,
    val avvisoTesto: String,
    /** Per riprendere il seguimento se l'app si riapre. */
    val json: String,
)

class MotoreSegui(private val orario: Orario) {
    private var seguito: Seguimento? = null

    /** Riprende un seguimento salvato (null se e' gia' finito). */
    fun riprendi(json: String): StatoSegui? {
        seguito = seguimentoDaJson(json)?.let { ricalcola(it, null, adessoMs() / 1000) }
        return stato()
    }

    /**
     * Comincia a seguire la linea [lineaId] verso [capolinea] alla fermata
     * [fermataId] (dal tabellone della fermata). Null se non ha passaggi.
     */
    suspend fun inizia(fermataId: String, lineaId: String, capolinea: String): StatoSegui? = withContext(Dispatchers.Main) {
        val adesso = adessoMs()
        val t = tabellone(fermataId, lineaId, adesso)
        val f = t?.fermate?.firstOrNull { it.id == fermataId }
        val r = f?.righe?.let { righe ->
            righe.firstOrNull { it.lineaId == lineaId && it.capolinea == capolinea } ?: righe.firstOrNull { it.lineaId == lineaId }
        }
        seguito = if (t != null && f != null && r != null) seguimento(f, r, t, adesso / 1000) else null
        stato()
    }

    /** Chiede di nuovo i tempi: il passaggio seguito si sposta col tempo reale. Null quando e' finito. */
    suspend fun aggiorna(): StatoSegui? = withContext(Dispatchers.Main) {
        val s = seguito ?: return@withContext null
        val adesso = adessoMs()
        val t = tabellone(s.fermataId, s.lineaId, adesso)
        seguito = seguito?.let { ricalcola(it, t, adesso / 1000) }
        stato()
    }

    fun smetti() {
        seguito = null
    }

    /** Lo stato per adesso (null se non si segue niente o il mezzo e' passato da un minuto). */
    fun stato(): StatoSegui? {
        val adesso = adessoMs() / 1000
        val s = seguito?.let { ricalcola(it, null, adesso) } ?: run {
            seguito = null
            return null
        }
        val avviso = s.copy(avvisato = true)
        return StatoSegui(
            fermataId = s.fermataId,
            lineaId = s.lineaId,
            fermata = s.fermata,
            linea = s.linea,
            capolinea = s.capolinea,
            colore = s.colore.toLong() and 0xFFFFFFFFL,
            arrivoMs = s.obiettivo * 1000,
            tempoReale = s.tempoReale,
            titolo = titoloSegui(s),
            testo = testoSegui(s, adesso),
            fonte = if (s.tempoReale) tx("tempo reale") else tx("da orario"),
            avvisoAlleMs = (s.obiettivo - AVVISO_SECONDI) * 1000,
            avvisoTitolo = titoloAvvisoSegui(avviso),
            avvisoTesto = testoAvvisoSegui(avviso, maxOf(adesso, s.obiettivo - AVVISO_SECONDI)),
            json = s.testoJson(),
        )
    }

    /** Il tabellone della sola fermata seguita (come lo chiede l'orologio Wear OS); null senza rete. */
    private suspend fun tabellone(fermataId: String, lineaId: String, adesso: Long): TabelloneOrologio? = try {
        calcolaTabelloneOrologio(orario, emptySet(), emptySet(), null, adesso, listOf(LineaSeguita(fermataId, lineaId)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
