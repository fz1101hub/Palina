@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.palinaroma.dati.Fermata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Porpora (Pantone 202 C) e oro (Pantone 130 C) di Roma, come il tabellone di Alexa
val Porpora = Color(0xFF862633)
val Oro = Color(0xFFF2A900)
val Travertino = Color(0xFFF2EFEA)
val Inchiostro = Color(0xFF1C1A1A)
val Secondario = Color(0xFF5F5A57)
val SuPorpora = Color(0xFFF1D9DC)

private val Colori = lightColorScheme(
    primary = Porpora,
    onPrimary = Color.White,
    primaryContainer = SuPorpora,
    onPrimaryContainer = Porpora,
    secondary = Oro,
    onSecondary = Inchiostro,
    secondaryContainer = Color(0xFFFFE7B0),
    onSecondaryContainer = Inchiostro,
    background = Travertino,
    onBackground = Inchiostro,
    surface = Color.White,
    onSurface = Inchiostro,
    surfaceVariant = Color(0xFFE8E3DD),
    onSurfaceVariant = Secondario,
    outline = Color(0xFFB9B1AA),
)

@Composable
fun TemaPalina(contenuto: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colori, content = contenuto)
}

/** Barra in alto porpora con il filo oro sotto. */
@Composable
fun BarraPorpora(
    titolo: String,
    sottotitolo: String? = null,
    indietro: (() -> Unit)? = null,
    azioni: @Composable RowScope.() -> Unit = {},
) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(titolo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!sottotitolo.isNullOrBlank()) {
                        Text(
                            sottotitolo,
                            style = MaterialTheme.typography.bodySmall,
                            color = SuPorpora,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                if (indietro != null) {
                    IconButton(onClick = indietro) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
            },
            actions = azioni,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Porpora,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Oro),
        )
    }
}

/** Il numero della linea nel riquadro oro. */
@Composable
fun BadgeLinea(nome: String, modifier: Modifier = Modifier, grande: Boolean = false) {
    Box(
        modifier
            .background(Oro, RoundedCornerShape(if (grande) 8.dp else 5.dp))
            .widthIn(min = if (grande) 60.dp else 36.dp)
            .padding(horizontal = 8.dp, vertical = if (grande) 8.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nome,
            color = Inchiostro,
            fontWeight = FontWeight.Bold,
            fontSize = if (grande) 20.sp else 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun Intestazione(testo: String) {
    Text(
        testo.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Secondario,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** Una fermata in un elenco, con le linee che ci passano. */
@Composable
fun RigaFermata(fermata: Fermata, distanza: Double?, linee: List<String>, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fermata.nome,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (distanza != null) {
                Text("${distanza.roundToInt()} m", color = Secondario, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Palina ${fermata.codice}", color = Secondario, style = MaterialTheme.typography.bodySmall)
        if (linee.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                linee.take(14).forEach { BadgeLinea(it) }
            }
        }
    }
    HorizontalDivider(color = Color(0xFFE2DDD8))
}

/** "in arrivo" oppure "7 min". */
fun testoMinuti(orario: Long, adesso: Long): String {
    val minuti = ((orario - adesso) / 60).toInt()
    return if (minuti < 1) "in arrivo" else "$minuti min"
}

fun ora(millisecondi: Long): String = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(millisecondi))

fun giorno(millisecondi: Long): String = SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(Date(millisecondi))
