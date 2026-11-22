package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabelloneTest {
    private val adesso = 1_000_000L

    private fun arrivo(linea: String, capolinea: String, vararg minuti: Int, tracciato: Boolean = true) = Arrivo(
        lineaId = linea,
        nomeLinea = linea,
        direzione = 0,
        capolinea = capolinea,
        passaggi = minuti.map { Passaggio(adesso + it * 60L + 20, "c$linea$it", tracciato) },
    )

    @Test
    fun leLineeFissateVannoInCimaEQuelleSenzaPassaggiNonSpariscono() {
        val arrivi = listOf(arrivo("64", "Stazione S. Pietro", 1), arrivo("40", "P.za Pia", 4), arrivo("H", "Capasso", 9))
        val diviso = dividiArrivi(arrivi, setOf("40", "170"))
        assertEquals(listOf("40"), diviso.fissate.map { it.lineaId })
        assertEquals(listOf("64", "H"), diviso.altre.map { it.lineaId })
        assertEquals(listOf("170"), diviso.senzaPassaggi)
    }

    @Test
    fun leggeLeLineeFissateDiUnaSolaFermata() {
        val salvate = setOf(chiaveFissata("70078", "64"), chiaveFissata("70078", "40"), chiaveFissata("20153", "85"))
        assertEquals(setOf("64", "40"), lineeFissate("70078", salvate))
        assertEquals(setOf("85"), lineeFissate("20153", salvate))
        assertEquals(emptySet<String>(), lineeFissate("7007", salvate))
    }

    @Test
    fun diceDaQuantoSonoArrivatiIDati() {
        assertEquals("adesso", testoAggiornato(10_000, 15_000))
        assertEquals("10 s fa", testoAggiornato(10_000, 22_000))
        assertEquals("50 s fa", testoAggiornato(10_000, 69_999))
        assertEquals("1 min fa", testoAggiornato(10_000, 70_000))
        assertEquals("3 min fa", testoAggiornato(0, 200_000))
        // orologio spostato indietro: niente tempi negativi
        assertEquals("adesso", testoAggiornato(20_000, 10_000))
    }

    @Test
    fun minutiInteriMaiNegativi() {
        assertEquals(0, minutiAttesa(adesso + 50, adesso))
        assertEquals(1, minutiAttesa(adesso + 61, adesso))
        assertEquals(0, minutiAttesa(adesso - 30, adesso))
    }

    @Test
    fun sciogliLeAbbreviazioniPerIlLettoreDelloSchermo() {
        assertEquals("Piazza Venezia", daLeggere("P.za Venezia"))
        assertEquals("Largo Argentina", daLeggere("L.go Argentina"))
        assertEquals("Stazione Metro Cipro (MA)", daLeggere("Staz.ne Metro Cipro (MA)"))
        assertEquals("Nazionale, Torino", daLeggere("Nazionale/Torino"))
        assertEquals("Piazzale Clodio", daLeggere("P.le Clodio"))
        assertEquals("Capasso", daLeggere("Capasso"))
    }

    @Test
    fun ogniRigaDiventaUnaFraseSola() {
        assertEquals(
            "64 per Stazione S. Pietro. Tra 1 minuto, in tempo reale. Poi tra 9 e 17 minuti.",
            fraseArrivo(arrivo("64", "Stazione S. Pietro", 1, 9, 17), adesso),
        )
        assertEquals(
            "40 per Piazza Pia. Tra 4 minuti, in tempo reale. Poi tra 13 minuti.",
            fraseArrivo(arrivo("40", "P.za Pia", 4, 13), adesso),
        )
        assertEquals(
            "H per Capasso. Tra 9 minuti, secondo l'orario.",
            fraseArrivo(arrivo("H", "Capasso", 9, tracciato = false), adesso),
        )
        assertEquals("70. In arrivo, in tempo reale.", fraseArrivo(arrivo("70", "", 0), adesso))
        // due vetture quasi insieme
        assertEquals(
            "40 per Termini. In arrivo, in tempo reale. Poi un altro in arrivo e uno tra 16 minuti.",
            fraseArrivo(arrivo("40", "Termini", 0, 0, 16), adesso),
        )
    }

    @Test
    fun iPassaggiSuccessiviInBreve() {
        assertEquals("poi 9 · 17 min", testoPoi(listOf(9, 17)))
        assertEquals("poi <1 · 16 min", testoPoi(listOf(0, 16)))
        assertEquals("poi 13 min", testoPoi(listOf(13)))
        assertEquals("", testoPoi(emptyList()))
    }

    @Test
    fun trovaLaPreferitaPiuVicinaEntroIlRaggio() {
        val venezia = Fermata("70078", "70078", "P.za Venezia", 41.8958, 12.4826)
        val plebiscito = Fermata("20153", "20153", "Plebiscito", 41.8966, 12.4810)
        val termini = Fermata("termini", "1", "Termini", 41.9010, 12.5015)
        // davanti al Vittoriano
        val vicina = preferitaVicina(listOf(termini, plebiscito, venezia), 41.8952, 12.4828)
        assertEquals("70078", vicina?.first?.id)
        assertNull(preferitaVicina(listOf(termini), 41.8952, 12.4828))
        assertNull(preferitaVicina(listOf(venezia), null, null))
    }
}
