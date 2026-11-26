package it.palinaroma

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.palinaroma.ui.CorsaSchermo
import it.palinaroma.ui.DettaglioTragittoSchermo
import it.palinaroma.ui.FermataSchermo
import it.palinaroma.ui.HomeSchermo
import it.palinaroma.ui.LineaSchermo
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.leggiRichiestaAvvisami
import it.palinaroma.dati.Aspetto
import it.palinaroma.ui.OrarioSchermo
import it.palinaroma.ui.SceltaLuogoSchermo
import it.palinaroma.ui.StatoTragitto
import it.palinaroma.ui.TemaPalina
import it.palinaroma.ui.TragittoSchermo
import it.palinaroma.ui.comeLuogo
import it.palinaroma.ui.rotta
import it.palinaroma.ui.SceltaDiscesaSchermo
import it.palinaroma.avviso.Avvisatore
import it.palinaroma.dati.Punto
import it.palinaroma.dati.posizioneAttuale
import it.palinaroma.widget.Widget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // la lingua dei testi (del motore): quella del telefono o quella scelta in Palina
        Archivio.linguaDelSistemaCambiata(this)
        // da una scorciatoia, da un widget o da una notifica
        if (savedInstanceState == null) Richieste.da(intent)
        enableEdgeToEdge()
        setContent {
            val aspetto by Archivio.aspetto.collectAsStateWithLifecycle()
            val cifreLed by Archivio.cifreLed.collectAsStateWithLifecycle()
            val scuro = when (aspetto) {
                Aspetto.AUTOMATICO -> isSystemInDarkTheme()
                Aspetto.CHIARO -> false
                Aspetto.SCURO -> true
            }
            // icone della barra di stato chiare sul tema scuro, scure su quello chiaro
            DisposableEffect(scuro) {
                val stile = if (scuro) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = stile, navigationBarStyle = stile)
                onDispose { }
            }
            // le etichette di prova (testTag) diventano identificativi visibili alla
            // prova automatica sull'emulatore; per chi usa l'app non cambia nulla
            @OptIn(ExperimentalComposeUiApi::class)
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                TemaPalina(scuro = scuro, cifreLed = cifreLed) { Navigazione() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Richieste.da(intent)
    }

    override fun onStop() {
        super.onStop()
        // uscendo da Palina i widget prendono i dati piu' recenti
        Widget.chiediDati(this, forza = false)
    }

    companion object {
        /** Cosa aprire: "vicina", "palina", "tragitto:<id>", "fermata:<id>", "linea:<id>", "avvisami:...", "scendi", "home". */
        const val APRI = "apri"
    }
}

/** Millisecondi della dissolvenza tra una schermata e l'altra. */
private const val DURATA_PASSAGGIO = 220

@Composable
private fun Navigazione() {
    val nav = rememberNavController()
    // Un doppio tocco veloce non deve aprire due volte la stessa schermata...
    val vai: (String) -> Unit = { rotta -> nav.navigate(rotta) { launchSingleTop = true } }
    // ...ne' chiuderne due insieme o lasciare lo schermo vuoto: si torna indietro
    // solo dalla schermata in primo piano, e mai oltre la prima.
    val indietro: () -> Unit = {
        if (nav.previousBackStackEntry != null &&
            nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
        ) {
            nav.popBackStack()
        }
    }
    val apriFermata: (String) -> Unit = { vai("fermata/" + Uri.encode(it)) }
    val apriLinea: (String, Int?) -> Unit = { id, direzione ->
        vai("linea/" + Uri.encode(id) + "?dir=" + (direzione ?: -1))
    }
    val apriTragitto: () -> Unit = { vai("tragitto") }
    // dalla ricerca della Home: "Come arrivo a ..." apre i tragitti e subito la scelta dell'arrivo
    val apriTragittoVerso: (String) -> Unit = { testo ->
        vai("tragitto")
        vai("luogo/a?testo=" + Uri.encode(testo))
    }

    val context = LocalContext.current
    val richiesta by Richieste.apri.collectAsStateWithLifecycle()
    val tornaAllaHome: () -> Unit = { nav.popBackStack("home", inclusive = false) }
    // un "Avvisami" lasciato in corso quando Android ha chiuso l'app riparte
    LaunchedEffect(Unit) {
        withTimeoutOrNull(30_000) { Archivio.orario.first { it != null } }
        runCatching { Avvisatore.riprendiSeServe(context) }
    }
    LaunchedEffect(richiesta) {
        val cosa = richiesta ?: return@LaunchedEffect
        Richieste.fatta()
        when {
            cosa == "home" -> tornaAllaHome()
            cosa == "palina" -> {
                tornaAllaHome()
                Richieste.cercaPalina(true)
            }
            cosa == "scendi" -> vai("scendi")
            cosa == "tragitto" -> {
                tornaAllaHome()
                apriTragitto()
            }
            cosa.startsWith("tragitto:") -> {
                val salvato = Archivio.salvati.value.firstOrNull { it.id == cosa.removePrefix("tragitto:") }
                tornaAllaHome()
                if (salvato != null) {
                    StatoTragitto.da = salvato.partenza
                    StatoTragitto.a = salvato.arrivo
                }
                apriTragitto()
            }
            cosa.startsWith("fermata:") -> {
                tornaAllaHome()
                apriFermata(cosa.removePrefix("fermata:"))
            }
            cosa.startsWith("linea:") -> {
                tornaAllaHome()
                apriLinea(cosa.removePrefix("linea:"), null)
            }
            // dal promemoria: "Avvisami" per il prossimo passaggio, con il conto alla rovescia in Home
            cosa.startsWith("avvisami:") -> {
                tornaAllaHome()
                val parti = leggiRichiestaAvvisami(cosa)
                if (parti != null) {
                    withTimeoutOrNull(30_000) { Archivio.orario.first { it != null } }
                    val s = runCatching { Avvisatore.avvia(context, parti[0], parti[1], parti[2], parti[3], scendi = false) }.getOrNull()
                    if (s == null) apriFermata(parti[0])
                }
            }
            cosa == "vicina" -> {
                tornaAllaHome()
                val orario = withTimeoutOrNull(20_000) { Archivio.orario.first { it != null } }
                val qui = posizioneAttuale(context)?.let { Punto(it.latitude, it.longitude) } ?: Archivio.posizioneRicordata()
                val vicina = if (orario != null && qui != null) {
                    withContext(Dispatchers.IO) { orario.fermateVicineUnite(qui.lat, qui.lon, 1500.0).firstOrNull()?.first }
                } else {
                    null
                }
                if (vicina != null) apriFermata(vicina.id)
            }
        }
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        // passaggio rapido tra le schermate (di serie dura 0,7 secondi)
        enterTransition = { fadeIn(tween(DURATA_PASSAGGIO)) },
        exitTransition = { fadeOut(tween(DURATA_PASSAGGIO)) },
        popEnterTransition = { fadeIn(tween(DURATA_PASSAGGIO)) },
        popExitTransition = { fadeOut(tween(DURATA_PASSAGGIO)) },
    ) {
        composable("home") {
            HomeSchermo(
                apriFermata = apriFermata,
                apriLinea = { apriLinea(it, null) },
                apriOrario = { vai("orario") },
                apriTragitto = apriTragitto,
                apriTragittoVerso = apriTragittoVerso,
                apriScendi = { vai("scendi") },
            )
        }
        composable(
            "fermata/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { voce ->
            val fermataId = voce.arguments?.getString("id").orEmpty()
            FermataSchermo(
                fermataId = fermataId,
                indietro = indietro,
                apriLinea = apriLinea,
                apriCorsa = { richiesta -> vai(richiesta.rotta()) },
                apriVettura = { linea, direzione, corsa, fermata ->
                    vai(
                        "linea/" + Uri.encode(linea) + "?dir=" + (direzione ?: -1) +
                            "&corsa=" + Uri.encode(corsa) + "&fermata=" + Uri.encode(fermata),
                    )
                },
                comeArrivare = { fermata ->
                    StatoTragitto.verso(fermata.comeLuogo())
                    apriTragitto()
                },
            )
        }
        composable(
            "linea/{id}?dir={dir}&corsa={corsa}&fermata={fermata}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("dir") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("corsa") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("fermata") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { voce ->
            LineaSchermo(
                lineaId = voce.arguments?.getString("id").orEmpty(),
                direzioneIniziale = voce.arguments?.getInt("dir")?.takeIf { it >= 0 },
                corsaEvidenziata = voce.arguments?.getString("corsa")?.takeIf { it.isNotEmpty() },
                fermataEvidenziata = voce.arguments?.getString("fermata")?.takeIf { it.isNotEmpty() },
                indietro = indietro,
                apriFermata = apriFermata,
            )
        }
        composable(
            "corsa?fermata={fermata}&linea={linea}&dir={dir}&previsto={previsto}&trip={trip}&astral={astral}&ritardo={ritardo}",
            arguments = listOf(
                navArgument("fermata") { type = NavType.StringType; defaultValue = "" },
                navArgument("linea") { type = NavType.StringType; defaultValue = "" },
                navArgument("dir") { type = NavType.IntType; defaultValue = -1 },
                navArgument("previsto") { type = NavType.LongType; defaultValue = 0L },
                navArgument("trip") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("astral") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("ritardo") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { voce ->
            val a = voce.arguments
            CorsaSchermo(
                corsaId = a?.getString("trip")?.takeIf { it.isNotEmpty() },
                fermataId = a?.getString("fermata").orEmpty(),
                lineaId = a?.getString("linea").orEmpty(),
                direzione = a?.getInt("dir")?.takeIf { it >= 0 },
                previsto = a?.getLong("previsto") ?: 0L,
                corsaAstral = a?.getString("astral")?.takeIf { it.isNotEmpty() },
                ritardo = a?.getString("ritardo")?.toIntOrNull(),
                indietro = indietro,
                apriFermata = apriFermata,
            )
        }
        composable("orario") {
            OrarioSchermo(indietro = indietro)
        }
        composable("scendi") {
            SceltaDiscesaSchermo(indietro = indietro)
        }
        composable("tragitto") {
            TragittoSchermo(
                indietro = indietro,
                scegliLuogo = { campo -> vai("luogo/$campo") },
                apriDettaglio = { indice -> vai("tragitto/$indice") },
            )
        }
        composable(
            "luogo/{campo}?testo={testo}",
            arguments = listOf(
                navArgument("campo") { type = NavType.StringType },
                navArgument("testo") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { voce ->
            SceltaLuogoSchermo(
                campo = voce.arguments?.getString("campo") ?: "a",
                indietro = indietro,
                testoIniziale = voce.arguments?.getString("testo").orEmpty(),
            )
        }
        composable(
            "tragitto/{indice}",
            arguments = listOf(navArgument("indice") { type = NavType.IntType }),
        ) { voce ->
            DettaglioTragittoSchermo(
                indice = voce.arguments?.getInt("indice") ?: 0,
                indietro = indietro,
                apriFermata = apriFermata,
            )
        }
    }
}
