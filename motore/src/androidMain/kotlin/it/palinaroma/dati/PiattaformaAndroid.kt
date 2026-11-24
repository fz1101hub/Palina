package it.palinaroma.dati

import java.text.Normalizer

private val ACCENTI = Regex("\\p{M}+")

actual fun senzaAccenti(testo: String): String =
    Normalizer.normalize(testo, Normalizer.Form.NFD).replace(ACCENTI, "")

actual fun adessoMs(): Long = System.currentTimeMillis()
