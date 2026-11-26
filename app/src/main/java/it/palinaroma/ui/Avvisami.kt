@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package it.palinaroma.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.avviso.Avvisatore
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.CartaSalvato
import it.palinaroma.dati.SchedaAvviso
import it.palinaroma.dati.TragittoSalvato
import it.palinaroma.dati.testoQuantiAvvisi
import it.palinaroma.dati.tx
import it.palinaroma.ponte.FermataDiscesa
import it.palinaroma.ponte.PropostaAvviso
import it.palinaroma.ponte.StatoSegui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Il permesso per le notifiche c'e' (o non serve, prima di Android 13). */
private fun notifichePermesse(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Il foglio "Avvisami per il 64": quando avvisare (ora di uscire, a 5 o a 2
 * minuti), cosa propone l'app (il successivo, se il prossimo non lo prendi in
 * tempo) e "poi avvisami quando scendere".
 */
@Composable
fun FoglioAvvisami(fermataId: String, lineaId: String, capolinea: String, chiudi: () -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var quando by remember { mutableStateOf("uscire") }
    var proposta by remember { mutableStateOf<PropostaAvviso?>(null) }
    var caricando by remember { mutableStateOf(true) }
    var scendi by remember { mutableStateOf(false) }
    // "Seguilo sulla schermata di blocco" (come nel disegno): acceso di solito
    var blocco by remember { mutableStateOf(true) }
    var attivando by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf<String?>(null) }
    val stato = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun attiva() {
        attivando = true
        ambito.launch {
            val s = Avvisatore.avvia(context, fermataId, lineaId, capolinea, proposta?.scelta ?: quando, scendi, blocco)
            attivando = false
            if (s == null) errore = tx("Non trovo un prossimo passaggio da seguire per questa linea.") else chiudi()
        }
    }
    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { attiva() }

    LaunchedEffect(quando) {
        caricando = proposta == null
        val p = Avvisatore.proposta(context, fermataId, lineaId, capolinea, quando)
        proposta = p
        caricando = false
        if (p != null && p.scelta != quando) quando = p.scelta
    }

    ModalBottomSheet(onDismissRequest = chiudi, sheetState = stato, containerColor = Superficie) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                // il foglio e' una finestra a parte: anche qui le etichette per le prove
                .semantics { testTagsAsResourceId = true }
                .testTag("foglio-avvisami"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val p = proposta
            if (p == null) {
                if (caricando) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accento, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                        Text(tx("Carico i tempi…"), color = Secondario, modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    Text(tx("Non riesco a leggere i tempi d'attesa. Controlla la connessione."), color = Secondario)
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BadgeLinea(p.linea, grande = true, colore = Color(p.colore))
                Column {
                    Text(p.titolo, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(p.sotto, color = Secondario, style = MaterialTheme.typography.bodyMedium)
                }
            }
            p.nota?.let { nota ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(AccentoFondo, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconaPiedi(Accento, 22.dp)
                    Text(nota, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Intestazione(tx("Quando").uppercase(), rientro = 0.dp)
            // le tre scelte in griglia, come nel disegno
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.opzioni.forEach { o ->
                    val scelta = o.codice == p.scelta
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (scelta) AccentoFondo else Color.Transparent)
                            .border(1.5.dp, if (scelta) Accento else Bordo, RoundedCornerShape(14.dp))
                            .selectable(selected = scelta, enabled = o.abilitata, role = Role.RadioButton, onClick = { quando = o.codice })
                            .testTag("quando-" + o.codice),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            o.etichetta,
                            color = when {
                                !o.abilitata -> Secondario.copy(alpha = 0.5f)
                                scelta -> Accento
                                else -> Testo
                            },
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(p.spiegazione, style = MaterialTheme.typography.bodyMedium, color = Testo)
            Column {
                HorizontalDivider(color = Divisore)
                RigaInterruttore(
                    tx("Seguilo sulla schermata di blocco"),
                    tx("quanto manca, senza aprire l'app"),
                    blocco,
                    Modifier.testTag("interruttore-blocco"),
                ) { blocco = it }
                HorizontalDivider(color = Divisore)
                RigaInterruttore(
                    tx("Poi avvisami quando scendere"),
                    tx("scegli la fermata quando sei a bordo"),
                    scendi,
                    Modifier.testTag("interruttore-scendi"),
                ) { scendi = it }
            }
            errore?.let { Text(it, color = Accento) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = chiudi,
                    border = BorderStroke(1.5.dp, Accento.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text(tx("Annulla"), color = Accento, fontSize = 15.sp) }
                Button(
                    onClick = {
                        if (!notifichePermesse(context) && Build.VERSION.SDK_INT >= 33) {
                            permesso.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            attiva()
                        }
                    },
                    enabled = p.possibile && !attivando,
                    colors = ButtonDefaults.buttonColors(containerColor = Oro, contentColor = Inchiostro),
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp)
                        .testTag("attiva-avviso"),
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(" " + tx("Attiva avviso"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            if (!notifichePermesse(context)) {
                Text(
                    tx("La prima volta Android ti chiede il permesso per le notifiche."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** L'avviso in corso (in Home): come nella schermata di blocco, con i pulsanti. */
@Composable
fun SchedaAvvisoInCorso(s: StatoSegui, apriScendi: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            adesso = System.currentTimeMillis()
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = FondoTabellone),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("avviso-in-corso"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = s.titoloScheda + ". " + s.testoScheda },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgeLinea(s.linea, colore = Color(s.colore))
                        Text(s.titoloScheda, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Accento, maxLines = 2)
                    }
                    Text(s.testoScheda, style = MaterialTheme.typography.bodyMedium)
                }
                if (s.contoMs > 0) {
                    val minuti = ((s.contoMs - adesso) / 60_000).toInt()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (minuti >= 1) "$minuti" else tx("ora§adesso"),
                            fontFamily = CarattereCifre,
                            fontWeight = FontWeight.Black,
                            fontSize = if (minuti >= 1) 36.sp else 22.sp,
                            color = Accento,
                        )
                        if (minuti >= 1) Text("min", color = Accento, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (s.fineMs > s.inizioMs) {
                val frazione = ((adesso - s.inizioMs).toFloat() / (s.fineMs - s.inizioMs)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { frazione }, color = Accento, trackColor = Bordo, modifier = Modifier.fillMaxWidth())
            }
            Text(s.dettaglio, style = MaterialTheme.typography.bodySmall, color = Secondario)
            FlowRowPulsanti {
                if (s.mostraUscito) {
                    TextButton(onClick = { Avvisatore.stoUscendo(context) }) { Text(tx("Sto uscendo"), color = Accento) }
                }
                if (s.mostraSuccessivo) {
                    TextButton(onClick = { ambito.launch { Avvisatore.prendiSuccessivo(context) } }) { Text(tx("Prendo il successivo"), color = Accento) }
                }
                if (s.mostraScegli) {
                    Button(onClick = apriScendi, colors = ButtonDefaults.buttonColors(containerColor = Oro, contentColor = Inchiostro)) {
                        Text(tx("Scegli dove scendi"), fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { Avvisatore.smetti(context) }) {
                    Text(if (s.mostraFine) tx("Fine viaggio") else tx("Smetti"), color = Secondario)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowPulsanti(contenuto: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) { contenuto() }
}

/** Dove scendi: le fermate della linea dopo quella dove sei salito. */
@Composable
fun SceltaDiscesaSchermo(indietro: () -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val stato by Avvisatore.stato.collectAsStateWithLifecycle()
    var fermate by remember { mutableStateOf<List<FermataDiscesa>?>(null) }
    LaunchedEffect(Unit) { fermate = Avvisatore.fermateDiscesa(context) }
    Scaffold(
        containerColor = Sfondo,
        topBar = {
            BarraSuperiore(
                titolo = tx("Dove scendi?"),
                sottotitolo = stato?.let { tx("{0} verso {1}", it.linea, it.capolinea) },
                indietro = indietro,
            )
        },
    ) { margini ->
        val elenco = fermate
        when {
            stato == null -> Text(tx("Nessun avviso in corso."), color = Secondario, modifier = Modifier.padding(margini).padding(20.dp))
            elenco == null -> LinearProgressIndicator(color = Accento, trackColor = AccentoFondo, modifier = Modifier.padding(margini).fillMaxWidth())
            elenco.isEmpty() -> Text(
                tx("Non trovo le fermate di questa linea."),
                color = Secondario,
                modifier = Modifier.padding(margini).padding(20.dp),
            )
            else -> LazyColumn(Modifier.padding(margini).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text(
                        tx("Ti avviso quando il mezzo lascia la fermata prima: «Scendi alla prossima»."),
                        color = Secondario,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(elenco, key = { it.id }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                ambito.launch {
                                    Avvisatore.scendiA(context, f.id)
                                    indietro()
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PallinoTappa(partenza = false)
                        Text(f.nome, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        if (f.ora.isNotEmpty()) Text(f.ora, color = Accento, fontFamily = CarattereCifre, fontWeight = FontWeight.Black)
                    }
                    HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

/** Gli avvisi di Roma Mobilita': i primi due, poi "mostra tutti"; il testo si apre toccandoli. */
@Composable
fun SchedaAvvisiRoma(avvisi: List<SchedaAvviso>, modifier: Modifier = Modifier) {
    if (avvisi.isEmpty()) return
    val context = LocalContext.current
    var tutti by remember { mutableStateOf(false) }
    var aperto by remember { mutableStateOf<String?>(null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentoFondo),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("avvisi-roma"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Accento, modifier = Modifier.size(20.dp))
                Text(tx("Avvisi di Roma Mobilità"), fontWeight = FontWeight.Bold, color = Accento, modifier = Modifier.weight(1f))
                Text(testoQuantiAvvisi(avvisi.size), style = MaterialTheme.typography.labelSmall, color = Secondario)
            }
            for (a in if (tutti) avvisi else avvisi.take(2)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { aperto = if (aperto == a.id) null else a.id }
                        .semantics(mergeDescendants = true) { contentDescription = a.perVoce },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val sopra = listOfNotNull(a.tipo, a.linee.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · ")
                    if (sopra.isNotEmpty()) Text(sopra, style = MaterialTheme.typography.labelMedium, color = Accento, fontWeight = FontWeight.Bold)
                    Text(
                        a.titolo,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (a.futuro) Secondario else Testo,
                        maxLines = if (aperto == a.id) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    a.periodo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Secondario) }
                    if (aperto == a.id) {
                        if (a.testo.isNotEmpty()) Text(a.testo, style = MaterialTheme.typography.bodySmall)
                        a.url?.let { url ->
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }) { Text(tx("Dettagli su Roma Mobilità"), color = Accento) }
                        }
                    }
                }
            }
            if (avvisi.size > 2) {
                TextButton(onClick = { tutti = !tutti }) {
                    Text(if (tutti) tx("Mostra meno") else tx("Mostra tutti ({0})", avvisi.size), color = Accento)
                }
            }
        }
    }
}

/** Un tragitto salvato in Home: "Lavoro → Casa · ESCI TRA 4 min", con ritorno ed elimina. */
@Composable
fun CartaTragittoSalvato(t: TragittoSalvato, carta: CartaSalvato, apri: () -> Unit, ritorno: (() -> Unit)?, rimuovi: () -> Unit) {
    var rinominando by remember { mutableStateOf(false) }
    if (rinominando) {
        DialogoNome(
            titolo = tx("Rinomina il tragitto"),
            spiegazione = tx("Per esempio «Casa → Lavoro». Lascia vuoto per usare i nomi dei due luoghi."),
            nomeIniziale = t.nome,
            lunghezza = 40,
            chiudi = { rinominando = false },
            salva = { nome ->
                Archivio.rinominaTragitto(t.id, nome)
                rinominando = false
            },
        )
    }
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentoFondo),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tragitto-salvato")
            .clickable(onClickLabel = tx("cerca il tragitto"), onClick = apri),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = carta.perVoce },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(t.nome, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (carta.viaggio.isNotEmpty()) {
                    Text(carta.viaggio, style = MaterialTheme.typography.bodySmall, color = Accento, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                Text(carta.sotto, style = MaterialTheme.typography.bodySmall, color = Secondario, maxLines = 2)
                carta.poi?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Secondario) }
            }
            if (carta.esci.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                    Text(carta.esci, style = MaterialTheme.typography.labelSmall, color = Accento, fontWeight = FontWeight.Bold)
                    if (carta.numero.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(carta.numero, fontFamily = CarattereCifre, fontWeight = FontWeight.Black, fontSize = 32.sp, color = Accento)
                            Text(" " + carta.unita, color = Accento, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = tx("Altro per {0}", t.nome), tint = Secondario)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (ritorno != null) {
                    DropdownMenuItem(text = { Text(tx("Ritorno")) }, onClick = {
                        menu = false
                        ritorno()
                    })
                }
                DropdownMenuItem(text = { Text(tx("Rinomina")) }, onClick = {
                    menu = false
                    rinominando = true
                })
                DropdownMenuItem(text = { Text(tx("Elimina")) }, onClick = {
                    menu = false
                    rimuovi()
                })
            }
        }
    }
}

/** Salva il tragitto con un nome ("Lavoro → Casa"). */
@Composable
fun DialogoSalvaTragitto(nomeIniziale: String, chiudi: () -> Unit, salva: (String) -> Unit) {
    DialogoNome(
        titolo = tx("Salva il tragitto"),
        spiegazione = tx("Lo ritrovi in Home con «esci tra», nel widget e tenendo premuta l'icona di Palina."),
        nomeIniziale = nomeIniziale,
        lunghezza = 40,
        chiudi = chiudi,
        salva = salva,
    )
}

/** Un nome da dare (a una fermata, a un tragitto): vuoto lo toglie. */
@Composable
fun DialogoNome(titolo: String, spiegazione: String, nomeIniziale: String, lunghezza: Int, chiudi: () -> Unit, salva: (String) -> Unit) {
    var nome by remember { mutableStateOf(nomeIniziale) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(titolo) },
        text = {
            // il dialogo e' una finestra a parte: anche qui le etichette per le prove
            Column(Modifier.semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spiegazione, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it.take(lunghezza) },
                    singleLine = true,
                    label = { Text(tx("Nome")) },
                    modifier = Modifier.testTag("campo-nome"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { salva(nome) }) { Text(tx("Salva"), color = Accento) } },
        dismissButton = { TextButton(onClick = chiudi) { Text(tx("Annulla"), color = Secondario) } },
    )
}

/** Un interruttore del foglio "Avvisami" (come nel disegno): titolo, spiegazione, l'interruttore d'oro. */
@Composable
private fun RigaInterruttore(titolo: String, sotto: String, acceso: Boolean, modifier: Modifier = Modifier, cambia: (Boolean) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = acceso, role = Role.Switch, onValueChange = cambia),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titolo, fontSize = 15.sp)
            Text(sotto, fontSize = 12.5.sp, color = Secondario)
        }
        Switch(
            checked = acceso,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Oro, checkedThumbColor = Inchiostro, checkedBorderColor = Oro),
        )
    }
}

/** Il pulsante "Avvisami" su una riga del tabellone. */
@Composable
fun PulsanteAvvisami(nomeLinea: String, avvisa: () -> Unit) {
    IconButton(onClick = avvisa, modifier = Modifier.testTag("avvisami")) {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = tx("Avvisami per il {0}", nomeLinea),
            tint = Secondario,
            modifier = Modifier.size(20.dp),
        )
    }
}
