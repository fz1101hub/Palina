package it.palinaroma.ui

import android.graphics.drawable.GradientDrawable
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.aggiornamentiPosizione
import it.palinaroma.dati.precisione
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Il blu del segnalino "sei qui", diverso dai colori di vetture e fermate. */
val BluPosizione = Color(0xFF1A73E8)

/**
 * La posizione del telefono aggiornata ogni pochi secondi, solo mentre la
 * schermata e' visibile e [attiva] vale true (cioe' c'e' il permesso).
 */
@Composable
fun rememberMiaPosizione(attiva: Boolean): Location? {
    val context = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    var posizione by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(attiva) {
        if (!attiva) return@LaunchedEffect
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            aggiornamentiPosizione(context).collect { posizione = it }
        }
    }
    return posizione
}

/** Aggiunge alla mappa il cerchio di precisione e il pallino blu "sei qui". */
fun disegnaMiaPosizione(mappa: MapView, posizione: Location, densita: Float) {
    val centro = GeoPoint(posizione.latitude, posizione.longitude)
    val metri = posizione.precisione().toDouble().coerceIn(5.0, 2_000.0)
    mappa.overlays.add(Polygon(mappa).apply {
        setPoints(Polygon.pointsAsCircle(centro, metri))
        fillPaint.color = BluPosizione.copy(alpha = 0.14f).toArgb()
        outlinePaint.color = BluPosizione.copy(alpha = 0.45f).toArgb()
        outlinePaint.strokeWidth = 1.5f * densita
        setOnClickListener { _, _, _ -> false }
    })
    val lato = (18 * densita).toInt()
    mappa.overlays.add(Marker(mappa).apply {
        position = centro
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BluPosizione.toArgb())
            setStroke((3 * densita).toInt(), android.graphics.Color.WHITE)
            setSize(lato, lato)
        }
        title = "Sei qui"
        setOnMarkerClickListener { _, _ -> true }
    })
}

/** Porta la mappa sulla posizione del telefono. */
fun centraSu(mappa: MapView, posizione: Location) {
    val zoom = maxOf(mappa.zoomLevelDouble, 16.0)
    mappa.controller.animateTo(GeoPoint(posizione.latitude, posizione.longitude), zoom, 600L)
}

/** Pulsante tondo col mirino: centra la mappa su di te. */
@Composable
fun PulsanteMiaPosizione(attivo: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = Color.White,
        contentColor = if (attivo) BluPosizione else Secondario,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
        modifier = modifier.semantics { contentDescription = "Mostra dove sono" },
    ) {
        val colore = if (attivo) BluPosizione else Secondario
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            val tratto = 2.dp.toPx()
            drawCircle(colore, radius = r * 0.55f, center = c, style = Stroke(width = tratto))
            drawCircle(colore, radius = r * 0.22f, center = c)
            drawLine(colore, Offset(c.x, 0f), Offset(c.x, c.y - r * 0.55f), strokeWidth = tratto)
            drawLine(colore, Offset(c.x, c.y + r * 0.55f), Offset(c.x, size.height), strokeWidth = tratto)
            drawLine(colore, Offset(0f, c.y), Offset(c.x - r * 0.55f, c.y), strokeWidth = tratto)
            drawLine(colore, Offset(c.x + r * 0.55f, c.y), Offset(size.width, c.y), strokeWidth = tratto)
        }
    }
}
