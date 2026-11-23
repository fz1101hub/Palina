@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.ArriviDivisi
import it.palinaroma.dati.CorsaRT
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.FontiFermata
import it.palinaroma.dati.BusOne
import it.palinaroma.dati.Rete
import it.palinaroma.dati.caricaFonti
import it.palinaroma.dati.MezzoRT
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.ancoraAttesi
import it.palinaroma.dati.arriviStazione
import it.palinaroma.dati.dividiArrivi
import it.palinaroma.dati.fraseArrivo
import it.palinaroma.dati.lineeFissate
import it.palinaroma.dati.minutiAttesa
import it.palinaroma.dati.testoAggiornato
import it.palinaroma.dati.testoPoi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Esegue [blocco]; se non riesce restituisce null (l'annullamento passa). */
suspend fun <T> seRiesce(blocco: suspend () -> T): T? =
    try {
        blocco()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

@Composable
fun FermataSchermo(
    fermataId: String,
    indietro: () -> Unit,
    apriLinea: (String, Int?) -> Unit,
    apriVettura: (lineaId: String, direzione: Int?, corsaId: String) -> Unit,
    apriCorsa: (RichiestaCorsa) -> Unit,
    comeArrivare: (Fermata) -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    val chiaviFissate by Archivio.fissate.collectAsStateWithLifecycle()
    val o = orario
    // le stazioni di scambio (Metromare e Metro B, Roma Nord e Metro A) sono una
    // stazione sola: la stazione Cotral e le banchine della metro, con gli orari di tutte
    val membri = remember(o, fermataId) { o?.stazioneUnita(fermataId) ?: listOf(fermataId) }
    val fermata = remember(o, fermataId) { o?.fermataUnita(fermataId) ?: o?.fermata(fermataId) }
    val fermateMembri = remember(o, membri) {
        o?.fermate(membri)?.let { trovate -> membri.mapNotNull { trovate[it] } }.orEmpty()
    }
    val linee = remember(o, membri) {
        membri.flatMap { o?.lineeAllaFermata(it).orEmpty() }.distinctBy { it.linea.id to it.direzione }
    }
    val nomiLinee = remember(linee) { linee.associate { it.linea.id to it.linea.nome } }
    val fissate = remember(chiaviFissate, fermataId) { lineeFissate(fermataId, chiaviFissate) }

    var arrivi by remember { mutableStateOf<List<Arrivo>?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }
    var aggiornando by remember { mutableStateOf(false) }
    var aggiornatoAlle by remember { mutableLongStateOf(0L) }
    var soloTempoReale by remember { mutableStateOf(false) }
    // Roma: il feed di Roma Mobilita' si legge ma non ha nessuna corsa (o e' fermo)
    var romaFerma by remember { mutableStateOf(false) }
    var adessoMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // per le stazioni dei treni Cotral: true se ASTRAL ha risposto, false se si vede l'orario
    var conAstral by remember { mutableStateOf<Boolean?>(null) }
    val adesso = adessoMs / 1000
    val ambito = rememberCoroutineScope()
    val ciclo = LocalLifecycleOwner.current
    // la rete della stazione: per quelle di scambio, quella della stazione Cotral
    val rete = Rete.di(membri.first())
    val retiMembri = remember(membri) { membri.map { Rete.di(it) }.toSet() }

    suspend fun componi(corse: List<CorsaRT>?, mezzi: List<MezzoRT>, fonti: Map<String, FontiFermata>?): List<Arrivo> =
        withContext(Dispatchers.IO) {
            arriviStazione(membri, o, corse, mezzi, fonti.orEmpty(), System.currentTimeMillis() / 1000)
        }

    // Tre fonti in parallelo, ognuna puo' mancare:
    // - tempo reale di Roma Mobilita' (vetture seguite via GPS), solo per le fermate di Roma,
    // - posizioni delle vetture: Roma Mobilita' per Roma, BusOne per i bus Cotral,
    // - Transitous: tutte le partenze da orario, aggiornate col tempo reale quando c'e'
    //   (per i treni Cotral prima ASTRAL, con i ritardi).
    suspend fun carica(forza: Boolean) {
        aggiornando = true
        try {
            val roma = Rete.ROMA in retiMembri
            val (corse, mezzi, fonti) = coroutineScope {
                val c = async { if (roma) seRiesce { TempoReale.corse(forza) } else null }
                val m = async {
                    val r = if (roma) seRiesce { TempoReale.mezzi(forza) } else null
                    val b = if (Rete.COTRAL in retiMembri) seRiesce { BusOne.mezzi(forza) } else null
                    if (r == null && b == null) null else r.orEmpty() + b.orEmpty()
                }
                // le fonti di ogni fermata della stazione (una sola, tranne che negli scambi)
                val p = async {
                    if (fermateMembri.isEmpty()) {
                        null
                    } else {
                        fermateMembri.map { f -> async { f.id to caricaFonti(f, o) } }.awaitAll().toMap()
                    }
                }
                if (arrivi == null && roma) {
                    // Alla prima apertura si mostra subito la fonte che risponde per prima (di solito
                    // Transitous: poche righe contro i MB del feed); il resto si aggiunge appena arriva.
                    val primaTransitous = select<Boolean> {
                        p.onAwait { true }
                        c.onAwait { false }
                    }
                    val parziale = if (primaTransitous) {
                        componi(null, emptyList(), p.await())
                    } else {
                        componi(c.await(), if (m.isCompleted) m.await().orEmpty() else emptyList(), null)
                    }
                    if (parziale.isNotEmpty() && arrivi == null) arrivi = parziale
                }
                Triple(c.await(), m.await().orEmpty(), p.await())
            }
            val fontiVuote = fonti == null || fonti.values.all { it.vuote }
            if (corse == null && fontiVuote) throw IllegalStateException("nessuna fonte disponibile")
            arrivi = componi(corse, mezzi, fonti)
            soloTempoReale = roma && fontiVuote
            romaFerma = roma && TempoReale.fermo(corse)
            if (rete == Rete.COTRAL_TRENI) conAstral = fonti?.get(membri.first())?.treni != null
            errore = null
            aggiornatoAlle = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errore = if (arrivi.isNullOrEmpty()) {
                "Non riesco a leggere i passaggi. Controlla la connessione e riprova."
            } else {
                "Non riesco ad aggiornare: i tempi qui sotto sono quelli delle ${ora(aggiornatoAlle)}."
            }
        } finally {
            aggiornando = false
        }
    }

    // Aggiorna ogni 30 secondi solo mentre la schermata e' visibile
    LaunchedEffect(fermataId, o) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                carica(forza = false)
                delay(30_000)
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            adessoMs = System.currentTimeMillis()
            delay(5_000)
        }
    }

    // Se l'aggiornamento non riesce (per esempio senza rete) i mezzi gia' passati spariscono da soli.
    val visibili = remember(arrivi, adesso) { arrivi?.let { ancoraAttesi(it, adesso) } }
    val diviso = remember(visibili, fissate) { visibili?.let { dividiArrivi(it, fissate) } }

    Scaffold(
        containerColor = Sfondo,
        topBar = {
            BarraSuperiore(
                titolo = fermata?.nome ?: "Fermata $fermataId",
                sottotitolo = fermata?.let { descrizioneFermata(it) },
                indietro = indietro,
                azioni = {
                    fermata?.let { f ->
                        IconButton(onClick = { comeArrivare(f) }) {
                            Icon(Icons.Filled.Place, contentDescription = "Come arrivo qui")
                        }
                    }
                    val preferita = fermataId in preferiti
                    IconButton(onClick = { Archivio.alternaPreferito(fermataId) }) {
                        Icon(
                            if (preferita) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (preferita) "Togli dai preferiti" else "Aggiungi ai preferiti",
                            tint = if (preferita) Accento else Testo,
                        )
                    }
                },
            )
        },
    ) { margini ->
        PullToRefreshBox(
            isRefreshing = aggiornando,
            onRefresh = { ambito.launch { carica(forza = true) } },
            modifier = Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { RigaAggiornamento(aggiornatoAlle, adessoMs) }
                errore?.let { testo ->
                    item { AvvisoRete(testo, riprova = { ambito.launch { carica(forza = true) } }) }
                }
                if (romaFerma && aggiornatoAlle > 0) {
                    item {
                        AvvisoFonte(
                            "Roma Mobilità in questo momento non pubblica il tempo reale: nessuna vettura nel suo flusso. " +
                                "I tempi qui sotto sono calcolati sull'orario.",
                        )
                    }
                }
                if (soloTempoReale && aggiornatoAlle > 0) {
                    item {
                        Text(
                            "Gli orari programmati non sono disponibili in questo momento: vedi solo i mezzi seguiti in tempo reale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondario,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                val d = diviso
                if (d != null) {
                    item {
                        TabelloneFermata(
                            diviso = d,
                            nomiLinee = nomiLinee,
                            conFissate = fissate.isNotEmpty(),
                            adesso = adesso,
                            testoVuoto = when {
                                errore != null || aggiornatoAlle == 0L -> null
                                soloTempoReale -> "Nessun mezzo seguito in tempo reale nei prossimi 90 minuti."
                                rete.treni -> "Nessun treno nei prossimi 90 minuti."
                                else -> "Nessun passaggio nei prossimi 90 minuti, né in tempo reale né da orario."
                            },
                            // i treni: il dettaglio della corsa, con le sole fermate dove fermano;
                            // Roma, e i bus Cotral visti dal GPS: la vettura sulla mappa;
                            // le altre reti: il percorso della linea, se c'e' nell'orario
                            apri = { a ->
                                val p = a.passaggi.first()
                                when {
                                    Rete.di(a.lineaId).treni -> ({
                                        apriCorsa(
                                            RichiestaCorsa(
                                                corsaId = p.idTransitous,
                                                fermataId = membri.firstOrNull { Rete.di(it) == Rete.di(a.lineaId) } ?: fermataId,
                                                lineaId = a.lineaId,
                                                direzione = a.direzione,
                                                previsto = p.orario - (p.ritardo ?: 0),
                                                corsaAstral = p.corsaId.takeIf { it.startsWith("astral:") },
                                                ritardo = p.ritardo,
                                            ),
                                        )
                                    })
                                    Rete.di(a.lineaId) == Rete.ROMA && Rete.ROMA in retiMembri ->
                                        ({ apriVettura(a.lineaId, a.direzione, p.corsaId) })
                                    Rete.di(a.lineaId) == Rete.COTRAL && p.gps && o?.linea(a.lineaId) != null ->
                                        ({ apriVettura(a.lineaId, a.direzione, p.corsaId) })
                                    o?.linea(a.lineaId) != null ->
                                        ({ apriLinea(a.lineaId, a.direzione) })
                                    else -> null
                                }
                            },
                            fissa = { lineaId -> Archivio.alternaLineaFissata(fermataId, lineaId) },
                        )
                    }
                    item {
                        Text(
                            when (rete) {
                                Rete.ROMA ->
                                    "Cifre oro con il pallino: vettura vista ora dal GPS. Grigie: tempo calcolato sull'orario. " +
                                        "Tocca una riga per vedere la vettura sulla mappa, trascina giù per aggiornare."
                                Rete.COTRAL ->
                                    "Cifre oro con il pallino: in tempo reale (Cotral, tramite Transitous); «GPS»: il bus è visto " +
                                        "ora dal GPS (BusOne). Grigie: orario programmato. Tocca una riga per vedere il bus o il percorso."
                                Rete.COTRAL_TRENI -> when (conAstral) {
                                    true -> "Orari, ritardi e soppressioni dei treni: ASTRAL. Cifre oro: ritardo già comunicato; " +
                                        "grigie: orario programmato. Tocca un treno per vedere tutte le sue fermate."
                                    else -> "Ritardi dei treni non disponibili ora (ASTRAL non risponde, o di notte " +
                                        "non ha ancora l'orario del giorno): vedi l'orario programmato. " +
                                        "Tocca un treno per vedere tutte le sue fermate."
                                } + if (Rete.ROMA in retiMembri) {
                                    " Qui ci sono anche le banchine della metro: i loro tempi sono da orario."
                                } else {
                                    ""
                                }
                                Rete.TRENITALIA ->
                                    "Cifre oro con il pallino: in tempo reale (Trenitalia, tramite Transitous). " +
                                        "Grigie: orario programmato. Tocca un treno per vedere tutte le sue fermate."
                                else ->
                                    "Cifre oro con il pallino: in tempo reale (${rete.etichetta}, tramite Transitous). " +
                                        "Grigie: orario programmato. Tocca una riga per vedere il percorso della linea."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondario,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                if (linee.isNotEmpty()) {
                    item { Intestazione("Linee di questa fermata", rientro = 8.dp) }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            // per le stazioni (centinaia di treni con nomi uguali) una volta per nome
                            linee.distinctBy { if (rete.treni) it.linea.nome else it.linea.id }.forEach { lf ->
                                val apribile = Rete.di(lf.linea.id) != Rete.TRENITALIA
                                BadgeLinea(
                                    lf.linea.nome,
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (apribile) {
                                                Modifier.clickable(onClickLabel = "apri la linea") { apriLinea(lf.linea.id, lf.direzione) }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    grande = true,
                                    colore = coloreRete(lf.linea.id),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "● Aggiornato 20 s fa" a sinistra, l'ora a cifre a punti a destra. */
@Composable
fun RigaAggiornamento(aggiornatoAlle: Long, adessoMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (aggiornatoAlle > 0) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(Accento, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (aggiornatoAlle > 0) "Aggiornato " + testoAggiornato(aggiornatoAlle, adessoMs) else "Carico i tempi d'attesa…",
            style = MaterialTheme.typography.bodySmall,
            color = Secondario,
            modifier = Modifier.weight(1f),
        )
        Text(
            ora(adessoMs),
            fontFamily = CarattereCifre,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
            color = Accento,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Avviso su una fonte dei dati (per esempio il tempo reale che manca): solo testo, senza azioni. */
@Composable
fun AvvisoFonte(testo: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AccentoFondo, RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Accento)
        Text(testo, style = MaterialTheme.typography.bodyMedium, color = Testo, modifier = Modifier.weight(1f))
    }
}

/** Avviso quando la rete manca: dice di quando sono i tempi e offre di riprovare. */
@Composable
fun AvvisoRete(testo: String, riprova: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AccentoFondo, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Accento)
        Text(testo, style = MaterialTheme.typography.bodyMedium, color = Testo, modifier = Modifier.weight(1f))
        TextButton(onClick = riprova) { Text("Riprova", color = Accento, fontWeight = FontWeight.Bold) }
    }
}

/** Il tabellone della fermata: in cima le linee fissate, sotto le altre. */
@Composable
private fun TabelloneFermata(
    diviso: ArriviDivisi,
    nomiLinee: Map<String, String>,
    conFissate: Boolean,
    adesso: Long,
    testoVuoto: String?,
    apri: (Arrivo) -> (() -> Unit)?,
    fissa: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .riquadroTabellone(FondoTabellone, Bordo)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (conFissate) {
            TitolettoTabellone("Le tue linee", Accento)
            diviso.fissate.forEach { a ->
                RigaArrivo(a, adesso, fissata = true, apri = apri(a), fissa = { fissa(a.lineaId) })
            }
            diviso.senzaPassaggi.forEach { id ->
                RigaFissataVuota(nomiLinee[id] ?: id, id, togli = { fissa(id) })
            }
            if (diviso.altre.isNotEmpty()) TitolettoTabellone("Altre linee", Secondario)
        } else if (diviso.altre.isNotEmpty()) {
            Text(
                "Tocca la puntina accanto a una linea: resta in cima qui e in Home.",
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, end = 10.dp),
            )
        }
        diviso.altre.forEach { a ->
            RigaArrivo(a, adesso, fissata = false, apri = apri(a), fissa = { fissa(a.lineaId) })
        }
        if (diviso.fissate.isEmpty() && diviso.altre.isEmpty() && testoVuoto != null) {
            Text(testoVuoto, color = Secondario, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun TitolettoTabellone(testo: String, colore: androidx.compose.ui.graphics.Color) {
    Intestazione(testo, colore = colore, rientro = 0.dp)
}

/** Una linea alla fermata: numero, direzione, prossimi passaggi, minuti grandi e puntina. */
@Composable
private fun RigaArrivo(arrivo: Arrivo, adesso: Long, fissata: Boolean, apri: (() -> Unit)?, fissa: () -> Unit) {
    val primo = arrivo.passaggi.first()
    val frase = fraseArrivo(arrivo, adesso)
    val reteLinea = Rete.di(arrivo.lineaId)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .testTag("arrivo")
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (apri != null) {
                            Modifier.clickable(
                                onClickLabel = if (reteLinea == Rete.ROMA || primo.gps) "vedi la vettura sulla mappa" else "vedi la linea",
                                onClick = apri,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clearAndSetSemantics { contentDescription = frase }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BadgeLinea(arrivo.nomeLinea, grande = true, colore = coloreRete(arrivo.lineaId))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        arrivo.capolinea.ifEmpty { "Direzione non indicata" },
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = if (arrivo.capolinea.isEmpty()) Secondario else Testo,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val poi = testoPoi(arrivo.passaggi.drop(1).take(2).map { minutiAttesa(it.orario, adesso) })
                    if (poi.isNotEmpty()) {
                        Text(poi, style = MaterialTheme.typography.bodySmall, color = Secondario)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (primo.avviso == "soppresso") {
                        Text("soppresso", color = Accento, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(ora(primo.orario * 1000), style = MaterialTheme.typography.labelSmall, color = Secondario)
                    } else {
                        CifreAttesa(minutiAttesa(primo.orario, adesso), primo.tracciato)
                        if (primo.avviso != null) {
                            Text(primo.avviso, style = MaterialTheme.typography.labelSmall, color = Accento, fontWeight = FontWeight.Bold)
                        } else {
                            FonteTempo(primo.tracciato, etichetta = if (reteLinea == Rete.ROMA || primo.gps) "GPS" else "tempo reale")
                        }
                    }
                }
            }
            IconButton(onClick = fissa) {
                Icon(
                    if (fissata) PuntinaPiena else PuntinaVuota,
                    contentDescription = if (fissata) "Togli il ${arrivo.nomeLinea} dalle tue linee" else "Fissa il ${arrivo.nomeLinea} in cima",
                    tint = if (fissata) Accento else Secondario,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(color = Divisore)
    }
}

/** Una linea fissata che nei prossimi 90 minuti non passa: resta in cima e lo dice. */
@Composable
private fun RigaFissataVuota(nome: String, lineaId: String, togli: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BadgeLinea(nome, grande = true, colore = coloreRete(lineaId))
            Text(
                "Nessun passaggio nei prossimi 90 minuti",
                style = MaterialTheme.typography.bodyMedium,
                color = Secondario,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = togli) {
                Icon(
                    PuntinaPiena,
                    contentDescription = "Togli il $nome dalle tue linee",
                    tint = Accento,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(color = Divisore)
    }
}
