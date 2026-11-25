package it.palinaroma.ponte

import it.palinaroma.dati.Json
import it.palinaroma.dati.ScriviJson
import it.palinaroma.dati.tx
import kotlin.math.abs

/*
 * "Segui": il prossimo passaggio di una linea a una fermata, con il conto alla
 * rovescia e un avviso 2 minuti prima. Lo stesso calcolo per l'orologio Wear OS
 * (Live Update) e per l'iPhone (Live Activity, anche sull'Apple Watch).
 */

/** Per quanto si segue al massimo una linea (poi si smette da soli). */
const val DURATA_MASSIMA_MS = 2 * 60 * 60_000L

/** A quanti secondi dall'arrivo l'orologio vibra. */
const val AVVISO_SECONDI = 120L

/**
 * Una linea seguita sull'orologio: il prossimo passaggio a una fermata, con il
 * conto alla rovescia sul quadrante (Live Update da Wear OS 7, attivita' in
 * corso prima) finche' il mezzo non arriva.
 */
data class Seguimento(
    val fermataId: String,
    val fermata: String,
    /** Per ritrovare la riga nei tabelloni successivi (vedi [chiaveLinea]). */
    val chiaveLinea: String,
    val lineaId: String,
    val linea: String,
    val capolinea: String,
    val colore: Int,
    /** Il passaggio seguito, in secondi. */
    val obiettivo: Long,
    val tempoReale: Boolean,
    /** Quando si e' cominciato, in millisecondi. */
    val inizio: Long,
    /** Il tabellone da cui viene [obiettivo] ([TabelloneOrologio.creato]). */
    val aggiornato: Long,
    /** Ha gia' vibrato per l'arrivo. */
    val avvisato: Boolean = false,
    /** Quanti secondi prima del passaggio avvisare (sull'orologio 2 minuti; con "Avvisami" dipende dalla scelta). */
    val anticipo: Long = AVVISO_SECONDI,
    /** La scelta di "Avvisami": "uscire", "cinque", "due" (vuoto per "Segui" sull'orologio). */
    val quando: String = "",
    /** I minuti a piedi fino alla fermata (0 se non si sanno). */
    val aPiedi: Int = 0,
    /** Ha detto "Sto uscendo": niente piu' "Esci ora". */
    val uscito: Boolean = false,
    /** La corsa del passaggio seguito, per seguire lo stesso mezzo fino a dove si scende. */
    val corsaId: String = "",
    /** "Poi avvisami quando scendere": dopo il passaggio si chiede dove si scende. */
    val scendiAttivo: Boolean = false,
    /** Dove si scende, una volta scelto. */
    val discesa: Discesa? = null,
) {
    /** Da chiedere al telefono, perche' la tenga nel tabellone anche lontano dalla fermata. */
    val lineaSeguita: LineaSeguita? get() = lineaId.takeIf { it.isNotEmpty() }?.let { LineaSeguita(fermataId, it) }

    fun stessaRiga(fermataId: String, chiaveLinea: String, capolinea: String): Boolean =
        this.fermataId == fermataId && this.chiaveLinea == chiaveLinea && this.capolinea == capolinea
}

/**
 * Dove si scende: la fermata, quando ci si arriva (secondi) e quando avvisare
 * ("Scendi alla prossima": quando il mezzo lascia la fermata prima).
 */
data class Discesa(
    val fermataId: String,
    val nome: String,
    val arrivo: Long,
    val avvisoAlle: Long,
    val tempoReale: Boolean,
    val avvisato: Boolean = false,
)

/** Il seguimento in corso e' finito (o va chiuso): passato da un minuto, arrivati, o dopo [DURATA_MASSIMA_MS]. */
fun finito(s: Seguimento, adesso: Long): Boolean = when {
    adesso * 1000 - s.inizio > DURATA_MASSIMA_MS -> true
    s.discesa != null -> s.discesa.arrivo < adesso - 60
    // si aspetta un quarto d'ora che si scelga dove scendere
    s.scendiAttivo -> s.obiettivo < adesso - 15 * 60
    else -> s.obiettivo < adesso - 60
}

/** La corsa del passaggio delle [orario] (secondi), se si sa. */
fun RigaOrologio.corsa(orario: Long): String = corse.getOrNull(orari.indexOf(orario)).orEmpty()

/** Si comincia a seguire il prossimo passaggio della riga [r]; null se non ne ha. [adesso] in secondi. */
fun seguimento(f: FermataOrologio, r: RigaOrologio, t: TabelloneOrologio, adesso: Long): Seguimento? {
    val primo = r.prossimi(adesso).firstOrNull() ?: return null
    return Seguimento(
        fermataId = f.id,
        fermata = f.nome,
        chiaveLinea = r.chiaveLinea,
        lineaId = r.lineaId,
        linea = r.linea,
        capolinea = r.capolinea,
        colore = r.colore,
        obiettivo = primo,
        tempoReale = r.tempoReale,
        inizio = adesso * 1000,
        aggiornato = t.creato,
        corsaId = r.corsa(primo),
    )
}

/**
 * Il seguimento [s] con i tempi del tabellone [t]. Finche' il mezzo non e'
 * arrivato si segue il passaggio piu' vicino a quello di prima (il tempo reale
 * lo sposta); quando e' arrivato non si passa al successivo. Null quando e'
 * finito: passato da un minuto, o dopo [DURATA_MASSIMA_MS]. [adesso] in secondi.
 */
fun ricalcola(s: Seguimento, t: TabelloneOrologio?, adesso: Long): Seguimento? {
    if (adesso * 1000 - s.inizio > DURATA_MASSIMA_MS) return null
    var nuovo = s
    if (t != null && t.creato > s.aggiornato && s.obiettivo > adesso + 30 && s.discesa == null) {
        val riga = t.riga(s.fermataId, s.chiaveLinea, s.capolinea)
        val prossimi = riga?.prossimi(adesso).orEmpty()
        // la stessa corsa, se si riconosce; altrimenti il passaggio piu' vicino a quello di prima
        val stessaCorsa = if (s.corsaId.isNotEmpty()) prossimi.firstOrNull { riga?.corsa(it) == s.corsaId } else null
        val scelto = stessaCorsa ?: prossimi.minByOrNull { abs(it - s.obiettivo) }
        nuovo = if (riga != null && scelto != null) {
            s.copy(
                obiettivo = scelto,
                tempoReale = riga.tempoReale && scelto == prossimi.first(),
                aggiornato = t.creato,
                // se il mezzo e' in ritardo si vibra di nuovo quando si avvicina
                avvisato = s.avvisato && scelto - adesso <= s.anticipo + 30,
                corsaId = riga.corsa(scelto).ifEmpty { s.corsaId },
            )
        } else {
            s.copy(aggiornato = t.creato)
        }
    }
    return nuovo.takeIf { !finito(it, adesso) }
}

/** "64 → Stazione San Pietro". */
fun titoloSegui(s: Seguimento): String = "${s.linea} → ${s.capolinea}"

/** "Piazza Venezia · tra 4' (14:05) · tempo reale". [adesso] in secondi. */
fun testoSegui(s: Seguimento, adesso: Long): String {
    val quando = if (s.obiettivo - adesso < 60) tx("in arrivo") else tx("tra {0}", testoMinuti(s.obiettivo, adesso))
    return "${s.fermata} · $quando (${oraBreve(s.obiettivo * 1000)}) · " + if (s.tempoReale) tx("tempo reale") else tx("da orario")
}

/** "64 in arrivo". */
fun titoloAvvisoSegui(s: Seguimento): String = tx("{0} in arrivo", s.linea)

/** "Piazza Venezia: tra 2', verso Stazione San Pietro". [adesso] in secondi. */
fun testoAvvisoSegui(s: Seguimento, adesso: Long): String {
    return if (s.obiettivo - adesso < 60) {
        tx("{0}: ora, verso {1}", s.fermata, s.capolinea)
    } else {
        tx("{0}: tra {1}, verso {2}", s.fermata, testoMinuti(s.obiettivo, adesso), s.capolinea)
    }
}

/** Il seguimento in JSON, per riprenderlo se l'app si riapre. */
fun Seguimento.testoJson(): String = ScriviJson.testo(
    mapOf(
        "fermataId" to fermataId, "fermata" to fermata, "chiaveLinea" to chiaveLinea, "lineaId" to lineaId,
        "linea" to linea, "capolinea" to capolinea, "colore" to colore, "obiettivo" to obiettivo,
        "tempoReale" to tempoReale, "inizio" to inizio, "aggiornato" to aggiornato, "avvisato" to avvisato,
        "anticipo" to anticipo, "quando" to quando, "aPiedi" to aPiedi, "uscito" to uscito, "corsaId" to corsaId,
        "scendiAttivo" to scendiAttivo,
        "discesa" to discesa?.let {
            mapOf(
                "fermataId" to it.fermataId, "nome" to it.nome, "arrivo" to it.arrivo, "avvisoAlle" to it.avvisoAlle,
                "tempoReale" to it.tempoReale, "avvisato" to it.avvisato,
            )
        },
    ),
)

/** Null se il testo non e' un seguimento valido. */
fun seguimentoDaJson(testo: String?): Seguimento? {
    if (testo.isNullOrBlank()) return null
    return try {
        val o = oggetto(Json.leggi(testo))
        Seguimento(
            fermataId = o.testoO("fermataId"),
            fermata = o.testoO("fermata"),
            chiaveLinea = o.testoO("chiaveLinea"),
            lineaId = o.testoO("lineaId"),
            linea = o.testoO("linea"),
            capolinea = o.testoO("capolinea"),
            colore = o.intero("colore") ?: 0,
            obiettivo = o.lungo("obiettivo") ?: return null,
            tempoReale = o["tempoReale"] == true,
            inizio = o.lungo("inizio") ?: 0L,
            aggiornato = o.lungo("aggiornato") ?: 0L,
            avvisato = o["avvisato"] == true,
            anticipo = o.lungo("anticipo") ?: AVVISO_SECONDI,
            quando = o.testoO("quando"),
            aPiedi = o.intero("aPiedi") ?: 0,
            uscito = o["uscito"] == true,
            corsaId = o.testoO("corsaId"),
            scendiAttivo = o["scendiAttivo"] == true,
            discesa = (o["discesa"] as? Map<*, *>)?.let { d ->
                @Suppress("UNCHECKED_CAST")
                val m = d as Map<String, Any?>
                Discesa(
                    fermataId = m.testoO("fermataId"),
                    nome = m.testoO("nome"),
                    arrivo = m.lungo("arrivo") ?: return@let null,
                    avvisoAlle = m.lungo("avvisoAlle") ?: 0L,
                    tempoReale = m["tempoReale"] == true,
                    avvisato = m["avvisato"] == true,
                ).takeIf { it.fermataId.isNotEmpty() }
            },
        ).takeIf { it.fermataId.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
