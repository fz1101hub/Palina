package it.palinaroma.dati

/*
 * Le fermate preferite in Home, uguali su Android e iPhone: quali sono, in
 * che ordine e cosa mostra la loro scheda.
 */

/**
 * Le preferite salvate (id) come fermate, in ordine di nome. Una stazione di
 * scambio salvata da una sua banchina ha il nome della stazione unita.
 */
fun fermatePreferite(orario: Orario, preferiti: Set<String>): List<Fermata> {
    val trovate = orario.fermate(preferiti)
    return preferiti.mapNotNull { id -> trovate[id]?.let { f -> orario.fermataUnita(id)?.copy(id = id) ?: f } }.sortedBy { it.nome }
}

/** Le preferite in Home: quella a cui sei vicino (entro 400 m) e tutte le altre dalla piu' vicina. */
data class PreferiteOrdinate(
    val evidenza: Pair<Fermata, Double>?,
    /** Dalla piu' vicina; senza posizione in ordine di nome (distanza null). */
    val ordinate: List<Pair<Fermata, Double?>>,
)

fun ordinaPreferite(preferite: List<Fermata>, lat: Double?, lon: Double?): PreferiteOrdinate {
    val ordinate = if (lat == null || lon == null) preferite.map { it to null }
    else preferite.map { it to distanzaMetri(lat, lon, it.lat, it.lon) }.sortedBy { it.second }
    return PreferiteOrdinate(preferitaVicina(preferite, lat, lon), ordinate)
}

/** Una preferita come piccolo tabellone: le linee fissate (o le prime tre) grandi, le altre in breve. */
data class SchedaPreferita(
    val fermata: Fermata,
    val distanza: Double?,
    /** "palina 70078 · 120 m", "Stazione · Metro B/B1 · Metromare · 1,2 km". */
    val sotto: String,
    /** Al posto delle righe: "Carico i tempi…", "Nessun passaggio…", o per le fissate che non passano. */
    val messaggio: String?,
    val principali: List<Arrivo>,
    /** Le altre linee: se ne mostrano 4, poi "+N". */
    val altre: List<Arrivo>,
)

fun schedaPreferita(
    orario: Orario?,
    fermata: Fermata,
    distanza: Double?,
    arrivi: List<Arrivo>?,
    fissate: Set<String>,
    adesso: Long,
): SchedaPreferita {
    val sotto = (
        if (Rete.di(fermata.id) == Rete.ROMA && orario?.lineeStazioneUnita(fermata.id) == null) tx("palina {0}", fermata.codice)
        else descrizioneFermata(fermata, orario)
        ) + (distanza?.let { " · " + testoMetri(it) } ?: "")
    val elenco = arrivi?.let { ancoraAttesi(it, adesso) }
    if (elenco == null) return SchedaPreferita(fermata, distanza, sotto, tx("Carico i tempi…"), emptyList(), emptyList())
    if (elenco.isEmpty()) {
        return SchedaPreferita(fermata, distanza, sotto, tx("Nessun passaggio nei prossimi 90 minuti."), emptyList(), emptyList())
    }
    val diviso = dividiArrivi(elenco, fissate)
    val principali = if (fissate.isNotEmpty()) diviso.fissate else elenco.take(3)
    val resto = if (fissate.isNotEmpty()) diviso.altre else elenco.drop(3)
    return SchedaPreferita(
        fermata, distanza, sotto,
        messaggio = if (principali.isEmpty()) tx("Le linee che hai fissato non passano nei prossimi 90 minuti.") else null,
        principali = principali,
        altre = resto,
    )
}
