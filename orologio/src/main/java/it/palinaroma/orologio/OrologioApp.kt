package it.palinaroma.orologio

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import it.palinaroma.dati.impostaLingua

/** L'app per l'orologio: i testi (del motore) nella lingua dell'orologio. */
class OrologioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        impostaLinguaOrologio(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        impostaLinguaOrologio(this)
    }
}

/**
 * La lingua dell'orologio; le prove sull'emulatore (che e' in inglese) la
 * fissano in italiano con la preferenza "lingua".
 */
fun impostaLinguaOrologio(context: Context) {
    impostaLingua(context, context.getSharedPreferences("orologio", Context.MODE_PRIVATE).getString("lingua", null))
}
