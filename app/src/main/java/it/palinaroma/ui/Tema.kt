@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.palinaroma.R
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.giornoLungo
import it.palinaroma.dati.oraARoma
import it.palinaroma.dati.pillolaPassaggio
import it.palinaroma.dati.tx
import it.palinaroma.dati.Colori
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.Rete
import it.palinaroma.dati.coloreReteArgb
import it.palinaroma.dati.daLeggere
import it.palinaroma.dati.descrizioneFermata
import it.palinaroma.dati.minutiAttesa
import kotlin.math.roundToInt

// Colori fissi: porpora (Pantone 202 C) e oro (Pantone 130 C) di Roma. Valgono
// uguali con il tema chiaro e con quello scuro (riquadri delle linee, pulsanti
// principali, segnalini sulle mappe, che restano chiare).
val Porpora = Color(Colori.PORPORA)
val Oro = Color(Colori.ORO)

/** Il testo sopra l'oro. */
val Inchiostro = Color(Colori.INCHIOSTRO)

/** I riquadri delle linee delle altre reti: blu Cotral, verde dei regionali Trenitalia. */
val BluCotral = Color(Colori.BLU_COTRAL)
val VerdeTreni = Color(Colori.VERDE_TRENI)

/** Il colore del riquadro di una linea, dalla sua rete. */
fun coloreRete(id: String): Color = Color(coloreReteArgb(id))

/** Sotto il nome di una fermata: "Palina 70078", "Cotral · palina 12345", "Stazione · Trenitalia". */
fun descrizioneFermata(fermata: Fermata): String = descrizioneFermata(fermata, Archivio.orario.value)

/** Il grigio dei disegni sulle mappe (tracciati, vetture dell'altra direzione): le mappe restano chiare. */
val GrigioMappa = Color(0xFF5F5A57)

/** I colori che cambiano tra tema chiaro e scuro. */
@Immutable
class Tavolozza(
    val scuro: Boolean,
    val sfondo: Color,
    val superficie: Color,
    val tabellone: Color,
    val bordo: Color,
    val divisore: Color,
    val testo: Color,
    val tenue: Color,
    /** Cifre dei tempi, titoletti, collegamenti: oro sul nero, ambra scuro sul chiaro. */
    val accento: Color,
    val accentoFondo: Color,
    /** I tempi calcolati sull'orario, senza GPS. */
    val orario: Color,
    val cifreLed: Boolean = true,
)

// Contrasti controllati (WCAG): testo, tenue, accento e orario almeno 4,5:1 su sfondo e superfici.
val TavolozzaScura = Tavolozza(
    scuro = true,
    sfondo = Color(0xFF0E0B0B),
    superficie = Color(0xFF161111),
    tabellone = Color(0xFF050404),
    bordo = Color(0xFF2B2323),
    divisore = Color(0xFF241D1D),
    testo = Color(0xFFF4EFEA),
    tenue = Color(0xFFB9B1AB),
    accento = Oro,
    accentoFondo = Color(0xFF2A1F12),
    orario = Color(0xFFCFC6BE),
)

val TavolozzaChiara = Tavolozza(
    scuro = false,
    sfondo = Color(0xFFF6F2EC),
    superficie = Color.White,
    tabellone = Color.White,
    bordo = Color(0xFFDDD4C9),
    divisore = Color(0xFFE6DFD6),
    testo = Color(0xFF1C1715),
    tenue = Color(0xFF5F5751),
    accento = Color(0xFF8A5A00),
    accentoFondo = Color(0xFFFBEFD3),
    orario = Color(0xFF6A625C),
)

val LocalTavolozza = staticCompositionLocalOf { TavolozzaChiara }

val Sfondo: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.sfondo
val Superficie: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.superficie
val FondoTabellone: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.tabellone
val Bordo: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.bordo
val Divisore: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.divisore
val Testo: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.testo
val Secondario: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.tenue
val Accento: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.accento
val AccentoFondo: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.accentoFondo
val ColoreOrario: Color @Composable @ReadOnlyComposable get() = LocalTavolozza.current.orario

/** Doto: il carattere a punti delle paline elettroniche (SIL Open Font License). */
private val Doto = FontFamily(Font(R.font.doto_nero, FontWeight.Black))

/** Il carattere delle cifre grandi: a punti, o quello normale se lo si e' scelto nelle impostazioni. */
val CarattereCifre: FontFamily
    @Composable @ReadOnlyComposable get() = if (LocalTavolozza.current.cifreLed) Doto else FontFamily.Default

private fun schemaMaterial(t: Tavolozza) = if (t.scuro) {
    darkColorScheme(
        primary = Oro,
        onPrimary = Inchiostro,
        primaryContainer = Color(0xFF3A2A10),
        onPrimaryContainer = Oro,
        secondary = Oro,
        onSecondary = Inchiostro,
        secondaryContainer = Color(0xFF3A2A10),
        onSecondaryContainer = t.testo,
        tertiary = Color(0xFFE8A0AA),
        background = t.sfondo,
        onBackground = t.testo,
        surface = t.sfondo,
        onSurface = t.testo,
        surfaceVariant = Color(0xFF1E1A1A),
        onSurfaceVariant = t.tenue,
        surfaceContainerLowest = t.sfondo,
        surfaceContainerLow = Color(0xFF131010),
        surfaceContainer = t.superficie,
        surfaceContainerHigh = Color(0xFF1A1414),
        surfaceContainerHighest = Color(0xFF1E1A1A),
        inverseSurface = t.testo,
        inverseOnSurface = t.sfondo,
        outline = Color(0xFF7A706A),
        outlineVariant = t.bordo,
    )
} else {
    lightColorScheme(
        primary = t.accento,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF6E0AE),
        onPrimaryContainer = Color(0xFF3A2400),
        secondary = Oro,
        onSecondary = Inchiostro,
        secondaryContainer = t.accentoFondo,
        onSecondaryContainer = t.testo,
        tertiary = Porpora,
        background = t.sfondo,
        onBackground = t.testo,
        surface = t.sfondo,
        onSurface = t.testo,
        surfaceVariant = Color(0xFFEFE9E1),
        onSurfaceVariant = t.tenue,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBF8F4),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF3EEE7),
        surfaceContainerHighest = Color(0xFFEFE9E1),
        inverseSurface = t.testo,
        inverseOnSurface = t.sfondo,
        outline = Color(0xFFA0958A),
        outlineVariant = t.bordo,
    )
}

@Composable
fun TemaPalina(scuro: Boolean, cifreLed: Boolean, contenuto: @Composable () -> Unit) {
    val base = if (scuro) TavolozzaScura else TavolozzaChiara
    val tavolozza = Tavolozza(
        base.scuro, base.sfondo, base.superficie, base.tabellone, base.bordo, base.divisore,
        base.testo, base.tenue, base.accento, base.accentoFondo, base.orario, cifreLed,
    )
    CompositionLocalProvider(LocalTavolozza provides tavolozza) {
        MaterialTheme(colorScheme = schemaMaterial(tavolozza), content = contenuto)
    }
}

/** La barra in alto: piatta, del colore dello sfondo, come il resto del tabellone. */
@Composable
fun BarraSuperiore(
    titolo: String,
    sottotitolo: String? = null,
    indietro: (() -> Unit)? = null,
    azioni: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    titolo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (!sottotitolo.isNullOrBlank()) {
                    Text(
                        sottotitolo,
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondario,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (indietro != null) {
                IconButton(onClick = indietro) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Indietro"))
                }
            }
        },
        actions = azioni,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Sfondo,
            scrolledContainerColor = Sfondo,
            titleContentColor = Testo,
            navigationIconContentColor = Testo,
            actionIconContentColor = Testo,
        ),
    )
}

/** Il numero della linea nel riquadro porpora, come sulle paline. */
@Composable
fun BadgeLinea(nome: String, modifier: Modifier = Modifier, grande: Boolean = false, colore: Color = Porpora) {
    Box(
        modifier
            .background(colore, RoundedCornerShape(if (grande) 8.dp else 6.dp))
            .widthIn(min = if (grande) 48.dp else 36.dp)
            .heightIn(min = if (grande) 36.dp else 24.dp)
            .padding(horizontal = if (grande) 7.dp else 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nome,
            color = Color.White,
            fontWeight = if (grande) FontWeight.Black else FontWeight.Bold,
            fontFamily = if (grande) CarattereCifre else FontFamily.Default,
            fontSize = if (grande) 21.sp else 13.sp,
            maxLines = 1,
        )
    }
}

/** Titoletto di una sezione: maiuscolo, spaziato, nel colore d'accento. */
@Composable
fun Intestazione(testo: String, modifier: Modifier = Modifier, colore: Color = Accento, rientro: Dp = 20.dp) {
    Text(
        testo.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = colore,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = modifier
            .padding(start = rientro, end = rientro, top = 18.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * I minuti d'attesa grandi, con le cifre a punti: "4 min", oppure tx("in arrivo").
 * Oro (o ambra) se la vettura e' seguita col GPS, grigio se il tempo viene dall'orario.
 */
@Composable
fun CifreAttesa(minuti: Int, tracciato: Boolean, modifier: Modifier = Modifier, grandezza: Int = 32) {
    val colore = if (tracciato) Accento else ColoreOrario
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (minuti < 1) {
            Text(tx("in arrivo"), color = colore, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        } else {
            Text(
                minuti.toString(),
                color = colore,
                fontFamily = CarattereCifre,
                fontWeight = FontWeight.Black,
                fontSize = grandezza.sp,
                lineHeight = grandezza.sp,
            )
            Text(tx("min"), color = colore, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Il riquadro scuro (o bianco) con il bordo doppio, come lo schermo di una palina. */
fun Modifier.riquadroTabellone(fondo: Color, bordo: Color): Modifier =
    this
        .clip(RoundedCornerShape(20.dp))
        .background(fondo)
        .border(2.dp, bordo, RoundedCornerShape(20.dp))

/** "64 · 1′" per gli elenchi di fermate: i prossimi passaggi in poco spazio. */
@Composable
fun PillolaAttesa(arrivo: Arrivo, adesso: Long) {
    val primo = arrivo.passaggi.first()
    val fondo = if (primo.tracciato) AccentoFondo else MaterialTheme.colorScheme.surfaceVariant
    val colore = if (primo.tracciato) Accento else ColoreOrario
    Text(
        // il testo della pillola e' nel motore, uguale sull'iPhone
        pillolaPassaggio(arrivo, adesso).testo,
        color = colore,
        fontWeight = if (primo.tracciato) FontWeight.Bold else FontWeight.Medium,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .background(fondo, RoundedCornerShape(13.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/**
 * Una fermata in un elenco. Con [arrivi] mostra i prossimi passaggi ("64 · 1′");
 * senza, le linee che ci passano.
 */
@Composable
fun RigaFermata(
    fermata: Fermata,
    distanza: Double?,
    linee: List<String>,
    onClick: () -> Unit,
    arrivi: List<Arrivo>? = null,
    adesso: Long = 0,
) {
    val conTempi = !arrivi.isNullOrEmpty()
    val rete = Rete.di(fermata.id)
    val sotto = descrizioneFermata(fermata)
    val descrizione = buildString {
        append(daLeggere(fermata.nome))
        // le stazioni di scambio dicono tutte le loro linee: "Metro B/B1, Metromare"
        val unita = Archivio.orario.value?.lineeStazioneUnita(fermata.id)
        if (unita != null) append(", " + unita.replace(" · ", ", ")) else if (rete != Rete.ROMA) append(", " + rete.nome)
        if (distanza != null) append(tx(", a {0} metri", distanza.roundToInt()))
        append(if (rete.treni) tx(". Stazione.") else tx(". Palina {0}.", fermata.codice))
        if (conTempi) {
            append(" ")
            append(arrivi.orEmpty().take(4).joinToString(" ") { a ->
                val m = minutiAttesa(a.passaggi.first().orario, adesso)
                if (m < 1) tx("{0} in arrivo.", a.nomeLinea) else tx("{0} tra {1} minuti.", a.nomeLinea, m)
            })
        } else if (linee.isNotEmpty()) {
            append(" " + tx("Linee {0}.", linee.take(14).joinToString(", ")))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("riga-fermata")
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = descrizione }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fermata.nome,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (conTempi) {
                // con i tempi la riga della palina non c'e': il numero va qui, per distinguere
                // le fermate con lo stesso nome (le paline ai due lati della strada)
                Text(
                    (if (rete == Rete.ROMA) sotto.replaceFirstChar { it.lowercase() } else sotto) +
                        (distanza?.let { " · ${it.roundToInt()} m" } ?: ""),
                    color = Secondario,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (distanza != null) {
                Text("${distanza.roundToInt()} m", color = Secondario, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (conTempi) {
            val elenco = arrivi.orEmpty()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                elenco.take(4).forEach { PillolaAttesa(it, adesso) }
                val altre = linee.size - elenco.take(4).map { it.nomeLinea }.distinct().size
                if (altre > 0) {
                    Text("+$altre", color = Secondario, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(sotto, color = Secondario, style = MaterialTheme.typography.bodySmall)
            if (linee.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    linee.take(14).forEach { BadgeLinea(it, colore = coloreRete(fermata.id)) }
                }
            }
        }
    }
    HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 20.dp))
}

/** tx("in arrivo") oppure "7 min". */
fun testoMinuti(orario: Long, adesso: Long): String {
    val minuti = ((orario - adesso) / 60).toInt()
    return if (minuti < 1) tx("in arrivo") else tx("{0} min", minuti)
}

fun ora(millisecondi: Long): String = oraARoma(millisecondi)

fun giorno(millisecondi: Long): String = giornoLungo(millisecondi)
