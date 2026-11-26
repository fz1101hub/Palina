package it.palinaroma.dati

import it.palinaroma.ponte.TrenoOrologio
import kotlin.math.roundToInt

/*
 * Quello che le schermate mostrano, deciso qui una volta per Android, iPhone e
 * Apple Watch: le schermate (Compose o SwiftUI) devono solo disegnarlo.
 */

/** I colori di Palina (ARGB), uguali su tutti i telefoni e orologi. */
object Colori {
    const val PORPORA = 0xFF862633
    const val ORO = 0xFFF2A900
    const val INCHIOSTRO = 0xFF1C1A1A
    const val BLU_COTRAL = 0xFF1D4F91
    const val VERDE_TRENI = 0xFF00703C
}

/** Il colore delle linee di una rete: porpora Roma, blu Cotral, verde Trenitalia. */
fun coloreReteArgb(id: String): Long = when (Rete.di(id)) {
    Rete.ROMA -> Colori.PORPORA
    Rete.COTRAL, Rete.COTRAL_TRENI -> Colori.BLU_COTRAL
    Rete.TRENITALIA -> Colori.VERDE_TRENI
}

/** Sotto il nome di una fermata: "Palina 70078", "Cotral · palina 12345", "Stazione · Trenitalia". */
fun descrizioneFermata(fermata: Fermata, orario: Orario?): String {
    // le stazioni di scambio: "Stazione · Metro B/B1 · Metromare"
    orario?.lineeStazioneUnita(fermata.id)?.let { return tx("Stazione · {0}", it) }
    val rete = Rete.di(fermata.id)
    return when {
        rete == Rete.ROMA -> tx("Palina {0}", fermata.codice)
        rete.treni -> tx("Stazione · {0}", rete.nome)
        else -> tx("{0} · palina {1}", rete.nome, fermata.codice)
    }
}

/** "140 m". */
fun testoDistanzaBreve(metri: Double): String = "${metri.roundToInt()} m"

/** Una fermata in un elenco (vicine, ricerca, preferite), senza i tempi. */
data class VoceFermata(
    val id: String,
    val nome: String,
    /** "Palina 70078", "Stazione · Metro B/B1 · Metromare". */
    val sotto: String,
    /** "140 m", o null se non si sa dove sei. */
    val distanza: String?,
    /** Le linee che ci passano, in ordine ("8", "64", "H"...), al massimo 14. */
    val linee: List<String>,
    /** Il colore dei loro bollini (ARGB). */
    val colore: Long,
    /** Il testo per chi usa il lettore dello schermo. */
    val perVoce: String,
    /** Lo stesso senza le linee (in Home al loro posto ci sono i tempi). */
    val perVoceSenzaLinee: String,
    val lat: Double,
    val lon: Double,
)

fun voceFermata(fermata: Fermata, distanzaMetri: Double?, orario: Orario?): VoceFermata {
    val rete = Rete.di(fermata.id)
    val membri = orario?.stazioneUnita(fermata.id) ?: listOf(fermata.id)
    val linee = membri.flatMap { id -> orario?.lineeAllaFermata(id).orEmpty().map { it.linea.nome } }
        .distinct()
        .sortedWith(ordineLinee)
    val perVoce = buildString {
        append(fermata.nome)
        val unita = orario?.lineeStazioneUnita(fermata.id)
        if (unita != null) append(", " + unita.replace(" · ", ", ")) else if (rete != Rete.ROMA) append(", " + rete.nome)
        if (distanzaMetri != null) append(tx(", a {0} metri", distanzaMetri.roundToInt()))
        append(if (rete.treni) tx(". Stazione.") else tx(". Palina {0}.", fermata.codice))
    }
    val conLinee = if (linee.isEmpty()) perVoce else perVoce + " " + tx("Linee {0}.", linee.take(14).joinToString(", "))
    return VoceFermata(
        id = fermata.id,
        nome = fermata.nome,
        sotto = descrizioneFermata(fermata, orario),
        distanza = distanzaMetri?.let { testoDistanzaBreve(it) },
        linee = linee.take(14),
        colore = coloreReteArgb(fermata.id),
        perVoce = conLinee,
        perVoceSenzaLinee = perVoce,
        lat = fermata.lat,
        lon = fermata.lon,
    )
}

/** Le fermate entro [raggio] metri, dalla più vicina (le stazioni di scambio una volta sola). */
fun vociVicine(orario: Orario, lat: Double, lon: Double, raggio: Double): List<VoceFermata> =
    orario.fermateVicineUnite(lat, lon, raggio).map { (f, d) -> voceFermata(f, d, orario) }

/** Le fermate che corrispondono al testo cercato, come nella ricerca dell'app Android. */
fun vociCercate(orario: Orario, testo: String): List<VoceFermata> =
    orario.cercaFermate(testo).map { voceFermata(it, null, orario) }

/** Una linea che passa da una fermata: "8 → Casaletto". */
data class VoceLinea(
    val lineaId: String,
    val nome: String,
    val capolinea: String,
    val direzione: Int,
    val colore: Long,
)

/** Le linee di una fermata (di tutte le fermate di una stazione di scambio), in ordine. */
fun lineeDellaFermata(orario: Orario, fermataId: String): List<VoceLinea> =
    orario.stazioneUnita(fermataId)
        .flatMap { orario.lineeAllaFermata(it) }
        .distinctBy { it.linea.id to it.direzione }
        .sortedWith(compareBy(ordineLinee) { it.linea.nome })
        .map { VoceLinea(it.linea.id, it.linea.nome, it.capolinea, it.direzione, coloreReteArgb(it.linea.id)) }

/** La fermata in cima alla sua schermata (le stazioni di scambio con il nome unito), o null se non c'è. */
fun intestazioneFermata(orario: Orario, fermataId: String): VoceFermata? {
    val fermata = orario.fermataUnita(fermataId) ?: orario.fermata(fermataId) ?: return null
    return voceFermata(fermata.copy(id = fermataId), null, orario)
}

/** Un prossimo passaggio in una riga di fermata: "64 · 3′", in oro se in tempo reale. */
data class PillolaPassaggio(val testo: String, val tempoReale: Boolean)

/** La pillola del primo passaggio di [arrivo] ([adesso] in secondi). */
fun pillolaPassaggio(arrivo: Arrivo, adesso: Long): PillolaPassaggio {
    val primo = arrivo.passaggi.first()
    val minuti = minutiAttesa(primo.orario, adesso)
    val tempo = if (minuti < 1) tx("in arrivo") else "$minuti′"
    val testo = when {
        primo.avviso == "soppresso" -> arrivo.nomeLinea + " · " + tx("soppresso")
        primo.tracciato -> "${arrivo.nomeLinea} · $tempo"
        else -> arrivo.nomeLinea + " · " + tx("{0} orario", tempo)
    }
    return PillolaPassaggio(testo, primo.tracciato)
}

/** Una fermata in Home con i suoi prossimi passaggi (al massimo 4) e quante altre linee ci passano. */
data class FermataConTempi(val voce: VoceFermata, val pillole: List<PillolaPassaggio>, val altre: Int, val perVoce: String)

fun fermateConTempi(voci: List<VoceFermata>, arrivi: ArriviFermate, adesso: Long): List<FermataConTempi> =
    voci.map { v ->
        val presi = ancoraAttesi(arrivi.perFermata[v.id].orEmpty(), adesso).take(4)
        val perVoce = if (presi.isEmpty()) v.perVoce else v.perVoceSenzaLinee + " " +
            presi.joinToString(" ") { a ->
                val m = minutiAttesa(a.passaggi.first().orario, adesso)
                if (m < 1) tx("{0} in arrivo.", a.nomeLinea) else tx("{0} tra {1} minuti.", a.nomeLinea, m)
            }
        FermataConTempi(
            voce = v,
            pillole = presi.map { pillolaPassaggio(it, adesso) },
            altre = (v.linee.size - presi.map { it.nomeLinea }.distinct().size).coerceAtLeast(0),
            perVoce = perVoce,
        )
    }

/** Le fermate per cui chiedere anche Transitous o ASTRAL: le prime 4 preferite e 3 vicine delle altre reti. */
fun fermateComplete(preferite: List<Fermata>, vicine: List<Fermata>): List<Fermata> =
    (preferite.take(4) + vicine.filter { Rete.di(it.id) != Rete.ROMA }.take(3)).distinctBy { it.id }

/** L'avviso sopra i tempi, se la rete non risponde o Roma Mobilita' e' ferma; null se va tutto bene. */
fun avvisoArrivi(arrivi: ArriviFermate, conRoma: Boolean): String? = when {
    arrivi.errore && arrivi.aggiornatoAlle > 0 ->
        tx("Non riesco ad aggiornare: i tempi sono quelli delle {0}.", oraARoma(arrivi.aggiornatoAlle))
    arrivi.errore -> tx("Non riesco a leggere i tempi d'attesa. Controlla la connessione.")
    arrivi.romaFerma && conRoma ->
        tx("Roma Mobilità ora non pubblica il tempo reale: i tempi di Roma sono calcolati sull'orario.")
    else -> null
}

/** Una riga del tabellone di una fermata: linea, direzione, minuti e da dove vengono. */
data class RigaArrivo(
    val lineaId: String,
    /** La direzione nell'orario, -1 se non si sa. */
    val direzione: Int,
    /** La corsa del primo passaggio (per evidenziare la vettura sulla linea). */
    val corsaId: String,
    /** Fissata in cima alla fermata e in Home. */
    val fissata: Boolean,
    /** Per i treni: come chiederne tutte le fermate (TrenoOrologio in JSON), altrimenti null. */
    val treno: String?,
    val nome: String,
    val colore: Long,
    /** "Casaletto", o "Direzione non indicata". */
    val capolinea: String,
    val capolineaNoto: Boolean,
    /** "poi 12 · 25 min", o vuoto. */
    val poi: String,
    /** "3", "in arrivo", "soppresso". */
    val attesa: String,
    /** Vero se [attesa] sono minuti (si scrive grande). */
    val minuti: Boolean,
    /** "GPS", "tempo reale", "da orario", un avviso ("bus sostitutivo") o l'ora del treno soppresso. */
    val sotto: String,
    val tempoReale: Boolean,
    /** Soppresso o con un avviso: si scrive nel colore d'accento. */
    val evidenzia: Boolean,
    val perVoce: String,
    /** "ultima corsa", "penultima corsa" o vuoto. */
    val ultima: String = "",
)

fun rigaArrivo(arrivo: Arrivo, adesso: Long, fissata: Boolean = false, stazione: String? = null, ultima: String = ""): RigaArrivo {
    val primo = arrivo.passaggi.first()
    val rete = Rete.di(arrivo.lineaId)
    val minuti = minutiAttesa(primo.orario, adesso)
    val soppresso = primo.avviso == "soppresso"
    return RigaArrivo(
        lineaId = arrivo.lineaId,
        direzione = arrivo.direzione ?: -1,
        corsaId = primo.corsaId,
        fissata = fissata,
        treno = if (stazione != null && Rete.di(arrivo.lineaId).treni) {
            TrenoOrologio(
                corsaId = primo.idTransitous,
                fermataId = stazione,
                lineaId = arrivo.lineaId,
                direzione = arrivo.direzione,
                previsto = primo.orario - (primo.ritardo ?: 0),
                corsaAstral = primo.corsaId.takeIf { it.startsWith("astral:") },
                ritardo = primo.ritardo,
            ).testoJson()
        } else {
            null
        },
        nome = arrivo.nomeLinea,
        colore = coloreReteArgb(arrivo.lineaId),
        capolinea = arrivo.capolinea.ifEmpty { tx("Direzione non indicata") },
        capolineaNoto = arrivo.capolinea.isNotEmpty(),
        poi = testoPoi(arrivo.passaggi.drop(1).take(2).map { minutiAttesa(it.orario, adesso) }),
        attesa = when {
            soppresso -> tx("soppresso")
            minuti < 1 -> tx("in arrivo")
            else -> minuti.toString()
        },
        minuti = !soppresso && minuti >= 1,
        sotto = when {
            soppresso -> oraARoma(primo.orario * 1000)
            primo.avviso != null -> testoAvviso(primo.avviso)
            !primo.tracciato -> tx("da orario")
            rete == Rete.ROMA || primo.gps -> "GPS"
            else -> tx("tempo reale")
        },
        tempoReale = primo.tracciato,
        evidenzia = soppresso || primo.avviso != null,
        perVoce = fraseArrivo(arrivo, adesso) + (if (ultima.isNotEmpty()) " ($ultima)." else ""),
        ultima = ultima,
    )
}

/**
 * Il tabellone di una fermata: in cima le linee fissate (anche quelle che ora
 * non passano), sotto le altre; l'avviso e l'ora dell'aggiornamento.
 */
data class TabelloneFermata(
    val fissate: List<RigaArrivo>,
    /** I nomi delle linee fissate che nei prossimi 90 minuti non passano. */
    val fissateSenzaPassaggi: List<String>,
    val altre: List<RigaArrivo>,
    val conFissate: Boolean,
    /** "Tocca la puntina…" quando non c'e' nessuna linea fissata. */
    val suggerimento: String?,
    val avviso: String?,
    val aggiornato: String?,
)

fun tabelloneFermata(
    fermataId: String,
    arrivi: ArriviFermate,
    adessoMs: Long,
    fissate: Set<String>,
    nomiLinee: Map<String, String>,
    stazioneDi: (Arrivo) -> String? = { null },
    /** Dalla sera, "ultima corsa" / "penultima corsa" per riga ([ultimeDelleRighe]). */
    ultime: Map<String, String> = emptyMap(),
): TabelloneFermata {
    val adesso = adessoMs / 1000
    val diviso = dividiArrivi(ancoraAttesi(arrivi.perFermata[fermataId].orEmpty(), adesso), fissate)
    return TabelloneFermata(
        fissate = diviso.fissate.map { rigaArrivo(it, adesso, fissata = true, stazione = stazioneDi(it), ultima = ultime[chiaveArrivo(it)].orEmpty()) },
        fissateSenzaPassaggi = diviso.senzaPassaggi.map { nomiLinee[it] ?: it },
        altre = diviso.altre.map { rigaArrivo(it, adesso, stazione = stazioneDi(it), ultima = ultime[chiaveArrivo(it)].orEmpty()) },
        conFissate = fissate.isNotEmpty(),
        suggerimento = if (fissate.isEmpty() && diviso.altre.isNotEmpty()) tx("Tocca la puntina accanto a una linea: resta in cima qui e in Home.") else null,
        avviso = avvisoArrivi(arrivi, Rete.di(fermataId) == Rete.ROMA),
        aggiornato = if (arrivi.aggiornatoAlle > 0) tx("Aggiornato {0}", testoAggiornato(arrivi.aggiornatoAlle, adessoMs)) else null,
    )
}

/** Una preferita in Home pronta da disegnare: righe grandi, altre linee in breve. */
data class SchedaPreferitaVista(
    val id: String,
    val nome: String,
    val sotto: String,
    val messaggio: String?,
    val righe: List<RigaArrivo>,
    val pillole: List<PillolaPassaggio>,
    /** Quante altre linee oltre le 4 pillole. */
    val inPiu: Int,
)

fun SchedaPreferita.vista(adesso: Long): SchedaPreferitaVista = SchedaPreferitaVista(
    id = fermata.id,
    nome = fermata.nome,
    sotto = sotto,
    messaggio = messaggio,
    righe = principali.map { rigaArrivo(it, adesso, fissata = false) },
    pillole = altre.take(4).map { pillolaPassaggio(it, adesso) },
    inPiu = (altre.size - 4).coerceAtLeast(0),
)

/** La Home: la preferita a cui sei vicino, le altre preferite, le fermate vicine e gli avvisi. */
data class StatoHome(
    val evidenza: SchedaPreferitaVista?,
    /** "Preferiti", "Altri preferiti" o null se non ce ne sono (oltre a quella in evidenza). */
    val titoloPreferite: String?,
    val preferite: List<SchedaPreferitaVista>,
    val vicine: List<FermataConTempi>,
    val avviso: String?,
    /** "aggiornato 20 s fa". */
    val aggiornato: String?,
    /** Il suggerimento quando non ci sono preferite. */
    val suggerimento: String?,
)

fun statoHome(
    orario: Orario?,
    ordine: PreferiteOrdinate,
    fissate: Set<String>,
    vicine: List<VoceFermata>,
    arrivi: ArriviFermate,
    adessoMs: Long,
): StatoHome {
    val adesso = adessoMs / 1000
    val evidenza = ordine.evidenza
    val resto = ordine.ordinate.filter { it.first.id != evidenza?.first?.id }
    fun scheda(f: Fermata, d: Double?) =
        schedaPreferita(orario, f, d, arrivi.perFermata[f.id], lineeFissate(f.id, fissate), adesso).vista(adesso)
    val conRoma = ordine.ordinate.any { Rete.di(it.first.id) == Rete.ROMA } || vicine.any { Rete.di(it.id) == Rete.ROMA }
    return StatoHome(
        evidenza = evidenza?.let { scheda(it.first, it.second) },
        titoloPreferite = when {
            resto.isEmpty() -> null
            evidenza == null -> tx("Preferiti")
            else -> tx("Altri preferiti")
        },
        preferite = resto.map { scheda(it.first, it.second) },
        vicine = fermateConTempi(vicine, arrivi, adesso),
        avviso = avvisoArrivi(arrivi, conRoma),
        aggiornato = if (arrivi.aggiornatoAlle > 0) tx("aggiornato {0}", testoAggiornato(arrivi.aggiornatoAlle, adessoMs)) else null,
        suggerimento = if (ordine.ordinate.isEmpty()) tx("Tocca il cuore in una fermata: la ritrovi qui in cima, con i suoi tempi.") else null,
    )
}

/** Gli avvisi delle corse ("soppresso", "bus sostitutivo") nella lingua dell'app. */
fun testoAvviso(avviso: String): String = when (avviso) {
    "soppresso" -> tx("soppresso")
    "bus sostitutivo" -> tx("bus sostitutivo")
    else -> avviso
}

/** La spiegazione sotto il tabellone di una fermata: da dove vengono i tempi e cosa si puo' toccare. */
fun legendaFermata(rete: Rete, conAstral: Boolean, conMetro: Boolean): String = when (rete) {
    Rete.ROMA ->
        tx("Cifre oro con il pallino: vettura vista ora dal GPS. Grigie: tempo calcolato sull'orario. Tocca una riga per vedere la vettura sulla mappa, trascina giù per aggiornare.")
    Rete.COTRAL ->
        tx("Cifre oro con il pallino: in tempo reale (Cotral, tramite Transitous); «GPS»: il bus è visto ora dal GPS (BusOne). Grigie: orario programmato. Tocca una riga per vedere il bus o il percorso.")
    Rete.COTRAL_TRENI -> (
        if (conAstral) {
            tx("Orari, ritardi e soppressioni dei treni: ASTRAL. Cifre oro: ritardo già comunicato; grigie: orario programmato. Tocca un treno per vedere tutte le sue fermate.")
        } else {
            tx("Ritardi dei treni non disponibili ora (ASTRAL non risponde, o di notte non ha ancora l'orario del giorno): vedi l'orario programmato. Tocca un treno per vedere tutte le sue fermate.")
        }
        ) + if (conMetro) " " + tx("Qui ci sono anche le banchine della metro: i loro tempi sono da orario.") else ""
    Rete.TRENITALIA ->
        tx("Cifre oro con il pallino: in tempo reale (Trenitalia, tramite Transitous). Grigie: orario programmato. Tocca un treno per vedere tutte le sue fermate.")
}
