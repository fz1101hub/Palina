package it.palinaroma.ponte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PonteTest {
    private val adesso = 1_795_500_000L

    private val tabellone = TabelloneOrologio(
        creato = adesso * 1000,
        nota = "Roma Mobilità ora non pubblica il tempo reale",
        fermate = listOf(
            FermataOrologio(
                id = "F:RL_SANPAOLO",
                nome = "Basilica San Paolo",
                sotto = "Stazione · Metro B/B1 · Metromare",
                preferita = true,
                distanza = 140,
                righe = listOf(
                    RigaOrologio("Metromare", 0xFF1D4F91.toInt(), "Cristoforo Colombo", listOf(adesso + 300, adesso + 1200), true),
                    RigaOrologio("MEB", 0xFF862633.toInt(), "Laurentina", listOf(adesso + 60), false, avviso = "soppresso"),
                ),
            ),
            FermataOrologio("70078", "Piazza Venezia", "Palina 70078", false, null, emptyList()),
        ),
    )

    @Test
    fun andataERitorno() {
        val letto = CodificaOrologio.leggi(CodificaOrologio.scrivi(tabellone))
        assertEquals(tabellone, letto)
    }

    @Test
    fun testoRovinatoONullo() {
        assertNull(CodificaOrologio.leggi(null))
        assertNull(CodificaOrologio.leggi(""))
        assertNull(CodificaOrologio.leggi("{non e' json"))
    }

    @Test
    fun minutiPerLoSchermoPiccolo() {
        assertEquals("ora", testoMinuti(adesso + 30, adesso))
        assertEquals("ora", testoMinuti(adesso - 20, adesso))
        assertEquals("3'", testoMinuti(adesso + 180, adesso))
        assertEquals("1 h 05'", testoMinuti(adesso + 3900, adesso))
        val riga = tabellone.fermate[0].righe[0]
        assertEquals("5' · 20'", testoPassaggi(riga, adesso))
        // passato da piu' di mezzo minuto: non si mostra
        assertEquals("15'", testoPassaggi(riga, adesso + 301))
    }

    @Test
    fun primoPassaggioDellaFermata() {
        val (riga, quando) = tabellone.fermate[0].primoPassaggio(adesso)!!
        assertEquals("MEB", riga.linea)
        assertEquals(adesso + 60, quando)
        assertNull(tabellone.fermate[1].primoPassaggio(adesso))
        assertEquals("140 m", testoDistanza(140))
        assertEquals("1,2 km", testoDistanza(1200))
    }
}
