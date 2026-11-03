@file:OptIn(ExperimentalMaterial3Api::class)

package it.palinaroma.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import it.palinaroma.dati.mezziDellaLinea
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

@Composable
fun LineaSchermo(lineaId: String, direzioneIniziale: Int?, indietro: () -> Unit, apriFermata: (String) -> Unit) {
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

    var mezzi by remember { mutableStateOf<List<MezzoRT>>(emptyList()) }
    val ciclo = LocalLifecycleOwner.current
    LaunchedEffect(lineaId, percorso?.direzione, o) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val tutti = TempoReale.mezzi()
                    mezzi = withContext(Dispatchers.IO) { mezziDellaLinea(tutti, lineaId, percorso?.direzione, o) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // senza rete restano gli ultimi mezzi noti
                }
                delay(15_000)
            }
        }
    }

    Scaffold(
        topBar = {
            BarraPorpora(
                titolo = "Linea ${linea?.nome ?: lineaId}",
                sottotitolo = percorso?.capolinea?.takeIf { it.isNotEmpty() }?.let { "verso $it" }
                    ?: linea?.descrizione,
                indietro = indietro,
            )
        },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            if (percorsi.size > 1) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    percorsi.forEach { p ->
                        FilterChip(
                            selected = p.direzione == percorso?.direzione,
                            onClick = { direzione = p.direzione },
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
            MappaLinea(
                forma = forma,
                fermate = fermate,
                mezzi = mezzi,
                apriFermata = apriFermata,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Text(
                when (mezzi.size) {
                    0 -> "Nessun mezzo tracciato in questa direzione"
                    1 -> "1 mezzo in servizio in questa direzione"
                    else -> "${mezzi.size} mezzi in servizio in questa direzione"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(fermate, key = { i, f -> "$i-${f.id}" }) { i, f ->
                    RigaPercorso(f, prima = i == 0, ultima = i == fermate.lastIndex) { apriFermata(f.id) }
                }
            }
        }
    }
}

/** Una fermata nell'elenco del percorso, con la linea porpora a sinistra. */
@Composable
private fun RigaPercorso(fermata: Fermata, prima: Boolean, ultima: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
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
                    .size(14.dp)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(Porpora, CircleShape),
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(fermata.nome, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Palina ${fermata.codice}", style = MaterialTheme.typography.bodySmall, color = Secondario)
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

/** Mappa OpenStreetMap con tracciato, fermate e mezzi in tempo reale. */
@Composable
private fun MappaLinea(
    forma: List<Punto>,
    fermate: List<Fermata>,
    mezzi: List<MezzoRT>,
    apriFermata: (String) -> Unit,
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
    val iconaMezzo = remember { cerchio(Oro.toArgb(), Inchiostro.toArgb(), (22 * densita).toInt()) }

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
        if (forma.size >= 2) {
            m.overlays.add(Polyline(m).apply {
                setPoints(forma.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Porpora.toArgb()
                outlinePaint.strokeWidth = 5 * densita
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
        }
        fermate.forEach { f ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(f.lat, f.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = iconaFermata
                title = f.nome
                setOnMarkerClickListener { _, _ ->
                    apriFermata(f.id)
                    true
                }
            })
        }
        mezzi.forEach { v ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(v.lat, v.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = iconaMezzo
                title = if (v.etichetta.isNotBlank()) "Vettura ${v.etichetta}" else "Vettura in servizio"
            })
        }
        m.overlays.add(CopyrightOverlay(m.context))
        m.invalidate()
    })

    LaunchedEffect(forma) {
        if (forma.size < 2) return@LaunchedEffect
        val riquadro = BoundingBox.fromGeoPoints(forma.map { GeoPoint(it.lat, it.lon) })
        val margine = (32 * densita).toInt()
        if (mappa.width > 0 && mappa.height > 0) {
            mappa.zoomToBoundingBox(riquadro, false, margine)
        } else {
            mappa.addOnFirstLayoutListener { _, _, _, _, _ -> mappa.zoomToBoundingBox(riquadro, false, margine) }
        }
    }
}
