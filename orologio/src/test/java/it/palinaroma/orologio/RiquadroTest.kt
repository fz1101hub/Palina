package it.palinaroma.orologio

import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.tabelloneDiEsempio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il riquadro e la complicazione si costruiscono in tutti i casi senza errori. */
class RiquadroTest {
    private val adesso = 1_795_500_000L

    @Test
    fun ilRiquadroMostraLaPrimaFermataConPassaggi() {
        val t = tabelloneDiEsempio(adesso)
        assertEquals("Basilica San Paolo", fermataDelRiquadro(t, adesso)?.nome)
        // passata un'ora: nessun passaggio, resta la prima fermata
        assertEquals("Basilica San Paolo", fermataDelRiquadro(t, adesso + 3600)?.nome)
        assertNull(fermataDelRiquadro(null, adesso))
        assertNotNull(riquadro(t, adesso, "it.palinaroma").tileTimeline)
        assertNotNull(riquadro(t, adesso + 3600, "it.palinaroma"))
        assertNotNull(riquadro(null, adesso, "it.palinaroma"))
        assertNotNull(riquadro(TabelloneOrologio(adesso * 1000, emptyList(), "Aggiungi una fermata"), adesso, "it.palinaroma"))
    }

    @Test
    fun ilTabelloneVecchioSiChiedeDiNuovo() {
        val t = tabelloneDiEsempio(adesso)
        assertEquals(false, ArchivioOrologio.vecchio(t, adesso * 1000 + 30_000))
        assertEquals(true, ArchivioOrologio.vecchio(t, adesso * 1000 + 61_000))
        assertEquals(true, ArchivioOrologio.vecchio(null))
    }

    @Test
    fun ilWidgetComeIlRiquadro() {
        val t = tabelloneDiEsempio(adesso)
        val grande = contenutoWidget(t, adesso, piccolo = false)
        assertEquals("Basilica San Paolo", grande.titolo)
        assertEquals(3, grande.righe.size)
        assertEquals("MEB", grande.righe[0].linea)
        assertEquals("2' · 9'", grande.righe[0].passaggi)
        assertTrue(grande.righe[1].tempoReale)
        assertTrue(grande.nota!!.startsWith("tempi delle "))
        val piccolo = contenutoWidget(t, adesso, piccolo = true)
        assertEquals(1, piccolo.righe.size)
        assertNull(piccolo.nota)
        assertEquals("Palina", contenutoWidget(null, adesso, piccolo = true).titolo)
        assertEquals("Nessun passaggio a breve", contenutoWidget(t, adesso + 7200, piccolo = false).nota)
    }

    /** Il tabellone [t] ricalcolato dal telefono [secondi] dopo, con la riga del 64 cambiata. */
    private fun conIl64(t: TabelloneOrologio, secondi: Long, vararg orari: Long): TabelloneOrologio =
        t.copy(
            creato = t.creato + secondi * 1000,
            fermate = t.fermate.map { f ->
                f.copy(righe = f.righe.map { r -> if (r.lineaId == "64") r.copy(orari = orari.toList()) else r })
            },
        )

    @Test
    fun seguireUnaLinea() {
        val t = tabelloneDiEsempio(adesso)
        val venezia = t.fermate[1]
        val otto: RigaOrologio = venezia.righe.first { it.linea == "8" }
        val s = seguimento(venezia, otto, t, adesso)!!
        assertEquals(adesso + 480, s.obiettivo)
        assertEquals("8 → Casaletto", titoloSegui(s))
        assertTrue(testoSegui(s, adesso).startsWith("Piazza Venezia · tra 8' ("))
        assertTrue(testoSegui(s, adesso).endsWith("da orario"))
        assertEquals("70078", s.lineaSeguita?.fermataId)
        // senza tabelloni nuovi resta com'e'; un minuto dopo il passaggio e' finito
        assertEquals(s, ricalcola(s, t, adesso + 60))
        assertNull(ricalcola(s, t, adesso + 480 + 61))
        // dopo due ore si smette comunque
        assertNull(ricalcola(s.copy(obiettivo = adesso + 9000), t, adesso + 7300))
    }

    @Test
    fun ilTempoRealeSpostaIlPassaggioSeguito() {
        val t = tabelloneDiEsempio(adesso)
        val venezia = t.fermate[1]
        val s = seguimento(venezia, venezia.righe.first { it.linea == "64" }, t, adesso)!!.copy(obiettivo = adesso + 300)
        // il bus seguito ora e' atteso a +7 minuti: si segue quello (il piu' vicino), non il successivo
        val ritardo = ricalcola(s, conIl64(t, 30, adesso + 420, adesso + 1500), adesso + 30)!!
        assertEquals(adesso + 420, ritardo.obiettivo)
        assertTrue(ritardo.tempoReale)
        // arrivato (a meno di mezzo minuto): non si passa al bus dopo
        val arrivato = s.copy(obiettivo = adesso + 50, aggiornato = t.creato)
        assertEquals(adesso + 50, ricalcola(arrivato, conIl64(t, 40, adesso + 900), adesso + 40)!!.obiettivo)
        // se la riga sparisce si continua con l'ultimo tempo
        val senza = t.copy(creato = t.creato + 60_000, fermate = emptyList())
        assertEquals(adesso + 300, ricalcola(s, senza, adesso + 60)!!.obiettivo)
        // un forte ritardo dopo la vibrazione: si vibra di nuovo
        val avvisato = s.copy(obiettivo = adesso + 100, avvisato = true)
        assertFalse(ricalcola(avvisato, conIl64(t, 30, adesso + 600), adesso + 30)!!.avvisato)
    }
}
