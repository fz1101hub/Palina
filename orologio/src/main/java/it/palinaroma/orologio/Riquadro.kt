package it.palinaroma.orologio

import android.app.PendingIntent
import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.ListenableFuture
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.primoPassaggio
import it.palinaroma.ponte.prossimi
import it.palinaroma.ponte.testoPassaggi
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val VERSIONE_RISORSE = "1"
private const val ORO = 0xFFF2A900.toInt()
private const val BIANCO = 0xFFFFFFFF.toInt()
private const val GRIGIO = 0xFFB8B2A7.toInt()

/**
 * Il riquadro (tile): la prima fermata del tabellone (la preferita piu' vicina)
 * con le prossime tre linee. Si ridisegna ogni minuto mentre e' in vista, e
 * subito quando arriva un tabellone nuovo; toccandolo si apre l'app.
 */
class Riquadro : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tabellone = ArchivioOrologio.carica(this)
        if (ArchivioOrologio.vecchio(tabellone)) ArchivioOrologio.richiediInSfondo(this)
        val adesso = System.currentTimeMillis() / 1000
        return subito(riquadro(tabellone, adesso, packageName))
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        subito(ResourceBuilders.Resources.Builder().setVersion(VERSIONE_RISORSE).build())
}

/** Un risultato gia' pronto, nella forma che chiede il sistema dei riquadri. */
private fun <T> subito(valore: T): ListenableFuture<T> =
    CallbackToFutureAdapter.getFuture { completamento ->
        completamento.set(valore)
        "riquadro"
    }

/** Il riquadro per il tabellone [t] (null: non ancora arrivato dal telefono). */
fun riquadro(t: TabelloneOrologio?, adesso: Long, pacchetto: String): TileBuilders.Tile =
    TileBuilders.Tile.Builder()
        .setResourcesVersion(VERSIONE_RISORSE)
        .setFreshnessIntervalMillis(60_000)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(contenutoRiquadro(t, adesso, pacchetto)))
        .build()

/** La fermata da mostrare: la prima con qualche passaggio, altrimenti la prima. */
fun fermataDelRiquadro(t: TabelloneOrologio?, adesso: Long): FermataOrologio? =
    t?.fermate?.firstOrNull { f -> f.righe.any { it.prossimi(adesso).isNotEmpty() } } ?: t?.fermate?.firstOrNull()

fun contenutoRiquadro(t: TabelloneOrologio?, adesso: Long, pacchetto: String): LayoutElementBuilders.LayoutElement {
    val colonna = LayoutElementBuilders.Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    val fermata = fermataDelRiquadro(t, adesso)
    if (fermata == null) {
        colonna.addContent(testo("Palina", 16f, ORO, grassetto = true))
        colonna.addContent(spazio(4f))
        colonna.addContent(
            testo(
                t?.nota ?: "Apri Palina per chiedere i tempi al telefono",
                12f, GRIGIO, righe = 3,
            ),
        )
    } else {
        colonna.addContent(testo(fermata.nome, 14f, ORO, grassetto = true))
        colonna.addContent(spazio(6f))
        val righe = fermata.righe.filter { it.prossimi(adesso).isNotEmpty() }.take(3)
        if (righe.isEmpty()) colonna.addContent(testo("Nessun passaggio a breve", 12f, GRIGIO, righe = 2))
        righe.forEach { r ->
            colonna.addContent(rigaRiquadro(r, adesso))
            colonna.addContent(spazio(4f))
        }
        val ora = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t!!.creato))
        colonna.addContent(testo("tempi delle $ora", 10f, GRIGIO))
    }
    val apri = ModifiersBuilders.Clickable.Builder()
        .setId("apri")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(pacchetto)
                        .setClassName(Principale::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()
    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(apri)
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).build())
                .build(),
        )
        .addContent(colonna.build())
        .build()
}

/** "[64] 3' · 12'": il numero della linea nel suo colore, i minuti in oro se in tempo reale. */
private fun rigaRiquadro(r: RigaOrologio, adesso: Long): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(r.colore))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(5f)).build())
                                .build(),
                        )
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(dp(5f)).setEnd(dp(5f)).setTop(dp(1f)).setBottom(dp(1f))
                                .build(),
                        )
                        .build(),
                )
                .addContent(testo(r.linea.take(10), 12f, BIANCO, grassetto = true))
                .build(),
        )
        .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
        .addContent(testo(testoPassaggi(r, adesso, 2), 14f, if (r.tempoReale) ORO else BIANCO, grassetto = true))
        .build()

private fun testo(s: String, dimensione: Float, colore: Int, grassetto: Boolean = false, righe: Int = 1): LayoutElementBuilders.Text =
    LayoutElementBuilders.Text.Builder()
        .setText(s)
        .setMaxLines(righe)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(dimensione))
                .setColor(argb(colore))
                .setWeight(if (grassetto) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build(),
        )
        .build()

private fun spazio(altezza: Float): LayoutElementBuilders.Spacer =
    LayoutElementBuilders.Spacer.Builder().setHeight(dp(altezza)).build()

/**
 * La complicazione: la linea e il conto alla rovescia del prossimo passaggio
 * della prima fermata. Il conto alla rovescia lo aggiorna l'orologio da solo;
 * i dati si chiedono di nuovo al telefono ogni 5 minuti.
 */
class Complicazione : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val tabellone = ArchivioOrologio.carica(this)
        if (ArchivioOrologio.vecchio(tabellone)) ArchivioOrologio.richiediInSfondo(this)
        val adesso = System.currentTimeMillis() / 1000
        val prossimo = tabellone?.fermate?.firstNotNullOfOrNull { f -> f.primoPassaggio(adesso)?.let { f to it } }
            ?: return NoDataComplicationData()
        val (fermata, passaggio) = prossimo
        return testoComplicazione(passaggio.first.linea, passaggio.second, fermata.nome, apriApp())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) {
            testoComplicazione("64", System.currentTimeMillis() / 1000 + 180, "Piazza Venezia", null)
        } else {
            null
        }

    private fun apriApp(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, Principale::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** "64 / 3 min": titolo la linea, testo il conto alla rovescia fino a [quando] (secondi). */
fun testoComplicazione(linea: String, quando: Long, fermata: String, tocco: PendingIntent?): ShortTextComplicationData {
    val conto = TimeDifferenceComplicationText.Builder(
        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
        CountDownTimeReference(Instant.ofEpochSecond(quando)),
    )
        .setMinimumTimeUnit(TimeUnit.MINUTES)
        .setDisplayAsNow(true)
        .build()
    return ShortTextComplicationData.Builder(conto, PlainComplicationText.Builder("Linea $linea a $fermata").build())
        .setTitle(PlainComplicationText.Builder(linea.take(7)).build())
        .apply { if (tocco != null) setTapAction(tocco) }
        .build()
}
