package it.palinaroma.ponte

import it.palinaroma.dati.Orario
import it.palinaroma.dati.Punto
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
    ): String = withContext(Dispatchers.Main) {
        val seguite = RichiestaOrologio.leggi(richiesta.encodeToByteArray())
        val posizione = if (conPosizione) Punto(lat, lon) else null
        CodificaOrologio.scrivi(calcolaTabelloneOrologio(orario, preferiti, fissate, posizione, adessoMs(), seguite))
    }

    /** Le fermate di un treno chiesto dall'orologio ([treno] in JSON). */
    suspend fun corsa(treno: String): String = withContext(Dispatchers.Main) {
        val richiesta = TrenoOrologio.leggi(treno)
            ?: return@withContext CodificaCorsa.scrivi(
                CorsaOrologio("", adessoMs(), "Treno", null, null, emptyList(), null, "Richiesta non valida."),
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
                        perVoce = "${r.linea} verso ${r.capolinea}: " + passaggi.replace("'", " minuti").replace(" · ", ", ") +
                            (if (r.tempoReale) ", in tempo reale" else ", da orario") + (r.avviso?.let { ", $it" } ?: ""),
                    )
                },
            )
        }
        return StatoWatch(
            fermate = fermate,
            nota = t.nota,
            aggiornato = "aggiornato " + testoAggiornato(t.creato, adessoMs),
            vecchio = adessoMs - t.creato > 60_000,
        )
    }
}
