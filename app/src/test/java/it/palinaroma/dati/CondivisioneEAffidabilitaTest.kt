package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mezzi in condivisione (GBFS 2 e 3) e affidabilita' delle linee. */
class CondivisioneEAffidabilitaTest {
    @Test
    fun gbfs() {
        val v2 = """{"data":{"en":{"feeds":[{"name":"free_bike_status","url":"https://x/fbs.json"},{"name":"vehicle_types","url":"https://x/vt.json"}]}}}"""
        assertEquals("https://x/fbs.json", feedGbfs(v2)["free_bike_status"])
        val v3 = """{"data":{"feeds":[{"name":"vehicle_status","url":"https://y/vs.json"}]}}"""
        assertEquals("https://y/vs.json", feedGbfs(v3)["vehicle_status"])
        val tipi = tipiGbfs("""{"data":{"vehicle_types":[{"vehicle_type_id":"s","form_factor":"scooter_standing"},{"vehicle_type_id":"b","form_factor":"bicycle"},{"vehicle_type_id":"m","form_factor":"moped"}]}}""")
        assertEquals(TipoMezzoCondiviso.MONOPATTINO, tipi["s"])
        assertEquals(TipoMezzoCondiviso.BICI, tipi["b"])
        assertEquals(TipoMezzoCondiviso.SCOOTER, tipi["m"])
        val mezzi = mezziGbfs(
            """{"data":{"bikes":[
                {"bike_id":"1","lat":41.8960,"lon":12.4826,"vehicle_type_id":"s","current_range_meters":18000,"rental_uris":{"android":"bird://scooter/1"}},
                {"bike_id":"2","lat":41.8961,"lon":12.4827,"vehicle_type_id":"b","is_reserved":true},
                {"bike_id":"3","lat":41.9000,"lon":12.5000,"vehicle_type_id":"b"}
            ]}}""",
            "Bird", tipi,
        )
        assertEquals(2, mezzi.size)
        assertEquals("bird://scooter/1", mezzi[0].apri)
        val qui = Punto(41.8958, 12.4826)
        val vicini = mezzi.map { it to distanzaMetri(qui.lat, qui.lon, it.lat, it.lon) }.filter { it.second < 400 }
        val r = riquadroCondivisione(vicini)!!
        assertEquals("1 monopattino entro 100 m", r.riassunto)
        assertEquals("Monopattino Bird", r.voci[0].titolo)
        assertTrue(r.voci[0].sotto.contains("18 km di autonomia"))
        assertEquals("Dati aperti di Bird", r.fonti)
        assertNull(riquadroCondivisione(emptyList()))
    }

    private fun tappa(lat: Double, lon: Double) = Tappa("Qui", lat, lon, null, null, null, null, null)

    @Test
    fun suggerimentoNeiTragitti() {
        val qui = tappa(41.8958, 12.4826)
        val piedi = Tratto(
            "WALK", 0, 900_000, 0, 900_000, false, 900, 1100.0, qui, tappa(41.905, 12.49), "", "", null, "",
            emptyList(), emptyList(), emptyList(), false, emptyList(),
        )
        val t = Tragitto(0, 1_800_000, 1800, 0, listOf(piedi))
        val monopattino = MezzoCondiviso("1", "Bird", TipoMezzoCondiviso.MONOPATTINO, 41.8960, 12.4826, null, null)
        val testo = suggerimentoCondivisione(t, listOf(monopattino to 22.0))
        assertEquals("Monopattino Bird a 20 m: 5 min invece di 15 a piedi", testo)
        // a piedi poco: non conviene
        assertNull(suggerimentoCondivisione(Tragitto(0, 0, 0, 0, listOf(piedi.copy(metri = 400.0, durata = 300))), listOf(monopattino to 22.0)))
        // troppo lontano dalla partenza
        assertNull(suggerimentoCondivisione(t, listOf(monopattino to 400.0)))
    }

    private fun alle(ore: Int, minuti: Int, giorniDopo: Int = 0) = istanteARoma(2026, 11, 26, ore, minuti, giorniDopo)

    @Test
    fun affidabilita() {
        // giovedi' 26 novembre: feriale; sabato 28; domenica 29; 8 dicembre festa
        assertEquals("F05", chiaveFascia(alle(8, 0)))
        assertEquals("F21", chiaveFascia(alle(22, 30)))
        assertEquals("F21", chiaveFascia(alle(0, 30, 1)))
        assertEquals("S01", chiaveFascia(alle(2, 0, 2)))
        assertEquals("D17", chiaveFascia(alle(18, 0, 3)))
        assertEquals("D09", chiaveFascia(alle(10, 0, 12)))
        // Pasquetta 2026 come una domenica
        assertEquals("D09", chiaveFascia(istanteARoma(2026, 4, 6, 10, 0)))
        val d = leggiAffidabilita("""{"v":1,"aggiornato":1,"giorni":12,"linee":{"64":{"F05":[340,190,78,9]},"40":{"F05":[50,20,96,0]}}}""")
        assertNotNull(d)
        assertEquals("Di solito a quest'ora: in orario 8 corse su 10, ritardo medio 3 min", testoAffidabilita(d, "64", alle(8, 0)))
        assertEquals("Di solito a quest'ora: in orario 10 corse su 10, quasi sempre puntuale", testoAffidabilita(d, "40", alle(8, 0)))
        assertNull(testoAffidabilita(d, "64", alle(14, 0)))
        assertEquals("ultimi 12 giorni, dal tempo reale di Roma Mobilità", fonteAffidabilita(d!!))
    }
}
