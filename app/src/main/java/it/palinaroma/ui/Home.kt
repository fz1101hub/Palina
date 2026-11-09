@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.Linea
import it.palinaroma.dati.Orario
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.posizioni
import it.palinaroma.dati.precisione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class FermataConLinee(val fermata: Fermata, val distanza: Double?, val linee: List<String>)

private fun Orario.conLinee(fermata: Fermata, distanza: Double?) =
    FermataConLinee(fermata, distanza, lineeAllaFermata(fermata.id).map { it.linea.nome }.distinct())

@Composable
fun HomeSchermo(
    apriFermata: (String) -> Unit,
    apriLinea: (String) -> Unit,
    apriOrario: () -> Unit,
    apriTragitto: () -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    var scheda by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            BarraPorpora("Palina Roma", azioni = {
                IconButton(onClick = apriOrario) {
                    Icon(Icons.Filled.Settings, contentDescription = "Orario e dati")
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = apriTragitto,
                containerColor = Porpora,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                text = { Text("Come arrivo…") },
            )
        },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            val o = orario
            if (o == null) {
                PannelloOrario(Modifier.padding(16.dp), primaVolta = true)
                return@Column
            }
            if (Archivio.daAggiornare()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SuPorpora),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clickable(onClick = apriOrario),
                ) {
                    Text(
                        "L'orario ha più di una settimana: tocca qui per aggiornarlo.",
                        color = Porpora,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            TabRow(selectedTabIndex = scheda, containerColor = Color.White, contentColor = Porpora) {
                listOf("Vicino a me", "Cerca", "Preferiti").forEachIndexed { i, titolo ->
                    Tab(selected = scheda == i, onClick = { scheda = i }, text = { Text(titolo) })
                }
            }
            when (scheda) {
                0 -> SchedaVicino(o, apriFermata)
                1 -> SchedaCerca(o, apriFermata, apriLinea)
                else -> SchedaPreferiti(o, apriFermata)
            }
        }
    }
}

@Composable
private fun SchedaVicino(orario: Orario, apriFermata: (String) -> Unit) {
    val context = LocalContext.current
    var permesso by remember { mutableStateOf(haPermessoPosizione(context)) }
    var messaggio by remember { mutableStateOf<String?>(null) }
    var vicine by remember { mutableStateOf<List<FermataConLinee>>(emptyList()) }
    var tentativo by remember { mutableIntStateOf(0) }
    var precisioneMetri by remember { mutableStateOf<Float?>(null) }
    var affinando by remember { mutableStateOf(false) }
    val richiesta = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permesso = esiti.values.any { it }
        if (permesso) tentativo++
    }

    // le fermate compaiono con la prima posizione (anche approssimativa) e si
    // aggiornano man mano che arriva una posizione piu' precisa
    LaunchedEffect(permesso, tentativo, orario) {
        if (!permesso) return@LaunchedEffect
        if (vicine.isEmpty()) messaggio = "Cerco la tua posizione…"
        affinando = true
        var usataLat = Double.NaN
        var usataLon = Double.NaN
        var usataPrecisione = Float.MAX_VALUE
        try {
            posizioni(context).collect { p ->
                precisioneMetri = p.precisione()
                val spostamento = if (usataLat.isNaN()) Double.MAX_VALUE
                else distanzaMetri(usataLat, usataLon, p.latitude, p.longitude)
                if (spostamento > 15 || p.precisione() < usataPrecisione / 2) {
                    usataLat = p.latitude
                    usataLon = p.longitude
                    usataPrecisione = p.precisione()
                    val trovate = withContext(Dispatchers.IO) {
                        orario.fermateVicine(p.latitude, p.longitude)
                            .map { (fermata, distanza) -> orario.conLinee(fermata, distanza) }
                    }
                    vicine = trovate
                    messaggio = if (trovate.isEmpty()) "Nessuna fermata entro 700 metri." else null
                }
            }
        } finally {
            affinando = false
        }
        if (usataLat.isNaN()) messaggio = "Posizione non disponibile: attiva la localizzazione e riprova."
    }

    if (!permesso) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Per mostrarti le fermate qui intorno serve la tua posizione. Resta sul telefono.")
            Button(onClick = {
                richiesta.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }) { Text("Usa la mia posizione") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (affinando) {
            item { LinearProgressIndicator(color = Porpora, modifier = Modifier.fillMaxWidth()) }
        }
        precisioneMetri?.let { metri ->
            item {
                Text(
                    when {
                        affinando && metri > 30 -> "Posizione approssimativa (±${metri.toInt()} m): la sto affinando…"
                        affinando -> "Posizione precisa a ±${metri.toInt()} m"
                        metri > 150 -> "Posizione poco precisa (±${metri.toInt()} m): attiva il GPS per migliorarla"
                        else -> "Posizione precisa a ±${metri.toInt()} m"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        messaggio?.let { testo ->
            item { Text(testo, color = Secondario, modifier = Modifier.padding(16.dp)) }
        }
        items(vicine, key = { it.fermata.id }) { f ->
            RigaFermata(f.fermata, f.distanza, f.linee) { apriFermata(f.fermata.id) }
        }
        item {
            TextButton(onClick = { tentativo++ }, modifier = Modifier.padding(8.dp)) {
                Text("Aggiorna la posizione")
            }
        }
    }
}

@Composable
private fun SchedaCerca(orario: Orario, apriFermata: (String) -> Unit, apriLinea: (String) -> Unit) {
    var testo by rememberSaveable { mutableStateOf("") }
    var linee by remember { mutableStateOf<List<Linea>>(emptyList()) }
    var fermate by remember { mutableStateOf<List<FermataConLinee>>(emptyList()) }

    LaunchedEffect(testo, orario) {
        delay(250)
        val risultati = withContext(Dispatchers.IO) {
            orario.cercaLinee(testo) to orario.cercaFermate(testo).take(40).map { orario.conLinee(it, null) }
        }
        linee = risultati.first
        fermate = risultati.second
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = testo,
            onValueChange = { testo = it },
            placeholder = { Text("Fermata, numero di palina o linea") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (linee.isNotEmpty()) {
                item { Intestazione("Linee") }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        linee.forEach { l ->
                            BadgeLinea(l.nome, Modifier.clickable { apriLinea(l.id) }, grande = true)
                        }
                    }
                }
            }
            if (fermate.isNotEmpty()) {
                item { Intestazione("Fermate") }
                items(fermate, key = { "f-" + it.fermata.id }) { f ->
                    RigaFermata(f.fermata, null, f.linee) { apriFermata(f.fermata.id) }
                }
            }
            if (testo.isNotBlank() && linee.isEmpty() && fermate.isEmpty()) {
                item { Text("Nessun risultato per \"$testo\".", color = Secondario, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun SchedaPreferiti(orario: Orario, apriFermata: (String) -> Unit) {
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    val elenco by produceState(initialValue = emptyList<FermataConLinee>(), preferiti, orario) {
        value = withContext(Dispatchers.IO) {
            val trovate = orario.fermate(preferiti)
            preferiti.mapNotNull { trovate[it] }.sortedBy { it.nome }.map { orario.conLinee(it, null) }
        }
    }
    if (preferiti.isEmpty()) {
        Text(
            "Nessun preferito. Apri una fermata e tocca il cuore in alto per ritrovarla qui.",
            color = Secondario,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(elenco, key = { it.fermata.id }) { f ->
            RigaFermata(f.fermata, null, f.linee) { apriFermata(f.fermata.id) }
        }
    }
}
