@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.arriviAllaFermata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FermataSchermo(
    fermataId: String,
    indietro: () -> Unit,
    apriLinea: (String, Int?) -> Unit,
    apriVettura: (lineaId: String, direzione: Int?, corsaId: String) -> Unit,
    comeArrivare: (Fermata) -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    val o = orario
    val fermata = remember(o, fermataId) { o?.fermata(fermataId) }
    val linee = remember(o, fermataId) { o?.lineeAllaFermata(fermataId).orEmpty() }

    var arrivi by remember { mutableStateOf<List<Arrivo>?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }
    var aggiornando by remember { mutableStateOf(false) }
    var aggiornatoAlle by remember { mutableLongStateOf(0L) }
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val ambito = rememberCoroutineScope()
    val ciclo = LocalLifecycleOwner.current

    suspend fun carica(forza: Boolean) {
        aggiornando = true
        try {
            val corse = TempoReale.corse(forza)
            // le corse con una vettura rilevata via GPS; senza questo feed vale la vettura indicata nelle corse
            val tracciate = try {
                TempoReale.mezzi(forza).map { it.corsaId }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            val istante = System.currentTimeMillis() / 1000
            arrivi = withContext(Dispatchers.IO) {
                arriviAllaFermata(corse, fermataId, o, istante, tracciate = tracciate)
            }
            errore = null
            aggiornatoAlle = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errore = "Non riesco a leggere i dati in tempo reale. Controlla la connessione e riprova."
        } finally {
            aggiornando = false
        }
    }

    // Aggiorna ogni 30 secondi solo mentre la schermata e' visibile
    LaunchedEffect(fermataId, o) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                carica(forza = false)
                delay(30_000)
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            adesso = System.currentTimeMillis() / 1000
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            BarraPorpora(
                titolo = fermata?.nome ?: "Fermata $fermataId",
                sottotitolo = fermata?.let { "Palina ${it.codice}" },
                indietro = indietro,
                azioni = {
                    fermata?.let { f ->
                        IconButton(onClick = { comeArrivare(f) }) {
                            Icon(Icons.Filled.Place, contentDescription = "Come arrivo qui")
                        }
                    }
                    val preferita = fermataId in preferiti
                    IconButton(onClick = { Archivio.alternaPreferito(fermataId) }) {
                        Icon(
                            if (preferita) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (preferita) "Togli dai preferiti" else "Aggiungi ai preferiti",
                        )
                    }
                },
            )
        },
    ) { margini ->
        PullToRefreshBox(
            isRefreshing = aggiornando,
            onRefresh = { ambito.launch { carica(forza = true) } },
            modifier = Modifier
                .padding(margini)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        if (aggiornatoAlle > 0) "Aggiornato alle ${ora(aggiornatoAlle)} · trascina giù per aggiornare"
                        else "Carico i tempi d'attesa…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondario,
                    )
                }
                item { LegendaFonti() }
                errore?.let { testo ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SuPorpora)) {
                            Text(testo, color = Porpora, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                val elenco = arrivi
                if (elenco != null && elenco.isEmpty() && errore == null) {
                    item {
                        Text(
                            "Nessun mezzo in arrivo nei prossimi 90 minuti tra quelli tracciati in tempo reale.",
                            color = Secondario,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(elenco.orEmpty(), key = { "${it.lineaId}|${it.direzione}|${it.capolinea}" }) { arrivo ->
                    RigaArrivo(arrivo, adesso) {
                        apriVettura(arrivo.lineaId, arrivo.direzione, arrivo.passaggi.first().corsaId)
                    }
                }
                if (linee.isNotEmpty()) {
                    item { Intestazione("Linee di questa fermata") }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            linee.distinctBy { it.linea.id }.forEach { lf ->
                                BadgeLinea(
                                    lf.linea.nome,
                                    Modifier.clickable { apriLinea(lf.linea.id, lf.direzione) },
                                    grande = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RigaArrivo(arrivo: Arrivo, adesso: Long, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .padding(10.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgeLinea(arrivo.nomeLinea, grande = true)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    arrivo.capolinea.ifEmpty { "Direzione non indicata" },
                    fontWeight = FontWeight.Bold,
                    color = if (arrivo.capolinea.isEmpty()) Secondario else Inchiostro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (arrivo.passaggi.size > 1) {
                    Text(
                        "poi " + arrivo.passaggi.drop(1).take(2).joinToString(", ") {
                            testoMinuti(it.orario, adesso) + if (it.tracciato) "" else " (da orario)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Secondario,
                    )
                }
            }
            val primo = arrivo.passaggi.first()
            val minuti = testoMinuti(primo.orario, adesso)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    minuti,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !primo.tracciato -> Secondario
                        minuti == "in arrivo" -> Porpora
                        else -> Inchiostro
                    },
                )
                FonteTempo(primo.tracciato)
            }
        }
    }
}

/** Spiega le due etichette dei tempi d'attesa. */
@Composable
private fun LegendaFonti() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FonteTempo(true)
            Text(
                "  la vettura è seguita in tempo reale: tocca per vederla sulla mappa",
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FonteTempo(false)
            Text(
                "  tempo calcolato sull'orario programmato, senza GPS",
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
    }
}
