// Le API di Remote Compose sono ancora riservate al gruppo di librerie (alpha), come negli esempi di Google
@file:SuppressLint("RestrictedApi")

package it.palinaroma.orologio

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.remote.creation.compose.action.rememberPendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.text.RemoteFontWeight
import androidx.compose.remote.creation.compose.text.RemoteTextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.ContenutoWidget
import it.palinaroma.ponte.contenutoWidget

/** Il servizio del widget di Wear OS 7 (nello stesso gruppo del [Riquadro], vedi res/xml/widget_palina.xml). */
@AssociateWithGlanceWearWidget(WidgetPalina::class)
class ServizioWidget : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = WidgetPalina()
}

/**
 * Il widget di Wear OS 7: la fermata preferita piu' vicina, come il riquadro,
 * in formato piccolo o grande. Si aggiorna quando arriva un tabellone (vedi
 * [ArchivioOrologio.aggiornaSuperfici]); toccandolo si apre l'app.
 */
class WidgetPalina : GlanceWearWidget() {
    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val tabellone = ArchivioOrologio.carica(context)
        if (ArchivioOrologio.vecchio(tabellone)) ArchivioOrologio.richiediInSfondo(context)
        val adesso = System.currentTimeMillis() / 1000
        val contenuto = contenutoWidget(tabellone, adesso, piccolo = params.containerType == ContainerInfo.CONTAINER_TYPE_SMALL)
        return WearWidgetDocument(background = WearWidgetBrush.color(SFONDO_WIDGET.rc)) {
            ContenutoDelWidget(contenuto)
        }
    }
}

private val SFONDO_WIDGET = Color(0xFF1E1C1A)

@RemoteComposable
@Composable
private fun ContenutoDelWidget(c: ContenutoWidget) {
    val apri = rememberPendingIntentAction { ctx ->
        PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, Principale::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    RemoteMaterialTheme {
        RemoteColumn(
            modifier = RemoteModifier.fillMaxSize().clickable(apri),
            verticalArrangement = RemoteArrangement.Center,
            horizontalAlignment = RemoteAlignment.Start,
        ) {
            RemoteText(
                text = c.titolo.rs,
                color = OroOrologio.rc,
                fontWeight = RemoteFontWeight.Bold,
                maxLines = 1,
                overflow = RemoteTextOverflow.Ellipsis,
                style = RemoteMaterialTheme.typography.titleSmall,
            )
            c.righe.forEach { r ->
                RemoteRow(
                    modifier = RemoteModifier.padding(top = 4.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                ) {
                    RemoteBox(
                        modifier = RemoteModifier
                            .clip(RemoteRoundedCornerShape(5.rdp))
                            .background(Color(r.colore).rc)
                            .padding(horizontal = 5.rdp, vertical = 1.rdp),
                    ) {
                        RemoteText(
                            text = r.linea.rs,
                            color = Color.White.rc,
                            fontWeight = RemoteFontWeight.Bold,
                            maxLines = 1,
                            style = RemoteMaterialTheme.typography.labelMedium,
                        )
                    }
                    RemoteText(
                        text = r.passaggi.rs,
                        modifier = RemoteModifier.padding(start = 6.rdp),
                        color = (if (r.tempoReale) OroOrologio else Color.White).rc,
                        fontWeight = RemoteFontWeight.Bold,
                        maxLines = 1,
                        overflow = RemoteTextOverflow.Ellipsis,
                        style = RemoteMaterialTheme.typography.labelLarge,
                    )
                }
            }
            c.nota?.let { nota ->
                RemoteText(
                    text = nota.rs,
                    modifier = RemoteModifier.padding(top = 4.rdp),
                    color = GrigioOrologio.rc,
                    maxLines = 2,
                    overflow = RemoteTextOverflow.Ellipsis,
                    style = RemoteMaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
