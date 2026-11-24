package it.palinaroma.dati

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * I tragitti visti dall'iPhone: la stessa ricerca, gli stessi testi e gli
 * stessi errori dell'app Android (Tragitti.kt); SwiftUI disegna soltanto.
 */

/** La schermata dei tragitti in un momento. */
data class StatoTragitti(
    val da: Luogo?,
    val a: Luogo?,
    /** 0 adesso, 1 parti alle, 2 arriva entro. */
    val quando: Int,
    val istante: Long,
    val etichettaParti: String,
    val etichettaArriva: String,
    val inCorso: Boolean,
    /** Un invito, un errore (con [riprova]) o "nessun tragitto". */
    val messaggio: String?,
    val riprova: Boolean,
    val carte: List<CartaTragitto>,
    val aPiedi: CartaTragitto?,
    val prima: Boolean,
    val dopo: Boolean,
    val caricandoAltri: Boolean,
    val calcolato: String?,
)

/** Un pezzo della mappa del tragitto: a piedi (tratteggiato) o con un mezzo. */
data class PezzoMappa(val aPiedi: Boolean, val colore: Long, val punti: List<Punto>)

/** Una vettura seguita col GPS su un tratto del tragitto. */
data class VetturaTragitto(val blocco: Int, val lat: Double, val lon: Double, val colore: Long, val linea: String, val testo: String)

/** I luoghi trovati per un testo: fermate dell'orario e indirizzi di Transitous. */
data class LuoghiTrovati(val fermate: List<Luogo>, val luoghi: List<Luogo>, val errore: String?, val nessuno: String?)

class MotoreTragitti(private val orario: Orario) {
    private var da: Luogo? = Luogo.posizione()
    private var a: Luogo? = null
    private var quando = 0
    private var istante = 0L
    private var risultato: EsitoRicercaTragitti? = null
    private var inCorso = false
    private var caricandoAltri = false
    private var chiaveUltima: String? = null

    private data class Richiesta(val da: Luogo, val a: Luogo, val istante: Long?, val arrivaEntro: Boolean)

    private var richiesta: Richiesta? = null

    fun scegliDa(luogo: Luogo) {
        da = luogo
    }

    fun scegliA(luogo: Luogo) {
        a = luogo
    }

    /** "Come arrivare" da una fermata: l'arrivo e' la fermata, la partenza resta (o e' la posizione). */
    fun versoFermata(fermataId: String) {
        val f = orario.fermata(fermataId) ?: return
        a = luogoDaFermata(f)
        if (da == null) da = Luogo.posizione()
    }

    fun inverti() {
        val vecchio = da
        da = a
        a = vecchio
    }

    fun adesso() {
        quando = 0
    }

    fun parti(istante: Long) {
        quando = 1
        this.istante = istante
    }

    fun arriva(istante: Long) {
        quando = 2
        this.istante = istante
    }

    private fun chiave(): String = listOf(da?.inRiga(), a?.inRiga(), quando.toString(), istante.toString()).joinToString("#")

    /** Serve la posizione per cercare (partenza o arrivo "La mia posizione"). */
    fun servePosizione(): Boolean = da?.tipo == TipoLuogo.POSIZIONE || a?.tipo == TipoLuogo.POSIZIONE

    /** Cercare solo se e' cambiato qualcosa o se i risultati hanno piu' di due minuti. */
    fun serveRicerca(): Boolean {
        if (da == null || a == null) return false
        val r = risultato ?: return true
        return chiave() != chiaveUltima || adessoMs() - r.calcolatoAlle >= 120_000
    }

    /** Cerca i tragitti; [lat] e [lon] sono la posizione (NaN se non la si conosce). */
    suspend fun cerca(lat: Double, lon: Double): StatoTragitti = withContext(Dispatchers.Main) {
        val daScelto = da
        val aScelto = a
        if (daScelto == null || aScelto == null) return@withContext stato()
        chiaveUltima = chiave()
        inCorso = true
        val qui = Luogo("La mia posizione", "", lat, lon, TipoLuogo.POSIZIONE)
        val partenza = if (daScelto.tipo == TipoLuogo.POSIZIONE) qui.takeIf { it.haCoordinate } else daScelto
        val arrivo = if (aScelto.tipo == TipoLuogo.POSIZIONE) qui.takeIf { it.haCoordinate } else aScelto
        val ist = if (quando == 0) null else istante
        val r = cercaTragitti(partenza, arrivo, ist, quando == 2)
        risultato = r
        if (r.errore == null && partenza != null && arrivo != null) richiesta = Richiesta(partenza, arrivo, ist, quando == 2)
        inCorso = false
        stato()
    }

    /** Le partenze precedenti o successive. */
    suspend fun altri(dopo: Boolean): StatoTragitti = withContext(Dispatchers.Main) {
        val r = risultato
        val q = richiesta
        if (r == null || q == null || r.errore != null) return@withContext stato()
        caricandoAltri = true
        risultato = altriTragitti(r, q.da, q.a, q.istante, q.arrivaEntro, dopo)
        caricandoAltri = false
        stato()
    }

    fun stato(): StatoTragitti {
        val r = risultato
        val adesso = adessoMs()
        val pronto = r != null && r.errore == null && !inCorso
        return StatoTragitti(
            da = da,
            a = a,
            quando = quando,
            istante = if (quando == 0) adesso else istante,
            etichettaParti = if (quando == 1) "Parti " + giornoEOra(istante) else "Parti alle…",
            etichettaArriva = if (quando == 2) "Arriva " + giornoEOra(istante) else "Arriva entro…",
            inCorso = inCorso,
            messaggio = when {
                a == null || da == null -> "Scegli dove vuoi andare: cerca un indirizzo, un luogo o una fermata."
                inCorso -> null
                r?.errore != null -> r.errore
                pronto && r.tragitti.isEmpty() -> "Nessun tragitto con i mezzi in questo orario. Prova a cambiare l'ora o il luogo."
                else -> null
            },
            riprova = !inCorso && r?.errore != null && a != null,
            carte = if (pronto) r.tragitti.map { cartaTragitto(it, adesso) } else emptyList(),
            aPiedi = if (pronto) r.soloAPiedi?.let { cartaAPiedi(it) } else null,
            prima = pronto && r.cursorePrima != null && r.tragitti.isNotEmpty(),
            dopo = pronto && r.cursoreDopo != null && r.tragitti.isNotEmpty(),
            caricandoAltri = caricandoAltri,
            calcolato = if (pronto) "Calcolato alle ${oraARoma(r.calcolatoAlle)}. Tocca un tragitto per la mappa e le fermate." else null,
        )
    }

    /** Il tragitto [indice] dell'elenco (-1 = tutto a piedi). */
    private fun tragitto(indice: Int): Tragitto? {
        val r = risultato ?: return null
        return if (indice < 0) r.soloAPiedi else r.tragitti.getOrNull(indice)
    }

    fun dettaglio(indice: Int): DettaglioTragitto? = tragitto(indice)?.let { dettaglioTragitto(it) }

    /** Le linee del tragitto sulla mappa, dalla partenza all'arrivo. */
    fun mappa(indice: Int): List<PezzoMappa> = tragitto(indice)?.tratti.orEmpty().map { t ->
        val punti = t.forma.ifEmpty { listOf(Punto(t.da.lat, t.da.lon), Punto(t.a.lat, t.a.lon)) }
            .filter { !it.lat.isNaN() && !it.lon.isNaN() }
        PezzoMappa(t.aPiedi, if (t.aPiedi) Colori.INCHIOSTRO else coloreTratto(t), punti)
    }

    /** Il punto di partenza e quello d'arrivo del tragitto. */
    fun estremi(indice: Int): List<Punto> {
        val t = tragitto(indice) ?: return emptyList()
        val primo = t.tratti.first().da
        val ultimo = t.tratti.last().a
        return listOf(Punto(primo.lat, primo.lon), Punto(ultimo.lat, ultimo.lon))
    }

    /** La fermata dell'app dove si sale nel blocco [blocco], per aprirne i tempi ("Tempi d'attesa a …"). */
    suspend fun fermataSalita(indice: Int, blocco: Int): VoceFermata? {
        val t = tragitto(indice)?.tratti?.getOrNull(blocco) ?: return null
        if (t.aPiedi) return null
        return withContext(dispatcherDati) {
            runCatching { fermataLocale(orario, t.da) }.getOrNull()?.let { voceFermata(it, null, orario) }
        }
    }

    /** Le vetture dei bus e tram del tragitto seguite col GPS (Roma Mobilita', BusOne per Cotral). */
    suspend fun vetture(indice: Int): List<VetturaTragitto> = withContext(Dispatchers.Main) {
        val t = tragitto(indice) ?: return@withContext emptyList()
        val adesso = adessoMs() / 1000
        val trovate = try {
            vettureTragitto(t)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        trovate.map { (i, v) ->
            val tr = t.tratti[i]
            VetturaTragitto(i, v.lat, v.lon, coloreTratto(tr), tr.linea.ifEmpty { tr.nomeMezzo }, testoVetturaTragitto(tr, v, adesso))
        }
    }

    // --- scelta della partenza e dell'arrivo

    /** Le fermate dell'orario che corrispondono al testo (le prime 5). */
    fun fermateTrovate(testo: String): List<Luogo> =
        if (testo.trim().length < 3) emptyList() else orario.cercaFermate(testo.trim()).take(5).map { luogoDaFermata(it) }

    /** Indirizzi e luoghi da Transitous, vicino a dove sei ([lat] NaN se non lo si sa). */
    suspend fun cercaLuoghi(testo: String, lat: Double, lon: Double): LuoghiTrovati = withContext(Dispatchers.Main) {
        val t = testo.trim()
        val fermate = withContext(dispatcherDati) { fermateTrovate(t) }
        try {
            val luoghi = Transitous.cerca(t, if (lat.isNaN()) null else Punto(lat, lon))
            LuoghiTrovati(fermate, luoghi, null, if (fermate.isEmpty() && luoghi.isEmpty()) "Nessun risultato per \"$t\"." else null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LuoghiTrovati(fermate, emptyList(), "La ricerca degli indirizzi non è disponibile: controlla la connessione.", null)
        }
    }

    /** Le fermate preferite come luoghi, per nome. */
    fun preferite(ids: Set<String>): List<Luogo> =
        orario.fermate(ids.toList()).values.sortedBy { it.nome }.map { luogoDaFermata(it) }

    /** I luoghi recenti salvati (uno per riga, come su Android). */
    fun recenti(salvati: String): List<Luogo> = salvati.lines().mapNotNull { luogoDaRiga(it) }

    /** Il testo da salvare dopo aver scelto [luogo]. */
    fun ricorda(salvati: String, luogo: Luogo): String =
        ricordaLuogo(recenti(salvati), luogo).joinToString("\n") { it.inRiga() }
}
