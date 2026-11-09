package it.palinaroma.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** Una posizione GPS piu' vecchia di cosi' viene disegnata sbiadita. */
const val SECONDI_POSIZIONE_VECCHIA = 180L

private val cacheIcone = HashMap<String, Drawable>()

/**
 * Segnaposto di una vettura: cerchio con il numero di linea, bordo bianco,
 * alone e una freccia che indica la direzione di marcia (se nota).
 * Quella evidenziata e' piu' grande e porpora.
 */
fun iconaVettura(
    risorse: Resources,
    linea: String,
    rotta: Float?,
    evidenziata: Boolean,
    vecchia: Boolean,
    densita: Float,
    altraDirezione: Boolean = false,
): Drawable {
    val rottaArrotondata = rotta?.let { (it / 10f).roundToInt() * 10 }
    val chiave = "$linea|$rottaArrotondata|$evidenziata|$vecchia|$densita|$altraDirezione"
    cacheIcone[chiave]?.let { return it }
    if (cacheIcone.size > 300) cacheIcone.clear()

    val diametro = (if (evidenziata) 44f else if (altraDirezione) 28f else 34f) * densita
    val freccia = (if (evidenziata) 11f else if (altraDirezione) 7f else 9f) * densita
    val alone = (if (evidenziata) 14f else if (altraDirezione) 0f else 6f) * densita
    val margine = max(freccia, alone) + 3 * densita
    val lato = (diametro + 2 * margine).toInt()
    val immagine = Bitmap.createBitmap(lato, lato, Bitmap.Config.ARGB_8888)
    val tela = Canvas(immagine)
    val cx = lato / 2f
    val cy = lato / 2f
    val raggio = diametro / 2f
    val pennello = Paint(Paint.ANTI_ALIAS_FLAG)

    // alone
    if (alone > 0f) {
        pennello.color = (if (evidenziata) Porpora else Inchiostro).copy(alpha = if (evidenziata) 0.28f else 0.2f).toArgb()
        tela.drawCircle(cx, cy, raggio + alone, pennello)
    }

    // freccia di marcia, ruotata secondo la rotta (0 = nord)
    if (rottaArrotondata != null) {
        tela.save()
        tela.rotate(rottaArrotondata.toFloat(), cx, cy)
        val punta = Path().apply {
            moveTo(cx, cy - raggio - freccia)
            lineTo(cx - freccia, cy - raggio + 3 * densita)
            lineTo(cx + freccia, cy - raggio + 3 * densita)
            close()
        }
        pennello.color = when {
            evidenziata -> Porpora.toArgb()
            altraDirezione -> Secondario.toArgb()
            else -> Inchiostro.toArgb()
        }
        tela.drawPath(punta, pennello)
        tela.restore()
    }

    // bordo bianco e cerchio
    pennello.color = android.graphics.Color.WHITE
    tela.drawCircle(cx, cy, raggio, pennello)
    pennello.color = when {
        evidenziata -> Porpora.toArgb()
        altraDirezione -> android.graphics.Color.rgb(0xCF, 0xC9, 0xC2)
        else -> Oro.toArgb()
    }
    tela.drawCircle(cx, cy, raggio - 3 * densita, pennello)

    // numero di linea, rimpicciolito se non ci sta
    pennello.color = if (evidenziata) android.graphics.Color.WHITE else Inchiostro.toArgb()
    pennello.typeface = Typeface.DEFAULT_BOLD
    pennello.textAlign = Paint.Align.CENTER
    var dimensione = (if (evidenziata) 17f else if (altraDirezione) 12f else 14f) * densita
    pennello.textSize = dimensione
    val spazio = (raggio - 5 * densita) * 2
    val testo = linea.ifEmpty { "•" }
    while (pennello.measureText(testo) > spazio && dimensione > 7 * densita) {
        dimensione -= 0.5f * densita
        pennello.textSize = dimensione
    }
    val misure = pennello.fontMetrics
    tela.drawText(testo, cx, cy - (misure.ascent + misure.descent) / 2, pennello)

    val icona = BitmapDrawable(risorse, immagine)
    if (vecchia || altraDirezione) icona.alpha = if (vecchia) 130 else 215
    cacheIcone[chiave] = icona
    return icona
}

/** Pallino porpora "GPS" oppure scritta grigia "da orario". */
@Composable
fun FonteTempo(tracciato: Boolean, modifier: Modifier = Modifier) {
    if (tracciato) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Porpora, CircleShape),
            )
            Text("GPS", style = MaterialTheme.typography.labelSmall, color = Porpora, fontWeight = FontWeight.Bold)
        }
    } else {
        Text("da orario", style = MaterialTheme.typography.labelSmall, color = Secondario, modifier = modifier)
    }
}
