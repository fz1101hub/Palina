package it.palinaroma.dati

/**
 * Bus Cotral in tempo reale da BusOne (https://busone.app): posizioni GPS dei
 * mezzi e tempi delle corse, in GTFS-Realtime, con gli stessi codici di corse,
 * linee e fermate dell'orario ufficiale Cotral (qui con il prefisso "C:").
 *
 * BusOne e' gratuito per l'uso personale non commerciale e chiede di dichiarare
 * chi siamo e che uso ne facciamo ([INTESTAZIONI]); senza, le richieste sono
 * limitate a circa una al minuto. Si legge solo mentre si guarda una fermata, una
 * linea o un tragitto con bus Cotral, o la Home con una fermata Cotral.
 */
object BusOne {
    const val BASE = "https://proxy.busone.app/cotral/gtfs-rt"

    /** Come chiedono le condizioni di BusOne: app con un contatto, e uso personale. */
    val INTESTAZIONI = mapOf(
        "X-BusOne-Client" to "Palina Roma (https://github.com/fz1101hub/Palina)",
        "X-BusOne-Use" to "personal",
    )

    private val feedMezzi = FeedInMemoria(
        validitaMs = 15_000,
        scarica = { etag, modificato -> scaricaFeed("$BASE/vehicle-positions.pb", etag, modificato, INTESTAZIONI) },
        decodifica = { FeedTempoReale.mezzi(it, Rete.COTRAL.prefisso) },
    )
    private val feedCorse = FeedInMemoria(
        validitaMs = 20_000,
        scarica = { etag, modificato -> scaricaFeed("$BASE/trip-updates.pb", etag, modificato, INTESTAZIONI) },
        decodifica = { FeedTempoReale.corse(it, Rete.COTRAL.prefisso) },
    )

    /** I bus Cotral visti dal GPS, con corse, linee e fermate col prefisso "C:". */
    suspend fun mezzi(forza: Boolean = false): List<MezzoRT> = feedMezzi.ottieni(forza)

    /** Le corse Cotral seguite, con i tempi alle fermate. */
    suspend fun corse(forza: Boolean = false): List<CorsaRT> = feedCorse.ottieni(forza)
}
