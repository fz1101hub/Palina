package it.palinaroma.dati

import it.palinaroma.ponte.CorsaOrologio
import it.palinaroma.ponte.TrenoOrologio
import it.palinaroma.ponte.calcolaCorsaOrologio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Il motore visto dalle schermate SwiftUI (iPhone, poi Apple Watch). Ogni
 * schermata ha il suo oggetto: le funzioni sospese si chiamano dal thread
 * principale e lavorano li' (le letture e la rete vanno da sole sui loro
 * thread); le altre ricalcolano subito con i dati gia' letti.
 */

/** Da chiamare una volta all'avvio dell'app per iPhone (o Apple Watch): rete e nome dell'app. */
fun preparaMotore(agenteUtente: String) {
    Collegamento.http = HttpApple
    Collegamento.agenteUtente = agenteUtente
}

/** La Home: preferite, fermate vicine, ricerca e tempi. */
class MotoreHome(private val orario: Orario) {
    private val caricatore = CaricatoreArrivi()
    private var arrivi = ArriviFermate()
    private var preferite: List<Fermata> = emptyList()
    private var fissate: Set<String> = emptySet()
    private var lat: Double? = null
    private var lon: Double? = null

    /** Le preferite salvate (id) e le linee fissate ("fermata|linea"), come sull'app Android. */
    fun impostaPreferiti(preferiti: Set<String>, fissate: Set<String>) {
        preferite = fermatePreferite(orario, preferiti)
        this.fissate = fissate
    }

    fun posizione(lat: Double, lon: Double) {
        this.lat = lat
        this.lon = lon
    }

    fun vicine(lat: Double, lon: Double): List<VoceFermata> = vociVicine(orario, lat, lon, 700.0)

    fun cerca(testo: String): List<VoceFermata> = vociCercate(orario, testo)

    /** Chiede i tempi (Roma Mobilita', BusOne, Transitous, ASTRAL) di preferite e vicine. */
    suspend fun aggiorna(vicine: List<VoceFermata>): StatoHome = withContext(Dispatchers.Main) {
        val ordine = ordinaPreferite(preferite, lat, lon)
        val fermateVicine = withContext(dispatcherDati) {
            val trovate = orario.fermate(vicine.map { it.id })
            vicine.mapNotNull { trovate[it.id] }
        }
        val idTempoReale = (ordine.ordinate.map { it.first.id } + vicine.map { it.id }).distinct()
        arrivi = caricatore.aggiorna(orario, fermateComplete(ordine.ordinate.map { it.first }, fermateVicine), idTempoReale, arrivi)
        stato(vicine)
    }

    /** La Home con i tempi gia' letti, ricalcolata per adesso (i minuti scendono da soli). */
    fun stato(vicine: List<VoceFermata>): StatoHome =
        statoHome(orario, ordinaPreferite(preferite, lat, lon), fissate, vicine, arrivi, adessoMs())
}

/** La schermata di una fermata: intestazione, linee, tabellone con le linee fissate. */
class MotoreFermata(private val orario: Orario, private val fermataId: String) {
    private val caricatore = CaricatoreArrivi()
    private var arrivi = ArriviFermate()
    private var fissate: Set<String> = emptySet()
    private val nomiLinee: Map<String, String> by lazy { lineeDellaFermata(orario, fermataId).associate { it.lineaId to it.nome } }

    fun intestazione(): VoceFermata? = intestazioneFermata(orario, fermataId)

    fun linee(): List<VoceLinea> = lineeDellaFermata(orario, fermataId)

    /** Le linee fissate salvate ("fermata|linea"): servono quelle di questa fermata. */
    fun impostaFissate(chiavi: Set<String>) {
        fissate = lineeFissate(fermataId, chiavi)
    }

    /** La chiave da salvare (o togliere) per fissare una linea a questa fermata. */
    fun chiaveDaFissare(lineaId: String): String = chiaveFissata(fermataId, lineaId)

    suspend fun aggiorna(): TabelloneFermata = withContext(Dispatchers.Main) {
        val fermata = withContext(dispatcherDati) { orario.fermata(fermataId) }
        if (fermata != null) arrivi = caricatore.aggiorna(orario, listOf(fermata), emptyList(), arrivi)
        tabellone()
    }

    /** Nelle stazioni unite i treni si chiedono dalla banchina della loro rete. */
    private val membri: List<String> by lazy { orario.stazioneUnita(fermataId) }

    /** Il tabellone con i tempi gia' letti, per adesso. */
    fun tabellone(): TabelloneFermata = tabelloneFermata(fermataId, arrivi, adessoMs(), fissate, nomiLinee) { a ->
        membri.firstOrNull { Rete.di(it) == Rete.di(a.lineaId) } ?: fermataId
    }
}

/** Una direzione di una linea: "→ Casaletto". */
data class VoceDirezione(val direzione: Int, val testo: String, val scelta: Boolean)

/** Una vettura sulla mappa della linea. */
data class VoceVettura(
    val chiave: String,
    val lat: Double,
    val lon: Double,
    /** La rotta in gradi, -1 se non si sa. */
    val rotta: Double,
    val etichetta: String,
    val altraDirezione: Boolean,
    val fuoriPercorso: Boolean,
    /** La vettura della corsa che stai aspettando. */
    val attesa: Boolean,
    /** La posizione ha piu' di 3 minuti. */
    val vecchia: Boolean,
)

/** La fermata della linea piu' vicina a te e la frase che lo dice. */
data class FermataVicinaLinea(val fermataId: String, val testo: String)

/** Le vetture della linea e il testo sotto la mappa. */
data class StatoLinea(val vetture: List<VoceVettura>, val testo: String, val senzaGps: String?)

/** La schermata di una linea: direzioni, fermate, tracciato e vetture con il GPS. */
class MotoreLinea(
    private val orario: Orario,
    val lineaId: String,
    direzioneIniziale: Int,
    private val corsaEvidenziata: String?,
    private val fermataEvidenziata: String?,
) {
    private val linea = orario.linea(lineaId)
    private val percorsi = orario.percorsi(lineaId)
    private val perDirezione = fermatePerDirezione(orario, lineaId, percorsi)
    private var vetture = VettureLette()
    private var caricato = false

    val nome: String = linea?.nome ?: lineaId
    val colore: Long = coloreReteArgb(lineaId)
    val fermataAttesa: Fermata? = fermataEvidenziata?.let { orario.fermata(it) }

    var direzione: Int = (percorsi.firstOrNull { it.direzione == direzioneIniziale } ?: percorsi.firstOrNull())?.direzione ?: 0
        private set

    private val percorso: Percorso? get() = percorsi.firstOrNull { it.direzione == direzione }

    /** L'orario ha almeno un percorso per questa linea. */
    fun haPercorso(): Boolean = percorsi.isNotEmpty()

    /** "verso Casaletto", o la descrizione della linea. */
    fun sottotitolo(): String =
        percorso?.capolinea?.takeIf { it.isNotEmpty() }?.let { "verso $it" } ?: linea?.descrizione.orEmpty()

    fun direzioni(): List<VoceDirezione> = percorsi.map {
        VoceDirezione(it.direzione, "→ " + it.capolinea.ifEmpty { "Direzione ${it.direzione + 1}" }, it.direzione == direzione)
    }

    fun scegli(direzione: Int) {
        if (direzione != this.direzione) {
            this.direzione = direzione
            vetture = vetture.copy(inDirezione = emptyList(), altre = emptyList())
        }
    }

    /** Passa all'altra direzione (o alla successiva). */
    fun cambiaDirezione() {
        if (percorsi.size < 2) return
        val i = percorsi.indexOfFirst { it.direzione == direzione }
        scegli(percorsi[(i + 1) % percorsi.size].direzione)
    }

    fun fermate(): List<Fermata> = orario.fermateDelPercorso(lineaId, direzione)

    /** Il tracciato della direzione scelta (o le fermate unite, se manca). */
    fun forma(): List<Punto> {
        val tracciato = percorso?.let { orario.forma(it.forma) }.orEmpty()
        return if (tracciato.size >= 2) tracciato else fermate().map { Punto(it.lat, it.lon) }
    }

    /** Il tracciato dell'altra direzione: spesso passa per strade diverse. */
    fun formaAltra(): List<Punto> {
        val altro = percorsi.firstOrNull { it.direzione != direzione } ?: return emptyList()
        return orario.forma(altro.forma).takeIf { it.size >= 2 }
            ?: orario.fermateDelPercorso(lineaId, altro.direzione).map { Punto(it.lat, it.lon) }
    }

    /** La fermata della linea piu' vicina a te: "Sei a 300 m da Piazza Venezia, la sua fermata più vicina ›". */
    fun fermataVicina(lat: Double, lon: Double): FermataVicinaLinea? =
        fermate().map { it to distanzaMetri(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }
            ?.let { (f, metri) -> FermataVicinaLinea(f.id, testoFermataVicinaLinea(f.nome, metri)) }

    /** Legge le vetture con il GPS (ogni 15 secondi): senza rete restano quelle di prima. */
    suspend fun aggiorna(): StatoLinea = withContext(Dispatchers.Main) {
        if (lineaConVetture(lineaId)) {
            try {
                vetture = leggiVettureLinea(
                    orario, lineaId, linea?.nome, direzione, perDirezione,
                    corsaEvidenziata, fermataEvidenziata, vetture,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // senza rete restano gli ultimi mezzi noti
            }
        }
        caricato = true
        stato()
    }

    fun stato(): StatoLinea {
        val adesso = adessoMs() / 1000
        val tutte = vetture.inDirezione + vetture.altre + listOfNotNull(vetture.evidenziato)
        val lontananze = lontananzeVetture(lineaId, tutte, forma(), formaAltra())
        val attesa = vetture.evidenziato?.let { chiaveMezzo(it) }
        val altre = vetture.altre.map { chiaveMezzo(it) }.toSet()
        val voci = tutte.distinctBy { chiaveMezzo(it) }.map { m ->
            val chiave = chiaveMezzo(m)
            VoceVettura(
                chiave = chiave,
                lat = m.lat,
                lon = m.lon,
                rotta = m.rotta?.toDouble() ?: -1.0,
                etichetta = m.etichetta,
                altraDirezione = chiave in altre && chiave != attesa,
                fuoriPercorso = (lontananze[chiave] ?: 0.0) > METRI_FUORI_PERCORSO,
                attesa = chiave == attesa,
                vecchia = m.rilevato > 0 && adesso - m.rilevato > POSIZIONE_VECCHIA_SECONDI,
            )
        }
        val testo = testoVetture(
            lineaId, vetture.inDirezione.size, vetture.altre.size, caricato, vetture.romaFerma, vetture.aggiornatoAlle,
        )
        val senzaGps = if (corsaEvidenziata != null && caricato && lineaConVetture(lineaId) && vetture.evidenziato == null) {
            testoSenzaGps(fermataAttesa, vetture.arrivoAtteso, adesso)
        } else {
            null
        }
        return StatoLinea(voci, testo, senzaGps)
    }

    /** La scheda della vettura toccata sulla mappa. */
    fun scheda(chiave: String): SchedaVettura? {
        val tutte = vetture.inDirezione + vetture.altre + listOfNotNull(vetture.evidenziato)
        val mezzo = tutte.firstOrNull { chiaveMezzo(it) == chiave } ?: return null
        val attesa = vetture.evidenziato?.let { chiaveMezzo(it) == chiave } ?: false
        val distanza = lontananzeVetture(lineaId, listOf(mezzo), forma(), formaAltra())[chiave]?.takeIf { it > METRI_FUORI_PERCORSO }
        return schedaVettura(
            mezzo,
            attesa = attesa,
            altraDirezione = vetture.altre.any { chiaveMezzo(it) == chiave },
            distanzaPercorso = distanza,
            fermata = fermataAttesa,
            arrivo = vetture.arrivoAtteso,
            adesso = adessoMs() / 1000,
        )
    }
}

/** Una fermata di un treno, pronta da disegnare. */
data class RigaCorsa(
    val nome: String,
    /** "07:45" (aggiornato), o "–". */
    val ora: String,
    /** L'ora programmata se e' diversa (si scrive barrata). */
    val oraPrevista: String?,
    /** "+3", "-1", o null. */
    val ritardo: String?,
    /** "Binario 4". */
    val binario: String?,
    val binarioCambiato: Boolean,
    val soppressa: Boolean,
    /** Orario stimato dal ritardo visto a un'altra stazione. */
    val stimato: Boolean,
    /** La stazione da cui lo guardi. */
    val qui: Boolean,
    /** Gia' passato. */
    val passata: Boolean,
    val perVoce: String,
)

/** Il dettaglio di un treno: tutte le fermate con orari, ritardo e binario. */
data class StatoCorsa(
    val titolo: String,
    val verso: String?,
    val fonte: String?,
    val righe: List<RigaCorsa>,
    /** L'indice della tua stazione, -1 se non c'e'. */
    val qui: Int,
    val errore: String?,
)

/** La schermata di un treno: lo stesso calcolo che il telefono fa per gli orologi. */
class MotoreCorsa(private val orario: Orario, private val treno: String) {
    suspend fun aggiorna(): StatoCorsa = withContext(Dispatchers.Main) {
        val richiesta = TrenoOrologio.leggi(treno)
            ?: return@withContext StatoCorsa("Treno", null, null, emptyList(), -1, "Richiesta non valida.")
        statoCorsa(calcolaCorsaOrologio(orario, richiesta), adessoMs())
    }
}

/** Le fermate di un treno pronte da disegnare (sull'iPhone e sull'Apple Watch). */
fun statoCorsa(corsa: CorsaOrologio, adesso: Long): StatoCorsa = StatoCorsa(
    titolo = corsa.titolo,
    verso = corsa.verso,
    fonte = corsa.fonte,
    righe = corsa.fermate.mapIndexed { i, f ->
        val ora = f.orario?.let { oraARoma(it) } ?: "–"
        val prevista = f.previsto?.let { oraARoma(it) }?.takeIf { it != ora && f.orario != null }
        val ritardo = f.ritardoMinuti?.takeIf { it != 0 }?.let { if (it > 0) "+$it" else "$it" }
        RigaCorsa(
            nome = f.nome,
            ora = ora,
            oraPrevista = prevista,
            ritardo = ritardo,
            binario = f.binario?.let { "Binario $it" },
            binarioCambiato = f.binarioCambiato,
            soppressa = f.soppressa,
            stimato = f.stimato,
            qui = i == corsa.qui,
            passata = (f.orario ?: f.previsto ?: Long.MAX_VALUE) < adesso - 60_000,
            perVoce = buildString {
                append(f.nome)
                if (f.soppressa) append(", fermata soppressa") else append(", alle $ora")
                if (ritardo != null) append(", ritardo $ritardo minuti")
                f.binario?.let { append(", binario $it") }
                if (i == corsa.qui) append(", la tua stazione")
            },
        )
    },
    qui = corsa.qui ?: -1,
    errore = corsa.errore,
)
