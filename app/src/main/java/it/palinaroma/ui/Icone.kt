package it.palinaroma.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * La puntina per fissare una linea in cima alla fermata: piena quando la linea
 * e' fissata, solo contorno quando non lo e'. Il colore lo decide chi la usa (tint).
 */
private fun puntina(piena: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (piena) "PuntinaPiena" else "Puntina",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = if (piena) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 3f)
            lineTo(15f, 3f)
            lineTo(14f, 9f)
            lineTo(18f, 13f)
            lineTo(6f, 13f)
            lineTo(10f, 9f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12f, 13f)
            lineTo(12f, 21f)
        }
    }.build()

val PuntinaPiena: ImageVector = puntina(piena = true)
val PuntinaVuota: ImageVector = puntina(piena = false)
