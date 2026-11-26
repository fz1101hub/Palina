package it.palinaroma.ponte

import it.palinaroma.dati.Orario
import it.palinaroma.dati.ScriviJson
import it.palinaroma.dati.tx
import it.palinaroma.dati.Punto
import it.palinaroma.dati.StatoCorsa
import it.palinaroma.dati.statoCorsa
import it.palinaroma.dati.adessoMs
import it.palinaroma.dati.testoAggiornato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Apple Watch: come per Wear OS, l'iPhone calcola il tabellone (stesso
 * calcolo, stesso formato JSON) e l'orologio lo mostra. Qui i due lati visti
 * da Swift.
 */

/** Sull'iPhone: le risposte alle richieste dell'Apple Watch, in JSON. */
class MotorePerWatch(private val orario: Orario) {
    /**
     * Il tabellone per l'orologio. [richiesta] e' quella mandata dall'orologio
     * (con le linee seguite), anche vuota.
     */
    suspend fun tabellone(
        preferiti: Set<String>,
        fissate: Set<String>,
        conPosizione: Boolean,
        lat: Double,
        lon: Double,
        richiesta: String,
        nomi: Map<String, String>,
    ): String = withContext(Dispatchers.Main) {
        val seguite = RichiestaOrologio.leggi(richiesta.encodeToByteArray())
        val posizione = if (conPosizione) Punto(lat, lon) else null
        CodificaOrologio.scrivi(calcolaTabelloneOrologio(orario, preferiti, fissate, posizione, adessoMs(), seguite, nomi = nomi))
    }

    /** Le fermate di un treno chiesto dall'orologio ([treno] in JSON). */
    suspend fun corsa(treno: String): String = withContext(Dispatchers.Main) {
        val richiesta = TrenoOrologio.leggi(treno)
            ?: return@withContext CodificaCorsa.scrivi(
                CorsaOrologio("", adessoMs(), tx("Treno"), null, null, emptyList(), null, tx("Richiesta non valida.")),
            )
        CodificaCorsa.scrivi(calcolaCorsaOrologio(orario, richiesta))
    }
}

/** Una riga sull'Apple Watch: linea, capolinea e i prossimi passaggi ("3' · 12' · 25'"). */
data class RigaWatch(
    val linea: String,
    val colore: Long,
    val capolinea: String,
    val passaggi: String,
    val tempoReale: Boolean,
    val avviso: String?,
    val perVoce: String,
    /** Per i treni: la richiesta del primo treno (JSON), per vederne le fermate. */
    val treno: String?,
)

data class FermataWatch(
    val id: String,
    val nome: String,
    /** "Palina 70078 · 120 m". */
    val sotto: String,
    val preferita: Boolean,
    val righe: List<RigaWatch>,
)

/** Cosa mostra l'Apple Watch adesso. */
data class StatoWatch(
    val fermate: List<FermataWatch>,
    val nota: String?,
    /** "aggiornato 20 s fa", o null se non c'e' ancora niente. */
    val aggiornato: String?,
    /** I tempi hanno piu' di un minuto: l'iPhone non ha risposto. */
    val vecchio: Boolean,
)

/** Sull'Apple Watch: l'ultimo tabellone ricevuto dall'iPhone e le righe da disegnare. */
class MotoreWatch {
    private var tabellone: TabelloneOrologio? = null

    internal fun tabelloneAttuale(): TabelloneOrologio? = tabellone

    /** Il tabellone ricevuto in JSON: vero se e' piu' nuovo di quello che c'era. */
    fun ricevi(json: String): Boolean {
        val nuovo = CodificaOrologio.leggi(json) ?: return false
        val attuale = tabellone
        if (attuale != null && attuale.creato > nuovo.creato) return false
        tabellone = nuovo
        return true
    }

    /** Il tabellone di esempio, per le prove sul simulatore. */
    fun esempio() {
        tabellone = tabelloneDiEsempio(adessoMs() / 1000)
    }

    /** Le fermate di un treno ricevute dall'iPhone (JSON), pronte da disegnare. */
    fun corsa(json: String): StatoCorsa? = CodificaCorsa.leggi(json)?.let { statoCorsa(it, adessoMs()) }

    /** Le fermate di un treno di esempio, per le prove sul simulatore. */
    fun esempioCorsa(treno: String): StatoCorsa? =
        TrenoOrologio.leggi(treno)?.let { statoCorsa(corsaDiEsempio(it, adessoMs() / 1000), adessoMs()) }

    /** La richiesta da mandare all'iPhone (vuota: nessuna linea seguita, per ora). */
    fun richiesta(): String = RichiestaOrologio.scrivi(emptyList()).decodeToString()

    fun stato(): StatoWatch {
        val t = tabellone ?: return StatoWatch(emptyList(), null, null, vecchio = false)
        val adessoMs = adessoMs()
        val adesso = adessoMs / 1000
        val fermate = t.fermate.map { f ->
            FermataWatch(
                id = f.id,
                nome = f.nome,
                sotto = f.sotto + (f.distanza?.let { " · " + testoDistanza(it) } ?: ""),
                preferita = f.preferita,
                righe = f.righe.filter { it.prossimi(adesso).isNotEmpty() }.map { r ->
                    val passaggi = testoPassaggi(r, adesso, 3)
                    RigaWatch(
                        linea = r.linea,
                        colore = r.colore.toLong() and 0xFFFFFFFFL,
                        capolinea = r.capolinea,
                        passaggi = passaggi,
                        tempoReale = r.tempoReale,
                        avviso = r.avviso,
                        perVoce = perVoceRigaOrologio(r, adesso),
                        treno = r.prossimi(adesso).firstOrNull()?.let { r.treno(it) }?.testoJson(),
                    )
                },
            )
        }
        return StatoWatch(
            fermate = fermate,
            nota = t.nota,
            aggiornato = tx("aggiornato {0}", testoAggiornato(t.creato, adessoMs)),
            vecchio = adessoMs - t.creato > 60_000,
        )
    }
}

/**
 * Le complicazioni e lo Smart Stack dell'Apple Watch: il contenuto del widget
 * Wear OS (stesso calcolo) minuto per minuto per la prossima ora, in JSON, da
 * dare a WidgetKit. Ogni voce: "alle" (ms), "titolo", "righe" (linea, colore,
 * passaggi, tempoReale), "nota", e per le complicazioni piccole "linea" e "tra".
 */
fun MotoreWatch.complicazioni(): String = complicazioniDi(tabelloneAttuale(), adessoMs())

internal fun complicazioniDi(t: TabelloneOrologio?, adessoMs: Long): String {
    val inizio = adessoMs / 60_000 * 60_000
    val voci = (0 until 60).map { i ->
        val alle = inizio + i * 60_000L
        val adesso = alle / 1000
        val c = contenutoWidget(t, adesso, piccolo = false)
        val primo = fermataDelRiquadro(t, adesso)?.primoPassaggio(adesso)
        buildMap<String, Any?> {
            put("alle", alle)
            put("titolo", c.titolo)
            put(
                "righe",
                c.righe.map { r ->
                    mapOf("linea" to r.linea, "colore" to (r.colore.toLong() and 0xFFFFFFFFL), "passaggi" to r.passaggi, "tempoReale" to r.tempoReale)
                },
            )
            put("nota", c.nota)
            if (primo != null) {
                put("linea", primo.first.linea.take(7))
                put("tra", testoMinuti(primo.second, adesso))
                put("inline", tx("{0} tra {1} · {2}", primo.first.linea.take(7), testoMinuti(primo.second, adesso), c.titolo))
                put("colore", primo.first.colore.toLong() and 0xFFFFFFFFL)
            }
        }
    }
    return ScriviJson.testo(mapOf("voci" to voci))
}
