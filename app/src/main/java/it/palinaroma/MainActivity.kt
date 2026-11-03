package it.palinaroma

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.palinaroma.ui.DettaglioTragittoSchermo
import it.palinaroma.ui.FermataSchermo
import it.palinaroma.ui.HomeSchermo
import it.palinaroma.ui.LineaSchermo
import it.palinaroma.ui.OrarioSchermo
import it.palinaroma.ui.Porpora
import it.palinaroma.ui.SceltaLuogoSchermo
import it.palinaroma.ui.StatoTragitto
import it.palinaroma.ui.TemaPalina
import it.palinaroma.ui.TragittoSchermo
import it.palinaroma.ui.comeLuogo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Porpora.toArgb()))
        setContent {
            TemaPalina { Navigazione() }
        }
    }
}

@Composable
private fun Navigazione() {
    val nav = rememberNavController()
    val apriFermata: (String) -> Unit = { nav.navigate("fermata/" + Uri.encode(it)) }
    val apriLinea: (String, Int?) -> Unit = { id, direzione ->
        nav.navigate("linea/" + Uri.encode(id) + "?dir=" + (direzione ?: -1))
    }
    val apriTragitto: () -> Unit = { nav.navigate("tragitto") { launchSingleTop = true } }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeSchermo(
                apriFermata = apriFermata,
                apriLinea = { apriLinea(it, null) },
                apriOrario = { nav.navigate("orario") },
                apriTragitto = apriTragitto,
            )
        }
        composable(
            "fermata/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { voce ->
            FermataSchermo(
                fermataId = voce.arguments?.getString("id").orEmpty(),
                indietro = { nav.popBackStack() },
                apriLinea = apriLinea,
                comeArrivare = { fermata ->
                    StatoTragitto.verso(fermata.comeLuogo())
                    apriTragitto()
                },
            )
        }
        composable(
            "linea/{id}?dir={dir}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("dir") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
        ) { voce ->
            LineaSchermo(
                lineaId = voce.arguments?.getString("id").orEmpty(),
                direzioneIniziale = voce.arguments?.getInt("dir")?.takeIf { it >= 0 },
                indietro = { nav.popBackStack() },
                apriFermata = apriFermata,
            )
        }
        composable("orario") {
            OrarioSchermo(indietro = { nav.popBackStack() })
        }
        composable("tragitto") {
            TragittoSchermo(
                indietro = { nav.popBackStack() },
                scegliLuogo = { campo -> nav.navigate("luogo/$campo") },
                apriDettaglio = { indice -> nav.navigate("tragitto/$indice") },
            )
        }
        composable(
            "luogo/{campo}",
            arguments = listOf(navArgument("campo") { type = NavType.StringType }),
        ) { voce ->
            SceltaLuogoSchermo(
                campo = voce.arguments?.getString("campo") ?: "a",
                indietro = { nav.popBackStack() },
            )
        }
        composable(
            "tragitto/{indice}",
            arguments = listOf(navArgument("indice") { type = NavType.IntType }),
        ) { voce ->
            DettaglioTragittoSchermo(
                indice = voce.arguments?.getInt("indice") ?: 0,
                indietro = { nav.popBackStack() },
                apriFermata = apriFermata,
            )
        }
    }
}
