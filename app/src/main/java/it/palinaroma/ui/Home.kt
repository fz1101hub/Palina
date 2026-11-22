@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.palinaroma.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import it.palinaroma.dati.Linea
import it.palinaroma.dati.Orario
import it.palinaroma.dati.PartenzaTransitous
import it.palinaroma.dati.StatoAggiornamento
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.Transitous
import it.palinaroma.dati.ancoraAttesi
import it.palinaroma.dati.arriviFermata
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.dividiArrivi
import it.palinaroma.dati.fraseArrivo
import it.palinaroma.dati.lineeFissate
import it.palinaroma.dati.minutiAttesa
import it.palinaroma.dati.posizioni
import it.palinaroma.dati.precisione
import it.palinaroma.dati.preferitaVicina
import it.palinaroma.dati.testoAggiornato
import it.palinaroma.dati.testoMetri
import it.palinaroma.dati.testoPoi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class FermataConLinee(val fermata: Fermata, val distanza: Double?, val linee: List<String>)

private fun Orario.conLinee(fermata: Fermata, distanza: Double?) =
    FermataConLinee(fermata, distanza, lineeAllaFermata(fermata.id).map { it.linea.nome }.distinct())

/** Spazio in fondo agli elenchi, perche' il pulsante "Come arrivo…" non copra le ultime righe. */
private val SOTTO_IL_PULSANTE = PaddingValues(bottom = 96.dp)

/** Le preferite con i passaggi completi (tempo reale e orario di Transitous): le altre solo tempo reale. */
private const val PREFERITE_COMPLETE = 4

@Composable
fun HomeSchermo(
    apriFermata: (String) -> Unit,
    apriLinea: (String) -> Unit,
    apriOrario: () -> Unit,
    apriTragitto: () -> Unit,
    apriTragittoVerso: (String) -> Unit,
) {
    val orario by Archivio.orario.collectAsStateWithLifecycle()
    val pronto by Archivio.pronto.collectAsStateWithLifecycle()
    var cercando by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = cercando) { cercando = false }

    Scaffold(
        containerColor = Sfondo,
        topBar = {
            BarraSuperiore("Palina Roma", azioni = {
                IconButton(onClick = apriOrario) {
                    Icon(Icons.Filled.Settings, contentDescription = "Impostazioni")
                }
            })
        },
        floatingActionButton = {
            if (!cercando) {
                ExtendedFloatingActionButton(
                    onClick = apriTragitto,
                    containerColor = Oro,
                    contentColor = Inchiostro,
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    text = { Text("Come arrivo…", fontWeight = FontWeight.Bold) },
                    // il testo del pulsante non arriva al lettore di schermo: lo si dice qui
                    modifier = Modifier
                        .testTag("come-arrivo")
                        .semantics { contentDescription = "Come arrivo" },
                )
            }
        },
    ) { margini ->
        Column(
            Modifier
                .padding(margini)
                .consumeWindowInsets(margini)
                .imePadding() // con la tastiera aperta l'elenco finisce sopra la tastiera, non sotto
                .fillMaxSize(),
        ) {
            val o = orario
            if (o == null) {
                if (pronto) {
                    PannelloOrario(Modifier.padding(16.dp), primaVolta = true)
                } else {
                    // primo avvio: si sta copiando l'orario incluso nell'app (un paio di secondi)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Preparo l'orario…", color = Secondario)
                        LinearProgressIndicator(color = Accento, trackColor = AccentoFondo, modifier = Modifier.fillMaxWidth())
                    }
                }
                return@Column
            }
            if (cercando) {
                SchedaCerca(o, apriFermata, apriLinea, apriTragittoVerso, chiudi = { cercando = false })
            } else {
                Tabellone(o, apriFermata, apriCerca = { cercando = true }, apriOrario = apriOrario)
            }
        }
    }
}

/** I passaggi delle fermate mostrate in Home, e di quando sono. */
private data class ArriviHome(
    val perFermata: Map<String, List<Arrivo>> = emptyMap(),
    val aggiornatoAlle: Long = 0L,
    val errore: Boolean = false,
)

/**
 * Tiene aggiornati i passaggi delle fermate in Home, ogni 30 secondi e solo
 * mentre la Home si vede. Per tutte: il feed in tempo reale di Roma Mobilita'
 * (uno solo per tutta la citta', gia' in memoria se si e' aperta una fermata).
 * Per le prime preferite anche gli orari di Transitous, al massimo una volta al minuto.
 */
@Composable
private fun rememberArriviHome(orario: Orario, complete: List<Fermata>, soloTempoReale: List<String>, riprova: Int): ArriviHome {
    var stato by remember { mutableStateOf(ArriviHome()) }
    val ciclo = LocalLifecycleOwner.current
    val partenze = remember { HashMap<String, Pair<Long, List<PartenzaTransitous>>>() }
    val idComplete = complete.map { it.id }
    LaunchedEffect(orario, idComplete, soloTempoReale, riprova) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val adessoMs = System.currentTimeMillis()
                val (corse, mezzi) = coroutineScope {
                    val c = async { seRiesce { TempoReale.corse() } }
                    val m = async { seRiesce { TempoReale.mezzi() } }
                    complete.map { f ->
                        async {
                            val gia = partenze[f.id]
                            if (gia == null || adessoMs - gia.first > 60_000) {
                                seRiesce { Transitous.partenze(f.id, f.lat, f.lon) }?.let { partenze[f.id] = adessoMs to it }
                            }
                        }
                    }.awaitAll()
                    c.await() to m.await().orEmpty()
                }
                val fresche = complete.any { partenze[it.id]?.first == adessoMs }
                if (corse != null || fresche) {
                    val ids = (idComplete + soloTempoReale).distinct()
                    val adesso = System.currentTimeMillis() / 1000
                    val calcolati = withContext(Dispatchers.IO) {
                        ids.associateWith { id -> arriviFermata(id, orario, corse, mezzi, partenze[id]?.second, adesso) }
                    }
                    stato = ArriviHome(calcolati, System.currentTimeMillis(), errore = false)
                } else {
                    // senza rete restano i tempi gia' letti, che si consumano da soli
                    stato = stato.copy(errore = true)
                }
                delay(30_000)
            }
        }
    }
    return stato
}

/** La posizione e le fermate vicine, con il permesso che serve per averle. */
@Stable
private class StatoVicino {
    var negatoPerSempre by mutableStateOf(false)
    var messaggio by mutableStateOf<String?>(null)
    var fermate by mutableStateOf<List<FermataConLinee>>(emptyList())
    var precisione by mutableStateOf<Float?>(null)
    var affinando by mutableStateOf(false)
    var lat by mutableStateOf<Double?>(null)
    var lon by mutableStateOf<Double?>(null)
    var tentativo by mutableIntStateOf(0)
}

@Composable
private fun rememberVicine(orario: Orario): Triple<StatoVicino, Boolean, () -> Unit> {
    val context = LocalContext.current
    val stato = remember { StatoVicino() }
    var permesso by rememberPermessoPosizione()
    val richiesta = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        permesso = esiti.values.any { it }
        if (permesso) stato.tentativo++ else stato.negatoPerSempre = permessoNegatoPerSempre(context)
    }
    // le fermate compaiono con la prima posizione (anche approssimativa) e si
    // aggiornano man mano che arriva una posizione piu' precisa
    LaunchedEffect(permesso, stato.tentativo, orario) {
        if (!permesso) return@LaunchedEffect
        if (stato.fermate.isEmpty()) stato.messaggio = "Cerco la tua posizione…"
        stato.affinando = true
        var usataLat = Double.NaN
        var usataLon = Double.NaN
        var usataPrecisione = Float.MAX_VALUE
        try {
            posizioni(context).collect { p ->
                stato.precisione = p.precisione()
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
                    stato.lat = p.latitude
                    stato.lon = p.longitude
                    stato.fermate = trovate
                    stato.messaggio = if (trovate.isEmpty()) "Nessuna fermata entro 700 metri." else null
                }
            }
        } finally {
            stato.affinando = false
        }
        if (usataLat.isNaN()) stato.messaggio = "Posizione non disponibile: attiva la localizzazione e riprova."
    }
    val chiedi: () -> Unit = { richiesta.launch(PERMESSI_POSIZIONE) }
    return Triple(stato, permesso, chiedi)
}

/** La Home: la preferita a cui sei vicino in cima, poi le altre preferite e le fermate vicine, tutte con i minuti. */
@Composable
private fun Tabellone(orario: Orario, apriFermata: (String) -> Unit, apriCerca: () -> Unit, apriOrario: () -> Unit) {
    val context = LocalContext.current
    val preferiti by Archivio.preferiti.collectAsStateWithLifecycle()
    val chiaviFissate by Archivio.fissate.collectAsStateWithLifecycle()
    val aggiornamento by Archivio.aggiornamento.collectAsStateWithLifecycle()
    val (vicino, permessoPosizione, chiediPermesso) = rememberVicine(orario)
    var riprova by remember { mutableIntStateOf(0) }
    var adessoMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            adessoMs = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val adesso = adessoMs / 1000

    val fermatePreferite by produceState(initialValue = emptyList<Fermata>(), preferiti, orario) {
        value = withContext(Dispatchers.IO) {
            val trovate = orario.fermate(preferiti)
            preferiti.mapNotNull { trovate[it] }.sortedBy { it.nome }
        }
    }
    val lat = vicino.lat
    val lon = vicino.lon
    val inEvidenza = remember(fermatePreferite, lat, lon) { preferitaVicina(fermatePreferite, lat, lon) }
    // le preferite dalla piu' vicina; senza posizione in ordine di nome
    val preferiteOrdinate: List<Pair<Fermata, Double?>> = remember(fermatePreferite, lat, lon) {
        if (lat == null || lon == null) fermatePreferite.map { it to null }
        else fermatePreferite.map { it to distanzaMetri(lat, lon, it.lat, it.lon) }.sortedBy { it.second }
    }
    val idVicine = vicino.fermate.map { it.fermata.id }
    val arrivi = rememberArriviHome(
        orario,
        complete = preferiteOrdinate.take(PREFERITE_COMPLETE).map { it.first },
        soloTempoReale = idVicine,
        riprova = riprova,
    )
    val lineePerFermata = remember(vicino.fermate) { vicino.fermate.associate { it.fermata.id to it.linee } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = SOTTO_IL_PULSANTE) {
        item { CampoCerca(apriCerca) }
        if (Archivio.daAggiornare() && aggiornamento !is StatoAggiornamento.InCorso) {
            item {
                Text(
                    "L'orario ha più di una settimana: tocca qui per aggiornarlo.",
                    color = Testo,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .background(AccentoFondo, RoundedCornerShape(14.dp))
                        .clickable(onClick = apriOrario)
                        .padding(12.dp),
                )
            }
        }
        if (arrivi.errore) {
            item {
                AvvisoRete(
                    if (arrivi.aggiornatoAlle > 0) "Non riesco ad aggiornare: i tempi sono quelli delle ${ora(arrivi.aggiornatoAlle)}."
                    else "Non riesco a leggere i tempi d'attesa. Controlla la connessione.",
                    riprova = { riprova++ },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (preferiteOrdinate.isNotEmpty()) {
            val evidenza = inEvidenza?.first
            val primaRiga: List<Pair<Fermata, Double?>> = inEvidenza?.let { listOf(it.first to it.second) } ?: emptyList()
            val resto = preferiteOrdinate.filter { it.first.id != evidenza?.id }
            if (primaRiga.isNotEmpty()) {
                item { TitoloConAggiornamento("Sei vicino a", arrivi.aggiornatoAlle, adessoMs) }
                items(primaRiga, key = { "e-" + it.first.id }) { (f, d) ->
                    SchedaPreferita(f, d, arrivi.perFermata[f.id], lineeFissate(f.id, chiaviFissate), adesso) { apriFermata(f.id) }
                }
            }
            if (resto.isNotEmpty()) {
                item {
                    if (primaRiga.isEmpty()) TitoloConAggiornamento("Preferiti", arrivi.aggiornatoAlle, adessoMs)
                    else Intestazione("Altri preferiti")
                }
                items(resto, key = { "p-" + it.first.id }) { (f, d) ->
                    SchedaPreferita(f, d, arrivi.perFermata[f.id], lineeFissate(f.id, chiaviFissate), adesso) { apriFermata(f.id) }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Intestazione("Vicino a te", Modifier.weight(1f))
                vicino.precisione?.let { metri ->
                    Text(
                        when {
                            vicino.affinando && metri > 30 -> "posizione approssimativa (±${metri.toInt()} m)"
                            metri > 150 -> "posizione poco precisa (±${metri.toInt()} m)"
                            else -> "posizione precisa a ${metri.toInt()} m"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondario,
                        modifier = Modifier.padding(end = 20.dp, bottom = 8.dp),
                    )
                }
            }
        }
        if (!permessoPosizione) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Per mostrarti le fermate qui intorno serve la tua posizione. Resta sul telefono.")
                    if (vicino.negatoPerSempre) {
                        // Android non mostra piu' la richiesta: il permesso si da' solo dalle impostazioni
                        Text(
                            "Il permesso è stato negato: puoi darlo dalle impostazioni dell'app, alla voce Autorizzazioni.",
                            color = Secondario,
                        )
                        Button(onClick = { apriImpostazioniApp(context) }, colors = pulsanteOro()) { Text("Apri le impostazioni") }
                    } else {
                        Button(onClick = chiediPermesso, colors = pulsanteOro()) { Text("Usa la mia posizione") }
                    }
                }
            }
        } else {
            if (vicino.affinando && vicino.fermate.isEmpty()) {
                item { LinearProgressIndicator(color = Accento, trackColor = AccentoFondo, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
            }
            vicino.messaggio?.let { testo ->
                item { Text(testo, color = Secondario, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }
            items(vicino.fermate, key = { "v-" + it.fermata.id }) { f ->
                val elenco = arrivi.perFermata[f.fermata.id]?.let { ancoraAttesi(it, adesso) }
                RigaFermata(
                    f.fermata,
                    f.distanza,
                    lineePerFermata[f.fermata.id].orEmpty(),
                    onClick = { apriFermata(f.fermata.id) },
                    arrivi = elenco,
                    adesso = adesso,
                )
            }
            item {
                TextButton(onClick = { vicino.tentativo++ }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Aggiorna la posizione", color = Accento)
                }
            }
        }
        if (preferiti.isEmpty()) {
            item {
                Text(
                    "Tocca il cuore in una fermata: la ritrovi qui in cima, con i suoi tempi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun pulsanteOro() = ButtonDefaults.buttonColors(containerColor = Oro, contentColor = Inchiostro)

/** Il titoletto di una sezione, con "aggiornato 20 s fa" a destra. */
@Composable
private fun TitoloConAggiornamento(titolo: String, aggiornatoAlle: Long, adessoMs: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        Intestazione(titolo, Modifier.weight(1f))
        if (aggiornatoAlle > 0) {
            Text(
                "aggiornato " + testoAggiornato(aggiornatoAlle, adessoMs),
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
                modifier = Modifier.padding(end = 20.dp, bottom = 8.dp),
            )
        }
    }
}

/** Il campo di ricerca in cima: un tocco apre la ricerca. */
@Composable
private fun CampoCerca(apri: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(Superficie, RoundedCornerShape(14.dp))
            .border(1.dp, Bordo, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "cerca", onClick = apri)
            .testTag("cerca")
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Secondario)
        Text("Fermata, palina, linea o luogo", color = Secondario)
    }
}

/** Una preferita come piccolo tabellone: le linee fissate (o le prime tre) grandi, le altre in breve. */
@Composable
private fun SchedaPreferita(
    fermata: Fermata,
    distanza: Double?,
    arrivi: List<Arrivo>?,
    fissate: Set<String>,
    adesso: Long,
    apri: () -> Unit,
) {
    val elenco = arrivi?.let { ancoraAttesi(it, adesso) }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .riquadroTabellone(FondoTabellone, Bordo)
            .testTag("preferita")
            .clickable(onClickLabel = "apri la fermata", onClick = apri)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                fermata.nome,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "palina ${fermata.codice}" + (distanza?.let { " · " + testoMetri(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Secondario,
            )
        }
        HorizontalDivider(color = Bordo)
        when {
            elenco == null -> Text("Carico i tempi…", color = Secondario, modifier = Modifier.padding(vertical = 10.dp))
            elenco.isEmpty() -> Text(
                "Nessun passaggio nei prossimi 90 minuti.",
                color = Secondario,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            else -> {
                val diviso = dividiArrivi(elenco, fissate)
                val principali = if (fissate.isNotEmpty()) diviso.fissate else elenco.take(3)
                val resto = if (fissate.isNotEmpty()) diviso.altre else elenco.drop(3)
                principali.forEach { RigaCompatta(it, adesso) }
                if (principali.isEmpty()) {
                    Text(
                        "Le linee che hai fissato non passano nei prossimi 90 minuti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondario,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (resto.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Altre:", style = MaterialTheme.typography.bodySmall, color = Secondario)
                        resto.take(4).forEach { PillolaAttesa(it, adesso) }
                        if (resto.size > 4) Text("+${resto.size - 4}", style = MaterialTheme.typography.bodySmall, color = Secondario)
                    }
                }
            }
        }
    }
}

/** Una linea in una preferita: numero, direzione, i minuti grandi. */
@Composable
private fun RigaCompatta(arrivo: Arrivo, adesso: Long) {
    val primo = arrivo.passaggi.first()
    val frase = fraseArrivo(arrivo, adesso)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clearAndSetSemantics { contentDescription = frase }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BadgeLinea(arrivo.nomeLinea, grande = true)
            Column(Modifier.weight(1f)) {
                Text(
                    arrivo.capolinea.ifEmpty { "Direzione non indicata" },
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val poi = testoPoi(arrivo.passaggi.drop(1).take(2).map { minutiAttesa(it.orario, adesso) })
                if (poi.isNotEmpty()) {
                    Text(poi, style = MaterialTheme.typography.bodySmall, color = Secondario)
                }
            }
            CifreAttesa(minutiAttesa(primo.orario, adesso), primo.tracciato, grandezza = 30)
        }
        HorizontalDivider(color = Divisore)
    }
}

/** La ricerca: fermate, numeri di palina e linee dall'orario; per un luogo si passa a "Come arrivo". */
@Composable
private fun SchedaCerca(
    orario: Orario,
    apriFermata: (String) -> Unit,
    apriLinea: (String) -> Unit,
    apriTragittoVerso: (String) -> Unit,
    chiudi: () -> Unit,
) {
    var testo by rememberSaveable { mutableStateOf("") }
    var linee by remember { mutableStateOf<List<Linea>>(emptyList()) }
    var fermate by remember { mutableStateOf<List<FermataConLinee>>(emptyList()) }
    val fuoco = remember { FocusRequester() }
    // la tastiera si apre solo la prima volta: tornando da una fermata non ricompare
    var giaAperta by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!giaAperta) runCatching { fuoco.requestFocus() }
        giaAperta = true
    }

    LaunchedEffect(testo, orario) {
        delay(250)
        val risultati = withContext(Dispatchers.IO) {
            orario.cercaLinee(testo) to orario.cercaFermate(testo).take(40).map { orario.conLinee(it, null) }
        }
        linee = risultati.first
        fermate = risultati.second
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = chiudi) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Chiudi la ricerca")
            }
            OutlinedTextField(
                value = testo,
                onValueChange = { testo = it },
                placeholder = { Text("Fermata, palina, linea o luogo") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (testo.isNotEmpty()) {
                        IconButton(onClick = { testo = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Cancella") }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fuoco),
            )
        }
        if (testo.isBlank()) {
            Text(
                "Scrivi il nome di una fermata, il numero della palina (è sul cartello, per esempio 70078), " +
                    "il numero di una linea o un indirizzo.",
                style = MaterialTheme.typography.bodyMedium,
                color = Secondario,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (testo.trim().length >= 3) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("cerca-luogo")
                            .clickable { apriTragittoVerso(testo.trim()) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Place, contentDescription = null, tint = Accento)
                        Column(Modifier.weight(1f)) {
                            Text("Come arrivo a «${testo.trim()}»", fontWeight = FontWeight.Medium)
                            Text("cerca anche tra indirizzi e luoghi", style = MaterialTheme.typography.bodySmall, color = Secondario)
                        }
                    }
                    HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            if (linee.isNotEmpty()) {
                item { Intestazione("Linee") }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        linee.forEach { l ->
                            BadgeLinea(
                                l.nome,
                                Modifier
                                    .testTag("linea")
                                    .clickable(onClickLabel = "apri la linea") { apriLinea(l.id) },
                                grande = true,
                            )
                        }
                    }
                }
            }
            if (fermate.isNotEmpty()) {
                item { Intestazione("Fermate") }
                items(fermate, key = { "f-" + it.fermata.id }) { f ->
                    RigaFermata(f.fermata, null, f.linee, onClick = { apriFermata(f.fermata.id) })
                }
            }
            if (testo.isNotBlank() && linee.isEmpty() && fermate.isEmpty()) {
                item {
                    Text(
                        "Nessuna fermata o linea per \"$testo\".",
                        color = Secondario,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
