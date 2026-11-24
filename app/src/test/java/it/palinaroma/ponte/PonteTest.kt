package it.palinaroma.ponte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // passato da meno di mezzo minuto: ancora "ora"; da di piu': non si mostra
        assertEquals("ora · 14'", testoPassaggi(riga, adesso + 310))
        assertEquals("13'", testoPassaggi(riga, adesso + 400))
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

    @Test
    fun treniNelTabelloneERichieste() {
        val esempio = tabelloneDiEsempio(adesso)
        val letto = CodificaOrologio.leggi(CodificaOrologio.scrivi(esempio))!!
        assertEquals(esempio, letto)
        val metromare = letto.riga("F:RL_SANPAOLO", "F:ROMALIDO", "Cristoforo Colombo")!!
        assertTrue(metromare.diTreni)
        // il treno del secondo passaggio, con la sua chiave
        val treno = metromare.treno(adesso + 1200)!!
        assertEquals("F:RL_SANPAOLO|F:ROMALIDO|${adesso + 1200}", treno.chiave)
        assertEquals(treno, TrenoOrologio.leggi(treno.testoJson()))
        assertNull(TrenoOrologio.leggi("{}"))
        assertNull(metromare.treno(adesso + 5))
        // i bus non hanno treni; se il capolinea cambia la riga si ritrova lo stesso
        assertFalse(letto.riga("70078", "64", "Stazione San Pietro")!!.diTreni)
        assertEquals("64", letto.riga("70078", "64", "Termini")?.linea)
        assertNull(letto.riga("70078", "999", "Termini"))
        // un tabellone vecchio, senza lineaId: si riconosce dal nome
        assertEquals("Metromare", tabellone.riga("F:RL_SANPAOLO", "Metromare", "Cristoforo Colombo")?.linea)
    }

    @Test
    fun corsaELineeSeguite() {
        val treno = TrenoOrologio(null, "F:RL_SANPAOLO", "F:ROMALIDO", 0, adesso + 120, "astral:1", 180)
        val corsa = corsaDiEsempio(treno, adesso)
        assertEquals(corsa, CodificaCorsa.leggi(CodificaCorsa.scrivi(corsa)))
        assertEquals(3, corsa.fermate[1].ritardoMinuti)
        assertNull(CodificaCorsa.leggi("non json"))
        val seguite = listOf(LineaSeguita("70078", "64"), LineaSeguita("F:RL_SANPAOLO", "F:ROMALIDO"))
        assertEquals(seguite, RichiestaOrologio.leggi(RichiestaOrologio.scrivi(seguite)))
        assertEquals(0, RichiestaOrologio.scrivi(emptyList()).size)
        assertEquals(emptyList<LineaSeguita>(), RichiestaOrologio.leggi(ByteArray(0)))
        assertEquals(emptyList<LineaSeguita>(), RichiestaOrologio.leggi("rotto".toByteArray()))
    }
}
