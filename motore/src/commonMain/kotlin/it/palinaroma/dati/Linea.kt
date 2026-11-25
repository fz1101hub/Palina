package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/*
 * La schermata di una linea: le vetture con il GPS, quanto sono lontane dal
 * percorso e i testi che le accompagnano. Uguale su Android e iPhone.
 */

/** Una vettura a piu' di cosi' dal tracciato si disegna sbiadita: forse e' in deviazione o in rimessa. */
const val METRI_FUORI_PERCORSO = 250.0

/** Una posizione GPS piu' vecchia di cosi' viene disegnata sbiadita. */
const val POSIZIONE_VECCHIA_SECONDI = 180L

/** Quale vettura e': la corsa, o il numero di vettura se la corsa non c'e'. */
fun chiaveMezzo(mezzo: MezzoRT): String = mezzo.corsaId.ifEmpty { "vettura:${mezzo.etichetta}" }

/** Le vetture con il GPS: Roma da Roma Mobilita', i bus Cotral da BusOne; per i treni solo il percorso. */
fun lineaConVetture(lineaId: String): Boolean = Rete.di(lineaId).let { it == Rete.ROMA || it == Rete.COTRAL }

/** Cotral non ha le corse nell'orario dell'app: la direzione dei bus si ricava dalle fermate di ogni percorso. */
fun fermatePerDirezione(orario: Orario, lineaId: String, percorsi: List<Percorso>): Map<Int, Set<String>> =
    if (Rete.di(lineaId) != Rete.COTRAL) {
        emptyMap()
    } else {
        percorsi.associate { p -> p.direzione to orario.fermateDelPercorso(lineaId, p.direzione).map { it.id }.toSet() }
    }

/** Le vetture lette per una linea: quelle nella direzione scelta, le altre, quella attesa e quando arriva. */
data class VettureLette(
    val inDirezione: List<MezzoRT> = emptyList(),
    val altre: List<MezzoRT> = emptyList(),
    val evidenziato: MezzoRT? = null,
    /** Quando la corsa attesa arriva alla fermata (secondi), dal tempo reale. */
    val arrivoAtteso: Long? = null,
    val romaFerma: Boolean = false,
    val aggiornatoAlle: Long = 0L,
)

/**
 * Legge le vetture di una linea (ogni 15 secondi). Lancia un'eccezione se non
 * riesce a leggere le posizioni: restano quelle di prima.
 */
suspend fun leggiVettureLinea(
    orario: Orario?,
    lineaId: String,
    nomeLinea: String?,
    direzione: Int?,
    perDirezione: Map<Int, Set<String>>,
    corsaEvidenziata: String?,
    fermataEvidenziata: String?,
    precedente: VettureLette,
): VettureLette {
    val rete = Rete.di(lineaId)
    val corseLette = try {
        if (rete == Rete.COTRAL) BusOne.corse() else TempoReale.corse()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val corse = corseLette.orEmpty()
    val romaFerma = if (rete == Rete.ROMA) TempoReale.fermo(corseLette) else precedente.romaFerma
    // le vetture senza corsa o linea si completano con le corse in tempo reale
    val grezzi = if (rete == Rete.COTRAL) BusOne.mezzi() else TempoReale.mezzi()
    val (tutti, vetture) = withContext(dispatcherDati) {
        val completi = completaMezzi(grezzi, corse).let {
            if (rete == Rete.COTRAL) conDirezione(it, corse, perDirezione) else it
        }
        completi to vettureDellaLinea(completi, lineaId, nomeLinea, direzione, orario)
    }
    var evidenziato = precedente.evidenziato
    var arrivo = precedente.arrivoAtteso
    if (corsaEvidenziata != null) {
        evidenziato = mezzoDellaCorsa(tutti, corsaEvidenziata)
        if (fermataEvidenziata != null) {
            val corsa = corse.firstOrNull { it.corsaId == corsaEvidenziata }
            arrivo = corsa?.fermate?.firstOrNull { stessoCodice(it.fermataId, fermataEvidenziata) }?.orario
        }
    }
    return VettureLette(vetture.inDirezione, vetture.altre, evidenziato, arrivo, romaFerma, adessoMs())
}

/**
 * Quanto dista ogni vettura dal tracciato piu' vicino tra le due direzioni.
 * Non per Cotral: i bus non hanno il tracciato stradale e la linea unisce le
 * fermate in linea retta, da cui un bus extraurbano si allontana di continuo.
 */
fun lontananzeVetture(lineaId: String, vetture: List<MezzoRT>, forma: List<Punto>, formaAltra: List<Punto>): Map<String, Double> {
    if (Rete.di(lineaId) == Rete.COTRAL) return emptyMap()
    return vetture.associate { v ->
        val qui = if (forma.size >= 2) distanzaDalTracciato(v.lat, v.lon, forma) else Double.POSITIVE_INFINITY
        val la = if (formaAltra.size >= 2) distanzaDalTracciato(v.lat, v.lon, formaAltra) else Double.POSITIVE_INFINITY
        chiaveMezzo(v) to minOf(qui, la)
    }.filterValues { it.isFinite() }
}

/** Sotto la mappa: "3 vetture GPS in questa direzione · 1 nell'altra (in grigio) · 07:42". */
fun testoVetture(
    lineaId: String,
    inDirezione: Int,
    altre: Int,
    caricato: Boolean,
    romaFerma: Boolean,
    aggiornatoAlle: Long,
): String {
    val rete = Rete.di(lineaId)
    val principale = if (!lineaConVetture(lineaId)) {
        tx("{0}: la posizione dei mezzi non è disponibile, solo il percorso", rete.nome)
    } else if (romaFerma && inDirezione == 0 && altre == 0) {
        tx("Roma Mobilità ora non pubblica le posizioni delle vetture")
    } else if (rete == Rete.COTRAL) {
        when (inDirezione) {
            0 -> if (caricato) tx("Nessun bus GPS in questa direzione") else tx("Cerco i bus…")
            else -> txn("{0} bus GPS in questa direzione", "{0} bus GPS in questa direzione§molti", inDirezione)
        } + if (caricato) " (BusOne)" else ""
    } else {
        when (inDirezione) {
            0 -> if (caricato) tx("Nessuna vettura GPS in questa direzione") else tx("Cerco le vetture…")
            else -> txn("{0} vettura GPS in questa direzione", "{0} vetture GPS in questa direzione", inDirezione)
        }
    }
    return principale + when (altre) {
        0 -> ""
        else -> " · " + txn("{0} nell'altra (in grigio)", "{0} nell'altra (in grigio)§molti", altre)
    } + if (aggiornatoAlle > 0) " · ${oraARoma(aggiornatoAlle)}" else ""
}

/** "Sei a 300 m da Piazza Venezia, la sua fermata più vicina ›" (o solo la distanza, se e' lontana). */
fun testoFermataVicinaLinea(nomeFermata: String, metri: Double): String =
    if (metri > 3_000) tx("Sei a {0} da questa linea", testoMetri(metri))
    else tx("Sei a {0} da {1}, la sua fermata più vicina ›", testoMetri(metri), nomeFermata)

/** "in arrivo" oppure "7 min" ([orario] e [adesso] in secondi). */
fun minutiOInArrivo(orario: Long, adesso: Long): String {
    val minuti = ((orario - adesso) / 60).toInt()
    return if (minuti < 1) tx("in arrivo") else tx("{0} min", minuti)
}

/** I testi della scheda di una vettura toccata sulla mappa. */
data class SchedaVettura(
    /** "La tua vettura 4012", "Vettura". */
    val titolo: String,
    /** "posizione rilevata 20 s fa". */
    val rilevata: String,
    /** La posizione ha piu' di 3 minuti: il testo [rilevata] va nel colore d'accento. */
    val vecchia: Boolean,
    /** Gli avvisi nel colore d'accento: segnale vecchio, fuori percorso. */
    val avvisi: List<String>,
    /** Va nell'altra direzione: si puo' toccare per vederla. */
    val altraDirezione: String?,
    /** "Arriva a Piazza Venezia tra 4 min · a 900 m in linea d'aria". */
    val arrivo: String?,
)

fun schedaVettura(
    mezzo: MezzoRT,
    attesa: Boolean,
    altraDirezione: Boolean,
    distanzaPercorso: Double?,
    fermata: Fermata?,
    arrivo: Long?,
    adesso: Long,
): SchedaVettura {
    val eta = etaRilevamento(mezzo.rilevato, adesso)
    val vecchia = mezzo.rilevato > 0 && adesso - mezzo.rilevato > POSIZIONE_VECCHIA_SECONDI
    val avvisi = buildList {
        if (vecchia) add(tx("Il segnale è vecchio: la vettura potrebbe essere più avanti."))
        if (distanzaPercorso != null) {
            add(tx("È a {0} dal percorso della linea: forse è in deviazione, in rimessa o sta andando al capolinea.", testoMetri(distanzaPercorso)))
        }
    }
    val testoArrivo = if (attesa && fermata != null) {
        val metri = distanzaMetri(mezzo.lat, mezzo.lon, fermata.lat, fermata.lon)
        val minuti = arrivo?.let { ((it - adesso) / 60).toInt() }
        when {
            minuti == null -> tx("Arriva a {0} · a {1} in linea d'aria", fermata.nome, testoMetri(metri))
            minuti < 1 -> tx("Arriva a {0} in arrivo · a {1} in linea d'aria", fermata.nome, testoMetri(metri))
            else -> tx("Arriva a {0} tra {1} min · a {2} in linea d'aria", fermata.nome, minuti, testoMetri(metri))
        }
    } else {
        null
    }
    return SchedaVettura(
        titolo = when {
            mezzo.etichetta.isBlank() -> if (attesa) tx("La tua vettura") else tx("Vettura")
            attesa -> tx("La tua vettura {0}", mezzo.etichetta)
            else -> tx("Vettura {0}", mezzo.etichetta)
        },
        rilevata = if (eta != null) tx("posizione rilevata {0}", eta) else tx("posizione in tempo reale"),
        vecchia = vecchia,
        avvisi = avvisi,
        altraDirezione = if (altraDirezione) tx("Va nell'altra direzione · mostra quella direzione ›") else null,
        arrivo = testoArrivo,
    )
}

/** La corsa attesa non ha una vettura con il GPS: il tempo e' quello dell'orario. */
fun testoSenzaGps(fermata: Fermata?, arrivo: Long?, adesso: Long): String =
    tx(
        "Il tempo d'attesa{0} è calcolato sull'orario programmato: la vettura potrebbe non essere ancora partita.",
        (fermata?.let { " " + tx("a {0}", it.nome) } ?: "") + (arrivo?.let { " (" + minutiOInArrivo(it, adesso) + ")" } ?: ""),
    )
