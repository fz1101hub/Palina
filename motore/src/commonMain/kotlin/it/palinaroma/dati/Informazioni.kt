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
    val TITOLO: String get() = tx("Impostazioni")
    val ASPETTO: String get() = tx("Aspetto")
    val CIFRE: String get() = tx("Cifre a punti")
    val CIFRE_SOTTO: String get() = tx("come sulle paline elettroniche")
    val ORARIO: String get() = tx("Orario di Roma Mobilità")
    val AGGIORNA: String get() = tx("Aggiorna ora")
    val AGGIORNATO: String get() = tx("Orario aggiornato.")
    val GIA_AGGIORNATO: String get() = tx("L'orario è già il più recente.")
    val LINGUA: String get() = tx("Lingua")
    val LINGUA_TELEFONO: String get() = tx("Come il telefono")

    val aspetti: List<SceltaAspetto>
        get() = listOf(
            SceltaAspetto("AUTOMATICO", tx("Automatico"), tx("chiaro o scuro come il telefono")),
            SceltaAspetto("CHIARO", tx("Chiaro"), tx("fondo color carta, cifre ambra")),
            SceltaAspetto("SCURO", tx("Scuro"), tx("tabellone nero, cifre oro")),
        )

    /** Le lingue da scegliere: "" e' quella del telefono; i nomi sono nella loro lingua. */
    val lingue: List<SceltaAspetto>
        get() = listOf(SceltaAspetto("", LINGUA_TELEFONO, Lingue.daCodiceSistema?.nome ?: "")) +
            Lingua.entries.map { SceltaAspetto(it.codice, it.nome, "") }

    /** "La grandezza del testo segue quella scelta nelle impostazioni di Android." */
    fun grandezzaTesto(sistema: String): String = tx("La grandezza del testo segue quella scelta nelle impostazioni di {0}.", sistema)

    fun nonRiuscito(messaggio: String): String = tx("Aggiornamento non riuscito: {0}", messaggio)

    fun versione(numero: String): String = "Palina Roma $numero"

    val schede: List<SchedaInfo>
        get() = listOf(
            SchedaInfo(
                tx("Tempo reale"),
                tx(
                    "I tempi d'attesa si leggono solo mentre la Home, una fermata o una linea sono aperte: " +
                        "ogni 30 secondi, la posizione dei mezzi ogni 15. Ogni lettura può scaricare alcuni MB " +
                        "(niente, se i dati non sono cambiati): con il traffico dati tienilo presente.",
                ),
            ),
            SchedaInfo(
                tx("Cotral e Trenitalia"),
                tx(
                    "Fermate e linee dei bus e dei treni Cotral e le stazioni Trenitalia del Lazio sono incluse nell'app " +
                        "e si aggiornano con l'app. Le partenze arrivano da Transitous, con il tempo reale quando c'è " +
                        "(bus Cotral e treni Trenitalia); per Metromare e Roma–Viterbo orari, ritardi e soppressioni " +
                        "vengono dal servizio orari di ASTRAL. I bus Cotral visti dal GPS, sulla mappa della linea e con la " +
                        "scritta «GPS» alla fermata, arrivano da BusOne, che li riceve da Cotral: solo mentre guardi una " +
                        "fermata, una linea o un tragitto con bus Cotral. Per i treni non ci sono posizioni, solo orari e ritardi.",
                ),
            ),
            SchedaInfo(
                tx("La tua posizione"),
                tx(
                    "Per le fermate vicine e per il segnalino \"sei qui\" sulle mappe la posizione resta sul telefono. " +
                        "Solo quando calcoli un tragitto da o verso la tua posizione le coordinate vengono inviate a Transitous, " +
                        "che calcola il percorso; quando cerchi un indirizzo gli arriva la zona in cui ti trovi, " +
                        "arrotondata a circa un chilometro, per mostrarti prima i risultati vicini.",
                ),
            ),
            SchedaInfo(
                tx("Fonti"),
                tx(
                    "Orario e tempo reale: open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT). " +
                        "Tragitti e orari programmati alle fermate: Transitous. Mappe © OpenStreetMap contributors. " +
                        "Bus e treni Cotral: open data di Cotral. Ritardi dei treni Cotral: ASTRAL. " +
                        "GPS dei bus Cotral: BusOne (https://busone.app) e Cotral, uso personale non commerciale. " +
                        "Stazioni e treni Trenitalia: BusOne (https://busone.app) e Trenitalia, uso personale non commerciale. " +
                        "Cifre a punti: carattere Doto, © The Doto Project Authors (SIL Open Font License 1.1). " +
                        "App personale, non affiliata ad ATAC, Roma Servizi per la Mobilità, Cotral, ASTRAL, Trenitalia, " +
                        "BusOne né Transitous.",
                ),
            ),
        )

    /** "Orario del 24 novembre 2026, incluso nell'app · 7269 fermate, 383 linee. " (poi la parte del telefono). */
    fun statoOrario(orario: Orario, creato: Long, incluso: Boolean): String =
        (if (incluso) tx("Orario del {0}, incluso nell'app · ", giornoLungo(creato)) else tx("Aggiornato il {0} · ", giornoLungo(creato))) +
            // durante l'aggiornamento il vecchio orario si chiude mentre lo schermo lo legge ancora: niente eccezioni
            tx("{0} fermate, {1} linee. ", runCatching { orario.info("fermate") }.getOrNull() ?: "?", runCatching { orario.info("linee") }.getOrNull() ?: "?")

    /** Come si aggiorna l'orario sull'iPhone: lo prepara GitHub ogni settimana, gia' pronto. */
    fun aggiornamentoIPhone(megabyte: Int): String =
        tx("Ogni settimana ne viene preparato uno nuovo: si aggiorna da solo in Wi-Fi; con i dati mobili puoi aggiornarlo da qui (circa {0} MB).", megabyte)

    /** Come si aggiorna l'orario su Android: si scarica da Roma Mobilita' e si trasforma sul telefono. */
    val AGGIORNAMENTO_ANDROID: String
        get() = tx("Quando ha più di una settimana si aggiorna da solo in Wi-Fi; con i dati mobili puoi aggiornarlo da qui (circa 50 MB).")
}

private fun mese(numero: Int): String = when (numero) {
    1 -> tx("gennaio")
    2 -> tx("febbraio")
    3 -> tx("marzo")
    4 -> tx("aprile")
    5 -> tx("maggio")
    6 -> tx("giugno")
    7 -> tx("luglio")
    8 -> tx("agosto")
    9 -> tx("settembre")
    10 -> tx("ottobre")
    11 -> tx("novembre")
    else -> tx("dicembre")
}

/** "24 novembre 2026" (a Roma). */
fun giornoLungo(millisecondi: Long): String {
    val g = giornoARoma(millisecondi)
    // "24 novembre 2026", "24 November 2026", "24 de noviembre de 2026", "24. November 2026"
    return tx("{0} {1} {2}§data", g.day, mese(g.month.number), g.year)
}
