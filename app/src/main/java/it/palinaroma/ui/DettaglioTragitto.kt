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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import it.palinaroma.dati.Orario
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.Tappa
import it.palinaroma.dati.Tragitto
import it.palinaroma.dati.Tratto
import it.palinaroma.dati.completaMezzi
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.etaRilevamento
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.mezzoDellaCorsa
import it.palinaroma.dati.testoDurata
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

@Composable
fun DettaglioTragittoSchermo(indice: Int, indietro: () -> Unit, apriFermata: (String) -> Unit) {
    val esito = StatoTragitto.esito as? EsitoTragitti.Pronto
    val tragitto = esito?.let { if (indice < 0) it.soloAPiedi else it.tragitti.getOrNull(indice) }

    // le vetture dei bus e tram del tragitto, se Roma Mobilita' le segue via GPS
    var mezzi by remember { mutableStateOf<List<MezzoRT>>(emptyList()) }
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val ciclo = LocalLifecycleOwner.current
    LaunchedEffect(tragitto) {
        if (tragitto == null || tragitto.mezzi.isEmpty()) return@LaunchedEffect
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
                    mezzi = completaMezzi(TempoReale.mezzi(), corse)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // senza rete restano le ultime posizioni
                }
                adesso = System.currentTimeMillis() / 1000
                delay(15_000)
            }
        }
    }
    // "sei qui" sulla mappa del tragitto
    val contesto = LocalContext.current
    var permessoPosizione by remember { mutableStateOf(haPermessoPosizione(contesto)) }
    var richiestaCentra by remember { mutableIntStateOf(0) }
    val chiediPermesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permessoPosizione = esiti.values.any { it }
        if (permessoPosizione) richiestaCentra++
    }
    val miaPosizione = rememberMiaPosizione(permessoPosizione)

    val vetture: Map<Int, MezzoRT> = remember(tragitto, mezzi) {
        tragitto?.tratti.orEmpty().withIndex()
            .filter { !it.value.aPiedi }
            .mapNotNull { (i, t) -> mezzoDellaCorsa(mezzi, t.corsaId)?.let { i to it } }
            .toMap()
    }

    Scaffold(
        topBar = {
            BarraPorpora(
                titolo = tragitto?.let { "${ora(it.partenza)} – ${ora(it.arrivo)}" } ?: "Tragitto",
                sottotitolo = tragitto?.let {
                    testoDurata(it.durata) + when (it.cambi) {
                        0 -> if (it.mezzi.isEmpty()) " a piedi" else " · nessun cambio"
                        1 -> " · 1 cambio"
                        else -> " · ${it.cambi} cambi"
                    }
                },
                indietro = indietro,
            )
        },
    ) { margini ->
        if (tragitto == null) {
            Text(
                "Questo tragitto non è più disponibile: torna indietro e cercalo di nuovo.",
                color = Secondario,
                modifier = Modifier
                    .padding(margini)
                    .padding(24.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.9f),
            ) {
                MappaTragitto(
                    tragitto,
                    vetture.map { (i, v) -> tragitto.tratti[i] to v },
                    adesso,
                    miaPosizione,
                    richiestaCentra,
                    Modifier.fillMaxSize(),
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
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1.1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item { RigaEstremo(true, tragitto.tratti.first().da.nome, tragitto.partenza) }
                tragitto.tratti.forEachIndexed { i, tratto ->
                    item(key = "tratto-$i") {
                        if (tratto.aPiedi) BloccoAPiedi(tratto) else BloccoMezzo(tratto, vetture[i], adesso, apriFermata)
                    }
                }
                item { RigaEstremo(false, tragitto.tratti.last().a.nome, tragitto.arrivo) }
                item { Attribuzione() }
            }
        }
    }
}

/** "Parti da Casa alle 19:12" / "Arrivo a Colosseo alle 19:45". */
@Composable
private fun RigaEstremo(partenza: Boolean, nome: String, istante: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { PallinoTappa(partenza, 18.dp) }
        Text(
            (if (partenza) "Parti da " else "Arrivi a ") + nome,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(ora(istante), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BloccoAPiedi(t: Tratto) {
    var aperto by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp),
    ) {
        // puntini verticali
        Box(
            Modifier
                .width(24.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight(),
            ) {
                drawLine(
                    color = Secondario,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height),
                    strokeWidth = size.width,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(size.width, size.width * 1.6f)),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconaPiedi(lato = 18.dp)
                Text(
                    "A piedi · " + testoDurata(t.durata) + (t.metri?.takeIf { it > 0 }?.let { " · " + testoMetri(it) } ?: ""),
                    color = Secondario,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (t.passi.size > 1) {
                TextButton(onClick = { aperto = !aperto }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (aperto) "Nascondi le indicazioni" else "Indicazioni a piedi", color = Porpora)
                    Icon(
                        if (aperto) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Porpora,
                    )
                }
                if (aperto) {
                    t.passi.forEach { p ->
                        Text(
                            p.testo + if (p.metri >= 10) " · " + testoMetri(p.metri) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Inchiostro,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** La fermata dell'app che corrisponde a una fermata di Transitous, per aprirne i tempi d'attesa. */
private fun fermataLocale(orario: Orario, tappa: Tappa): Fermata? {
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

@Composable
private fun BloccoMezzo(t: Tratto, vettura: MezzoRT?, adesso: Long, apriFermata: (String) -> Unit) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val salita by produceState<Fermata?>(initialValue = null, orario, t) {
        val o = orario
        value = if (o == null) null else withContext(Dispatchers.IO) { runCatching { fermataLocale(o, t.da) }.getOrNull() }
    }
    var aperto by remember { mutableStateOf(false) }
    val colore = t.colore?.let { Color(it) } ?: Porpora

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            Modifier
                .width(24.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(colore, RoundedCornerShape(3.dp)),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgeLinea(t.linea.ifEmpty { t.nomeMezzo }, grande = true)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        if (t.direzione.isNotEmpty()) "${t.nomeMezzo} per ${t.direzione}" else t.nomeMezzo,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (t.gestore.isNotEmpty()) {
                        Text(t.gestore, style = MaterialTheme.typography.bodySmall, color = Secondario)
                    }
                }
            }
            if (t.cancellato) {
                Text("Corsa soppressa", color = Porpora, fontWeight = FontWeight.Bold)
            }
            if (vettura != null) RigaVettura(t, vettura, adesso)
            RigaFermataTragitto("Sali a", t.da, t.partenza, t.partenzaPrevista, t.tempoReale)
            val fermate = t.intermedie.size + 1
            TextButton(
                onClick = { aperto = !aperto },
                enabled = t.intermedie.isNotEmpty(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    (if (fermate == 1) "1 fermata" else "$fermate fermate") + " · " + testoDurata(t.durata),
                    color = if (t.intermedie.isNotEmpty()) Porpora else Secondario,
                )
                if (t.intermedie.isNotEmpty()) {
                    Icon(
                        if (aperto) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Porpora,
                    )
                }
            }
            if (aperto) {
                t.intermedie.forEach { tappa ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            tappa.nome,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        tappa.orario?.let { Text(ora(it), style = MaterialTheme.typography.bodySmall, color = Secondario) }
                    }
                }
            }
            RigaFermataTragitto("Scendi a", t.a, t.arrivo, t.arrivoPrevisto, t.tempoReale)
            t.avvisi.forEach { avviso ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SuPorpora, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Porpora, modifier = Modifier.size(18.dp))
                    Text(avviso, color = Porpora, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
            salita?.let { f ->
                Text(
                    "Tempi d'attesa a ${f.nome} ›",
                    color = Porpora,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { apriFermata(f.id) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/** Dove si trova adesso la vettura di questo tratto. */
@Composable
private fun RigaVettura(t: Tratto, vettura: MezzoRT, adesso: Long) {
    val eta = etaRilevamento(vettura.rilevato, adesso)
    val dove = if (t.partenza / 1000 > adesso && !t.da.lat.isNaN()) {
        val metri = distanzaMetri(vettura.lat, vettura.lon, t.da.lat, t.da.lon)
        "è a ${testoMetri(metri)} dalla fermata"
    } else {
        "è in viaggio"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(SuPorpora, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FonteTempo(true)
        Text(
            "  La vettura" + (if (vettura.etichetta.isNotBlank()) " ${vettura.etichetta}" else "") + " $dove" +
                (eta?.let { " · rilevata $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = Porpora,
        )
    }
}

@Composable
private fun RigaFermataTragitto(azione: String, tappa: Tappa, istante: Long, previsto: Long, tempoReale: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$azione ${tappa.nome}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dettagli = listOfNotNull(
                tappa.codice?.let { "Palina $it" },
                tappa.binario?.let { "Binario $it" },
            )
            if (dettagli.isNotEmpty()) {
                Text(dettagli.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Secondario)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val ritardo = ((istante - previsto) / 60_000).toInt()
            Text(ora(istante), fontWeight = FontWeight.Bold, color = if (tempoReale && ritardo != 0) Porpora else Inchiostro)
            if (tempoReale) {
                Text(
                    when {
                        ritardo > 0 -> "+$ritardo min"
                        ritardo < 0 -> "$ritardo min"
                        else -> "in orario"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ritardo != 0) Porpora else Secondario,
                )
            } else {
                FonteTempo(false)
            }
        }
    }
}

private fun cerchio(riempimento: Int, bordo: Int, lato: Int): Drawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(riempimento)
        setStroke(maxOf(2, lato / 5), bordo)
        setSize(lato, lato)
    }

/** Il tragitto sulla mappa: mezzi a tinta piena, tratti a piedi tratteggiati. */
@Composable
private fun MappaTragitto(
    tragitto: Tragitto,
    vetture: List<Pair<Tratto, MezzoRT>>,
    adesso: Long,
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
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(41.9028, 12.4964))
        }
    }
    val iconaPartenza = remember { cerchio(android.graphics.Color.WHITE, Porpora.toArgb(), (18 * densita).toInt()) }
    val iconaArrivo = remember { cerchio(Oro.toArgb(), Inchiostro.toArgb(), (20 * densita).toInt()) }
    val iconaFermata = remember { cerchio(android.graphics.Color.WHITE, Inchiostro.toArgb(), (11 * densita).toInt()) }

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

    val tracciati = remember(tragitto) {
        tragitto.tratti.map { t ->
            val punti = if (t.forma.size >= 2) {
                t.forma.map { GeoPoint(it.lat, it.lon) }
            } else {
                (listOf(t.da) + t.intermedie + listOf(t.a))
                    .filter { !it.lat.isNaN() && !it.lon.isNaN() }
                    .map { GeoPoint(it.lat, it.lon) }
            }
            t to punti
        }
    }

    AndroidView(factory = { mappa }, modifier = modifier, update = { m ->
        m.overlays.clear()
        tracciati.forEach { (t, punti) ->
            if (punti.size < 2) return@forEach
            m.overlays.add(Polyline(m).apply {
                setPoints(punti)
                outlinePaint.strokeCap = Paint.Cap.ROUND
                if (t.aPiedi) {
                    outlinePaint.color = Secondario.toArgb()
                    outlinePaint.strokeWidth = 4 * densita
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(2 * densita, 8 * densita), 0f)
                } else {
                    outlinePaint.color = t.colore ?: Porpora.toArgb()
                    outlinePaint.strokeWidth = 6 * densita
                }
            })
        }
        tragitto.mezzi.forEach { t ->
            listOf(t.da, t.a).filter { !it.lat.isNaN() && !it.lon.isNaN() }.forEach { tappa ->
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(tappa.lat, tappa.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = iconaFermata
                    title = tappa.nome
                })
            }
        }
        val inizio = tragitto.tratti.first().da
        val fine = tragitto.tratti.last().a
        listOf(inizio to iconaPartenza, fine to iconaArrivo).forEach { (tappa, icona) ->
            if (tappa.lat.isNaN() || tappa.lon.isNaN()) return@forEach
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(tappa.lat, tappa.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = icona
                title = tappa.nome
            })
        }
        miaPosizione?.let { disegnaMiaPosizione(m, it, densita) }
        // le vetture in tempo reale, sopra a tutto
        vetture.forEach { (t, v) ->
            val vecchia = v.rilevato > 0 && adesso - v.rilevato > SECONDI_POSIZIONE_VECCHIA
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(v.lat, v.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = iconaVettura(m.context.resources, t.linea.ifEmpty { t.nomeMezzo }, v.rotta, true, vecchia, densita)
                title = "Vettura " + v.etichetta
            })
        }
        m.overlays.add(CopyrightOverlay(m.context))
        m.invalidate()
    })

    var centrataPer by remember { mutableIntStateOf(0) }
    LaunchedEffect(richiestaCentra, miaPosizione != null) {
        val p = miaPosizione ?: return@LaunchedEffect
        if (richiestaCentra > centrataPer) {
            centrataPer = richiestaCentra
            centraSu(mappa, p)
        }
    }

    LaunchedEffect(tracciati) {
        val tutti = tracciati.flatMap { it.second }
        if (tutti.isEmpty()) return@LaunchedEffect
        val riquadro = BoundingBox.fromGeoPoints(tutti)
        val margine = (36 * densita).toInt()
        fun inquadra() {
            if (riquadro.latitudeSpan < 0.0005 && riquadro.longitudeSpanWithDateLine < 0.0005) {
                mappa.controller.setZoom(17.0)
                mappa.controller.setCenter(riquadro.centerWithDateLine)
            } else {
                mappa.zoomToBoundingBox(riquadro, false, margine)
            }
        }
        if (mappa.width > 0 && mappa.height > 0) inquadra() else mappa.addOnFirstLayoutListener { _, _, _, _, _ -> inquadra() }
    }
}
