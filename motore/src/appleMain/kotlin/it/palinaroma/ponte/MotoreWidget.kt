package it.palinaroma.ponte

import it.palinaroma.dati.CodificaTragittiSalvati
import it.palinaroma.dati.Orario
import it.palinaroma.dati.Punto
import it.palinaroma.dati.adessoMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * I widget dell'iPhone (schermata Home e di blocco): l'app calcola i dati con
 * lo stesso calcolo dei widget Android e li lascia, minuto per minuto, nel
 * gruppo condiviso con l'estensione dei widget, che li disegna.
 */
class MotoreWidgetTelefono(private val orario: Orario) {
    /**
     * I dati dei widget in JSON. [salvati] sono i tragitti salvati (JSON),
     * [prima] i dati di prima (per non richiedere ogni volta le partenze).
     */
    suspend fun calcola(
        preferiti: Set<String>,
        fissate: Set<String>,
        conPosizione: Boolean,
        lat: Double,
        lon: Double,
        salvati: String,
        prima: String,
        forza: Boolean,
    ): String = withContext(Dispatchers.Main) {
        // sull'iPhone l'app non sa quali fermate e tragitti hai scelto nei widget: tutte le preferite e tutti i tragitti
        val d = calcolaDatiWidget(
            orario,
            preferiti,
            fissate,
            if (conPosizione) Punto(lat, lon) else null,
            CodificaTragittiSalvati.leggi(salvati),
            CodificaWidget.leggi(prima)?.partenze.orEmpty(),
            forza,
            fermateWidget = preferiti.sorted().take(12),
            tragittiWidget = null,
        )
        CodificaWidget.scrivi(d)
    }
}

/** Le voci dei widget per la prossima ora, dai dati salvati (JSON per WidgetKit). */
fun vociWidgetTelefono(dati: String): String = vociWidget(CodificaWidget.leggi(dati), adessoMs())
