package it.palinaroma.dati

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
    orario?.lineeStazioneUnita(fermata.id)?.let { return "Stazione · $it" }
    val rete = Rete.di(fermata.id)
    return when {
        rete == Rete.ROMA -> "Palina ${fermata.codice}"
        rete.treni -> "Stazione · ${rete.etichetta}"
        else -> "${rete.etichetta} · palina ${fermata.codice}"
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
        if (unita != null) append(", " + unita.replace(" · ", ", ")) else if (rete != Rete.ROMA) append(", ${rete.etichetta}")
        if (distanzaMetri != null) append(", a ${distanzaMetri.roundToInt()} metri")
        append(if (rete.treni) ". Stazione." else ". Palina ${fermata.codice}.")
        if (linee.isNotEmpty()) append(" Linee " + linee.take(14).joinToString(", ") + ".")
    }
    return VoceFermata(
        id = fermata.id,
        nome = fermata.nome,
        sotto = descrizioneFermata(fermata, orario),
        distanza = distanzaMetri?.let { testoDistanzaBreve(it) },
        linee = linee.take(14),
        colore = coloreReteArgb(fermata.id),
        perVoce = perVoce,
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
    val tempo = if (minuti < 1) "in arrivo" else "$minuti′"
    val testo = when {
        primo.avviso == "soppresso" -> "${arrivo.nomeLinea} · soppresso"
        primo.tracciato -> "${arrivo.nomeLinea} · $tempo"
        else -> "${arrivo.nomeLinea} · $tempo orario"
    }
    return PillolaPassaggio(testo, primo.tracciato)
}

/** Una fermata in Home con i suoi prossimi passaggi (al massimo 4) e quante altre linee ci passano. */
data class FermataConTempi(val voce: VoceFermata, val pillole: List<PillolaPassaggio>, val altre: Int, val perVoce: String)

fun fermateConTempi(voci: List<VoceFermata>, arrivi: ArriviFermate, adesso: Long): List<FermataConTempi> =
    voci.map { v ->
        val presi = ancoraAttesi(arrivi.perFermata[v.id].orEmpty(), adesso).take(4)
        val perVoce = if (presi.isEmpty()) v.perVoce else v.perVoce.substringBefore(" Linee ") + " " +
            presi.joinToString(" ") { a ->
                val m = minutiAttesa(a.passaggi.first().orario, adesso)
                "${a.nomeLinea} " + (if (m < 1) "in arrivo" else "tra $m minuti") + "."
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
        "Non riesco ad aggiornare: i tempi sono quelli delle ${oraARoma(arrivi.aggiornatoAlle)}."
    arrivi.errore -> "Non riesco a leggere i tempi d'attesa. Controlla la connessione."
    arrivi.romaFerma && conRoma ->
        "Roma Mobilità ora non pubblica il tempo reale: i tempi di Roma sono calcolati sull'orario."
    else -> null
}

/** Una riga del tabellone di una fermata: linea, direzione, minuti e da dove vengono. */
data class RigaArrivo(
    val lineaId: String,
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
)

fun rigaArrivo(arrivo: Arrivo, adesso: Long): RigaArrivo {
    val primo = arrivo.passaggi.first()
    val rete = Rete.di(arrivo.lineaId)
    val minuti = minutiAttesa(primo.orario, adesso)
    val soppresso = primo.avviso == "soppresso"
    return RigaArrivo(
        lineaId = arrivo.lineaId,
        nome = arrivo.nomeLinea,
        colore = coloreReteArgb(arrivo.lineaId),
        capolinea = arrivo.capolinea.ifEmpty { "Direzione non indicata" },
        capolineaNoto = arrivo.capolinea.isNotEmpty(),
        poi = testoPoi(arrivo.passaggi.drop(1).take(2).map { minutiAttesa(it.orario, adesso) }),
        attesa = when {
            soppresso -> "soppresso"
            minuti < 1 -> "in arrivo"
            else -> minuti.toString()
        },
        minuti = !soppresso && minuti >= 1,
        sotto = when {
            soppresso -> oraARoma(primo.orario * 1000)
            primo.avviso != null -> primo.avviso
            !primo.tracciato -> "da orario"
            rete == Rete.ROMA || primo.gps -> "GPS"
            else -> "tempo reale"
        },
        tempoReale = primo.tracciato,
        evidenzia = soppresso || primo.avviso != null,
        perVoce = fraseArrivo(arrivo, adesso),
    )
}

/** Il tabellone di una fermata: le righe in ordine e l'avviso, se c'e'. */
data class TabelloneFermata(val righe: List<RigaArrivo>, val avviso: String?, val aggiornato: String?)

fun tabelloneFermata(fermataId: String, arrivi: ArriviFermate, adessoMs: Long): TabelloneFermata {
    val adesso = adessoMs / 1000
    val righe = ancoraAttesi(arrivi.perFermata[fermataId].orEmpty(), adesso).map { rigaArrivo(it, adesso) }
    return TabelloneFermata(
        righe = righe,
        avviso = avvisoArrivi(arrivi, Rete.di(fermataId) == Rete.ROMA),
        aggiornato = if (arrivi.aggiornatoAlle > 0) "Aggiornato " + testoAggiornato(arrivi.aggiornatoAlle, adessoMs) else null,
    )
}
