package it.palinaroma.orologio

import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.tabelloneDiEsempio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun laComplicazioneHaLaLineaComeTitolo() {
        val c = testoComplicazione("SG335D", adesso + 300, "Frascati", null)
        assertNotNull(c)
    }

    @Test
    fun ilTabelloneVecchioSiChiedeDiNuovo() {
        val t = tabelloneDiEsempio(adesso)
        assertEquals(false, ArchivioOrologio.vecchio(t, adesso * 1000 + 30_000))
        assertEquals(true, ArchivioOrologio.vecchio(t, adesso * 1000 + 61_000))
        assertEquals(true, ArchivioOrologio.vecchio(null))
    }
}
