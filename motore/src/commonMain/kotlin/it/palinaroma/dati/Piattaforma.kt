package it.palinaroma.dati

/** Il testo scomposto (NFD) senza i segni diacritici: "Città" -> "Citta". */
expect fun senzaAccenti(testo: String): String

/** Millisecondi dal 1970, come System.currentTimeMillis(). */
expect fun adessoMs(): Long
