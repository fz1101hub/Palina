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
