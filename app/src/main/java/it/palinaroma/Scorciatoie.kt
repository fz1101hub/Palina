package it.palinaroma

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import it.palinaroma.dati.Archivio
import it.palinaroma.ponte.scorciatoie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le scorciatoie tenendo premuta l'icona: fermata piu' vicina, i tragitti
 * salvati, numero della palina (i testi dal motore, come sull'iPhone).
 */
object Scorciatoie {
    /** Le icone del disegno: segnaposto, percorso, tastierino. */
    private fun icona(id: String): Int = when {
        id == "vicina" -> R.drawable.ic_scorciatoia_vicina
        id.startsWith("tragitto:") -> R.drawable.ic_scorciatoia_tragitto
        else -> R.drawable.ic_scorciatoia_palina
    }

    fun aggiorna(context: Context) {
        val elenco = scorciatoie(Archivio.salvati.value).mapIndexed { i, s ->
            ShortcutInfoCompat.Builder(context, s.id)
                .setShortLabel(s.breve)
                .setLongLabel(s.titolo)
                .setRank(i)
                .setIcon(IconCompat.createWithResource(context, icona(s.id)))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.APRI, s.id),
                )
                .build()
        }
        runCatching {
            val massimo = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            ShortcutManagerCompat.setDynamicShortcuts(context, elenco.take(massimo))
        }
    }
}

/**
 * Cosa aprire appena l'app parte o torna in primo piano: da una scorciatoia,
 * da un widget o da una notifica ("vicina", "palina", "tragitto:<id>",
 * "fermata:<id>", "scendi", "home").
 */
object Richieste {
    private val _apri = MutableStateFlow<String?>(null)
    val apri: StateFlow<String?> = _apri.asStateFlow()

    fun da(intent: Intent?) {
        val cosa = intent?.getStringExtra(MainActivity.APRI) ?: return
        intent.removeExtra(MainActivity.APRI)
        _apri.value = cosa
    }

    fun fatta() {
        _apri.value = null
    }

    /** "palina": la Home apre la ricerca con la tastiera dei numeri. */
    private val _cercaPalina = MutableStateFlow(false)
    val cercaPalina: StateFlow<Boolean> = _cercaPalina.asStateFlow()

    fun cercaPalina(si: Boolean) {
        _cercaPalina.value = si
    }
}
