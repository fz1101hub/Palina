package it.palinaroma.ponte

import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.BusOne
import it.palinaroma.dati.CercaCorsa
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.Orario
import it.palinaroma.dati.Punto
import it.palinaroma.dati.Rete
import it.palinaroma.dati.RichiestaCorsa
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.adessoMs
import it.palinaroma.dati.arriviStazione
import it.palinaroma.dati.bellaScrittura
import it.palinaroma.dati.caricaFonti
import it.palinaroma.dati.coloreReteArgb
import it.palinaroma.dati.descrizioneFermata
import it.palinaroma.dati.dispatcherDati
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.dividiArrivi
import it.palinaroma.dati.lineeFissate
import it.palinaroma.dati.nomeLineaCorsa
import it.palinaroma.dati.testoAvviso
import it.palinaroma.dati.testoFonte
import it.palinaroma.dati.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/*
 * Il tabellone e le fermate dei treni per l'orologio, calcolati dal telefono:
 * lo stesso calcolo per Wear OS (dal telefono Android) e per Apple Watch
 * (dall'iPhone).
 */

/** Quante fermate preferite e vicine vanno all'orologio, e quante righe per fermata. */
const val PREFERITE_OROLOGIO = 6
const val VICINE_OROLOGIO = 3
const val RIGHE_OROLOGIO = 6

/**
 * Il tabellone per l'orologio: le fermate [preferiti] (dalla piu' vicina a
 * [posizione]) e qualche fermata vicina, ognuna con le prossime linee (prima
 * quelle [fissate] e quelle [seguite] sull'orologio).
 */
suspend fun calcolaTabelloneOrologio(
    orario: Orario,
    preferiti: Set<String>,
    fissate: Set<String>,
    posizione: Punto?,
    adessoMs: Long = adessoMs(),
    seguite: List<LineaSeguita> = emptyList(),
    /** Fermate che ci devono essere comunque (quelle scelte nei widget del telefono). */
    sempre: Collection<String> = emptyList(),
    /** I nomi dati alle fermate ("Lavoro"): anche sull'orologio. */
    nomi: Map<String, String> = emptyMap(),
): TabelloneOrologio {
    val adesso = adessoMs / 1000

    // le fermate: preferite, poi vicine che non lo sono gia'
    val scelte: List<Triple<Fermata, Double?, Boolean>> = withContext(dispatcherDati) {
        val elenco = preferiti.toList()
        val trovate = orario.fermate(elenco)
        val preferite = elenco.mapNotNull { id -> trovate[id]?.let { f -> orario.fermataUnita(id)?.copy(id = id) ?: f } }
            .map { f -> Triple(f, posizione?.let { distanzaMetri(it.lat, it.lon, f.lat, f.lon) }, true) }
            .sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.first.nome }))
            .take(PREFERITE_OROLOGIO)
        val giaScelte = preferite.flatMap { orario.stazioneUnita(it.first.id) }.toSet()
        val vicine = posizione?.let { p ->
            orario.fermateVicineUnite(p.lat, p.lon, 500.0)
                .filter { (f, _) -> orario.stazioneUnita(f.id).none { it in giaScelte } }
                .take(VICINE_OROLOGIO)
                .map { (f, d) -> Triple(f, d, false) }
        }.orEmpty()
        // le fermate delle linee seguite sull'orologio ci sono sempre, anche se ci si e' allontanati
        val fin = preferite + vicine
        val presenti = fin.map { it.first.id }.toSet()
        val seguiteFermate = (seguite.map { it.fermataId } + sempre).distinct().filter { it !in presenti }
        val trovateSeguite = orario.fermate(seguiteFermate)
        val inPiu = seguiteFermate.mapNotNull { id -> trovateSeguite[id]?.let { f -> orario.fermataUnita(id)?.copy(id = id) ?: f } }
            .map { f -> Triple(f, posizione?.let { distanzaMetri(it.lat, it.lon, f.lat, f.lon) }, f.id in preferiti) }
        fin + inPiu
    }
    if (scelte.isEmpty()) {
        return TabelloneOrologio(adessoMs, emptyList(), tx("Aggiungi una fermata ai preferiti sul telefono, o attiva la posizione."))
    }

    // le fonti di tutte le fermate (anche quelle delle stazioni di scambio), in parallelo
    val membriPer = scelte.associate { it.first.id to orario.stazioneUnita(it.first.id) }
    val tutte = membriPer.values.flatten().distinct()
    val conRoma = tutte.any { Rete.di(it) == Rete.ROMA }
    val conCotral = tutte.any { Rete.di(it) == Rete.COTRAL }
    val (corse, mezzi, fonti) = coroutineScope {
        val c = async { if (conRoma) provando { TempoReale.corse() } else null }
        val m = async {
            val roma = if (conRoma) provando { TempoReale.mezzi() }.orEmpty() else emptyList()
            val cotral = if (conCotral) provando { BusOne.mezzi() }.orEmpty() else emptyList()
            roma + cotral
        }
        val fermate = withContext(dispatcherDati) { orario.fermate(tutte) }
        val f = tutte.mapNotNull { fermate[it] }
            .map { fermata -> async { fermata.id to provando { caricaFonti(fermata, orario, adessoMs) } } }
            .awaitAll()
            .mapNotNull { (id, fonti) -> fonti?.let { id to it } }
            .toMap()
        Triple(c.await(), m.await(), f)
    }

    val fermate = withContext(Dispatchers.Default) {
        scelte.map { (f, distanza, preferita) ->
            val membri = membriPer.getValue(f.id)
            val arrivi = arriviStazione(membri, orario, corse, mezzi, membri.associateWith { fonti[it] }, adesso)
            // le linee seguite stanno in cima, come quelle fissate
            val inCima = lineeFissate(f.id, fissate) + seguite.filter { it.fermataId == f.id }.map { it.lineaId }
            FermataOrologio(
                id = f.id,
                nome = f.nome,
                sotto = descrizioneFermata(f, orario),
                preferita = preferita,
                distanza = distanza?.toInt(),
                righe = righeOrologio(arrivi, inCima, adesso) { a ->
                    membri.firstOrNull { Rete.di(it) == Rete.di(a.lineaId) } ?: f.id
                },
                etichetta = nomi[f.id].orEmpty(),
            )
        }
    }
    val nota = if (conRoma && TempoReale.fermo(corse)) tx("Roma Mobilità ora non pubblica il tempo reale: tempi da orario.") else null
    return TabelloneOrologio(adessoMs, fermate, nota)
}

/**
 * Le righe per l'orologio: prima le linee fissate, poi le altre; al massimo
 * [quante], tre passaggi l'una. Per i treni anche come chiederne le fermate,
 * dalla stazione [stazioneDi] (nelle stazioni unite, quella della rete del treno).
 */
fun righeOrologio(
    arrivi: List<Arrivo>,
    fissate: Set<String>,
    adesso: Long,
    quante: Int = RIGHE_OROLOGIO,
    stazioneDi: (Arrivo) -> String = { "" },
): List<RigaOrologio> {
    val divisi = dividiArrivi(arrivi, fissate)
    return (divisi.fissate + divisi.altre)
        .filter { a -> a.passaggi.any { it.orario >= adesso - 30 } }
        .take(quante)
        .map { a ->
            val prossimi = a.passaggi.filter { it.orario >= adesso - 30 }.take(3)
            val stazione = if (Rete.di(a.lineaId).treni) stazioneDi(a).ifEmpty { null } else null
            RigaOrologio(
                linea = a.nomeLinea,
                colore = coloreReteArgb(a.lineaId).toInt(),
                capolinea = a.capolinea,
                orari = prossimi.map { it.orario },
                tempoReale = prossimi.first().tracciato,
                avviso = prossimi.first().avviso?.let { testoAvviso(it) },
                lineaId = a.lineaId,
                corse = prossimi.map { it.corsaId },
                treni = if (stazione == null) {
                    emptyList()
                } else {
                    prossimi.map { p ->
                        TrenoOrologio(
                            corsaId = p.idTransitous,
                            fermataId = stazione,
                            lineaId = a.lineaId,
                            direzione = a.direzione,
                            previsto = p.orario - (p.ritardo ?: 0),
                            corsaAstral = p.corsaId.takeIf { it.startsWith("astral:") },
                            ritardo = p.ritardo,
                        )
                    }
                },
            )
        }
}

/** Le fermate del [treno] chiesto dall'orologio, come nel dettaglio della corsa sul telefono. */
suspend fun calcolaCorsaOrologio(orario: Orario?, treno: TrenoOrologio): CorsaOrologio {
    val richiesta = RichiestaCorsa(treno.corsaId, treno.fermataId, treno.lineaId, treno.direzione, treno.previsto, treno.corsaAstral, treno.ritardo)
    val esito = CercaCorsa(orario, richiesta).aggiorna()
    val adesso = adessoMs()
    val d = esito.dettaglio
        ?: return CorsaOrologio(treno.chiave, adesso, nomeLineaCorsa(null, treno.lineaId, orario).ifEmpty { tx("Treno") }, null, null, emptyList(), null, esito.errore)
    val titolo = listOf(nomeLineaCorsa(d, treno.lineaId, orario), d.tratto.numero).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { tx("Treno") }
    val verso = d.tratto.direzione.takeIf { it.isNotBlank() }?.let { dir ->
        tx("verso {0}", dir) + (d.tratto.gestore.takeIf { it.isNotBlank() }?.let { " · " + bellaScrittura(it) } ?: "")
    }
    return CorsaOrologio(
        chiave = treno.chiave,
        creato = adesso,
        titolo = titolo,
        verso = verso,
        fonte = d.testoFonte(breve = true),
        fermate = d.fermate.map { f ->
            FermataTreno(f.nome, f.orario, f.previsto, f.binario, f.binarioCambiato, f.soppressa, f.stimato)
        },
        qui = d.qui,
    )
}

private suspend fun <T> provando(blocco: suspend () -> T): T? =
    try {
        blocco()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
