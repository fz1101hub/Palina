package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Feed generati con la libreria ufficiale gtfs-realtime-bindings (Python). */
class FeedTempoRealeTest {
    private val t0 = 1793700000L

    private val corse = Base64.getDecoder().decode(
        "CgsKAzIuMBig4abXBhJ5CgExGnQKDgoFVDcxNEEqAzcxNDABEhwIAxIRCOL//////////wEQ6OKm1wYiBTcwMjQzEhEIBBoGEKTjptcG" +
            "IgU3MDI0NBITCAUSBhDg46bXBiIFNzAyNDUoARIJCAYiBTcwMjQ2GgYKBDQ1MjEo0///////////ARIkCgEyGh8KDAoEVDY0WCADKgI2" +
            "NBIPEgYQhOKm1wYiBTcwMjQzEi0KATMaKAoHCgJUOCoBOBIQEgYQlOWm1wYiBjA3MDI0MzILKglDQVNBTEVUVE8SBwoBNCoCMAE=",
    )

    private val mezzi = Base64.getDecoder().decode(
        "CgUKAzIuMBJGCgJ2MSJACg4KBVQ3MTRBKgM3MTQwABIYDZqZJ0IVAABIQR0AALRCIQAAAAAASpNAKKDhptcGQg4KBDQ1MjESBjQ1MjEg" +
            "QRIZCgJ2MiITCgkKA1Q2NCoCNjRCBgoEMzAwMQ==",
    )

    @Test
    fun leggeLeCorse() {
        val lista = FeedTempoReale.corse(corse)
        assertEquals(3, lista.size)

        val c714 = lista[0]
        assertEquals("T714A", c714.corsaId)
        assertEquals("714", c714.lineaId)
        assertEquals(1, c714.direzione)
        assertFalse(c714.cancellata)
        // la fermata senza orario e' scartata, quella saltata e' segnata
        assertEquals(listOf("70243", "70244", "70245"), c714.fermate.map { it.fermataId })
        assertEquals(t0 + 200, c714.fermate[0].orario)
        assertEquals(t0 + 260, c714.fermate[1].orario)  // solo partenza
        assertTrue(c714.fermate[2].saltata)
        assertEquals("4521", c714.veicolo)
        assertEquals("", lista[2].veicolo)

        assertTrue(lista[1].cancellata)
        assertNull(lista[1].direzione)
        assertEquals("CASALETTO", lista[2].capolinea)
    }

    @Test
    fun leggeIMezzi() {
        val lista = FeedTempoReale.mezzi(mezzi)
        assertEquals(1, lista.size)  // il mezzo senza posizione e' scartato
        val m = lista[0]
        assertEquals("714", m.lineaId)
        assertEquals(0, m.direzione)
        assertEquals(41.9, m.lat, 1e-4)
        assertEquals(12.5, m.lon, 1e-4)
        assertEquals("4521 A", m.etichetta)
        assertEquals(90f, m.rotta)
        assertEquals(t0, m.rilevato)
    }

    @Test
    fun distingueGPSEOrario() {
        val lista = FeedTempoReale.corse(corse)
        // la 714 ha la vettura nel feed; la 8 no, a meno che non compaia tra le vetture rilevate
        val senzaGps = arriviAllaFermata(lista, "70243", orario = null, adesso = t0)
        assertEquals(listOf(true, false), senzaGps.map { it.passaggi.first().tracciato })
        val conGps = arriviAllaFermata(lista, "70243", orario = null, adesso = t0, tracciate = setOf("T8"))
        assertEquals(listOf(true, true), conGps.map { it.passaggi.first().tracciato })
        assertEquals("T714A", conGps[0].passaggi.first().corsaId)
    }

    @Test
    fun uniscePassaggiInTempoRealeEProgrammati() {
        val rt = passaggiTempoReale(FeedTempoReale.corse(corse), "70243", orario = null, adesso = t0)
        fun partenza(corsa: String, linea: String, direzione: Int?, secondi: Long, tempoReale: Boolean = false) =
            PartenzaTransitous(corsa, "it-Lazio-Rome_$linea", linea, direzione, "Capolinea $linea",
                (t0 + secondi) * 1000, (t0 + secondi) * 1000, tempoReale, false, "it-Lazio-Rome_70243")
        val programmati = passaggiProgrammati(
            listOf(
                partenza("20261109_08:00_it-Lazio-Rome_T714A", "714", 1, 205), // stessa corsa della 714 in tempo reale
                partenza("altro-codice", "714", 1, 230),                         // stessa corsa, codice diverso: scartata
                partenza("T714Z", "714", 1, 1500),                                // corsa successiva, solo da orario
                partenza("N1", "n1", null, 900),                                  // linea notturna, solo da orario
                partenza("tardi", "n1", null, 6000),                              // oltre i 90 minuti
            ),
            orario = null, adesso = t0,
            mezzi = listOf(MezzoRT("N1", "n1", null, 41.9, 12.5, "7001")),
        )
        assertEquals(4, programmati.size)
        val uniti = uniscPassaggi(rt, programmati)
        val arrivi = raggruppa(uniti, orario = null)
        assertEquals(listOf("714", "8", "n1", "714"), arrivi.map { it.nomeLinea })
        assertEquals(listOf(t0 + 200), arrivi[0].orari)                     // vince il tempo reale
        assertEquals(listOf(t0 + 1500), arrivi[3].orari)                    // la successiva, con il capolinea di Transitous
        assertEquals("Capolinea 714", arrivi[3].capolinea)
        assertTrue(arrivi[2].passaggi.single().tracciato)                    // la n1 ha la vettura nel feed
        assertFalse(arrivi[3].passaggi.single().tracciato)
    }

    @Test
    fun scriveDaQuantoERilevata() {
        assertNull(etaRilevamento(0, t0))
        assertEquals("adesso", etaRilevamento(t0 - 5, t0))
        assertEquals("40 s fa", etaRilevamento(t0 - 42, t0))
        assertEquals("3 min fa", etaRilevamento(t0 - 200, t0))
        assertEquals("adesso", etaRilevamento(t0 + 30, t0))
    }

    @Test
    fun arriviRaggruppatiPerLineaEDirezione() {
        val lista = FeedTempoReale.corse(corse)
        val arrivi = arriviAllaFermata(lista, "70243", orario = null, adesso = t0)
        // la 64 e' cancellata; la 8 ha la fermata scritta con lo zero davanti
        assertEquals(listOf("714", "8"), arrivi.map { it.lineaId })
        assertEquals("Casaletto", arrivi[1].capolinea)
        assertEquals(listOf(t0 + 200), arrivi[0].orari)
    }

    @Test
    fun ignoraIPassaggiOltreLOrizzonte() {
        val lista = FeedTempoReale.corse(corse)
        assertTrue(arriviAllaFermata(lista, "70243", null, adesso = t0 + 5000).isEmpty())
        assertTrue(arriviAllaFermata(lista, "70243", null, adesso = t0 - 3 * 3600).isEmpty())
    }
}
