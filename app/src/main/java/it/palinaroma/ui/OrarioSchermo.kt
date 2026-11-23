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
            Scheda(
                "Tempo reale",
                "I tempi d'attesa si leggono solo mentre la Home, una fermata o una linea sono aperte: " +
                    "ogni 30 secondi, la posizione dei mezzi ogni 15. Ogni lettura può scaricare alcuni MB " +
                    "(niente, se i dati non sono cambiati): con il traffico dati tienilo presente.",
            )
            Scheda(
                "Cotral e Trenitalia",
                "Fermate e linee dei bus e dei treni Cotral e le stazioni Trenitalia del Lazio sono incluse nell'app " +
                    "e si aggiornano con l'app. Le partenze arrivano da Transitous, con il tempo reale quando c'è " +
                    "(bus Cotral e treni Trenitalia); per Metromare e Roma–Viterbo orari, ritardi e soppressioni " +
                    "vengono dal servizio orari di ASTRAL. I bus Cotral visti dal GPS, sulla mappa della linea e con la " +
                    "scritta «GPS» alla fermata, arrivano da BusOne, che li riceve da Cotral: solo mentre guardi una " +
                    "fermata, una linea o un tragitto con bus Cotral. Per i treni non ci sono posizioni, solo orari e ritardi.",
            )
            Scheda(
                "La tua posizione",
                "Per le fermate vicine e per il segnalino \"sei qui\" sulle mappe la posizione resta sul telefono. " +
                    "Solo quando calcoli un tragitto da o verso la tua posizione le coordinate vengono inviate a Transitous, " +
                    "che calcola il percorso; quando cerchi un indirizzo gli arriva la zona in cui ti trovi, " +
                    "arrotondata a circa un chilometro, per mostrarti prima i risultati vicini.",
            )
            Scheda(
                "Fonti",
                "Orario e tempo reale: open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT). " +
                    "Tragitti e orari programmati alle fermate: Transitous. Mappe © OpenStreetMap contributors. " +
                    "Bus e treni Cotral: open data di Cotral. Ritardi dei treni Cotral: ASTRAL. " +
                    "GPS dei bus Cotral: BusOne (https://busone.app) e Cotral, uso personale non commerciale. " +
                    "Stazioni e treni Trenitalia: BusOne (https://busone.app) e Trenitalia, uso personale non commerciale. " +
                    "Cifre a punti: carattere Doto, © The Doto Project Authors (SIL Open Font License 1.1). " +
                    "App personale, non affiliata ad ATAC, Roma Servizi per la Mobilità, Cotral, ASTRAL, Trenitalia, " +
                    "BusOne né Transitous.",
            )
            Text(
                "Palina Roma ${BuildConfig.VERSION_NAME}",
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
                "Aspetto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(Aspetto.AUTOMATICO, "Automatico", "chiaro o scuro come il telefono"),
                    Triple(Aspetto.CHIARO, "Chiaro", "fondo color carta, cifre ambra"),
                    Triple(Aspetto.SCURO, "Scuro", "tabellone nero, cifre oro"),
                ).forEach { (valore, nome, descrizione) ->
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
                    Text("Cifre a punti")
                    Text("come sulle paline elettroniche", style = MaterialTheme.typography.bodySmall, color = Secondario)
                }
                Switch(checked = cifreLed, onCheckedChange = null)
            }
            Text(
                "La grandezza del testo segue quella scelta nelle impostazioni di Android.",
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
                if (primaVolta) "Benvenuto in Palina Roma" else "Orario di Roma Mobilità",
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
                    (if (incluso) "Orario del ${giorno(Archivio.ultimoAggiornamento())}, incluso nell'app · " else "Aggiornato il ${giorno(Archivio.ultimoAggiornamento())} · ") +
                        "${o.info("fermate") ?: "?"} fermate, ${o.info("linee") ?: "?"} linee. " +
                        "Quando ha più di una settimana si aggiorna da solo in Wi-Fi; " +
                        "con i dati mobili puoi aggiornarlo da qui (circa 50 MB)."
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
                    Text("Aggiornamento non riuscito: ${s.messaggio}", color = Accento)
                StatoAggiornamento.Completato ->
                    Text("Orario aggiornato.", color = Secondario)
                StatoAggiornamento.Fermo -> Unit
            }
            Button(
                onClick = { Archivio.aggiorna() },
                enabled = stato !is StatoAggiornamento.InCorso,
            ) {
                Text(if (o == null) "Scarica l'orario" else "Aggiorna ora")
            }
        }
    }
}
