package it.palinaroma.dati

import kotlinx.datetime.number

/*
 * I testi delle impostazioni, uguali su Android e iPhone: aspetto, orario e
 * le schede che spiegano da dove vengono i dati.
 */

/** Una scheda di spiegazione nelle impostazioni. */
data class SchedaInfo(val titolo: String, val testo: String)

/** Una scelta dell'aspetto: [chiave] e' quella salvata ("AUTOMATICO", "CHIARO", "SCURO"). */
data class SceltaAspetto(val chiave: String, val nome: String, val descrizione: String)

object TestiImpostazioni {
    const val TITOLO = "Impostazioni"
    const val ASPETTO = "Aspetto"
    const val CIFRE = "Cifre a punti"
    const val CIFRE_SOTTO = "come sulle paline elettroniche"
    const val ORARIO = "Orario di Roma Mobilità"
    const val AGGIORNA = "Aggiorna ora"
    const val AGGIORNATO = "Orario aggiornato."

    val aspetti: List<SceltaAspetto> = listOf(
        SceltaAspetto("AUTOMATICO", "Automatico", "chiaro o scuro come il telefono"),
        SceltaAspetto("CHIARO", "Chiaro", "fondo color carta, cifre ambra"),
        SceltaAspetto("SCURO", "Scuro", "tabellone nero, cifre oro"),
    )

    /** "La grandezza del testo segue quella scelta nelle impostazioni di Android." */
    fun grandezzaTesto(sistema: String): String = "La grandezza del testo segue quella scelta nelle impostazioni di $sistema."

    fun nonRiuscito(messaggio: String): String = "Aggiornamento non riuscito: $messaggio"

    fun versione(numero: String): String = "Palina Roma $numero"

    val schede: List<SchedaInfo> = listOf(
        SchedaInfo(
            "Tempo reale",
            "I tempi d'attesa si leggono solo mentre la Home, una fermata o una linea sono aperte: " +
                "ogni 30 secondi, la posizione dei mezzi ogni 15. Ogni lettura può scaricare alcuni MB " +
                "(niente, se i dati non sono cambiati): con il traffico dati tienilo presente.",
        ),
        SchedaInfo(
            "Cotral e Trenitalia",
            "Fermate e linee dei bus e dei treni Cotral e le stazioni Trenitalia del Lazio sono incluse nell'app " +
                "e si aggiornano con l'app. Le partenze arrivano da Transitous, con il tempo reale quando c'è " +
                "(bus Cotral e treni Trenitalia); per Metromare e Roma–Viterbo orari, ritardi e soppressioni " +
                "vengono dal servizio orari di ASTRAL. I bus Cotral visti dal GPS, sulla mappa della linea e con la " +
                "scritta «GPS» alla fermata, arrivano da BusOne, che li riceve da Cotral: solo mentre guardi una " +
                "fermata, una linea o un tragitto con bus Cotral. Per i treni non ci sono posizioni, solo orari e ritardi.",
        ),
        SchedaInfo(
            "La tua posizione",
            "Per le fermate vicine e per il segnalino \"sei qui\" sulle mappe la posizione resta sul telefono. " +
                "Solo quando calcoli un tragitto da o verso la tua posizione le coordinate vengono inviate a Transitous, " +
                "che calcola il percorso; quando cerchi un indirizzo gli arriva la zona in cui ti trovi, " +
                "arrotondata a circa un chilometro, per mostrarti prima i risultati vicini.",
        ),
        SchedaInfo(
            "Fonti",
            "Orario e tempo reale: open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT). " +
                "Tragitti e orari programmati alle fermate: Transitous. Mappe © OpenStreetMap contributors. " +
                "Bus e treni Cotral: open data di Cotral. Ritardi dei treni Cotral: ASTRAL. " +
                "GPS dei bus Cotral: BusOne (https://busone.app) e Cotral, uso personale non commerciale. " +
                "Stazioni e treni Trenitalia: BusOne (https://busone.app) e Trenitalia, uso personale non commerciale. " +
                "Cifre a punti: carattere Doto, © The Doto Project Authors (SIL Open Font License 1.1). " +
                "App personale, non affiliata ad ATAC, Roma Servizi per la Mobilità, Cotral, ASTRAL, Trenitalia, " +
                "BusOne né Transitous.",
        ),
    )

    /** "Orario del 24 novembre 2026, incluso nell'app · 7269 fermate, 383 linee. " (poi la parte del telefono). */
    fun statoOrario(orario: Orario, creato: Long, incluso: Boolean): String =
        (if (incluso) "Orario del ${giornoLungo(creato)}, incluso nell'app · " else "Aggiornato il ${giornoLungo(creato)} · ") +
            "${orario.info("fermate") ?: "?"} fermate, ${orario.info("linee") ?: "?"} linee. "

    /** Come si aggiorna l'orario sull'iPhone: lo prepara GitHub ogni settimana, gia' pronto. */
    fun aggiornamentoIPhone(megabyte: Int): String =
        "Ogni settimana ne viene preparato uno nuovo: si aggiorna da solo in Wi-Fi; " +
            "con i dati mobili puoi aggiornarlo da qui (circa $megabyte MB)."

    /** Come si aggiorna l'orario su Android: si scarica da Roma Mobilita' e si trasforma sul telefono. */
    const val AGGIORNAMENTO_ANDROID =
        "Quando ha più di una settimana si aggiorna da solo in Wi-Fi; " +
            "con i dati mobili puoi aggiornarlo da qui (circa 50 MB)."

    const val GIA_AGGIORNATO = "L'orario è già il più recente."
}

private val MESI = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

/** "24 novembre 2026" (a Roma). */
fun giornoLungo(millisecondi: Long): String {
    val g = giornoARoma(millisecondi)
    return "${g.day} ${MESI[g.month.number - 1]} ${g.year}"
}
