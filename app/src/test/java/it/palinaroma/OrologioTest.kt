package it.palinaroma

import it.palinaroma.dati.Arrivo
import it.palinaroma.dati.Passaggio
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.righeOrologio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le righe che il telefono manda all'orologio. */
class OrologioTest {
    private val adesso = 1_795_500_000L

    private fun arrivo(linea: String, nome: String, vararg minuti: Int, tracciato: Boolean = false, avviso: String? = null) =
        Arrivo(linea, nome, 0, "Capolinea $nome", minuti.map { Passaggio(adesso + it * 60L, "c$linea$it", tracciato, avviso) })

    @Test
    fun primaLeFissatePoiLeAltreSoloQuelleConPassaggi() {
        val arrivi = listOf(
            arrivo("64", "64", 2, 10, 18, 30, tracciato = true),
            arrivo("F:ROMALIDO", "Metromare", 5, avviso = "soppresso"),
            arrivo("40", "40", -5),  // gia' passato: niente
            arrivo("H", "H", 7),
        )
        val righe = righeOrologio(arrivi, fissate = setOf("H"), adesso = adesso)
        assertEquals(listOf("H", "64", "Metromare"), righe.map { it.linea })
        val sessantaquattro = righe[1]
        assertEquals(3, sessantaquattro.orari.size)  // tre passaggi al massimo
        assertTrue(sessantaquattro.tempoReale)
        assertEquals("soppresso", righe[2].avviso)
        assertNull(righe[0].avviso)
        // il colore della rete, come sul telefono: Cotral blu, Roma porpora
        assertEquals(0xFF1D4F91.toInt(), righe[2].colore)
        assertEquals(0xFF862633.toInt(), righe[1].colore)
        // e passa per il formato del ponte senza perdere niente
        val tabellone = TabelloneOrologio(adesso * 1000, emptyList())
        assertEquals(tabellone, CodificaOrologio.leggi(CodificaOrologio.scrivi(tabellone)))
    }

    @Test
    fun alMassimoSeiRighe() {
        val arrivi = (1..9).map { arrivo("L$it", "L$it", it) }
        assertEquals(6, righeOrologio(arrivi, emptySet(), adesso).size)
    }

    @Test
    fun treniConLaRichiestaDelleFermate() {
        val treno = Arrivo(
            "F:ROMALIDO", "Metromare", 0, "Cristoforo Colombo",
            listOf(
                Passaggio(adesso + 300, "astral:77", true, ritardo = 120),
                Passaggio(adesso + 900, "F:corsa2", false, idTransitous = "it-Lazio-Cotral-Ferrovie_corsa2"),
            ),
        )
        val bus = arrivo("64", "64", 2)
        val righe = righeOrologio(listOf(treno, bus), emptySet(), adesso) { "F:RL_SANPAOLO" }
        val metromare = righe.first { it.linea == "Metromare" }
        assertEquals("F:ROMALIDO", metromare.lineaId)
        assertEquals(2, metromare.treni.size)
        // ASTRAL: l'orario programmato e' quello aggiornato meno il ritardo
        val primo = metromare.treni[0]
        assertEquals(adesso + 180, primo.previsto)
        assertEquals("astral:77", primo.corsaAstral)
        assertEquals("F:RL_SANPAOLO", primo.fermataId)
        assertNull(primo.corsaId)
        assertEquals("it-Lazio-Cotral-Ferrovie_corsa2", metromare.treni[1].corsaId)
        assertNull(metromare.treni[1].corsaAstral)
        // i bus non hanno treni da aprire
        assertTrue(righe.first { it.linea == "64" }.treni.isEmpty())
        assertEquals("64", righe.first { it.linea == "64" }.lineaId)
    }
}
