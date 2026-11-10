@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.ErroreTransitous
import it.palinaroma.dati.Fermata
import it.palinaroma.dati.Luogo
import it.palinaroma.dati.TipoLuogo
import it.palinaroma.dati.Tragitto
import it.palinaroma.dati.Tratto
import it.palinaroma.dati.Transitous
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.inRiga
import it.palinaroma.dati.Punto
import it.palinaroma.dati.posizioneAttuale
import it.palinaroma.dati.ultimaPosizioneNota
import it.palinaroma.dati.testoDurata
import it.palinaroma.dati.testoMetri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface Quando {
    data object Adesso : Quando
    data class Partenza(val istante: Long) : Quando
    data class Arrivo(val istante: Long) : Quando
}

sealed interface EsitoTragitti {
    data object Vuoto : EsitoTragitti
    data object InCorso : EsitoTragitti
    data class Errore(val messaggio: String) : EsitoTragitti
    data class Pronto(
        val tragitti: List<Tragitto>,
        val soloAPiedi: Tragitto?,
        val cursorePrima: String?,
        val cursoreDopo: String?,
        val calcolatoAlle: Long,
    ) : EsitoTragitti
}

/** La ricerca in corso: resta viva passando dall'elenco al dettaglio. */
object StatoTragitto {
    var da by mutableStateOf<Luogo?>(Luogo.posizione())
    var a by mutableStateOf<Luogo?>(null)
    var quando by mutableStateOf<Quando>(Quando.Adesso)
    var esito by mutableStateOf<EsitoTragitti>(EsitoTragitti.Vuoto)
    var caricandoAltri by mutableStateOf(false)

    private data class Richiesta(val da: Luogo, val a: Luogo, val istante: Long?, val arrivaEntro: Boolean)

    private var richiesta: Richiesta? = null
    private var chiaveUltima: String? = null

    fun verso(luogo: Luogo) {
        a = luogo
        if (da == null) da = Luogo.posizione()
    }

    fun inverti() {
        val vecchioDa = da
        da = a
        a = vecchioDa
    }

    private fun chiave(): String = listOf(da?.inRiga(), a?.inRiga(), quando.toString()).joinToString("#")

    /** Cerca solo se e' cambiato qualcosa o se i risultati hanno piu' di due minuti. */
    suspend fun cercaSeServe(context: Context) {
        val pronto = esito as? EsitoTragitti.Pronto
        if (chiave() == chiaveUltima && pronto != null && System.currentTimeMillis() - pronto.calcolatoAlle < 120_000) return
        cerca(context)
    }

    suspend fun cerca(context: Context) {
        val daScelto = da ?: return
        val aScelto = a ?: return
        chiaveUltima = chiave()
        esito = EsitoTragitti.InCorso
        try {
            val partenza = risolvi(context, daScelto)
            val arrivo = risolvi(context, aScelto)
            if (partenza == null || arrivo == null) {
                esito = EsitoTragitti.Errore("Non riesco a leggere la tua posizione: attiva la localizzazione e riprova.")
                return
            }
            if (distanzaMetri(partenza.lat, partenza.lon, arrivo.lat, arrivo.lon) < 40) {
                esito = EsitoTragitti.Errore("Partenza e arrivo sono nello stesso punto.")
                return
            }
            val (istante, arrivaEntro) = when (val q = quando) {
                Quando.Adesso -> null to false
                is Quando.Partenza -> q.istante to false
                is Quando.Arrivo -> q.istante to true
            }
            val nuova = Richiesta(partenza, arrivo, istante, arrivaEntro)
            val r = Transitous.tragitti(partenza, arrivo, istante, arrivaEntro, null)
            richiesta = nuova
            esito = EsitoTragitti.Pronto(
                r.tragitti.distinctBy { it.chiave },
                r.soloAPiedi,
                r.cursorePrima,
                r.cursoreDopo,
                System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErroreTransitous) {
            esito = EsitoTragitti.Errore(e.message ?: "Transitous non ha risposto.")
        } catch (e: Exception) {
            esito = EsitoTragitti.Errore("Non riesco a contattare Transitous. Controlla la connessione e riprova.")
        }
    }

    /** Partenze precedenti o successive, aggiunte all'elenco. */
    suspend fun altri(dopo: Boolean) {
        val attuale = esito as? EsitoTragitti.Pronto ?: return
        val r0 = richiesta ?: return
        val cursore = (if (dopo) attuale.cursoreDopo else attuale.cursorePrima) ?: return
        if (caricandoAltri) return
        caricandoAltri = true
        try {
            val r = Transitous.tragitti(r0.da, r0.a, r0.istante, r0.arrivaEntro, cursore)
            val uniti = (if (dopo) attuale.tragitti + r.tragitti else r.tragitti + attuale.tragitti)
                .distinctBy { it.chiave }
                .sortedBy { it.partenza }
            esito = attuale.copy(
                tragitti = uniti,
                cursorePrima = if (dopo) attuale.cursorePrima else r.cursorePrima,
                cursoreDopo = if (dopo) r.cursoreDopo else attuale.cursoreDopo,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // restano i risultati gia' trovati
        } finally {
            caricandoAltri = false
        }
    }

    private suspend fun risolvi(context: Context, luogo: Luogo): Luogo? {
        if (luogo.tipo != TipoLuogo.POSIZIONE) return luogo
        val posizione = posizioneAttuale(context) ?: return null
        return luogo.copy(lat = posizione.latitude, lon = posizione.longitude)
    }
}

fun Fermata.comeLuogo() = Luogo(nome, "Palina $codice", lat, lon, TipoLuogo.FERMATA)

// ---------------------------------------------------------------------------
// Schermata principale dei tragitti
// ---------------------------------------------------------------------------

@Composable
fun TragittoSchermo(indietro: () -> Unit, scegliLuogo: (String) -> Unit, apriDettaglio: (Int) -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val stato = StatoTragitto
    var permesso by rememberPermessoPosizione()
    var negatoPerSempre by remember { mutableStateOf(false) }
    val richiestaPermesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permesso = esiti.values.any { it }
        if (!permesso) negatoPerSempre = permessoNegatoPerSempre(context)
    }
    var sceltaOrario by remember { mutableStateOf<Boolean?>(null) } // false = partenza, true = arrivo
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val serveLaPosizione = stato.da?.tipo == TipoLuogo.POSIZIONE || stato.a?.tipo == TipoLuogo.POSIZIONE

    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            adesso = System.currentTimeMillis()
        }
    }
    // la posizione si cerca subito, mentre si sceglie la destinazione
    LaunchedEffect(permesso) {
        if (permesso && serveLaPosizione && stato.a == null) posizioneAttuale(context)
    }
    LaunchedEffect(stato.da, stato.a, stato.quando, permesso) {
        if (stato.da == null || stato.a == null) return@LaunchedEffect
        if (serveLaPosizione && !permesso) return@LaunchedEffect
        stato.cercaSeServe(context)
    }

    Scaffold(
        topBar = {
            BarraPorpora(
                titolo = "Tragitto",
                sottotitolo = "Bus, tram, metro e treni",
                indietro = indietro,
                azioni = {
                    if (stato.da != null && stato.a != null) {
                        IconButton(onClick = { ambito.launch { stato.cerca(context) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Calcola di nuovo")
                        }
                    }
                },
            )
        },
    ) { margini ->
        LazyColumn(
            modifier = Modifier
                .padding(margini)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ModuloLuoghi(
                    da = stato.da,
                    a = stato.a,
                    scegli = scegliLuogo,
                    inverti = { stato.inverti() },
                )
            }
            item {
                SceltaQuando(stato.quando, scegli = { arrivo ->
                    if (arrivo == null) stato.quando = Quando.Adesso else sceltaOrario = arrivo
                })
            }
            if (serveLaPosizione && !permesso) {
                item {
                    Avviso(
                        "Per partire o arrivare alla tua posizione serve il permesso di localizzazione. " +
                            "Le coordinate vengono inviate a Transitous solo per calcolare il tragitto." +
                            if (negatoPerSempre) " Il permesso è stato negato: puoi darlo dalle impostazioni dell'app." else "",
                    ) {
                        Button(
                            onClick = {
                                if (negatoPerSempre) apriImpostazioniApp(context) else richiestaPermesso.launch(PERMESSI_POSIZIONE)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Porpora),
                        ) { Text(if (negatoPerSempre) "Apri le impostazioni" else "Usa la mia posizione") }
                    }
                }
            }
            when (val esito = stato.esito) {
                EsitoTragitti.Vuoto -> if (stato.a == null) {
                    item {
                        Text(
                            "Scegli dove vuoi andare: cerca un indirizzo, un luogo o una fermata.",
                            color = Secondario,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                EsitoTragitti.InCorso -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(color = Porpora, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                        Text("Calcolo i tragitti…", color = Secondario, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                is EsitoTragitti.Errore -> item {
                    Avviso(esito.messaggio) {
                        TextButton(onClick = { ambito.launch { stato.cerca(context) } }) { Text("Riprova", color = Porpora) }
                    }
                }
                is EsitoTragitti.Pronto -> {
                    if (esito.cursorePrima != null) {
                        item {
                            TextButton(
                                onClick = { ambito.launch { stato.altri(dopo = false) } },
                                enabled = !stato.caricandoAltri,
                            ) { Text("Partenze precedenti", color = Porpora) }
                        }
                    }
                    if (esito.tragitti.isEmpty()) {
                        item {
                            Text(
                                "Nessun tragitto con i mezzi in questo orario. Prova a cambiare l'ora o il luogo.",
                                color = Secondario,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    itemsIndexed(esito.tragitti, key = { _, t -> t.chiave }) { i, t ->
                        CartaTragitto(t, adesso) { apriDettaglio(i) }
                    }
                    if (esito.cursoreDopo != null && esito.tragitti.isNotEmpty()) {
                        item {
                            TextButton(
                                onClick = { ambito.launch { stato.altri(dopo = true) } },
                                enabled = !stato.caricandoAltri,
                            ) { Text(if (stato.caricandoAltri) "Cerco…" else "Partenze successive", color = Porpora) }
                        }
                    }
                    esito.soloAPiedi?.let { piedi ->
                        item { CartaAPiedi(piedi) { apriDettaglio(-1) } }
                    }
                    item {
                        Text(
                            "Calcolato alle ${ora(esito.calcolatoAlle)}. Tocca un tragitto per la mappa e le fermate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondario,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            item { Attribuzione() }
        }
    }

    sceltaOrario?.let { arrivo ->
        DialogoOrario(
            arrivo = arrivo,
            iniziale = when (val q = stato.quando) {
                is Quando.Partenza -> q.istante
                is Quando.Arrivo -> q.istante
                Quando.Adesso -> System.currentTimeMillis()
            },
            chiudi = { sceltaOrario = null },
            conferma = { istante ->
                stato.quando = if (arrivo) Quando.Arrivo(istante) else Quando.Partenza(istante)
                sceltaOrario = null
            },
        )
    }
}

@Composable
private fun Avviso(testo: String, azione: @Composable () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = SuPorpora), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(testo, color = Porpora)
            azione()
        }
    }
}

@Composable
private fun ModuloLuoghi(da: Luogo?, a: Luogo?, scegli: (String) -> Unit, inverti: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CampoLuogo("Da", da, "Scegli la partenza", partenza = true) { scegli("da") }
                HorizontalDivider(color = Color(0xFFE2DDD8), modifier = Modifier.padding(start = 44.dp))
                CampoLuogo("A", a, "Dove vuoi andare?", partenza = false) { scegli("a") }
            }
            IconButton(
                onClick = inverti,
                modifier = Modifier.semantics { contentDescription = "Scambia partenza e arrivo" },
            ) {
                Text(
                    "⇅",
                    fontSize = 24.sp,
                    color = Porpora,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun CampoLuogo(etichetta: String, luogo: Luogo?, vuoto: String, partenza: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PallinoTappa(partenza)
        Column(Modifier.padding(start = 14.dp)) {
            Text(etichetta, style = MaterialTheme.typography.labelSmall, color = Secondario)
            Text(
                luogo?.nome ?: vuoto,
                color = if (luogo == null) Secondario else Inchiostro,
                fontWeight = if (luogo == null) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (luogo != null && luogo.dettaglio.isNotBlank()) {
                Text(
                    luogo.dettaglio,
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Cerchio bianco bordato di porpora per la partenza, pieno d'oro per l'arrivo. */
@Composable
fun PallinoTappa(partenza: Boolean, lato: Dp = 16.dp) {
    Box(
        Modifier
            .size(lato)
            .background(if (partenza) Color.White else Oro, CircleShape)
            .border(3.dp, if (partenza) Porpora else Inchiostro, CircleShape),
    )
}

@Composable
private fun SceltaQuando(quando: Quando, scegli: (Boolean?) -> Unit) {
    val colori = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Oro,
        selectedLabelColor = Inchiostro,
        containerColor = Color.White,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = quando == Quando.Adesso,
            onClick = { scegli(null) },
            label = { Text("Adesso") },
            colors = colori,
        )
        FilterChip(
            selected = quando is Quando.Partenza,
            onClick = { scegli(false) },
            label = { Text(if (quando is Quando.Partenza) "Parti " + giornoEOra(quando.istante) else "Parti alle…") },
            colors = colori,
        )
        FilterChip(
            selected = quando is Quando.Arrivo,
            onClick = { scegli(true) },
            label = { Text(if (quando is Quando.Arrivo) "Arriva " + giornoEOra(quando.istante) else "Arriva entro…") },
            colors = colori,
        )
    }
}

private fun giornoEOra(istante: Long): String {
    val zona = ZoneId.systemDefault()
    val giorno = Instant.ofEpochMilli(istante).atZone(zona).toLocalDate()
    val oggi = LocalDate.now(zona)
    val prefisso = when (giorno) {
        oggi -> ""
        oggi.plusDays(1) -> "domani "
        else -> giorno.dayOfMonth.toString() + "/" + giorno.monthValue + " "
    }
    return prefisso + ora(istante)
}

@Composable
private fun DialogoOrario(arrivo: Boolean, iniziale: Long, chiudi: () -> Unit, conferma: (Long) -> Unit) {
    val zona = ZoneId.systemDefault()
    val inizio = Instant.ofEpochMilli(iniziale).atZone(zona)
    val statoOra = rememberTimePickerState(initialHour = inizio.hour, initialMinute = inizio.minute, is24Hour = true)
    var domani by remember { mutableStateOf(inizio.toLocalDate() == LocalDate.now(zona).plusDays(1)) }

    Dialog(onDismissRequest = chiudi) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (arrivo) "Vuoi arrivare entro le…" else "Vuoi partire alle…",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val colori = FilterChipDefaults.filterChipColors(selectedContainerColor = Oro, selectedLabelColor = Inchiostro)
                    FilterChip(selected = !domani, onClick = { domani = false }, label = { Text("Oggi") }, colors = colori)
                    FilterChip(selected = domani, onClick = { domani = true }, label = { Text("Domani") }, colors = colori)
                }
                TimePicker(state = statoOra)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = chiudi) { Text("Annulla", color = Secondario) }
                    TextButton(onClick = {
                        val giorno = LocalDate.now(zona).plusDays(if (domani) 1 else 0)
                        conferma(giorno.atTime(statoOra.hour, statoOra.minute).atZone(zona).toInstant().toEpochMilli())
                    }) { Text("Conferma", color = Porpora, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Risultati
// ---------------------------------------------------------------------------

@Composable
private fun CartaTragitto(t: Tragitto, adesso: Long, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ora(t.partenza)} – ${ora(t.arrivo)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(testoDurata(t.durata), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            SequenzaTratti(t.tratti)
            val primo = t.mezzi.firstOrNull()
            if (primo != null) {
                val tra = ((primo.partenza - adesso) / 60_000).toInt()
                val quando = when {
                    tra in 0..59 -> if (tra < 1) "in partenza" else "tra $tra min"
                    else -> "alle ${ora(primo.partenza)}"
                }
                Text(
                    "${primo.nomeMezzo} ${primo.linea} $quando da ${primo.da.nome}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val cambi = when (t.cambi) {
                0 -> "Nessun cambio"
                1 -> "1 cambio"
                else -> "${t.cambi} cambi"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$cambi · ${t.minutiAPiedi} min a piedi",
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    modifier = Modifier.weight(1f),
                )
                when {
                    t.mezzi.any { it.cancellato } -> Text("Corsa soppressa", style = MaterialTheme.typography.bodySmall, color = Porpora, fontWeight = FontWeight.Bold)
                    t.mezzi.any { it.tempoReale } -> Row(verticalAlignment = Alignment.CenterVertically) {
                        FonteTempo(true)
                        Text(" tempo reale", style = MaterialTheme.typography.labelSmall, color = Porpora)
                    }
                    else -> FonteTempo(false)
                }
            }
        }
    }
}

@Composable
private fun CartaAPiedi(t: Tragitto, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconaPiedi(lato = 22.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text("Tutto a piedi: ${testoDurata(t.durata)}", fontWeight = FontWeight.SemiBold)
                if (t.metriAPiedi > 0) {
                    Text(testoMetri(t.metriAPiedi), style = MaterialTheme.typography.bodySmall, color = Secondario)
                }
            }
        }
    }
}

/** [a piedi 5] › [714] › [Metro B] */
@Composable
fun SequenzaTratti(tratti: List<Tratto>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tratti.forEachIndexed { i, tratto ->
            if (i > 0) {
                Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                    Text("›", color = Secondario, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (tratto.aPiedi) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconaPiedi(lato = 16.dp)
                        Text(
                            "${(tratto.durata + 59) / 60}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondario,
                        )
                    }
                } else {
                    BadgeLinea(tratto.linea.ifEmpty { tratto.nomeMezzo })
                }
            }
        }
    }
}

/** Omino stilizzato per i tratti a piedi. */
@Composable
fun IconaPiedi(colore: Color = Secondario, lato: Dp = 16.dp) {
    Canvas(Modifier.size(lato)) {
        val w = size.width
        val h = size.height
        val tratto = w * 0.13f
        drawCircle(color = colore, radius = w * 0.12f, center = Offset(w * 0.56f, h * 0.13f))
        drawLine(color = colore, start = Offset(w * 0.53f, h * 0.30f), end = Offset(w * 0.46f, h * 0.60f), strokeWidth = tratto, cap = StrokeCap.Round)
        drawLine(color = colore, start = Offset(w * 0.46f, h * 0.60f), end = Offset(w * 0.28f, h * 0.94f), strokeWidth = tratto, cap = StrokeCap.Round)
        drawLine(color = colore, start = Offset(w * 0.46f, h * 0.60f), end = Offset(w * 0.68f, h * 0.94f), strokeWidth = tratto, cap = StrokeCap.Round)
        drawLine(color = colore, start = Offset(w * 0.51f, h * 0.38f), end = Offset(w * 0.27f, h * 0.52f), strokeWidth = tratto, cap = StrokeCap.Round)
        drawLine(color = colore, start = Offset(w * 0.51f, h * 0.38f), end = Offset(w * 0.76f, h * 0.50f), strokeWidth = tratto, cap = StrokeCap.Round)
    }
}

/** Attribuzione richiesta da Transitous, con il collegamento alle fonti. */
@Composable
fun Attribuzione() {
    val collegamento = LocalUriHandler.current
    Text(
        "Tragitti calcolati da Transitous con i dati di Roma Mobilità, Cotral, Trenitalia e OpenStreetMap. " +
            "Tocca qui per tutte le fonti.",
        style = MaterialTheme.typography.bodySmall,
        color = Secondario,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { collegamento.openUri(Transitous.FONTI) } } // senza un browser non succede nulla
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

// ---------------------------------------------------------------------------
// Scelta di partenza e arrivo
// ---------------------------------------------------------------------------

@Composable
fun SceltaLuogoSchermo(campo: String, indietro: () -> Unit) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    var testo by rememberSaveable { mutableStateOf("") }
    var luoghi by remember { mutableStateOf<List<Luogo>>(emptyList()) }
    var fermate by remember { mutableStateOf<List<Fermata>>(emptyList()) }
    var cercando by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf<String?>(null) }
    val recenti = remember { Archivio.luoghiRecenti() }
    val fermatePreferite by produceState(initialValue = emptyList<Fermata>(), preferiti, orario) {
        value = withContext(Dispatchers.IO) {
            orario?.fermate(preferiti)?.values?.sortedBy { it.nome }.orEmpty()
        }
    }
    val fuoco = remember { FocusRequester() }
    LaunchedEffect(Unit) { fuoco.requestFocus() }

    fun scegli(luogo: Luogo) {
        Archivio.ricordaLuogo(luogo)
        if (campo == "da") StatoTragitto.da = luogo else StatoTragitto.a = luogo
        indietro()
    }

    LaunchedEffect(testo, orario) {
        val t = testo.trim()
        errore = null
        if (t.length < 3) {
            luoghi = emptyList()
            fermate = emptyList()
            cercando = false
            return@LaunchedEffect
        }
        delay(400)
        fermate = withContext(Dispatchers.IO) { orario?.cercaFermate(t)?.take(5).orEmpty() }
        cercando = true
        try {
            luoghi = Transitous.cerca(t, ultimaPosizioneNota()?.let { Punto(it.latitude, it.longitude) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            luoghi = emptyList()
            errore = "La ricerca degli indirizzi non è disponibile: controlla la connessione."
        } finally {
            cercando = false
        }
    }

    Scaffold(
        topBar = { BarraPorpora(if (campo == "da") "Da dove parti?" else "Dove vai?", indietro = indietro) },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .consumeWindowInsets(margini)
                .imePadding() // i risultati restano sopra la tastiera
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = testo,
                onValueChange = { testo = it },
                placeholder = { Text("Indirizzo, luogo o fermata") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (testo.isNotEmpty()) {
                        IconButton(onClick = { testo = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Cancella") }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(fuoco),
            )
            if (cercando) {
                LinearProgressIndicator(color = Porpora, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (testo.trim().length < 3) {
                    item {
                        RigaLuogo(
                            icona = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Porpora) },
                            nome = "La mia posizione",
                            dettaglio = "Dal telefono: va a Transitous solo per calcolare il tragitto",
                        ) { scegli(Luogo.posizione()) }
                    }
                    if (recenti.isNotEmpty()) {
                        item { Intestazione("Recenti") }
                        items(recenti) { l -> RigaLuogo(icona = { IconaLuogo(l.tipo) }, nome = l.nome, dettaglio = l.dettaglio) { scegli(l) } }
                    }
                    if (fermatePreferite.isNotEmpty()) {
                        item { Intestazione("Fermate preferite") }
                        items(fermatePreferite) { f ->
                            RigaLuogo(
                                icona = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Porpora) },
                                nome = f.nome,
                                dettaglio = "Palina ${f.codice}",
                            ) { scegli(f.comeLuogo()) }
                        }
                    }
                } else {
                    if (fermate.isNotEmpty()) {
                        item { Intestazione("Fermate") }
                        items(fermate) { f ->
                            RigaLuogo(icona = { IconaLuogo(TipoLuogo.FERMATA) }, nome = f.nome, dettaglio = "Palina ${f.codice}") {
                                scegli(f.comeLuogo())
                            }
                        }
                    }
                    if (luoghi.isNotEmpty()) {
                        item { Intestazione("Indirizzi e luoghi") }
                        items(luoghi) { l -> RigaLuogo(icona = { IconaLuogo(l.tipo) }, nome = l.nome, dettaglio = l.dettaglio) { scegli(l) } }
                    }
                    errore?.let { e -> item { Text(e, color = Secondario, modifier = Modifier.padding(16.dp)) } }
                    if (!cercando && errore == null && luoghi.isEmpty() && fermate.isEmpty()) {
                        item { Text("Nessun risultato per \"${testo.trim()}\".", color = Secondario, modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconaLuogo(tipo: TipoLuogo) {
    when (tipo) {
        TipoLuogo.FERMATA -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(Color.White, CircleShape)
                    .border(3.dp, Porpora, CircleShape),
            )
        }
        TipoLuogo.POSIZIONE -> Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Porpora)
        else -> Icon(Icons.Filled.Place, contentDescription = null, tint = Secondario)
    }
}

@Composable
private fun RigaLuogo(icona: @Composable () -> Unit, nome: String, dettaglio: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { icona() }
        Column(Modifier.padding(start = 12.dp)) {
            Text(nome, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (dettaglio.isNotBlank()) {
                Text(dettaglio, style = MaterialTheme.typography.bodySmall, color = Secondario, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = Color(0xFFE2DDD8))
}
