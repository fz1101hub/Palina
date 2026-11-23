package it.palinaroma.dati

/**
 * Le reti di trasporto dell'app. Roma (ATAC e Roma TPL, dall'orario di Roma
 * Mobilita') usa gli identificativi cosi' come sono; le altre, incluse nell'APK
 * in un database a parte (reti.db), hanno un prefisso davanti a fermate e linee
 * ("C:" + codice Cotral), cosi' non si confondono mai con quelle di Roma.
 *
 * [datiTransitous] e' il nome con cui Transitous chiama quei dati: le sue
 * fermate e linee sono "nome_codice" (per esempio "it-Lazio-Cotral_12345").
 */
enum class Rete(val prefisso: String, val datiTransitous: String, val etichetta: String) {
    ROMA("", "it-Lazio-Rome", ""),
    COTRAL("C:", "it-Lazio-Cotral", "Cotral"),
    COTRAL_TRENI("F:", "it-Lazio-Cotral-Ferrovie", "Treni Cotral"),
    TRENITALIA("T:", "it-trenitalia", "Trenitalia"),
    ;

    /** I treni: niente GPS dei mezzi, si parla di stazioni e di ritardi. */
    val treni: Boolean get() = this == COTRAL_TRENI || this == TRENITALIA

    companion object {
        /** La rete di una fermata o di una linea dell'app, dal prefisso. */
        fun di(id: String): Rete = entries.firstOrNull { it.prefisso.isNotEmpty() && id.startsWith(it.prefisso) } ?: ROMA

        /** La rete di un identificativo di Transitous ("it-Lazio-Cotral_12345"), o null se non e' di una rete dell'app. */
        fun daTransitous(id: String): Rete? {
            val dati = id.substringBefore('_', "")
            return entries.firstOrNull { it.datiTransitous == dati }
        }
    }
}

/** Il codice originale, senza il prefisso della rete: "C:12345" -> "12345". */
fun idOriginale(id: String): String = id.removePrefix(Rete.di(id).prefisso)

/** La fermata come la chiama Transitous: "it-Lazio-Rome_70078", "it-Lazio-Cotral_12345". */
fun fermataTransitous(fermataId: String): String {
    val rete = Rete.di(fermataId)
    return rete.datiTransitous + "_" + idOriginale(fermataId)
}

/**
 * I nomi delle linee ferroviarie Cotral nel GTFS ("ROMALIDO", "RVURB", "RVEXT")
 * diventano quelli che si leggono alle stazioni.
 */
fun nomeLineaTreniCotral(nomeGtfs: String): String = when (nomeGtfs.trim().uppercase()) {
    "ROMALIDO" -> "Metromare"
    "RVURB" -> "Roma Nord"
    "RVEXT" -> "Roma–Viterbo"
    else -> nomeGtfs.trim()
}
