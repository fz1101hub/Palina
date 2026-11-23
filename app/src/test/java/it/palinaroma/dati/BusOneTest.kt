package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * GPS dei bus Cotral (BusOne) e flusso di Roma vuoto. Feed generati con la
 * libreria ufficiale gtfs-realtime-bindings (Python), come quelli visti dal vivo.
 */
class BusOneTest {
    private val t0 = 1795400000L

    /** Due bus: il 4221 sulla corsa 158556819 della linea 884, in arrivo a f7414; lo 0126 solo con il GPS. */
    private val mezzi = Base64.getDecoder().decode(
        "CgsKAzIuMBjAwo7YBhJNCgQ0MjIxIkUKEAoJMTU4NTU2ODE5KgM4ODQSFA1y8yZCFY/kWkEdAAAAQy3NzFRBGAQoqsKO2AY6BWY3NDE0" +
            "QgwKBDQyMjESBDQyMjESIgoEMDEyNiIaEgoNjbQmQhXYNllBKITCjtgGQgYKBDAxMjY=",
    )

    /** La corsa 158556819 con tre fermate, un minuto l'una dall'altra. */
    private val corse = Base64.getDecoder().decode(
        "CgsKAzIuMBjAwo7YBhJmCgkxNTg1NTY4MTkaWQoQCgkxNTg1NTY4MTkqAzg4NBITCAESCAgAEMDCjtgGIgVmNzQwOBITCAISCAgAEPzC" +
            "jtgGIgVmNzQxNBITCAMSCAgAELjDjtgGIgVmNzQyMBoGCgQ0MjIx",
    )

    /** Solo l'intestazione, come il flusso di Roma Mobilita' la notte del 23 novembre 2026. */
    private val vuoto = Base64.getDecoder().decode("CgsKAzIuMBjAwo7YBg==")

    @Test
    fun iBusHannoIlPrefissoDiCotralELaFermata() {
        val lista = FeedTempoReale.mezzi(mezzi, Rete.COTRAL.prefisso)
        assertEquals(2, lista.size)
        val bus = lista[0]
        assertEquals("C:158556819", bus.corsaId)
        assertEquals("C:884", bus.lineaId)
        assertEquals("C:f7414", bus.fermataId)
        assertEquals("4221", bus.etichetta)
        assertEquals(41.73774, bus.lat, 1e-4)
        assertEquals(128f, bus.rotta)
        assertEquals(t0 - 22, bus.rilevato)
        // senza corsa resta senza codici, non con un prefisso solo
        assertEquals("", lista[1].corsaId)
        assertEquals("", lista[1].lineaId)
        assertEquals("", lista[1].fermataId)
        // senza prefisso i codici restano come sono
        assertEquals("158556819", FeedTempoReale.mezzi(mezzi)[0].corsaId)
    }

    @Test
    fun leCorseHannoIlPrefissoAncheNelleFermate() {
        val corsa = FeedTempoReale.corse(corse, Rete.COTRAL.prefisso).single()
        assertEquals("C:158556819", corsa.corsaId)
        assertEquals("C:884", corsa.lineaId)
        assertEquals(listOf("C:f7408", "C:f7414", "C:f7420"), corsa.fermate.map { it.fermataId })
        assertEquals("4221", corsa.veicolo)
    }

    @Test
    fun ilFlussoVuotoSiRiconosce() {
        assertEquals(t0, FeedTempoReale.istante(vuoto))
        assertEquals(t0, FeedTempoReale.istante(corse))
        assertTrue(FeedTempoReale.corse(vuoto).isEmpty())
        assertTrue(FeedTempoReale.mezzi(vuoto).isEmpty())
        assertEquals(0L, FeedTempoReale.istante(ByteArray(0)))

        val corsa = FeedTempoReale.corse(corse)
        // letto e vuoto: Roma Mobilita' non pubblica
        assertTrue(senzaTempoReale(emptyList(), t0, t0 + 10))
        // con le corse e recente: va bene
        assertFalse(senzaTempoReale(corsa, t0, t0 + 30))
        // con le corse ma fermo da piu' di 10 minuti
        assertTrue(senzaTempoReale(corsa, t0, t0 + 601))
        // ora del feed sconosciuta: conta solo se ci sono corse
        assertFalse(senzaTempoReale(corsa, 0, t0))
        // non letto (senza rete): non e' colpa di Roma Mobilita'
        assertFalse(senzaTempoReale(null, t0, t0 + 10))
    }

    @Test
    fun corseDiTransitousComeLeChiamaLApp() {
        assertEquals("C:158556819", corsaApp("20261123_03:45_it-Lazio-Cotral_158556819"))
        assertEquals("T714A", corsaApp("20261109_08:00_it-Lazio-Rome_T714A"))
        assertEquals("T:FR1_123", corsaApp("20261123_06:00_it-trenitalia_FR1_123"))
        assertNull(corsaApp("20261123_06:00_de-DELFI_123"))
        assertNull(corsaApp("158556819"))
    }

    @Test
    fun lePartenzeCotralVisteDalGpsPortanoAlBus() {
        val bus = FeedTempoReale.mezzi(mezzi, Rete.COTRAL.prefisso)
        fun partenza(corsa: String, secondi: Long, tempoReale: Boolean) =
            PartenzaTransitous(corsa, "it-Lazio-Cotral_884", "884", 0, "Frosinone",
                (t0 + secondi) * 1000, (t0 + secondi) * 1000, tempoReale, false, "it-Lazio-Cotral_f7414")
        val passaggi = passaggiProgrammati(
            listOf(
                partenza("20261123_03:45_it-Lazio-Cotral_158556819", 60, tempoReale = true),  // il 4221
                partenza("20261123_04:45_it-Lazio-Cotral_158556820", 3600, tempoReale = true), // ritardo, senza GPS
                partenza("20261123_05:45_it-Lazio-Cotral_158556821", 5000, tempoReale = false),
            ),
            orario = null, adesso = t0, mezzi = bus,
        )
        assertEquals(listOf("C:158556819", "C:158556820", "C:158556821"), passaggi.map { it.passaggio.corsaId })
        assertEquals(listOf(true, false, false), passaggi.map { it.passaggio.gps })
        assertEquals(listOf(true, true, false), passaggi.map { it.passaggio.tracciato })
        // dalla corsa, con o senza il codice di Transitous, si trova il bus
        assertEquals("4221", mezzoDellaCorsa(bus, "C:158556819")?.etichetta)
        assertEquals("4221", mezzoDellaCorsa(bus, "20261123_03:45_it-Lazio-Cotral_158556819")?.etichetta)
        assertNull(mezzoDellaCorsa(bus, "158556819"))  // senza prefisso sarebbe una corsa di Roma
    }

    @Test
    fun laDirezioneDeiBusDalleFermate() {
        val perDirezione = mapOf(
            0 to setOf("C:f7400", "C:f7408", "C:f7414", "C:f7420"),
            1 to setOf("C:f7421", "C:f7415", "C:f7409", "C:f7400"),
        )
        assertEquals(0, direzioneDalleFermate(listOf("C:f7408", "C:f7414", "C:f7420"), perDirezione))
        assertNull(direzioneDalleFermate(listOf("C:f7400"), perDirezione))  // capolinea comune: pari
        assertNull(direzioneDalleFermate(listOf("C:f9999"), perDirezione))
        assertNull(direzioneDalleFermate(emptyList(), perDirezione))

        val bus = FeedTempoReale.mezzi(mezzi, Rete.COTRAL.prefisso)
        val tempi = FeedTempoReale.corse(corse, Rete.COTRAL.prefisso)
        val conDir = conDirezione(bus, tempi, perDirezione)
        assertEquals(0, conDir[0].direzione)
        assertNull(conDir[1].direzione)  // senza corsa e senza fermata
        // una linea sola direzione: niente da decidere
        assertNull(conDirezione(bus, tempi, mapOf(0 to perDirezione.getValue(0)))[0].direzione)
        // con la sola fermata del GPS
        assertEquals(1, conDirezione(listOf(bus[0].copy(corsaId = "", fermataId = "C:f7415")), emptyList(), perDirezione)[0].direzione)
    }
}
