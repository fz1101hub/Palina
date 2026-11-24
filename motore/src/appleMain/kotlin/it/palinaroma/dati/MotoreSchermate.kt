package it.palinaroma.dati

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Da chiamare una volta all'avvio dell'app per iPhone (o Apple Watch): rete e nome dell'app. */
fun preparaMotore(agenteUtente: String) {
    Collegamento.http = HttpApple
    Collegamento.agenteUtente = agenteUtente
}

/**
 * Il motore visto dalle schermate SwiftUI della Home: fermate vicine, ricerca e
 * tempi. Le funzioni sospese si chiamano dal thread principale e lavorano li'
 * (le letture e la rete vanno da sole sui loro thread).
 */
class MotoreHome(private val orario: Orario) {
    private val caricatore = CaricatoreArrivi()
    private var arrivi = ArriviFermate()

    fun vicine(lat: Double, lon: Double): List<VoceFermata> = vociVicine(orario, lat, lon, 700.0)

    fun cerca(testo: String): List<VoceFermata> = vociCercate(orario, testo)

    /** Chiede i tempi (Roma Mobilita', BusOne, Transitous, ASTRAL) e li mette nelle righe. */
    suspend fun aggiorna(voci: List<VoceFermata>): List<FermataConTempi> = withContext(Dispatchers.Main) {
        val fermate = withContext(dispatcherDati) {
            val trovate = orario.fermate(voci.map { it.id })
            voci.mapNotNull { trovate[it.id] }
        }
        arrivi = caricatore.aggiorna(orario, fermateComplete(emptyList(), fermate), voci.map { it.id }, arrivi)
        fermateConTempi(voci, arrivi, adessoMs() / 1000)
    }

    /** Le righe con i tempi gia' letti, ricalcolate per adesso (i minuti scendono da soli). */
    fun righe(voci: List<VoceFermata>): List<FermataConTempi> = fermateConTempi(voci, arrivi, adessoMs() / 1000)

    fun avviso(): String? = avvisoArrivi(arrivi, conRoma = true)
}

/** Il motore visto dalla schermata di una fermata: intestazione, linee e tabellone. */
class MotoreFermata(private val orario: Orario, private val fermataId: String) {
    private val caricatore = CaricatoreArrivi()
    private var arrivi = ArriviFermate()

    fun intestazione(): VoceFermata? = intestazioneFermata(orario, fermataId)

    fun linee(): List<VoceLinea> = lineeDellaFermata(orario, fermataId)

    suspend fun aggiorna(): TabelloneFermata = withContext(Dispatchers.Main) {
        val fermata = withContext(dispatcherDati) { orario.fermata(fermataId) }
        if (fermata != null) arrivi = caricatore.aggiorna(orario, listOf(fermata), emptyList(), arrivi)
        tabelloneFermata(fermataId, arrivi, adessoMs())
    }

    /** Il tabellone con i tempi gia' letti, per adesso. */
    fun tabellone(): TabelloneFermata = tabelloneFermata(fermataId, arrivi, adessoMs())
}
