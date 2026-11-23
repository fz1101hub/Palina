package it.palinaroma.dati

import kotlin.math.abs

/**
 * Il dettaglio di una corsa (per ora dei treni): tutte le fermate dove il treno
 * ferma davvero, con gli orari. Le fermate vengono da Transitous (/trip), che non
 * elenca quelle dove si passa senza fermarsi: un Regionale Veloce mostra solo le
 * sue fermate, non tutte quelle della linea.
 */
data class FermataCorsa(
    val nome: String,
    /** Il codice di Transitous ("it-trenitalia_S08409"). */
    val idTransitous: String?,
    val lat: Double,
    val lon: Double,
    /** Orario aggiornato (tempo reale, o stimato dal ritardo di ASTRAL), in ms. */
    val orario: Long?,
    /** Orario programmato, in ms. */
    val previsto: Long?,
    val binario: String?,
    /** Il binario e' cambiato rispetto all'orario. */
    val binarioCambiato: Boolean,
    val soppressa: Boolean,
    /** L'orario e' stimato: il ritardo visto a un'altra stazione, portato avanti. */
    val stimato: Boolean,
) {
    /** Minuti di ritardo (negativi se in anticipo), se l'orario e' aggiornato. */
    val ritardoMinuti: Int?
        get() = if (orario != null && previsto != null) Math.round((orario - previsto) / 60_000.0).toInt() else null
}

/** Da dove vengono gli orari del dettaglio. */
enum class FonteCorsa { TEMPO_REALE, ASTRAL, ORARIO }

data class DettaglioCorsa(
    val tratto: Tratto,
    val fermate: List<FermataCorsa>,
    /** La fermata da cui si e' aperto il dettaglio, se si trova nella corsa. */
    val qui: Int?,
    val fonte: FonteCorsa,
)

/**
 * Le fermate della corsa [t]. [quiId] e' la fermata dell'app da cui la si guarda
 * ([quiLat], [quiLon] per riconoscerla anche se Transitous la chiama con un altro
 * codice). Se Transitous non ha il tempo reale e c'e' il [ritardoSecondi] visto
 * da ASTRAL a quella stazione, lo si applica da li' in poi: alla stazione e' il
 * dato di ASTRAL, dopo e' una stima.
 */
fun dettaglioCorsa(t: Tratto, quiId: String?, quiLat: Double, quiLon: Double, ritardoSecondi: Int?): DettaglioCorsa {
    val tappe = listOf(t.da) + t.intermedie + listOf(t.a)
    val qui = indiceFermata(tappe, quiId, quiLat, quiLon)
    val conAstral = !t.tempoReale && ritardoSecondi != null && qui != null
    val fermate = tappe.mapIndexed { i, tappa ->
        val previsto = tappa.orarioPrevisto ?: tappa.orario
        val orario = when {
            conAstral && i >= qui!! -> previsto?.plus(ritardoSecondi!! * 1000L)
            else -> tappa.orario ?: previsto
        }
        FermataCorsa(
            nome = tappa.nome,
            idTransitous = tappa.fermataId,
            lat = tappa.lat,
            lon = tappa.lon,
            orario = orario,
            previsto = previsto,
            binario = tappa.binario ?: tappa.binarioPrevisto,
            binarioCambiato = tappa.binario != null && tappa.binarioPrevisto != null && tappa.binario != tappa.binarioPrevisto,
            soppressa = tappa.cancellata || t.cancellato,
            stimato = conAstral && i > qui!!,
        )
    }
    val fonte = when {
        t.tempoReale -> FonteCorsa.TEMPO_REALE
        conAstral -> FonteCorsa.ASTRAL
        else -> FonteCorsa.ORARIO
    }
    return DettaglioCorsa(t, fermate, qui, fonte)
}

/**
 * Quale delle [tappe] e' la fermata dell'app [quiId]: con lo stesso codice di
 * Transitous, altrimenti la piu' vicina entro 300 metri.
 */
fun indiceFermata(tappe: List<Tappa>, quiId: String?, lat: Double, lon: Double): Int? {
    if (quiId != null) {
        val codice = fermataTransitous(quiId)
        val esatta = tappe.indexOfFirst { it.fermataId == codice || it.fermataId == quiId }
        if (esatta >= 0) return esatta
    }
    if (lat.isNaN() || lon.isNaN()) return null
    return tappe.withIndex()
        .filter { !it.value.lat.isNaN() && !it.value.lon.isNaN() }
        .map { it.index to distanzaMetri(lat, lon, it.value.lat, it.value.lon) }
        .filter { it.second <= 300 }
        .minByOrNull { it.second }?.first
}

/**
 * Per i treni Cotral visti da ASTRAL, che non dice la corsa dell'orario: la
 * partenza di Transitous della stessa linea (e direzione, se si sa) con
 * l'orario programmato piu' vicino a [previstoMs], entro un minuto e mezzo.
 */
fun scegliCorsa(partenze: List<PartenzaTransitous>, lineaId: String, direzione: Int?, previstoMs: Long): PartenzaTransitous? {
    val stessaLinea = partenze.filter { lineaId in candidatiLinea(it.lineaId) || it.lineaId == lineaId }
    val stessaDirezione = stessaLinea.filter { direzione == null || it.direzione == null || it.direzione == direzione }
    return stessaDirezione.ifEmpty { stessaLinea }
        .minByOrNull { abs(it.previsto - previstoMs) }
        ?.takeIf { abs(it.previsto - previstoMs) <= 90_000 }
}

/** La fermata dell'app che corrisponde a un codice di Transitous, se la rete e' nell'app. */
fun fermataDaTransitous(idTransitous: String?): String? {
    val id = idTransitous ?: return null
    val rete = Rete.daTransitous(id) ?: return null
    val codice = id.substringAfter('_', "").ifEmpty { return null }
    return rete.prefisso + codice
}
