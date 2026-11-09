@file:OptIn(ExperimentalMaterial3Api::class)

package it.palinaroma.ui

import android.Manifest
import android.graphics.DashPathEffect
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.MezzoRT
import it.palinaroma.dati.Punto
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.distanzaDalTracciato
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.etaRilevamento
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.completaMezzi
import it.palinaroma.dati.vettureDellaLinea
import it.palinaroma.dati.mezzoDellaCorsa
import it.palinaroma.dati.stessoCodice
import it.palinaroma.dati.testoMetri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Linea sulla mappa con le vetture rilevate via GPS. Se si arriva da una fermata,
 * [corsaEvidenziata] e' la corsa attesa: la sua vettura e' in evidenza.
 */
@Composable
fun LineaSchermo(
    lineaId: String,
    direzioneIniziale: Int?,
    corsaEvidenziata: String?,
    fermataEvidenziata: String?,
    indietro: () -> Unit,
    apriFermata: (String) -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val o = orario
    val linea = remember(o, lineaId) { o?.linea(lineaId) }
    val percorsi = remember(o, lineaId) { o?.percorsi(lineaId).orEmpty() }
    var direzione by rememberSaveable { mutableStateOf(direzioneIniziale) }
    val percorso = percorsi.firstOrNull { it.direzione == direzione } ?: percorsi.firstOrNull()
    val fermate = remember(o, lineaId, percorso) {
        if (o != null && percorso != null) o.fermateDelPercorso(lineaId, percorso.direzione) else emptyList()
    }
    val forma = remember(o, percorso, fermate) {
        val tracciato = if (o != null && percorso != null) o.forma(percorso.forma) else emptyList()
        if (tracciato.size >= 2) tracciato else fermate.map { Punto(it.lat, it.lon) }
    }
    // il tracciato dell'altra direzione: spesso passa per strade diverse
    val formaAltra = remember(o, lineaId, percorsi, percorso) {
        val altro = percorsi.firstOrNull { it.direzione != percorso?.direzione }
        if (o == null || altro == null) {
            emptyList()
        } else {
            o.forma(altro.forma).takeIf { it.size >= 2 }
                ?: o.fermateDelPercorso(lineaId, altro.direzione).map { Punto(it.lat, it.lon) }
        }
    }
    val fermataAttesa = remember(o, fermataEvidenziata) { fermataEvidenziata?.let { o?.fermata(it) } }

    // "sei qui": la posizione del telefono sulla mappa
    val contesto = LocalContext.current
    var permessoPosizione by remember { mutableStateOf(haPermessoPosizione(contesto)) }
    var richiestaCentra by remember { mutableIntStateOf(0) }
    val chiediPermesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permessoPosizione = esiti.values.any { it }
        if (permessoPosizione) richiestaCentra++
    }
    val miaPosizione = rememberMiaPosizione(permessoPosizione)
    val fermataVicina = remember(miaPosizione, fermate) {
        miaPosizione?.let { p ->
            fermate.map { it to distanzaMetri(p.latitude, p.longitude, it.lat, it.lon) }.minByOrNull { it.second }
        }
    }

    var mezzi by remember { mutableStateOf<List<MezzoRT>>(emptyList()) }
    var altre by remember { mutableStateOf<List<MezzoRT>>(emptyList()) }
    var evidenziato by remember { mutableStateOf<MezzoRT?>(null) }
    var arrivoAtteso by remember { mutableStateOf<Long?>(null) }
    var caricato by remember { mutableStateOf(false) }
    var aggiornatoAlle by remember { mutableLongStateOf(0L) }
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var selezionato by remember { mutableStateOf<String?>(null) }

    val ciclo = LocalLifecycleOwner.current
    LaunchedEffect(lineaId, percorso?.direzione, o) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val corse = try {
                        TempoReale.corse()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    // le vetture senza corsa o linea si completano con le corse in tempo reale
                    val tutti = completaMezzi(TempoReale.mezzi(), corse)
                    val vetture = withContext(Dispatchers.IO) {
                        vettureDellaLinea(tutti, lineaId, linea?.nome, percorso?.direzione, o)
                    }
                    mezzi = vetture.inDirezione
                    altre = vetture.altre
                    if (corsaEvidenziata != null) {
                        evidenziato = mezzoDellaCorsa(tutti, corsaEvidenziata)
                        if (fermataEvidenziata != null) {
                            val corsa = corse.firstOrNull { it.corsaId == corsaEvidenziata }
                            arrivoAtteso = corsa?.fermate?.firstOrNull { stessoCodice(it.fermataId, fermataEvidenziata) }?.orario
                        }
                    }
                    aggiornatoAlle = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // senza rete restano gli ultimi mezzi noti
                }
                caricato = true
                delay(15_000)
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            adesso = System.currentTimeMillis() / 1000
            delay(5_000)
        }
    }

    val cambiaDirezione: () -> Unit = {
        val i = percorsi.indexOfFirst { it.direzione == percorso?.direzione }
        if (percorsi.size > 1) {
            direzione = percorsi[(i + 1) % percorsi.size].direzione
            selezionato = null
        }
    }

    // quanto dista ogni vettura dal tracciato piu' vicino tra le due direzioni
    val lontananze: Map<String, Double> = remember(mezzi, altre, evidenziato, forma, formaAltra) {
        (mezzi + altre + listOfNotNull(evidenziato)).associate { v ->
            val qui = if (forma.size >= 2) distanzaDalTracciato(v.lat, v.lon, forma) else Double.POSITIVE_INFINITY
            val la = if (formaAltra.size >= 2) distanzaDalTracciato(v.lat, v.lon, formaAltra) else Double.POSITIVE_INFINITY
            v.chiave() to minOf(qui, la)
        }.filterValues { it.isFinite() }
    }
    val fuoriPercorso: Set<String> = lontananze.filterValues { it > METRI_FUORI_PERCORSO }.keys

    // la vettura mostrata nella scheda: quella toccata, altrimenti quella attesa
    val inScheda = selezionato?.let { id -> (mezzi + altre + listOfNotNull(evidenziato)).firstOrNull { it.chiave() == id } }
        ?: evidenziato

    Scaffold(
        topBar = {
            BarraPorpora(
                titolo = "Linea ${linea?.nome ?: lineaId}",
                sottotitolo = percorso?.capolinea?.takeIf { it.isNotEmpty() }?.let { "verso $it" }
                    ?: linea?.descrizione,
                indietro = indietro,
                azioni = {
                    if (percorsi.size > 1) {
                        IconButton(onClick = cambiaDirezione) {
                            Text("⇄", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
            )
        },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            if (percorso != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("DIREZIONE", style = MaterialTheme.typography.labelSmall, color = Secondario)
                        Text(
                            "→ " + percorso.capolinea.ifEmpty { "Direzione ${percorso.direzione + 1}" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (percorsi.size == 1) {
                            Text(
                                "Nell'orario questa linea ha una sola direzione (per esempio è circolare).",
                                style = MaterialTheme.typography.bodySmall,
                                color = Secondario,
                            )
                        }
                    }
                    if (percorsi.size > 1) {
                        Button(
                            onClick = cambiaDirezione,
                            colors = ButtonDefaults.buttonColors(containerColor = Porpora),
                            modifier = Modifier.padding(start = 12.dp),
                        ) { Text("⇄ Cambia") }
                    }
                }
                HorizontalDivider(color = Color(0xFFE2DDD8))
            }
            if (percorsi.size > 2) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    percorsi.forEach { p ->
                        FilterChip(
                            selected = p.direzione == percorso?.direzione,
                            onClick = {
                                direzione = p.direzione
                                selezionato = null
                            },
                            label = {
                                Text(
                                    "→ " + p.capolinea.ifEmpty { "Direzione ${p.direzione + 1}" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Oro,
                                selectedLabelColor = Inchiostro,
                            ),
                        )
                    }
                }
            }
            if (percorso == null) {
                Text(
                    "Per questa linea l'orario non ha un percorso.",
                    color = Secondario,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1.25f),
            ) {
                MappaLinea(
                    nomeLinea = linea?.nome ?: lineaId,
                    forma = forma,
                    formaAltra = formaAltra,
                    fuoriPercorso = fuoriPercorso,
                    fermate = fermate,
                    mezzi = mezzi,
                    altre = altre,
                    evidenziato = evidenziato,
                    fermataAttesa = fermataAttesa,
                    adesso = adesso,
                    apriFermata = apriFermata,
                    seleziona = { selezionato = it.chiave() },
                    miaPosizione = miaPosizione,
                    richiestaCentra = richiestaCentra,
                    modifier = Modifier.fillMaxSize(),
                )
                PulsanteMiaPosizione(
                    attivo = miaPosizione != null,
                    onClick = {
                        if (permessoPosizione) {
                            richiestaCentra++
                        } else {
                            chiediPermesso.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
                when {
                    inScheda != null -> SchedaVettura(
                        mezzo = inScheda,
                        nomeLinea = linea?.nome ?: lineaId,
                        attesa = inScheda.chiave() == evidenziato?.chiave(),
                        altraDirezione = altre.any { it.chiave() == inScheda.chiave() },
                        mostraQuellaDirezione = cambiaDirezione,
                        distanzaPercorso = lontananze[inScheda.chiave()]?.takeIf { it > METRI_FUORI_PERCORSO },
                        fermata = fermataAttesa,
                        arrivo = arrivoAtteso,
                        adesso = adesso,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp),
                    )
                    corsaEvidenziata != null && caricato -> SchedaSenzaGps(
                        fermata = fermataAttesa,
                        arrivo = arrivoAtteso,
                        adesso = adesso,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp),
                    )
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Oro, CircleShape),
                )
                Text(
                    when (mezzi.size) {
                        0 -> if (caricato) "Nessuna vettura GPS in questa direzione" else "Cerco le vetture…"
                        1 -> "1 vettura GPS in questa direzione"
                        else -> "${mezzi.size} vetture GPS in questa direzione"
                    } + when (altre.size) {
                        0 -> ""
                        1 -> " · 1 nell'altra (in grigio)"
                        else -> " · ${altre.size} nell'altra (in grigio)"
                    } + if (aggiornatoAlle > 0) " · ${ora(aggiornatoAlle)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            fermataVicina?.let { (f, metri) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { apriFermata(f.id) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(BluPosizione, CircleShape),
                    )
                    Text(
                        if (metri > 3_000) "Sei a ${testoMetri(metri)} da questa linea"
                        else "Sei a ${testoMetri(metri)} da ${f.nome}, la sua fermata più vicina ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = BluPosizione,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(fermate, key = { i, f -> "$i-${f.id}" }) { i, f ->
                    RigaPercorso(
                        f,
                        prima = i == 0,
                        ultima = i == fermate.lastIndex,
                        attesa = fermataAttesa?.id == f.id,
                    ) { apriFermata(f.id) }
                }
            }
        }
    }
}

/** Scheda sopra la mappa: quale vettura, da quanto e' stata rilevata, quando arriva. */
@Composable
private fun SchedaVettura(
    mezzo: MezzoRT,
    nomeLinea: String,
    attesa: Boolean,
    altraDirezione: Boolean,
    mostraQuellaDirezione: () -> Unit,
    distanzaPercorso: Double?,
    fermata: Fermata?,
    arrivo: Long?,
    adesso: Long,
    modifier: Modifier,
) {
    val eta = etaRilevamento(mezzo.rilevato, adesso)
    val vecchia = mezzo.rilevato > 0 && adesso - mezzo.rilevato > SECONDI_POSIZIONE_VECCHIA
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (attesa) Porpora else Oro, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    nomeLinea,
                    color = if (attesa) Color.White else Inchiostro,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (attesa) "La tua vettura" else "Vettura") +
                        (if (mezzo.etichetta.isNotBlank()) " ${mezzo.etichetta}" else ""),
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FonteTempo(tracciato = true)
                    Text(
                        if (eta != null) " posizione rilevata $eta" else " posizione in tempo reale",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vecchia) Porpora else Secondario,
                    )
                }
                if (vecchia) {
                    Text(
                        "Il segnale è vecchio: la vettura potrebbe essere più avanti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Porpora,
                    )
                }
                if (distanzaPercorso != null) {
                    Text(
                        "È a ${testoMetri(distanzaPercorso)} dal percorso della linea: forse è in deviazione, " +
                            "in rimessa o sta andando al capolinea.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Porpora,
                    )
                }
                if (altraDirezione) {
                    Text(
                        "Va nell'altra direzione · mostra quella direzione ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = Porpora,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = mostraQuellaDirezione),
                    )
                }
                if (attesa && fermata != null) {
                    val metri = distanzaMetri(mezzo.lat, mezzo.lon, fermata.lat, fermata.lon)
                    val quando = arrivo?.let { " " + testoMinuti(it, adesso).let { t -> if (t == "in arrivo") t else "tra $t" } } ?: ""
                    Text(
                        "Arriva a ${fermata.nome}$quando · a ${testoMetri(metri)} in linea d'aria",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** La corsa attesa non ha una vettura con il GPS: il tempo e' quello dell'orario. */
@Composable
private fun SchedaSenzaGps(fermata: Fermata?, arrivo: Long?, adesso: Long, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FonteTempo(tracciato = false)
                Text(
                    "  La tua corsa non ha una posizione GPS",
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Il tempo d'attesa" + (fermata?.let { " a ${it.nome}" } ?: "") +
                    (arrivo?.let { " (" + testoMinuti(it, adesso) + ")" } ?: "") +
                    " è calcolato sull'orario programmato: la vettura potrebbe non essere ancora partita.",
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
    }
}

/** Una fermata nell'elenco del percorso, con la linea porpora a sinistra. */
@Composable
private fun RigaPercorso(fermata: Fermata, prima: Boolean, ultima: Boolean, attesa: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (attesa) SuPorpora else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(24.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(4.dp)
                        .weight(1f)
                        .background(if (prima) Color.Transparent else Porpora),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .width(4.dp)
                        .background(if (ultima) Color.Transparent else Porpora),
                )
            }
            Box(
                Modifier
                    .size(if (attesa) 18.dp else 14.dp)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(if (attesa) Oro else Porpora, CircleShape),
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                fermata.nome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (attesa) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "Palina ${fermata.codice}" + if (attesa) " · la tua fermata" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
    }
}

private fun cerchio(riempimento: Int, bordo: Int, lato: Int): Drawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(riempimento)
        setStroke(maxOf(2, lato / 6), bordo)
        setSize(lato, lato)
    }

/** Mappa OpenStreetMap con tracciato, fermate e vetture in tempo reale. */
@Composable
private fun MappaLinea(
    nomeLinea: String,
    forma: List<Punto>,
    formaAltra: List<Punto>,
    fuoriPercorso: Set<String>,
    fermate: List<Fermata>,
    mezzi: List<MezzoRT>,
    altre: List<MezzoRT>,
    evidenziato: MezzoRT?,
    fermataAttesa: Fermata?,
    adesso: Long,
    apriFermata: (String) -> Unit,
    seleziona: (MezzoRT) -> Unit,
    miaPosizione: Location?,
    richiestaCentra: Int,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val densita = LocalDensity.current.density
    val mappa = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(41.9028, 12.4964))
        }
    }
    val iconaFermata = remember { cerchio(android.graphics.Color.WHITE, Porpora.toArgb(), (12 * densita).toInt()) }
    val iconaAttesa = remember { cerchio(Oro.toArgb(), Porpora.toArgb(), (20 * densita).toInt()) }

    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val osservatore = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_RESUME -> mappa.onResume()
                Lifecycle.Event.ON_PAUSE -> mappa.onPause()
                else -> Unit
            }
        }
        ciclo.addObserver(osservatore)
        onDispose {
            ciclo.removeObserver(osservatore)
            mappa.onDetach()
        }
    }

    AndroidView(factory = { mappa }, modifier = modifier, update = { m ->
        m.overlays.clear()
        if (formaAltra.size >= 2) {
            m.overlays.add(Polyline(m).apply {
                setPoints(formaAltra.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Secondario.copy(alpha = 0.55f).toArgb()
                outlinePaint.strokeWidth = 3 * densita
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(6 * densita, 6 * densita), 0f)
            })
        }
        if (forma.size >= 2) {
            m.overlays.add(Polyline(m).apply {
                setPoints(forma.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Porpora.copy(alpha = 0.75f).toArgb()
                outlinePaint.strokeWidth = 5 * densita
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        fermate.forEach { f ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(f.lat, f.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = if (f.id == fermataAttesa?.id) iconaAttesa else iconaFermata
                title = f.nome
                setOnMarkerClickListener { _, _ ->
                    apriFermata(f.id)
                    true
                }
            })
        }
        miaPosizione?.let { disegnaMiaPosizione(m, it, densita) }
        // le vetture sopra a tutto; quella attesa per ultima, cosi' resta in cima
        // prima quelle dell'altra direzione, in grigio e sotto alle altre
        altre.filter { it.chiave() != evidenziato?.chiave() }.forEach { v ->
            val vecchia = (v.rilevato > 0 && adesso - v.rilevato > SECONDI_POSIZIONE_VECCHIA) || v.chiave() in fuoriPercorso
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(v.lat, v.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = iconaVettura(m.context.resources, nomeLinea, v.rotta, false, vecchia, densita, altraDirezione = true)
                setOnMarkerClickListener { _, _ ->
                    seleziona(v)
                    true
                }
            })
        }
        val vetture = mezzi.filter { it.chiave() != evidenziato?.chiave() } + listOfNotNull(evidenziato)
        vetture.forEach { v ->
            val attesa = v.chiave() == evidenziato?.chiave()
            val vecchia = (v.rilevato > 0 && adesso - v.rilevato > SECONDI_POSIZIONE_VECCHIA) || v.chiave() in fuoriPercorso
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(v.lat, v.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = iconaVettura(m.context.resources, nomeLinea, v.rotta, attesa, vecchia, densita)
                setOnMarkerClickListener { _, _ ->
                    seleziona(v)
                    true
                }
            })
        }
        m.overlays.add(CopyrightOverlay(m.context))
        m.invalidate()
    })

    // inquadra il tracciato; quando compare la vettura attesa, inquadra lei e la fermata
    var inquadrataVettura by remember { mutableStateOf(false) }
    // centra su di te quando si preme il pulsante (anche se la posizione arriva dopo)
    var centrataPer by remember { mutableIntStateOf(0) }
    LaunchedEffect(richiestaCentra, miaPosizione != null) {
        val p = miaPosizione ?: return@LaunchedEffect
        if (richiestaCentra > centrataPer) {
            centrataPer = richiestaCentra
            centraSu(mappa, p)
        }
    }

    LaunchedEffect(forma, evidenziato != null) {
        val punti = if (evidenziato != null && fermataAttesa != null && !inquadrataVettura) {
            inquadrataVettura = true
            listOf(GeoPoint(evidenziato.lat, evidenziato.lon), GeoPoint(fermataAttesa.lat, fermataAttesa.lon))
        } else if (!inquadrataVettura) {
            forma.map { GeoPoint(it.lat, it.lon) }
        } else {
            emptyList()
        }
        if (punti.size < 2) return@LaunchedEffect
        val riquadro = BoundingBox.fromGeoPoints(punti)
        val margine = (56 * densita).toInt()
        fun inquadra() {
            if (riquadro.latitudeSpan < 0.002 && riquadro.longitudeSpanWithDateLine < 0.002) {
                mappa.controller.setZoom(17.0)
                mappa.controller.setCenter(riquadro.centerWithDateLine)
            } else {
                mappa.zoomToBoundingBox(riquadro, false, margine)
            }
        }
        if (mappa.width > 0 && mappa.height > 0) {
            inquadra()
        } else {
            mappa.addOnFirstLayoutListener { _, _, _, _, _ -> inquadra() }
        }
    }
}

/** Oltre questa distanza dal tracciato una vettura si segnala come fuori percorso. */
private const val METRI_FUORI_PERCORSO = 250.0

/** Per riconoscere una vettura anche quando il feed non ne indica la corsa. */
private fun MezzoRT.chiave(): String = corsaId.ifEmpty { "vettura:$etichetta" }
