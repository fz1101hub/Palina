package it.palinaroma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.BuildConfig
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Aspetto
import it.palinaroma.dati.ORIGINE_INCLUSA
import it.palinaroma.dati.StatoAggiornamento
import it.palinaroma.dati.TestiImpostazioni

@Composable
fun OrarioSchermo(indietro: () -> Unit) {
    Scaffold(containerColor = Sfondo, topBar = { BarraSuperiore("Impostazioni", indietro = indietro) }) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PannelloAspetto()
            PannelloOrario(Modifier.fillMaxWidth(), primaVolta = false)
            // i testi sono nel motore, uguali sull'iPhone
            TestiImpostazioni.schede.forEach { Scheda(it.titolo, it.testo) }
            Text(
                TestiImpostazioni.versione(BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
    }
}

/** Chiaro, scuro o come il telefono; e le cifre a punti delle paline. */
@Composable
private fun PannelloAspetto() {
    val aspetto by Archivio.aspetto.collectAsStateWithLifecycle()
    val cifreLed by Archivio.cifreLed.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = Superficie), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                TestiImpostazioni.ASPETTO,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(Modifier.selectableGroup()) {
                TestiImpostazioni.aspetti.map { Triple(Aspetto.valueOf(it.chiave), it.nome, it.descrizione) }.forEach { (valore, nome, descrizione) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = aspetto == valore,
                                onClick = { Archivio.scegliAspetto(valore) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = aspetto == valore, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(nome)
                            Text(descrizione, style = MaterialTheme.typography.bodySmall, color = Secondario)
                        }
                    }
                }
            }
            HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = cifreLed, onValueChange = { Archivio.scegliCifreLed(it) }, role = Role.Switch)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(TestiImpostazioni.CIFRE)
                    Text(TestiImpostazioni.CIFRE_SOTTO, style = MaterialTheme.typography.bodySmall, color = Secondario)
                }
                Switch(checked = cifreLed, onCheckedChange = null)
            }
            Text(
                TestiImpostazioni.grandezzaTesto("Android"),
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Scheda(titolo: String, testo: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Superficie), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(testo, color = Secondario)
        }
    }
}

/** Stato dell'orario statico, con il pulsante per scaricarlo o aggiornarlo. */
@Composable
fun PannelloOrario(modifier: Modifier = Modifier, primaVolta: Boolean) {
    val stato by Archivio.aggiornamento.collectAsStateWithLifecycle()
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = Superficie), modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (primaVolta) "Benvenuto in Palina Roma" else TestiImpostazioni.ORARIO,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val o = orario
            Text(
                if (o == null) {
                    "Per iniziare scarico l'orario di Roma Mobilità: serve per i nomi delle fermate, " +
                        "le direzioni e i tracciati delle linee. Sono circa 50 MB, meglio in Wi-Fi; " +
                        "poi lo trasformo in un piccolo database, ci vuole qualche minuto."
                } else {
                    val incluso = o.info("origine") == ORIGINE_INCLUSA
                    TestiImpostazioni.statoOrario(o, Archivio.ultimoAggiornamento(), incluso) +
                        TestiImpostazioni.AGGIORNAMENTO_ANDROID
                },
                color = Secondario,
            )
            when (val s = stato) {
                is StatoAggiornamento.InCorso -> {
                    Text(s.fase, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { s.frazione },
                        color = Accento,
                        trackColor = AccentoFondo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is StatoAggiornamento.Errore ->
                    Text(TestiImpostazioni.nonRiuscito(s.messaggio), color = Accento)
                StatoAggiornamento.Completato ->
                    Text(TestiImpostazioni.AGGIORNATO, color = Secondario)
                StatoAggiornamento.Fermo -> Unit
            }
            Button(
                onClick = { Archivio.aggiorna() },
                enabled = stato !is StatoAggiornamento.InCorso,
            ) {
                Text(if (o == null) "Scarica l'orario" else TestiImpostazioni.AGGIORNA)
            }
        }
    }
}
