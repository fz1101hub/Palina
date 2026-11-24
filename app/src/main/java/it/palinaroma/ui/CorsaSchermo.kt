package it.palinaroma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.CercaCorsa
import it.palinaroma.dati.DettaglioCorsa
import it.palinaroma.dati.FermataCorsa
import it.palinaroma.dati.RichiestaCorsa
import it.palinaroma.dati.Tragitto
import it.palinaroma.dati.bellaScrittura
import it.palinaroma.dati.fermataDaTransitous
import it.palinaroma.dati.nomeLineaCorsa
import it.palinaroma.dati.testoFonte
import kotlinx.coroutines.delay

/**
 * Il dettaglio di una corsa di un treno: tutte le sue fermate (solo quelle dove
 * ferma davvero), con orari, ritardo e binario. Si apre toccando un treno nel
 * tabellone di una stazione.
 *
 * [corsaId]: la corsa per Transitous. Per i treni Cotral visti da ASTRAL non c'e':
 * la si cerca tra le partenze di Transitous della stazione [fermataId], per
 * linea, direzione e orario programmato [previsto] (secondi); [corsaAstral] e
 * [ritardo] (secondi) servono a portare il ritardo di ASTRAL nel dettaglio.
 */
@Composable
fun CorsaSchermo(
    corsaId: String?,
    fermataId: String,
    lineaId: String,
    direzione: Int?,
    previsto: Long,
    corsaAstral: String?,
    ritardo: Int?,
    indietro: () -> Unit,
    apriFermata: (String) -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val o = orario
    var dettaglio by remember { mutableStateOf<DettaglioCorsa?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }
    var adessoMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ciclo = LocalLifecycleOwner.current
    val lista = rememberLazyListState()
    val seiQui = rememberSeiQui()

    val richiesta = remember(corsaId, fermataId, lineaId, direzione, previsto, corsaAstral, ritardo) {
        RichiestaCorsa(corsaId, fermataId, lineaId, direzione, previsto, corsaAstral, ritardo)
    }
    LaunchedEffect(richiesta, o) {
        val cerca = CercaCorsa(o, richiesta)
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val esito = cerca.aggiorna()
                if (esito.dettaglio != null) {
                    dettaglio = esito.dettaglio
                    errore = null
                } else if (dettaglio == null) {
                    errore = esito.errore
                }
                adessoMs = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }

    // all'apertura la lista parte dalla stazione da cui si e' toccato il treno
    LaunchedEffect(dettaglio?.qui != null) {
        val qui = dettaglio?.qui ?: return@LaunchedEffect
        lista.scrollToItem((qui + 1 - 2).coerceAtLeast(0))
    }

    val d = dettaglio
    val nomeLinea = nomeLineaCorsa(d, lineaId, o)
    val titolo = listOf(nomeLinea, d?.tratto?.numero.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Treno" }

    Scaffold(
        containerColor = Sfondo,
        topBar = {
            BarraSuperiore(
                titolo = titolo,
                sottotitolo = d?.tratto?.direzione?.takeIf { it.isNotBlank() }?.let {
                    "verso $it" + (d.tratto.gestore.takeIf { g -> g.isNotBlank() }?.let { g -> " · " + bellaScrittura(g) } ?: "")
                },
                indietro = indietro,
            )
        },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            if (d == null) {
                Text(
                    errore ?: "Cerco le fermate del treno…",
                    color = Secondario,
                    modifier = Modifier.padding(24.dp),
                )
                return@Scaffold
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.75f),
            ) {
                // sulla mappa la corsa ha il colore della rete, come nell'elenco delle fermate
                val coloreLinea = coloreRete(lineaId).toArgb()
                val t = remember(d.tratto, coloreLinea) { d.tratto.copy(colore = coloreLinea) }
                val tappe = remember(t) { listOf(t.da) + t.intermedie + listOf(t.a) }
                MappaTragitto(
                    Tragitto(t.partenza, t.arrivo, t.durata, 0, listOf(t)),
                    emptyList(),
                    adessoMs / 1000,
                    seiQui.posizione,
                    seiQui.richiestaCentra,
                    Modifier.fillMaxSize(),
                    fermate = tappe,
                    qui = d.qui?.let { tappe.getOrNull(it) },
                )
                ComandiSeiQui(seiQui)
            }
            LazyColumn(
                state = lista,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.25f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                item { RigaFonteCorsa(d) }
                itemsIndexed(d.fermate, key = { i, f -> "$i-${f.idTransitous}" }) { i, f ->
                    val idApp = remember(f.idTransitous) { fermataDaTransitous(f.idTransitous) }
                    val apribile = idApp != null && o?.fermata(idApp) != null
                    RigaFermataCorsa(
                        fermata = f,
                        prima = i == 0,
                        ultima = i == d.fermate.lastIndex,
                        qui = i == d.qui,
                        passata = (f.orario ?: Long.MAX_VALUE) < adessoMs - 60_000,
                        colore = coloreRete(lineaId),
                        apri = if (apribile) ({ apriFermata(idApp!!) }) else null,
                    )
                }
                item { Attribuzione() }
            }
        }
    }
}

/** Da dove vengono gli orari: tempo reale, ritardo di ASTRAL o solo orario. */
@Composable
private fun RigaFonteCorsa(d: DettaglioCorsa) {
    Text(
        d.testoFonte(),
        style = MaterialTheme.typography.bodySmall,
        color = if (d.tratto.cancellato) Accento else Secondario,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Una fermata della corsa: orario (e quello programmato se cambia), nome, binario. */
@Composable
private fun RigaFermataCorsa(
    fermata: FermataCorsa,
    prima: Boolean,
    ultima: Boolean,
    qui: Boolean,
    passata: Boolean,
    colore: Color,
    apri: (() -> Unit)?,
) {
    val tenue = passata && !qui
    val coloreTesto = if (tenue) Secondario else Testo
    val orarioTesto = fermata.orario?.let { ora(it) } ?: "–"
    val aggiornato = fermata.orario
    val programmato = fermata.previsto
    val cambiato = aggiornato != null && programmato != null && ora(aggiornato) != ora(programmato)
    val descrizione = buildString {
        append(fermata.nome)
        if (fermata.soppressa) append(", fermata soppressa") else append(", ").append(orarioTesto)
        fermata.ritardoMinuti?.takeIf { it > 0 }?.let { append(", ").append(it).append(" minuti di ritardo") }
        if (fermata.stimato) append(", stimato")
        fermata.binario?.let { append(", binario ").append(it) }
        if (fermata.binarioCambiato) append(", binario cambiato")
        if (qui) append(", la tua stazione")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (qui) AccentoFondo else Color.Transparent)
            .let { if (apri != null) it.clickable(onClickLabel = "vedi le partenze da questa stazione", onClick = apri) else it }
            .semantics(mergeDescendants = true) { contentDescription = descrizione }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // orario, con quello programmato barrato se e' cambiato
        Column(Modifier.width(58.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.End) {
            Text(
                orarioTesto,
                fontWeight = FontWeight.Bold,
                color = when {
                    fermata.soppressa -> Secondario
                    fermata.stimato -> coloreTesto
                    cambiato -> Accento
                    else -> coloreTesto
                },
                textDecoration = if (fermata.soppressa) TextDecoration.LineThrough else null,
            )
            if (cambiato && !fermata.soppressa) {
                Text(
                    ora(fermata.previsto!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
        }
        // la linea del percorso, con un pallino per fermata
        Box(
            Modifier
                .width(32.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val coloreLinea = if (tenue) Secondario else colore
            val bianco = Sfondo
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                val spessore = 4.dp.toPx()
                if (!prima) drawLine(coloreLinea, Offset(x, 0f), Offset(x, size.height / 2), spessore)
                if (!ultima) drawLine(coloreLinea, Offset(x, size.height / 2), Offset(x, size.height), spessore)
                val raggio = (if (qui || prima || ultima) 7 else 5).dp.toPx()
                drawCircle(coloreLinea, raggio, Offset(x, size.height / 2))
                drawCircle(if (qui) Oro else bianco, raggio - 2.dp.toPx(), Offset(x, size.height / 2))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                fermata.nome,
                color = coloreTesto,
                fontWeight = if (qui || prima || ultima) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (fermata.soppressa) TextDecoration.LineThrough else null,
            )
            val sotto = listOfNotNull(
                "soppressa".takeIf { fermata.soppressa },
                "la tua stazione".takeIf { qui },
                fermata.ritardoMinuti?.takeIf { it > 0 && !fermata.soppressa }?.let { "+$it min" + if (fermata.stimato) " (stima)" else "" },
            )
            if (sotto.isNotEmpty()) {
                Text(sotto.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (qui) Accento else Secondario)
            }
        }
        fermata.binario?.let { b ->
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text("bin.", style = MaterialTheme.typography.labelSmall, color = Secondario)
                Text(
                    b,
                    fontWeight = FontWeight.Bold,
                    color = if (fermata.binarioCambiato) Accento else coloreTesto,
                )
            }
        }
    }
}

/** La rotta del dettaglio della corsa (vedi [CorsaSchermo]). */
fun RichiestaCorsa.rotta(): String =
    "corsa?fermata=" + android.net.Uri.encode(fermataId) +
        "&linea=" + android.net.Uri.encode(lineaId) +
        "&dir=" + (direzione ?: -1) +
        "&previsto=" + previsto +
        (corsaId?.let { "&trip=" + android.net.Uri.encode(it) } ?: "") +
        (corsaAstral?.let { "&astral=" + android.net.Uri.encode(it) } ?: "") +
        (ritardo?.let { "&ritardo=$it" } ?: "")
