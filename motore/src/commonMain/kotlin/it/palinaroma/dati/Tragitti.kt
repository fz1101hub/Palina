package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.number

/*
 * I tragitti con Transitous, uguali su Android e iPhone: la ricerca (con i
 * suoi errori), le carte dei risultati e il dettaglio di un tragitto.
 */

/** L'esito di una ricerca di tragitti: i tragitti trovati, o perche' non ce ne sono. */
data class EsitoRicercaTragitti(
    val tragitti: List<Tragitto>,
    val soloAPiedi: Tragitto?,
    val cursorePrima: String?,
    val cursoreDopo: String?,
    val calcolatoAlle: Long,
    /** Il messaggio da mostrare se la ricerca non e' riuscita (allora [tragitti] e' vuoto). */
    val errore: String?,
)

private fun conErrore(messaggio: String) = EsitoRicercaTragitti(emptyList(), null, null, null, adessoMs(), messaggio)

/**
 * Cerca i tragitti da [partenza] ad [arrivo] (con le coordinate gia' lette:
 * [partenza] null vuol dire che la posizione non c'e'). [istante] null =
 * adesso; [arrivaEntro] = l'istante e' l'arrivo.
 */
suspend fun cercaTragitti(partenza: Luogo?, arrivo: Luogo?, istante: Long?, arrivaEntro: Boolean): EsitoRicercaTragitti {
    if (partenza == null || arrivo == null || !partenza.haCoordinate || !arrivo.haCoordinate) {
        return conErrore(tx("Non riesco a leggere la tua posizione: attiva la localizzazione e riprova."))
    }
    if (distanzaMetri(partenza.lat, partenza.lon, arrivo.lat, arrivo.lon) < 40) {
        return conErrore(tx("Partenza e arrivo sono nello stesso punto."))
    }
    return try {
        val r = Transitous.tragitti(partenza, arrivo, istante, arrivaEntro, null)
        EsitoRicercaTragitti(r.tragitti.distinctBy { it.chiave }, r.soloAPiedi, r.cursorePrima, r.cursoreDopo, adessoMs(), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ErroreTransitous) {
        conErrore(e.message ?: tx("Transitous non ha risposto."))
    } catch (_: Exception) {
        conErrore(tx("Non riesco a contattare Transitous. Controlla la connessione e riprova."))
    }
}

/** Le partenze precedenti o successive, unite a quelle gia' trovate (senza rete restano quelle). */
suspend fun altriTragitti(
    attuale: EsitoRicercaTragitti,
    partenza: Luogo,
    arrivo: Luogo,
    istante: Long?,
    arrivaEntro: Boolean,
    dopo: Boolean,
): EsitoRicercaTragitti {
    val cursore = (if (dopo) attuale.cursoreDopo else attuale.cursorePrima) ?: return attuale
    return try {
        val r = Transitous.tragitti(partenza, arrivo, istante, arrivaEntro, cursore)
        val uniti = (if (dopo) attuale.tragitti + r.tragitti else r.tragitti + attuale.tragitti)
            .distinctBy { it.chiave }
            .sortedBy { it.partenza }
        attuale.copy(
            tragitti = uniti,
            cursorePrima = if (dopo) attuale.cursorePrima else r.cursorePrima,
            cursoreDopo = if (dopo) r.cursoreDopo else attuale.cursoreDopo,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        attuale
    }
}

/** Una fermata dell'app come luogo di partenza o arrivo. */
fun luogoDaFermata(f: Fermata): Luogo = Luogo(f.nome, tx("Palina {0}", f.codice), f.lat, f.lon, TipoLuogo.FERMATA)

/** I luoghi recenti (dal piu' nuovo), con [luogo] in cima: al massimo 8. La posizione non si ricorda. */
fun ricordaLuogo(recenti: List<Luogo>, luogo: Luogo): List<Luogo> {
    if (luogo.tipo == TipoLuogo.POSIZIONE || !luogo.haCoordinate) return recenti
    return (listOf(luogo) + recenti.filter { it.nome != luogo.nome || it.dettaglio != luogo.dettaglio }).take(8)
}

/** "parti alle 19:12", "domani 7:30", "3/12 8:00": il giorno solo se non e' oggi. */
fun giornoEOra(istante: Long, adesso: Long = adessoMs()): String {
    val giorno = giornoARoma(istante)
    val oggi = giornoARoma(adesso)
    val domani = giornoARoma(adesso + 24 * 3600 * 1000L)
    val prefisso = when (giorno) {
        oggi -> ""
        domani -> return tx("domani {0}", oraARoma(istante))
        else -> "${giorno.day}/${giorno.month.number} "
    }
    return prefisso + oraARoma(istante)
}

// --- le carte dei risultati

/** Un pezzo della sequenza [a piedi 5] › [714] › [Metro B]. */
data class PezzoTragitto(
    val aPiedi: Boolean,
    /** I minuti a piedi ("5") o il nome della linea. */
    val testo: String,
    val colore: Long,
)

/** Una carta nell'elenco dei tragitti. */
data class CartaTragitto(
    /** "19:12 – 19:45". */
    val orari: String,
    /** "33 min". */
    val durata: String,
    val pezzi: List<PezzoTragitto>,
    /** "Bus 714 tra 4 min da Piazza Venezia", o null se e' tutto a piedi. */
    val primoMezzo: String?,
    /** "1 cambio · 6 min a piedi". */
    val cambiEPiedi: String,
    val soppressa: Boolean,
    val tempoReale: Boolean,
    val perVoce: String,
)

/** Il colore di un tratto: quello della linea, se Transitous lo da', o porpora. */
fun coloreTratto(t: Tratto): Long = t.colore?.let { it.toLong() and 0xFFFFFFFFL } ?: Colori.PORPORA

fun cartaTragitto(t: Tragitto, adessoMs: Long): CartaTragitto {
    val primo = t.mezzi.firstOrNull()
    val testoPrimo = primo?.let {
        val tra = ((it.partenza - adessoMs) / 60_000).toInt()
        when {
            tra in 0..59 && tra < 1 -> tx("{0} {1} in partenza da {2}", it.nomeMezzo, it.linea, it.da.nome)
            tra in 0..59 -> tx("{0} {1} tra {2} min da {3}", it.nomeMezzo, it.linea, tra, it.da.nome)
            else -> tx("{0} {1} alle {2} da {3}", it.nomeMezzo, it.linea, oraARoma(it.partenza), it.da.nome)
        }
    }
    val cambi = when (t.cambi) {
        0 -> tx("Nessun cambio")
        else -> txn("{0} cambio", "{0} cambi", t.cambi)
    }
    val orari = "${oraARoma(t.partenza)} – ${oraARoma(t.arrivo)}"
    return CartaTragitto(
        orari = orari,
        durata = testoDurata(t.durata),
        pezzi = t.tratti.map {
            if (it.aPiedi) PezzoTragitto(true, "${(it.durata + 59) / 60}", Colori.PORPORA)
            else PezzoTragitto(false, it.linea.ifEmpty { it.nomeMezzo }, coloreTratto(it))
        },
        primoMezzo = testoPrimo,
        cambiEPiedi = cambi + " · " + tx("{0} min a piedi", t.minutiAPiedi),
        soppressa = t.mezzi.any { it.cancellato },
        tempoReale = t.mezzi.any { it.tempoReale },
        perVoce = tx("Dalle {0} alle {1}, {2}.", oraARoma(t.partenza), oraARoma(t.arrivo), testoDurata(t.durata)) + " " +
            (testoPrimo?.let { "$it. " } ?: "") + tx("{0}, {1} minuti a piedi.", cambi, t.minutiAPiedi) +
            (if (t.mezzi.any { it.cancellato }) " " + tx("Corsa soppressa.") else ""),
    )
}

/** La carta "Tutto a piedi: 25 min". */
fun cartaAPiedi(t: Tragitto): CartaTragitto = CartaTragitto(
    orari = tx("Tutto a piedi: {0}", testoDurata(t.durata)),
    durata = "",
    pezzi = emptyList(),
    primoMezzo = null,
    cambiEPiedi = if (t.metriAPiedi > 0) testoMetri(t.metriAPiedi) else "",
    soppressa = false,
    tempoReale = false,
    perVoce = tx("Tutto a piedi, {0}", testoDurata(t.durata)) + (if (t.metriAPiedi > 0) ", ${testoMetri(t.metriAPiedi)}" else "") + ".",
)

// --- il dettaglio di un tragitto

/** "Sali a Piazza Venezia" con palina, binario, ora e ritardo. */
data class RigaTappa(
    val testo: String,
    /** "Palina 70078 · Binario 2", o vuoto. */
    val dettagli: String,
    val ora: String,
    /** "+3 min", "in orario", "da orario". */
    val stato: String,
    /** Ritardo o anticipo in tempo reale: ora e stato nel colore d'accento. */
    val evidenzia: Boolean,
    val tempoReale: Boolean,
)

fun rigaTappa(azione: String, tappa: Tappa, istante: Long, previsto: Long, tempoReale: Boolean): RigaTappa {
    val ritardo = ((istante - previsto) / 60_000).toInt()
    return RigaTappa(
        testo = tx(azione, tappa.nome),
        dettagli = listOfNotNull(tappa.codice?.let { tx("Palina {0}", it) }, tappa.binario?.let { tx("Binario {0}", it) }).joinToString(" · "),
        ora = oraARoma(istante),
        stato = when {
            !tempoReale -> tx("da orario")
            ritardo > 0 -> "+$ritardo min"
            ritardo < 0 -> "$ritardo min"
            else -> tx("in orario")
        },
        evidenzia = tempoReale && ritardo != 0,
        tempoReale = tempoReale,
    )
}

/** Una fermata intermedia: nome e ora. */
data class TappaBreve(val nome: String, val ora: String)

/** Un pezzo del tragitto: a piedi o con un mezzo. */
data class BloccoTragitto(
    val aPiedi: Boolean,
    /** "A piedi · 5 min · 350 m" o "Bus per Termini". */
    val titolo: String,
    val linea: String,
    val colore: Long,
    val gestore: String,
    val soppresso: Boolean,
    val sali: RigaTappa?,
    val scendi: RigaTappa?,
    /** "5 fermate · 12 min". */
    val fermate: String,
    val intermedie: List<TappaBreve>,
    /** Le indicazioni a piedi ("Gira a destra in Via del Corso · 120 m"). */
    val passi: List<String>,
    val avvisi: List<String>,
    /** La corsa, per trovarne la vettura con il GPS. */
    val corsaId: String,
)

/** Il dettaglio di un tragitto: intestazione, estremi e blocchi. */
data class DettaglioTragitto(
    /** "19:12 – 19:45". */
    val titolo: String,
    /** "33 min · 1 cambio". */
    val sottotitolo: String,
    /** "Parti da Casa". */
    val partenza: String,
    val oraPartenza: String,
    /** "Arrivi a Colosseo". */
    val arrivo: String,
    val oraArrivo: String,
    val blocchi: List<BloccoTragitto>,
)

fun dettaglioTragitto(t: Tragitto): DettaglioTragitto = DettaglioTragitto(
    titolo = "${oraARoma(t.partenza)} – ${oraARoma(t.arrivo)}",
    sottotitolo = testoDurata(t.durata) + when (t.cambi) {
        0 -> " " + if (t.mezzi.isEmpty()) tx("a piedi") else "· " + tx("nessun cambio")
        else -> " · " + txn("{0} cambio", "{0} cambi", t.cambi)
    },
    partenza = tx("Parti da {0}", t.tratti.first().da.nome),
    oraPartenza = oraARoma(t.partenza),
    arrivo = tx("Arrivi a {0}", t.tratti.last().a.nome),
    oraArrivo = oraARoma(t.arrivo),
    blocchi = t.tratti.map { tr ->
        if (tr.aPiedi) {
            BloccoTragitto(
                aPiedi = true,
                titolo = tx("A piedi") + " · " + testoDurata(tr.durata) + (tr.metri?.takeIf { it > 0 }?.let { " · " + testoMetri(it) } ?: ""),
                linea = "", colore = Colori.PORPORA, gestore = "", soppresso = false, sali = null, scendi = null,
                fermate = "", intermedie = emptyList(),
                passi = if (tr.passi.size > 1) tr.passi.map { p -> p.testo + if (p.metri >= 10) " · " + testoMetri(p.metri) else "" } else emptyList(),
                avvisi = emptyList(), corsaId = "",
            )
        } else {
            val n = tr.intermedie.size + 1
            BloccoTragitto(
                aPiedi = false,
                titolo = if (tr.direzione.isNotEmpty()) tx("{0} per {1}", tr.nomeMezzo, tr.direzione) else tr.nomeMezzo,
                linea = tr.linea.ifEmpty { tr.nomeMezzo },
                colore = coloreTratto(tr),
                gestore = tr.gestore,
                soppresso = tr.cancellato,
                sali = rigaTappa(tx("Sali a {0}"), tr.da, tr.partenza, tr.partenzaPrevista, tr.tempoReale),
                scendi = rigaTappa(tx("Scendi a {0}"), tr.a, tr.arrivo, tr.arrivoPrevisto, tr.tempoReale),
                fermate = txn("{0} fermata", "{0} fermate", n) + " · " + testoDurata(tr.durata),
                intermedie = tr.intermedie.map { TappaBreve(it.nome, it.orario?.let { o -> oraARoma(o) }.orEmpty()) },
                passi = emptyList(),
                avvisi = tr.avvisi,
                corsaId = tr.corsaId,
            )
        }
    },
)

/** "La vettura 4012 è a 300 m dalla fermata · rilevata 20 s fa". */
fun testoVetturaTragitto(t: Tratto, vettura: MezzoRT, adesso: Long): String {
    val eta = etaRilevamento(vettura.rilevato, adesso)
    val dove = if (t.partenza / 1000 > adesso && !t.da.lat.isNaN()) {
        tx("è a {0} dalla fermata", testoMetri(distanzaMetri(vettura.lat, vettura.lon, t.da.lat, t.da.lon)))
    } else {
        tx("è in viaggio")
    }
    return tx("La vettura {0} {1}", vettura.etichetta, dove).replace("  ", " ") +
        (eta?.let { " · " + tx("rilevata {0}", it) } ?: "")
}

/** La fermata dell'app che corrisponde a una fermata di Transitous, per aprirne i tempi d'attesa. */
fun fermataLocale(orario: Orario, tappa: Tappa): Fermata? {
    if (tappa.lat.isNaN() || tappa.lon.isNaN()) return null
    val candidati = listOfNotNull(
        tappa.codice,
        tappa.fermataId?.substringAfterLast('_'),
        tappa.fermataId?.substringAfterLast(':'),
        tappa.fermataId,
    ).distinct()
    for (c in candidati) {
        val f = orario.fermata(c) ?: continue
        if (distanzaMetri(f.lat, f.lon, tappa.lat, tappa.lon) < 300) return f
    }
    return orario.fermateVicine(tappa.lat, tappa.lon, 60.0).firstOrNull()?.first
}

/** Le vetture dei mezzi di un tragitto (indice del tratto -> vettura), da Roma Mobilita' e BusOne. */
suspend fun vettureTragitto(t: Tragitto): Map<Int, MezzoRT> {
    if (t.mezzi.isEmpty()) return emptyMap()
    val conCotral = t.tratti.any { tr -> !tr.aPiedi && corsaApp(tr.corsaId)?.let { Rete.di(it) == Rete.COTRAL } == true }
    val corse = try {
        TempoReale.corse()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
    val cotral = if (conCotral) {
        try {
            BusOne.mezzi()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    } else {
        emptyList()
    }
    val grezzi = TempoReale.mezzi()
    val mezzi = withContext(Dispatchers.Default) { completaMezzi(grezzi, corse) } + cotral
    return t.tratti.withIndex()
        .filter { !it.value.aPiedi }
        .mapNotNull { (i, tr) -> mezzoDellaCorsa(mezzi, tr.corsaId)?.let { i to it } }
        .toMap()
}

/** Quando la posizione non e' permessa ma serve per la partenza o l'arrivo. */
fun avvisoPermessoTragitti(negatoPerSempre: Boolean): String =
    tx("Per partire o arrivare alla tua posizione serve il permesso di localizzazione. Le coordinate vengono inviate a Transitous solo per calcolare il tragitto.") +
        if (negatoPerSempre) " " + tx("Il permesso è stato negato: puoi darlo dalle impostazioni dell'app.") else ""

/** Il testo dell'attribuzione richiesta da Transitous, con il collegamento alle fonti. */
fun attribuzioneTransitous(): String =
    tx("Tragitti calcolati da Transitous con i dati di Roma Mobilità, Cotral, Trenitalia e OpenStreetMap. Tocca qui per tutte le fonti.")

