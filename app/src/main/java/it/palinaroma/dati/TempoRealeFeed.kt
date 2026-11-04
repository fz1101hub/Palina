package it.palinaroma.dati

/**
 * Lettura dei feed GTFS-Realtime di Roma Mobilita' senza librerie esterne:
 * un piccolo decodificatore protobuf che legge solo i campi che servono.
 * Numeri dei campi da gtfs-realtime.proto.
 */
class LettoreProto(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val fine: Int = buf.size,
) {
    fun finito(): Boolean = pos >= fine

    fun leggiVarint(): Long {
        var risultato = 0L
        var spostamento = 0
        while (true) {
            check(pos < fine) { "varint troncato" }
            val b = buf[pos++].toInt()
            risultato = risultato or ((b and 0x7F).toLong() shl spostamento)
            if ((b and 0x80) == 0) return risultato
            spostamento += 7
            check(spostamento < 64) { "varint troppo lungo" }
        }
    }

    fun leggiTag(): Int = leggiVarint().toInt()

    fun leggiFloat(): Float {
        check(pos + 4 <= fine) { "float troncato" }
        val bit = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return java.lang.Float.intBitsToFloat(bit)
    }

    fun leggiMessaggio(): LettoreProto {
        val n = leggiVarint().toInt()
        check(n >= 0 && pos + n <= fine) { "messaggio troncato" }
        val figlio = LettoreProto(buf, pos, pos + n)
        pos += n
        return figlio
    }

    fun leggiStringa(): String {
        val n = leggiVarint().toInt()
        check(n >= 0 && pos + n <= fine) { "stringa troncata" }
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun salta(tipo: Int) {
        when (tipo) {
            0 -> leggiVarint()
            1 -> pos += 8
            2 -> {
                val n = leggiVarint().toInt()
                check(n >= 0) { "lunghezza negativa" }
                pos += n
            }
            5 -> pos += 4
            else -> error("tipo di campo $tipo non supportato")
        }
        check(pos <= fine) { "campo troncato" }
    }
}

private const val VARINT = 0
private const val LUNGHEZZA = 2
private const val FIXED32 = 5

private fun tag(campo: Int, tipo: Int) = (campo shl 3) or tipo

/** Una fermata prevista per una corsa in tempo reale. */
data class FermataRT(val fermataId: String, val orario: Long, val saltata: Boolean)

/**
 * Una corsa del feed "trip updates". Orari in secondi epoch.
 * [veicolo] e' la vettura assegnata: se c'e', la corsa e' seguita via GPS.
 */
data class CorsaRT(
    val corsaId: String,
    val lineaId: String,
    val direzione: Int?,
    val cancellata: Boolean,
    val capolinea: String,
    val fermate: List<FermataRT>,
    val veicolo: String = "",
)

/**
 * Un mezzo del feed "vehicle positions": posizione GPS, rotta in gradi
 * (0 = nord, in senso orario) e ora del rilevamento in secondi epoch (0 se manca).
 */
data class MezzoRT(
    val corsaId: String,
    val lineaId: String,
    val direzione: Int?,
    val lat: Double,
    val lon: Double,
    val etichetta: String,
    val rotta: Float? = null,
    val rilevato: Long = 0L,
)

private class Descrittore(
    var corsaId: String = "",
    var lineaId: String = "",
    var direzione: Int? = null,
    var relazione: Int = 0,
)

object FeedTempoReale {

    fun corse(dati: ByteArray): List<CorsaRT> {
        val corse = ArrayList<CorsaRT>()
        perOgniEntita(dati, campo = 3) { corse.add(leggiCorsa(it)) }
        return corse
    }

    fun mezzi(dati: ByteArray): List<MezzoRT> {
        val mezzi = ArrayList<MezzoRT>()
        perOgniEntita(dati, campo = 4) { m -> leggiMezzo(m)?.let { mezzi.add(it) } }
        return mezzi
    }

    /** FeedMessage.entity (2) -> FeedEntity.<campo> */
    private inline fun perOgniEntita(dati: ByteArray, campo: Int, azione: (LettoreProto) -> Unit) {
        val feed = LettoreProto(dati)
        while (!feed.finito()) {
            val t = feed.leggiTag()
            if (t == tag(2, LUNGHEZZA)) {
                val entita = feed.leggiMessaggio()
                while (!entita.finito()) {
                    val te = entita.leggiTag()
                    if (te == tag(campo, LUNGHEZZA)) azione(entita.leggiMessaggio())
                    else entita.salta(te and 7)
                }
            } else {
                feed.salta(t and 7)
            }
        }
    }

    private fun leggiDescrittore(m: LettoreProto): Descrittore {
        val d = Descrittore()
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(1, LUNGHEZZA) -> d.corsaId = m.leggiStringa()
                tag(5, LUNGHEZZA) -> d.lineaId = m.leggiStringa()
                tag(6, VARINT) -> d.direzione = m.leggiVarint().toInt()
                tag(4, VARINT) -> d.relazione = m.leggiVarint().toInt()
                else -> m.salta(t and 7)
            }
        }
        return d
    }

    private fun leggiCorsa(m: LettoreProto): CorsaRT {
        var d = Descrittore()
        var capolinea = ""
        var veicolo = ""
        val fermate = ArrayList<FermataRT>()
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(1, LUNGHEZZA) -> d = leggiDescrittore(m.leggiMessaggio())
                tag(2, LUNGHEZZA) -> leggiFermata(m.leggiMessaggio())?.let { fermate.add(it) }
                tag(3, LUNGHEZZA) -> veicolo = leggiVeicolo(m.leggiMessaggio())
                tag(6, LUNGHEZZA) -> capolinea = leggiCapolinea(m.leggiMessaggio())
                else -> m.salta(t and 7)
            }
        }
        // CANCELED = 3, DELETED = 7
        val cancellata = d.relazione == 3 || d.relazione == 7
        return CorsaRT(d.corsaId, d.lineaId, d.direzione, cancellata, capolinea, fermate, veicolo)
    }

    /** VehicleDescriptor: label (2) se c'e', altrimenti id (1). */
    private fun leggiVeicolo(v: LettoreProto): String {
        var id = ""
        var nome = ""
        while (!v.finito()) {
            val tv = v.leggiTag()
            when (tv) {
                tag(1, LUNGHEZZA) -> id = v.leggiStringa()
                tag(2, LUNGHEZZA) -> nome = v.leggiStringa()
                else -> v.salta(tv and 7)
            }
        }
        return nome.ifBlank { id }.trim()
    }

    private fun leggiFermata(m: LettoreProto): FermataRT? {
        var fermata = ""
        var arrivo = 0L
        var partenza = 0L
        var relazione = 0
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(4, LUNGHEZZA) -> fermata = m.leggiStringa()
                tag(2, LUNGHEZZA) -> arrivo = leggiOrario(m.leggiMessaggio())
                tag(3, LUNGHEZZA) -> partenza = leggiOrario(m.leggiMessaggio())
                tag(5, VARINT) -> relazione = m.leggiVarint().toInt()
                else -> m.salta(t and 7)
            }
        }
        val orario = if (arrivo > 0) arrivo else partenza
        if (fermata.isEmpty() || orario <= 0) return null
        return FermataRT(fermata, orario, saltata = relazione == 1)
    }

    /** StopTimeEvent.time (2) */
    private fun leggiOrario(m: LettoreProto): Long {
        var orario = 0L
        while (!m.finito()) {
            val t = m.leggiTag()
            if (t == tag(2, VARINT)) orario = m.leggiVarint() else m.salta(t and 7)
        }
        return orario
    }

    /** TripProperties.trip_headsign (5) */
    private fun leggiCapolinea(m: LettoreProto): String {
        var capolinea = ""
        while (!m.finito()) {
            val t = m.leggiTag()
            if (t == tag(5, LUNGHEZZA)) capolinea = m.leggiStringa() else m.salta(t and 7)
        }
        return capolinea.trim()
    }

    private fun leggiMezzo(m: LettoreProto): MezzoRT? {
        var d = Descrittore()
        var lat = Double.NaN
        var lon = Double.NaN
        var etichetta = ""
        var rotta: Float? = null
        var rilevato = 0L
        while (!m.finito()) {
            val t = m.leggiTag()
            when (t) {
                tag(1, LUNGHEZZA) -> d = leggiDescrittore(m.leggiMessaggio())
                tag(2, LUNGHEZZA) -> {
                    val p = m.leggiMessaggio()
                    while (!p.finito()) {
                        val tp = p.leggiTag()
                        when (tp) {
                            tag(1, FIXED32) -> lat = p.leggiFloat().toDouble()
                            tag(2, FIXED32) -> lon = p.leggiFloat().toDouble()
                            tag(3, FIXED32) -> rotta = p.leggiFloat().takeIf { !it.isNaN() && it >= 0f && it <= 360f }
                            else -> p.salta(tp and 7)
                        }
                    }
                }
                tag(5, VARINT) -> rilevato = m.leggiVarint()
                tag(8, LUNGHEZZA) -> etichetta = leggiVeicolo(m.leggiMessaggio())
                else -> m.salta(t and 7)
            }
        }
        if (lat.isNaN() || lon.isNaN()) return null
        return MezzoRT(d.corsaId, d.lineaId, d.direzione, lat, lon, etichetta, rotta, rilevato)
    }
}
