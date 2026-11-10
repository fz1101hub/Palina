package it.palinaroma.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.aggiornamentiPosizione
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.precisione
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Il blu del segnalino "sei qui", diverso dai colori di vetture e fermate. */
val BluPosizione = Color(0xFF1A73E8)

/** Lo stesso blu, piu' scuro: per i testi piccoli, che cosi' si leggono bene sul fondo chiaro. */
val BluPosizioneTesto = Color(0xFF1558B0)

val PERMESSI_POSIZIONE = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * Il permesso di posizione, riletto ogni volta che si torna sulla schermata:
 * cosi' vale subito anche se e' stato dato dalle impostazioni del telefono.
 */
@Composable
fun rememberPermessoPosizione(): MutableState<Boolean> {
    val context = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    val stato = remember { mutableStateOf(haPermessoPosizione(context)) }
    LaunchedEffect(ciclo) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) { stato.value = haPermessoPosizione(context) }
    }
    return stato
}

/**
 * Dopo un rifiuto: true se Android non mostrera' piu' la richiesta e il permesso
 * si puo' dare solo dalle impostazioni dell'app.
 */
fun permessoNegatoPerSempre(context: Context): Boolean {
    val attivita = context as? Activity ?: return false
    return PERMESSI_POSIZIONE.none { ActivityCompat.shouldShowRequestPermissionRationale(attivita, it) }
}

/** Apre la pagina dell'app nelle impostazioni del telefono, dove si danno i permessi. */
fun apriImpostazioniApp(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

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

/**
 * Tutto quello che serve a una mappa per il "sei qui": la posizione, quante
 * volte e' stato chiesto di centrarla, cosa fa il pulsante col mirino e
 * l'eventuale avviso da mostrare (permesso negato, posizione che non arriva).
 */
class SeiQui(
    val posizione: Location?,
    val richiestaCentra: Int,
    val avviso: String?,
    val premi: () -> Unit,
    val toccaAvviso: (() -> Unit)?,
)

@Composable
fun rememberSeiQui(): SeiQui {
    val context = LocalContext.current
    var permesso by rememberPermessoPosizione()
    var richiestaCentra by remember { mutableIntStateOf(0) }
    var negatoPerSempre by remember { mutableStateOf(false) }
    val chiedi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permesso = esiti.values.any { it }
        if (permesso) richiestaCentra++ else negatoPerSempre = permessoNegatoPerSempre(context)
    }
    val posizione = rememberMiaPosizione(permesso)

    // se dopo qualche secondo la posizione non arriva lo si dice, invece di non fare nulla
    var attesaLunga by remember { mutableStateOf(false) }
    LaunchedEffect(richiestaCentra, posizione != null) {
        attesaLunga = false
        if (richiestaCentra > 0 && posizione == null) {
            delay(8_000)
            attesaLunga = true
        }
    }

    val avviso = when {
        !permesso && negatoPerSempre -> "Il permesso di posizione è negato: tocca qui per darlo dalle impostazioni"
        permesso && posizione == null && attesaLunga -> "Non trovo la tua posizione: attiva la localizzazione del telefono"
        else -> null
    }
    return SeiQui(
        posizione = posizione,
        richiestaCentra = richiestaCentra,
        avviso = avviso,
        premi = { if (permesso) richiestaCentra++ else chiedi.launch(PERMESSI_POSIZIONE) },
        toccaAvviso = if (!permesso && negatoPerSempre) ({ apriImpostazioniApp(context) }) else null,
    )
}

/** Il pulsante col mirino e, se serve, l'avviso: da mettere nel riquadro della mappa. */
@Composable
fun BoxScope.ComandiSeiQui(seiQui: SeiQui) {
    PulsanteMiaPosizione(
        attivo = seiQui.posizione != null,
        onClick = seiQui.premi,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp),
    )
    seiQui.avviso?.let { testo ->
        val forma = RoundedCornerShape(10.dp)
        val tocco = seiQui.toccaAvviso
        Text(
            testo,
            style = MaterialTheme.typography.bodySmall,
            color = Inchiostro,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 10.dp, end = 66.dp)
                .shadow(3.dp, forma)
                .background(Color.White, forma)
                .then(if (tocco != null) Modifier.clickable(onClick = tocco) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
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
