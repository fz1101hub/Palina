package it.palinaroma.orologio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import it.palinaroma.ponte.CorsaOrologio
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.FermataTreno
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.TrenoOrologio
import it.palinaroma.ponte.Seguimento
import it.palinaroma.ponte.seguimento
import it.palinaroma.ponte.chiaveLinea
import it.palinaroma.ponte.oraBreve
import it.palinaroma.ponte.prossimi
import it.palinaroma.ponte.riga
import it.palinaroma.ponte.testoDistanza
import it.palinaroma.ponte.testoMinuti
import it.palinaroma.ponte.testoPassaggi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** I colori di Palina sul nero dell'orologio. */
val OroOrologio = Color(0xFFF2A900)
val GrigioOrologio = Color(0xFFB8B2A7)
private val SchedaOrologio = Color(0xFF1E1C1A)
private val OroFondo = Color(0xFF3A2E10)

/**
 * L'app sull'orologio: le fermate preferite e vicine con i prossimi passaggi;
 * toccando una linea i suoi passaggi (da seguire con un Live Update) e, per i
 * treni, tutte le fermate di ciascun treno.
 */
class Principale : ComponentActivity() {
    /** Dove andare quando l'app si apre da una notifica (la linea seguita). */
    private val apertura = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchivioOrologio.carica(this)
        if (savedInstanceState == null) apertura.value = rottaDa(intent)
        setContent {
            MaterialTheme {
                AppPalina(apertura)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        apertura.value = rottaDa(intent)
    }

    companion object {
        private const val FERMATA = "fermata"
        private const val LINEA = "linea"
        private const val CAPOLINEA = "capolinea"

        /** Apre l'app sulla riga di una linea (per la notifica della linea seguita). */
        fun intentRiga(context: Context, fermataId: String, chiaveLinea: String, capolinea: String): Intent =
            Intent(context, Principale::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(FERMATA, fermataId)
                .putExtra(LINEA, chiaveLinea)
                .putExtra(CAPOLINEA, capolinea)

        private fun rottaDa(intent: Intent?): String? {
            val fermata = intent?.getStringExtra(FERMATA) ?: return null
            return rottaRiga(fermata, intent.getStringExtra(LINEA).orEmpty(), intent.getStringExtra(CAPOLINEA).orEmpty())
        }
    }
}

private fun rottaRiga(fermataId: String, chiaveLinea: String, capolinea: String): String =
    "riga?f=" + Uri.encode(fermataId) + "&l=" + Uri.encode(chiaveLinea) + "&c=" + Uri.encode(capolinea)

private fun rottaTreno(treno: TrenoOrologio): String = "treno?t=" + Uri.encode(treno.testoJson())

@Composable
fun AppPalina(apertura: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    val navigazione = rememberSwipeDismissableNavController()
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

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
    LaunchedEffect(Unit) {
        apertura.collect { rotta ->
            if (rotta != null) {
                apertura.value = null
                navigazione.navigate(rotta) { launchSingleTop = true }
            }
        }
    }

    SwipeDismissableNavHost(navController = navigazione, startDestination = "elenco") {
        composable("elenco") {
            SchermataPalina(adesso) { f, r -> navigazione.navigate(rottaRiga(f.id, r.chiaveLinea, r.capolinea)) }
        }
        // gli argomenti delle rotte sono testi (codificati nella rotta)
        composable("riga?f={f}&l={l}&c={c}") { voce ->
            val a = voce.arguments
            SchermataRiga(
                fermataId = a?.getString("f").orEmpty(),
                chiaveLinea = a?.getString("l").orEmpty(),
                capolinea = a?.getString("c").orEmpty(),
                adesso = adesso,
                apriTreno = { navigazione.navigate(rottaTreno(it)) },
            )
        }
        composable("treno?t={t}") { voce ->
            val treno = remember(voce) { TrenoOrologio.leggi(voce.arguments?.getString("t")) }
            if (treno != null) SchermataTreno(treno, adesso)
        }
    }
}

/** Lo schermo tondo: lista che scorre con l'ora in alto. */
@Composable
private fun Schermo(
    lista: androidx.wear.compose.foundation.lazy.ScalingLazyListState = rememberScalingLazyListState(),
    contenuto: ScalingLazyListScope.() -> Unit,
) {
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
            content = contenuto,
        )
        TimeText()
    }
}

@Composable
private fun Nota(testo: String, colore: Color = GrigioOrologio) {
    Text(testo, color = colore, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
}

@Composable
fun SchermataPalina(adesso: Long, apriRiga: (FermataOrologio, RigaOrologio) -> Unit) {
    val context = LocalContext.current
    val tabellone by ArchivioOrologio.tabellone.collectAsStateWithLifecycle()
    val telefono by ArchivioOrologio.telefono.collectAsStateWithLifecycle()
    val seguito by Seguito.stato.collectAsStateWithLifecycle()
    val ambito = rememberCoroutineScope()

    Schermo {
        item {
            Text("Palina", color = OroOrologio, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        seguito?.let { s ->
            item {
                val t = tabellone
                val f = t?.fermate?.firstOrNull { it.id == s.fermataId }
                val r = t?.riga(s.fermataId, s.chiaveLinea, s.capolinea)
                SchedaSeguito(s, adesso) { if (f != null && r != null) apriRiga(f, r) }
            }
        }
        val t = tabellone
        if (t == null) {
            item {
                Nota(
                    if (telefono == StatoTelefono.NON_RAGGIUNGIBILE) {
                        "Telefono non raggiungibile: apri Palina sul telefono e controlla il Bluetooth."
                    } else {
                        "Chiedo i tempi al telefono…"
                    },
                )
            }
        } else {
            t.nota?.let { nota -> item { Nota(nota) } }
            items(t.fermate, key = { it.id }) { f -> SchedaFermata(f, adesso) { r -> apriRiga(f, r) } }
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
}

/** La linea che si sta seguendo, in cima all'elenco. */
@Composable
private fun SchedaSeguito(s: Seguimento, adesso: Long, apri: () -> Unit) {
    val minuti = testoMinuti(s.obiettivo, adesso)
    Column(
        Modifier
            .fillMaxWidth()
            .background(OroFondo, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "apri la linea seguita", onClick = apri)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("Stai seguendo", color = GrigioOrologio, style = MaterialTheme.typography.bodySmall)
        Text(
            "${s.linea} · " + if (minuti == "ora") "in arrivo" else minuti,
            color = OroOrologio,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(s.fermata, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

/** Una fermata con le sue righe; ogni riga si tocca per i dettagli. */
@Composable
private fun SchedaFermata(f: FermataOrologio, adesso: Long, apriRiga: (RigaOrologio) -> Unit) {
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
        righe.forEach { r -> RigaPassaggi(r, adesso) { apriRiga(r) } }
    }
}

/** Il numero della linea nel suo colore. */
@Composable
private fun Distintivo(r: RigaOrologio) {
    Box(
        Modifier
            .background(Color(r.colore), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(r.linea, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}

private fun minutiDaLeggere(o: Long, adesso: Long): String =
    testoMinuti(o, adesso).let { if (it == "ora") "in arrivo" else it.removeSuffix("'") + " minuti" }

/** "64 → Termini, 3' · 12'": oro se in tempo reale, come sul telefono. */
@Composable
private fun RigaPassaggi(r: RigaOrologio, adesso: Long, apri: () -> Unit) {
    val prossimi = r.prossimi(adesso)
    val descrizione = buildString {
        append(if (r.diTreni) "Treno " else "Linea ").append(r.linea).append(" verso ").append(r.capolinea).append(": ")
        append(prossimi.take(3).joinToString(", ") { minutiDaLeggere(it, adesso) })
        if (r.tempoReale) append(", in tempo reale")
        r.avviso?.let { append(", ").append(it) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = descrizione }
            .clickable(onClickLabel = if (r.diTreni) "vedi i treni e le loro fermate" else "vedi i passaggi e seguila", onClick = apri)
            .padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Distintivo(r)
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
    val ora = remember(t.creato) { oraBreve(t.creato) }
    val testo = when {
        telefono == StatoTelefono.NON_RAGGIUNGIBILE -> "Telefono non raggiungibile: tempi delle $ora"
        adesso * 1000 - t.creato > 3 * 60_000 -> "Tempi delle $ora"
        else -> "Aggiornato alle $ora"
    }
    Nota(testo)
}

/**
 * Una linea a una fermata: i prossimi passaggi, il pulsante per seguirla (Live
 * Update con il conto alla rovescia e una vibrazione 2 minuti prima) e, per i
 * treni, ogni passaggio apre le fermate di quel treno.
 */
@Composable
fun SchermataRiga(fermataId: String, chiaveLinea: String, capolinea: String, adesso: Long, apriTreno: (TrenoOrologio) -> Unit) {
    val context = LocalContext.current
    val tabellone by ArchivioOrologio.tabellone.collectAsStateWithLifecycle()
    val seguito by Seguito.stato.collectAsStateWithLifecycle()
    val t = tabellone
    val fermata = t?.fermate?.firstOrNull { it.id == fermataId }
    val r = t?.riga(fermataId, chiaveLinea, capolinea)
    val questa = seguito?.stessaRiga(fermataId, chiaveLinea, capolinea) == true ||
        (r != null && seguito?.stessaRiga(fermataId, r.chiaveLinea, r.capolinea) == true)
    var avviso by remember { mutableStateOf<String?>(null) }

    val segui = {
        val s = if (fermata != null && r != null) seguimento(fermata, r, t, System.currentTimeMillis() / 1000) else null
        if (s == null) {
            avviso = "Nessun passaggio da seguire."
        } else {
            Seguito.avvia(context, s)
            avviso = null
        }
    }
    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        if (concesso) segui() else avviso = "Per seguire la linea consenti a Palina le notifiche."
    }

    Schermo {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r != null) {
                    Distintivo(r)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "→ " + (r?.capolinea ?: capolinea),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        item { Nota(fermata?.nome.orEmpty()) }
        val prossimi = r?.prossimi(adesso).orEmpty()
        if (r == null || prossimi.isEmpty()) {
            item { Nota("Nessun passaggio a breve per questa linea.") }
        } else {
            r.avviso?.let { a -> item { Nota(a, OroOrologio) } }
            itemsIndexed(prossimi) { i, o ->
                val oro = i == 0 && r.tempoReale
                val treno = r.treno(o)
                if (treno != null) {
                    // un treno: si tocca per vederne tutte le fermate
                    OutlinedButton(
                        onClick = { apriTreno(treno) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Treno delle ${oraBreve(o * 1000)}, ${minutiDaLeggere(o, adesso)}: vedi le fermate" },
                    ) {
                        Column {
                            Text(
                                "${testoMinuti(o, adesso)} · ${oraBreve(o * 1000)}",
                                color = if (oro) OroOrologio else Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("Fermate del treno", color = GrigioOrologio, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = minutiDaLeggere(o, adesso) + ", alle " + oraBreve(o * 1000) + if (oro) ", in tempo reale" else ""
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            testoMinuti(o, adesso),
                            color = if (oro) OroOrologio else Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(oraBreve(o * 1000), color = GrigioOrologio, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (r.tempoReale) item { Nota("In oro: in tempo reale", OroOrologio) }
        }
        item {
            if (questa) {
                OutlinedButton(onClick = { Seguito.ferma(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Smetti di seguire")
                }
            } else {
                Button(
                    onClick = {
                        val serve = android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (serve) permesso.launch(Manifest.permission.POST_NOTIFICATIONS) else segui()
                    },
                    enabled = prossimi.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Segui")
                }
            }
        }
        item {
            Nota(
                avviso ?: if (questa) {
                    "Il conto alla rovescia è sul quadrante; l'orologio vibra 2 minuti prima."
                } else {
                    "Conto alla rovescia sul quadrante e una vibrazione 2 minuti prima."
                },
            )
        }
    }
}

/** Tutte le fermate di un treno, chieste al telefono; la lista parte dalla propria stazione. */
@Composable
fun SchermataTreno(treno: TrenoOrologio, adesso: Long) {
    val context = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    val corsaRicevuta by ArchivioOrologio.corsa.collectAsStateWithLifecycle()
    val telefono by ArchivioOrologio.telefono.collectAsStateWithLifecycle()
    val corsa = corsaRicevuta?.takeIf { it.chiave == treno.chiave }
    val lista = rememberScalingLazyListState()

    // le fermate (e i ritardi) si chiedono di nuovo ogni minuto finche' lo schermo e' aperto
    LaunchedEffect(treno.chiave) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ArchivioOrologio.richiediCorsa(context, treno)
                delay(60_000)
            }
        }
    }
    // la lista parte dalla stazione da cui lo si guarda
    LaunchedEffect(corsa?.chiave, corsa?.qui) {
        val qui = corsa?.qui ?: return@LaunchedEffect
        lista.scrollToItem(INTESTAZIONE_TRENO + qui)
    }

    Schermo(lista) {
        item {
            Text(
                corsa?.titolo ?: "Treno",
                color = OroOrologio,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item { Nota(corsa?.verso ?: "delle ${oraBreve(treno.previsto * 1000)}") }
        item {
            Nota(
                when {
                    corsa == null && telefono == StatoTelefono.NON_RAGGIUNGIBILE -> "Telefono non raggiungibile: non posso chiedere le fermate."
                    corsa == null -> "Chiedo le fermate al telefono…"
                    corsa.errore != null -> corsa.errore!!
                    else -> corsa.fonte.orEmpty()
                },
            )
        }
        if (corsa != null) {
            itemsIndexed(corsa.fermate) { i, f -> RigaFermataTreno(f, i == corsa.qui, adesso) }
        }
    }
}

/** Il titolo, la direzione e la fonte, prima delle fermate. */
private const val INTESTAZIONE_TRENO = 3

/** Una fermata del treno: orario (e quello programmato, barrato, se cambia), nome, binario. */
@Composable
private fun RigaFermataTreno(f: FermataTreno, qui: Boolean, adesso: Long) {
    val passata = (f.orario ?: Long.MAX_VALUE) < adesso * 1000 - 60_000 && !qui
    val aggiornato = f.orario
    val programmato = f.previsto
    val orario = aggiornato?.let { oraBreve(it) } ?: "–"
    val cambiato = aggiornato != null && programmato != null && oraBreve(aggiornato) != oraBreve(programmato)
    val descrizione = buildString {
        append(f.nome)
        if (f.soppressa) append(", fermata soppressa") else append(", ").append(orario)
        f.ritardoMinuti?.takeIf { it > 0 }?.let { append(", ").append(it).append(" minuti di ritardo") }
        if (f.stimato) append(", stimato")
        f.binario?.let { append(", binario ").append(it) }
        if (qui) append(", la tua stazione")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (qui) OroFondo else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = descrizione },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(46.dp)) {
            Text(
                orario,
                color = when {
                    passata || f.soppressa -> GrigioOrologio
                    cambiato -> OroOrologio
                    else -> Color.White
                },
                fontWeight = FontWeight.Bold,
                textDecoration = if (f.soppressa) TextDecoration.LineThrough else null,
                style = MaterialTheme.typography.labelMedium,
            )
            if (cambiato && !f.soppressa) {
                Text(
                    oraBreve(programmato),
                    color = GrigioOrologio,
                    textDecoration = TextDecoration.LineThrough,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                f.nome,
                color = if (passata) GrigioOrologio else Color.White,
                fontWeight = if (qui) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (f.soppressa) TextDecoration.LineThrough else null,
                style = MaterialTheme.typography.bodySmall,
            )
            val sotto = listOfNotNull(
                "soppressa".takeIf { f.soppressa },
                f.binario?.let { "bin. $it" + if (f.binarioCambiato) " (cambiato)" else "" },
                f.ritardoMinuti?.takeIf { it > 0 && !f.soppressa }?.let { "+$it min" + if (f.stimato) " (stima)" else "" },
            )
            if (sotto.isNotEmpty()) {
                Text(sotto.joinToString(" · "), color = if (qui) OroOrologio else GrigioOrologio, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
