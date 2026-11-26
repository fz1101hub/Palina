package it.palinaroma

import android.content.Context
import android.speech.tts.TextToSpeech
import it.palinaroma.dati.Lingua
import it.palinaroma.dati.Lingue
import java.util.Locale

/**
 * La risposta a voce ("Lavoro. 64 per Stazione S. Pietro, tra 3 minuti..."):
 * quando Palina si apre dall'assistente o dalla scorciatoia "Quanto manca".
 * Il testo viene dal motore, lo stesso che legge Siri sull'iPhone.
 */
object LettoreVoce {
    private var voce: TextToSpeech? = null
    private var pronta = false
    private var inAttesa: String? = null

    fun leggi(context: Context, testo: String) {
        val v = voce
        if (v != null && pronta) {
            parla(v, testo)
            return
        }
        inAttesa = testo
        if (v != null) return
        voce = TextToSpeech(context.applicationContext) { stato ->
            pronta = stato == TextToSpeech.SUCCESS
            val t = voce
            val testoInAttesa = inAttesa
            if (pronta && t != null && testoInAttesa != null) {
                inAttesa = null
                parla(t, testoInAttesa)
            }
        }
    }

    private fun parla(v: TextToSpeech, testo: String) {
        val lingua = when (Lingue.attuale) {
            Lingua.IT -> Locale.ITALIAN
            Lingua.EN -> Locale.UK
            Lingua.FR -> Locale.FRENCH
            Lingua.ES -> Locale.forLanguageTag("es-ES")
            Lingua.DE -> Locale.GERMAN
            Lingua.PT -> Locale.forLanguageTag("pt-BR")
        }
        runCatching { v.language = lingua }
        v.speak(testo, TextToSpeech.QUEUE_FLUSH, null, "palina")
    }

    fun ferma() {
        voce?.stop()
    }
}
