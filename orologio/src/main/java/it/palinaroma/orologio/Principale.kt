package it.palinaroma.orologio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.prossimi
import it.palinaroma.ponte.testoDistanza
import it.palinaroma.ponte.testoMinuti
import it.palinaroma.ponte.testoPassaggi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** I colori di Palina sul nero dell'orologio. */
val OroOrologio = Color(0xFFF2A900)
val GrigioOrologio = Color(0xFFB8B2A7)
private val SchedaOrologio = Color(0xFF1E1C1A)

/** L'app sull'orologio: le fermate preferite e vicine, con i prossimi passaggi. */
class Principale : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchivioOrologio.carica(this)
        setContent {
            MaterialTheme {
                SchermataPalina()
            }
        }
    }
}

@Composable
fun SchermataPalina() {
    val context = LocalContext.current
    val tabellone by ArchivioOrologio.tabellone.collectAsStateWithLifecycle()
    val telefono by ArchivioOrologio.telefono.collectAsStateWithLifecycle()
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val ciclo = LocalLifecycleOwner.current
    val ambito = rememberCoroutineScope()

    // finche' l'app e' aperta: tempi dal telefono ogni 30 secondi, minuti ricalcolati ogni 15
    LaunchedEffect(Unit) {
        ArchivioOrologio.leggiDatoCondiviso(context)
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                while (true) {
                    adesso = System.currentTimeMillis() / 1000
                    delay(15_000)
                }
            }
            while (true) {
                ArchivioOrologio.richiedi(context)
                delay(30_000)
            }
        }
    }

    val lista = rememberScalingLazyListState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ScalingLazyColumn(
            state = lista,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "Palina",
                    color = OroOrologio,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val t = tabellone
            if (t == null) {
                item {
                    Text(
                        if (telefono == StatoTelefono.NON_RAGGIUNGIBILE) {
                            "Telefono non raggiungibile: apri Palina sul telefono e controlla il Bluetooth."
                        } else {
                            "Chiedo i tempi al telefono…"
                        },
                        color = GrigioOrologio,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                t.nota?.let { nota ->
                    item {
                        Text(nota, color = GrigioOrologio, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(t.fermate, key = { it.id }) { f -> SchedaFermata(f, adesso) }
                item { TestoAggiornato(t, telefono, adesso) }
            }
            item {
                Button(
                    onClick = { ambito.launch { ArchivioOrologio.richiedi(context, forza = true) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Aggiorna")
                }
            }
        }
        TimeText()
    }
}

/** Una fermata con le sue righe. */
@Composable
private fun SchedaFermata(f: FermataOrologio, adesso: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SchedaOrologio, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            f.nome,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        val sotto = listOfNotNull(f.distanza?.let { testoDistanza(it) }, f.sotto.ifBlank { null }).joinToString(" · ")
        if (sotto.isNotEmpty()) {
            Text(sotto, color = GrigioOrologio, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        val righe = f.righe.filter { it.prossimi(adesso).isNotEmpty() }
        if (righe.isEmpty()) {
            Text("Nessun passaggio a breve", color = GrigioOrologio, style = MaterialTheme.typography.bodySmall)
        }
        righe.forEach { RigaPassaggi(it, adesso) }
    }
}

/** "64 → Termini, 3' · 12'": oro se in tempo reale, come sul telefono. */
@Composable
private fun RigaPassaggi(r: RigaOrologio, adesso: Long) {
    val prossimi = r.prossimi(adesso)
    val descrizione = buildString {
        append("Linea ").append(r.linea).append(" verso ").append(r.capolinea).append(": ")
        append(prossimi.take(3).joinToString(", ") { o -> testoMinuti(o, adesso).let { if (it == "ora") "in arrivo" else it.removeSuffix("'") + " minuti" } })
        if (r.tempoReale) append(", in tempo reale")
        r.avviso?.let { append(", ").append(it) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .semantics(mergeDescendants = true) { contentDescription = descrizione },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(Color(r.colore), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(r.linea, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.capolinea,
                color = GrigioOrologio,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                testoPassaggi(r, adesso) + (r.avviso?.let { " · $it" } ?: ""),
                color = if (r.tempoReale) OroOrologio else Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun TestoAggiornato(t: TabelloneOrologio, telefono: StatoTelefono, adesso: Long) {
    val ora = remember(t.creato) { SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t.creato)) }
    val testo = when {
        telefono == StatoTelefono.NON_RAGGIUNGIBILE -> "Telefono non raggiungibile: tempi delle $ora"
        adesso * 1000 - t.creato > 3 * 60_000 -> "Tempi delle $ora"
        else -> "Aggiornato alle $ora"
    }
    Text(testo, color = GrigioOrologio, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
}
