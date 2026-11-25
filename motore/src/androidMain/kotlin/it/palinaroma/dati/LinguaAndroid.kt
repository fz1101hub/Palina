package it.palinaroma.dati

import android.content.Context

/**
 * La lingua dei testi su Android e Wear OS: quella del telefono (o quella
 * scelta per l'app nelle impostazioni di Android 13), oppure [scelta] dalle
 * impostazioni di Palina ("" o null per seguire il telefono).
 */
fun impostaLingua(context: Context, scelta: String?) {
    val sistema = context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
    Lingue.imposta(sistema, scelta)
}
