package it.palinaroma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.ORIGINE_INCLUSA
import it.palinaroma.dati.StatoAggiornamento

@Composable
fun OrarioSchermo(indietro: () -> Unit) {
    Scaffold(topBar = { BarraPorpora("Orario e dati", indietro = indietro) }) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PannelloOrario(Modifier.fillMaxWidth(), primaVolta = false)
            Scheda(
                "Tempo reale",
                "I tempi d'attesa e la posizione dei mezzi si leggono solo mentre una fermata o una linea è aperta, " +
                    "ogni 30 e 15 secondi. Ogni lettura scarica alcuni MB: con il traffico dati tienilo presente.",
            )
            Scheda(
                "Fonti",
                "Orario e tempo reale: open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT). Mappe © OpenStreetMap contributors. " +
                    "App personale, non affiliata ad ATAC né a Roma Servizi per la Mobilità.",
            )
        }
    }
}

@Composable
private fun Scheda(titolo: String, testo: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
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
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = modifier) {
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
                        color = Porpora,
                        trackColor = SuPorpora,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is StatoAggiornamento.Errore ->
                    Text("Aggiornamento non riuscito: ${s.messaggio}", color = Porpora)
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
