@file:OptIn(ExperimentalComposeUiApi::class)

package it.palinaroma.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Aspetto
import it.palinaroma.dati.dispatcherDati
import it.palinaroma.dati.tx
import it.palinaroma.ui.Accento
import it.palinaroma.ui.BarraSuperiore
import it.palinaroma.ui.Divisore
import it.palinaroma.ui.Secondario
import it.palinaroma.ui.Sfondo
import it.palinaroma.ui.TemaPalina
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La scelta di un widget: quale fermata (tra le preferite) o quale tragitto
 * salvato mostra. Si apre quando si aggiunge il widget e, da Android 12,
 * tenendolo premuto ("Modifica"). "Automatica" e' la scelta di partenza.
 */
class ConfiguraWidget : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Archivio.linguaDelSistemaCambiata(this)
        // se si esce senza scegliere, il widget non si aggiunge
        setResult(RESULT_CANCELED)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val tipo = Widget.tipo(this, id)
        enableEdgeToEdge()
        setContent {
            val aspetto by Archivio.aspetto.collectAsStateWithLifecycle()
            val cifreLed by Archivio.cifreLed.collectAsStateWithLifecycle()
            val scuro = when (aspetto) {
                Aspetto.AUTOMATICO -> isSystemInDarkTheme()
                Aspetto.CHIARO -> false
                Aspetto.SCURO -> true
            }
            TemaPalina(scuro = scuro, cifreLed = cifreLed) {
                SceltaWidget(
                    tragitto = tipo == "tragitto",
                    attuale = Widget.scelta(this, id),
                    chiudi = { finish() },
                    scegli = { valore ->
                        Widget.salvaScelta(this, id, valore)
                        Widget.disegnaTutti(this)
                        Widget.chiediDati(this, forza = true)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    },
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                )
            }
        }
    }
}

/** Una voce della scelta: valore ("" = automatica), nome e riga sotto. */
private data class Voce(val valore: String, val nome: String, val sotto: String)

@Composable
private fun SceltaWidget(tragitto: Boolean, attuale: String, chiudi: () -> Unit, scegli: (String) -> Unit, modifier: Modifier) {
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    val salvati by Archivio.salvati.collectAsStateWithLifecycle()
    val nomi by Archivio.nomi.collectAsStateWithLifecycle()
    val voci by produceState<List<Voce>?>(initialValue = null, preferiti, salvati, tragitto, nomi) {
        value = if (tragitto) {
            listOf(Voce("", tx("Automatico"), tx("Il tragitto che parte più vicino a dove sei"))) +
                salvati.map { Voce(it.id, it.nome, it.arrivo.nome) }
        } else {
            withTimeoutOrNull(20_000) { Archivio.pronto.first { it } }
            val orario = Archivio.orario.value
            val trovate = orario?.let { o -> withContext(dispatcherDati) { o.fermate(preferiti.toList()) } }.orEmpty()
            listOf(Voce("", tx("Automatica"), tx("La preferita a cui sei vicino"))) +
                preferiti.mapNotNull { id ->
                    trovate[id]?.let { f ->
                        val palina = if (f.codice.isNotEmpty()) tx("palina {0}", f.codice) else ""
                        val nome = nomi[id]
                        if (nome != null) Voce(id, nome, listOf(f.nome, palina).filter { it.isNotEmpty() }.joinToString(" · ")) else Voce(id, f.nome, palina)
                    }
                }
                    .sortedBy { it.nome }
        }
    }
    Scaffold(
        containerColor = Sfondo,
        modifier = modifier,
        topBar = {
            BarraSuperiore(
                titolo = if (tragitto) tx("Quale tragitto?") else tx("Quale fermata?"),
                sottotitolo = if (tragitto) tx("Widget «esci tra»") else tx("Widget della fermata"),
                indietro = chiudi,
            )
        },
    ) { margini ->
        val elenco = voci
        LazyColumn(Modifier.padding(margini).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (elenco != null) {
                items(elenco, key = { "v-" + it.valore }) { v ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.RadioButton) { scegli(v.valore) }
                            .testTag("scelta-widget")
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v.valore == attuale, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accento))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(v.nome)
                            if (v.sotto.isNotEmpty()) Text(v.sotto, style = MaterialTheme.typography.bodySmall, color = Secondario)
                        }
                    }
                    HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (elenco.size == 1) {
                    item {
                        Text(
                            if (tragitto) {
                                tx("Salva un tragitto in «Come arrivo» per vedere quando uscire.")
                            } else {
                                tx("Aggiungi una fermata ai preferiti (il cuore nella fermata) per sceglierla qui.")
                            },
                            color = Secondario,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}
