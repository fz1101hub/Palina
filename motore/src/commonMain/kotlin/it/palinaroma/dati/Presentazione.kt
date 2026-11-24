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
