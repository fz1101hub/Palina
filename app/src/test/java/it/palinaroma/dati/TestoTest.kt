package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestoTest {
    @Test
    fun divideLeRigheCsv() {
        assertEquals(listOf("a", "b", "", "d"), LettoreCsv.dividi("a,b,,d"))
        assertEquals(listOf("714", "PALAZZO, SPORT", "1"), LettoreCsv.dividi("714,\"PALAZZO, SPORT\",1"))
        assertEquals(listOf("x", "detto \"Er\" Bus"), LettoreCsv.dividi("x,\"detto \"\"Er\"\" Bus\""))
    }

    @Test
    fun scriveBeneICapolinea() {
        assertEquals("Stazione Termini", bellaScrittura("STAZIONE TERMINI"))
        assertEquals("P.za dell'Arco", bellaScrittura("P.ZA DELL'ARCO"))
        assertEquals("Staz.ne Metro Cipro (MA)", bellaScrittura("STAZ.NE METRO CIPRO (MA)"))
        assertEquals("Staz.ne S. Pietro FS", bellaScrittura("STAZ.NE S. PIETRO FS"))
        assertEquals("Ponte Mammolo", bellaScrittura("Ponte Mammolo"))
        assertEquals("L.go Argentina", bellaScrittura("  L.GO   ARGENTINA "))
        // dopo la barra comincia un'altra strada
        assertEquals("Nazionale/Torino", bellaScrittura("NAZIONALE/TORINO"))
        assertEquals("Siculiana/Termini Imerese", bellaScrittura("SICULIANA/TERMINI IMERESE"))
        assertEquals("Nazionale/Quattro Fontane", bellaScrittura("NAZIONALE/QUATTRO FONTANE"))
        assertEquals("Via del Corso/Minghetti", bellaScrittura("VIA DEL CORSO/MINGHETTI"))
        assertEquals("Termini (MA-MB-FS)", bellaScrittura("TERMINI (MA-MB-FS)"))
    }

    @Test
    fun normalizzaPerLaRicerca() {
        assertEquals("sant agnese annibaliano", normalizza("Sant'Agnese/Annibaliano"))
        assertEquals("citta universitaria", normalizza("Città Universitaria"))
    }

    @Test
    fun confrontaICodici() {
        assertTrue(stessoCodice("70243", "070243"))
        assertFalse(stessoCodice("70243", "70244"))
        assertFalse(stessoCodice("0", "00"))
    }

    @Test
    fun ordinaLeLinee() {
        val linee = listOf("H", "714", "8", "C3", "64", "16", "N1")
        assertEquals(listOf("8", "16", "64", "714", "C3", "H", "N1"), linee.sortedWith(ordineLinee))
    }

    @Test
    fun misuraLeDistanze() {
        // Termini - Colosseo, circa 1,4 km in linea d'aria
        val d = distanzaMetri(41.9010, 12.5016, 41.8902, 12.4922)
        assertTrue(d in 1300.0..1600.0)
    }
}
