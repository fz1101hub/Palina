package it.palinaroma.ponte

import it.palinaroma.dati.tx

/*
 * Cosa mostrano riquadro, widget e complicazioni dell'orologio: lo stesso per
 * Wear OS e per Apple Watch (complicazioni e Smart Stack).
 */

/** La fermata da mostrare: la prima con qualche passaggio, altrimenti la prima. */
fun fermataDelRiquadro(t: TabelloneOrologio?, adesso: Long): FermataOrologio? =
    t?.fermate?.firstOrNull { f -> f.righe.any { it.prossimi(adesso).isNotEmpty() } } ?: t?.fermate?.firstOrNull()

/** Una riga del widget: la linea nel suo colore e i prossimi passaggi. */
data class RigaWidget(val linea: String, val colore: Int, val passaggi: String, val tempoReale: Boolean)

/** Quello che mostra il widget, preparato fuori da Remote Compose (si prova sul computer). */
data class ContenutoWidget(val titolo: String, val righe: List<RigaWidget>, val nota: String?)

/**
 * Il contenuto del widget per il tabellone [t]: come il riquadro, la prima
 * fermata con passaggi. Il widget piccolo (2x1) ha una riga sola, il grande (2x2)
 * tre e l'ora dei tempi. [adesso] in secondi.
 */
fun contenutoWidget(t: TabelloneOrologio?, adesso: Long, piccolo: Boolean): ContenutoWidget {
    val fermata = fermataDelRiquadro(t, adesso)
        ?: return ContenutoWidget("Palina", emptyList(), t?.nota ?: tx("Apri Palina per chiedere i tempi al telefono"))
    val righe = fermata.righe.filter { it.prossimi(adesso).isNotEmpty() }
        .take(if (piccolo) 1 else 3)
        .map { RigaWidget(it.linea.take(10), it.colore, testoPassaggi(it, adesso, 2), it.tempoReale) }
    val nota = when {
        righe.isEmpty() -> tx("Nessun passaggio a breve")
        piccolo -> null
        else -> tx("tempi delle {0}", oraBreve(t!!.creato))
    }
    return ContenutoWidget(fermata.nomeBreve, righe, nota)
}
