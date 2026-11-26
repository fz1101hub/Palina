package it.palinaroma.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * L'ultima corsa: dalla sera si dice se il bus (o il treno, la metro) che stai
 * guardando e' l'ultimo o il penultimo della giornata, alla fermata, nei
 * risultati della ricerca e nei tragitti salvati. Le partenze vengono da
 * Transitous, fino alla fine del servizio; se non si sanno tutte non si dice
 * niente: meglio nessuna scritta che un "ultima corsa" sbagliato.
 */

enum class UltimaCorsa { ULTIMA, PENULTIMA }

/** "ultima corsa", "penultima corsa" o vuoto. */
fun testoUltimaCorsa(u: UltimaCorsa?): String = when (u) {
    UltimaCorsa.ULTIMA -> tx("ultima corsa")
    UltimaCorsa.PENULTIMA -> tx("penultima corsa")
    null -> ""
}

/**
 * Si controlla dalle 18 alle 3: di giorno le linee passano ancora per ore, e
 * tra le 3 e le 5 le notturne si mescolano alle prime corse del mattino.
 */
fun daControllareUltima(istanteMs: Long): Boolean {
    val ora = oraDelGiornoARoma(istanteMs)
    return ora >= 18 || ora < 3
}

/** La fine della giornata di servizio dopo [istanteMs]: le 5 di Roma. */
fun fineServizio(istanteMs: Long): Long {
    val giorno = giornoARoma(istanteMs)
    val oggi = secondiARoma(giorno, 5, 0) * 1000
    return if (istanteMs < oggi) oggi else secondiARoma(giorno, 5, 0, giorniDopo = 1) * 1000
}

/** Quante corse restano dopo una, contate fra le partenze: 0 = ultima, 1 = penultima. */
fun ultimaDaRimaste(rimaste: Int): UltimaCorsa? = when (rimaste) {
    0 -> UltimaCorsa.ULTIMA
    1 -> UltimaCorsa.PENULTIMA
    else -> null
}

// --- alla fermata

/** Le corse rimaste oggi a una fermata, raggruppate per linea e direzione come i tempi d'attesa. */
data class CorseRimaste(val calcolato: Long, val arrivi: List<Arrivo>)

/** La chiave di una riga del tabellone (linea, direzione, capolinea). */
fun chiaveArrivo(a: Arrivo): String = a.lineaId + "|" + (a.direzione ?: -1) + "|" + a.capolinea

/**
 * Per la riga [arrivo] del tabellone: il suo primo passaggio e' l'ultima o la
 * penultima corsa? Si cerca la stessa corsa tra quelle rimaste (stesso codice,
 * o lo stesso orario a meno di 5 minuti) e si contano quelle dopo.
 */
fun CorseRimaste.per(arrivo: Arrivo): UltimaCorsa? {
    val primo = arrivo.passaggi.firstOrNull() ?: return null
    val gruppo = arrivi.firstOrNull {
        it.lineaId == arrivo.lineaId && it.direzione == arrivo.direzione &&
            (arrivo.direzione != null || it.capolinea == arrivo.capolinea)
    } ?: return null
    val passaggi = gruppo.passaggi.sortedBy { it.orario }
    var indice = passaggi.indexOfFirst {
        (primo.corsaId.isNotEmpty() && it.corsaId == primo.corsaId) ||
            (primo.idTransitous != null && it.idTransitous == primo.idTransitous)
    }
    if (indice < 0) {
        indice = passaggi.withIndex()
            .filter { kotlin.math.abs(it.value.orario - primo.orario) <= 5 * 60 }
            .minByOrNull { kotlin.math.abs(it.value.orario - primo.orario) }?.index ?: -1
    }
    if (indice < 0) return null
    return ultimaDaRimaste(passaggi.size - indice - 1)
}

/** Le scritte per le righe del tabellone: chiave della riga -> "ultima corsa" / "penultima corsa". */
fun ultimeDelleRighe(rimaste: CorseRimaste?, arrivi: List<Arrivo>): Map<String, String> {
    if (rimaste == null) return emptyMap()
    return arrivi.mapNotNull { a -> rimaste.per(a)?.let { chiaveArrivo(a) to testoUltimaCorsa(it) } }.toMap()
}

private val memoriaFermate = HashMap<String, CorseRimaste>()
private val chiaveMemoria = Mutex()

/**
 * Le corse rimaste oggi alla [fermata] (dalla sera; di giorno null), tenute
 * 10 minuti. Null anche se Transitous non risponde.
 */
suspend fun corseRimasteFermata(fermata: Fermata, orario: Orario?, adessoMs: Long = adessoMs()): CorseRimaste? {
    if (!daControllareUltima(adessoMs)) return null
    chiaveMemoria.withLock { memoriaFermate[fermata.id] }?.let { if (adessoMs - it.calcolato < 10 * 60_000) return it }
    val da = adessoMs - 30 * 60_000
    val fine = fineServizio(adessoMs)
    val partenze = try {
        Transitous.partenzeFinoA(fermata.id, fermata.lat, fermata.lon, da, fine)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    val passaggi = passaggiProgrammati(partenze, orario, da / 1000, orizzonteMinuti = ((fine - da) / 60_000).toInt() + 1)
    val rimaste = CorseRimaste(adessoMs, raggruppa(passaggi, orario))
    chiaveMemoria.withLock { memoriaFermate[fermata.id] = rimaste }
    return rimaste
}

// --- nei tragitti

/** La chiave di un tratto con un mezzo: la corsa e quando parte. */
fun chiaveTratto(t: Tratto): String = t.corsaId.ifEmpty { t.linea } + "@" + t.partenzaPrevista

private fun normalizzaCapolinea(testo: String): String = testo.lowercase().filter { it.isLetterOrDigit() }

/** La partenza [p] e' della stessa linea e direzione del tratto [t]? */
fun stessaLinea(t: Tratto, p: PartenzaTransitous): Boolean {
    if (t.lineaId.isNotEmpty() && p.lineaId.isNotEmpty()) {
        if (t.lineaId != p.lineaId) return false
        if (t.direzioneId != null && p.direzione != null) return t.direzioneId == p.direzione
    } else if (!t.linea.equals(p.linea, ignoreCase = true)) {
        return false
    }
    val a = normalizzaCapolinea(t.direzione)
    val b = normalizzaCapolinea(p.capolinea)
    return a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a)
}

/** Dopo il tratto [t], quante corse della stessa linea e direzione restano tra le [partenze]? */
fun rimasteDopo(t: Tratto, partenze: List<PartenzaTransitous>): Int =
    partenze.filter { !it.cancellata && it.corsaId != t.corsaId && it.previsto > t.partenzaPrevista + 30_000 && stessaLinea(t, it) }
        .distinctBy { it.corsaId.ifEmpty { it.previsto.toString() } }
        .size

private val memoriaTratti = HashMap<String, UltimaCorsa?>()

/** Per il tratto [t]: ultima o penultima corsa di oggi? (null: no, o non si sa). */
suspend fun ultimaCorsaTratto(t: Tratto): UltimaCorsa? {
    if (t.aPiedi || t.cancellato || !daControllareUltima(t.partenzaPrevista)) return null
    val fermata = t.da.fermataId ?: return null
    val chiave = chiaveTratto(t)
    chiaveMemoria.withLock { if (memoriaTratti.containsKey(chiave)) return memoriaTratti[chiave] }
    val partenze = try {
        Transitous.partenzeFinoA(fermata, t.da.lat, t.da.lon, t.partenzaPrevista + 60_000, fineServizio(t.partenzaPrevista), idTransitous = true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    val esito = ultimaDaRimaste(rimasteDopo(t, partenze))
    chiaveMemoria.withLock {
        if (memoriaTratti.size > 300) memoriaTratti.clear()
        memoriaTratti[chiave] = esito
    }
    return esito
}

/** "64: ultima corsa", "Metro B: penultima corsa". */
fun testoUltimaDelTratto(t: Tratto, u: UltimaCorsa): String =
    tx("{0}: {1}", t.linea.ifEmpty { t.nomeMezzo }, testoUltimaCorsa(u))

/**
 * Per ogni tragitto ([Tragitto.chiave]) i mezzi che sono l'ultima o la
 * penultima corsa. Al massimo 4 richieste insieme; i tratti uguali si chiedono
 * una volta sola.
 */
suspend fun ultimeDeiTragitti(tragitti: List<Tragitto>): Map<String, List<Pair<Tratto, UltimaCorsa>>> = coroutineScope {
    val tratti = tragitti.flatMap { it.mezzi }.filter { daControllareUltima(it.partenzaPrevista) }.distinctBy { chiaveTratto(it) }
    val esiti = HashMap<String, UltimaCorsa?>()
    for (gruppo in tratti.chunked(4)) {
        gruppo.map { t -> async { chiaveTratto(t) to ultimaCorsaTratto(t) } }.awaitAll().forEach { (k, v) -> esiti[k] = v }
    }
    tragitti.mapNotNull { tr ->
        val trovati = tr.mezzi.mapNotNull { m -> esiti[chiaveTratto(m)]?.let { m to it } }
        if (trovati.isEmpty()) null else tr.chiave to trovati
    }.toMap()
}

/** Le scritte per i risultati di una ricerca: chiave del tragitto -> "64: ultima corsa · Metro B: penultima corsa". */
suspend fun ultimeCorseTragitti(tragitti: List<Tragitto>): Map<String, String> =
    ultimeDeiTragitti(tragitti).mapValues { (_, v) -> v.joinToString(" · ") { (t, u) -> testoUltimaDelTratto(t, u) } }
