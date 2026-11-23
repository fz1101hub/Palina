package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RetiTest {

    @Test
    fun laReteSiRiconosceDalPrefisso() {
        assertEquals(Rete.ROMA, Rete.di("70078"))
        assertEquals(Rete.COTRAL, Rete.di("C:12345"))
        assertEquals(Rete.COTRAL_TRENI, Rete.di("F:PSP"))
        assertEquals(Rete.TRENITALIA, Rete.di("T:S08409"))
        assertEquals("12345", idOriginale("C:12345"))
        assertEquals("70078", idOriginale("70078"))
        assertTrue(Rete.TRENITALIA.treni)
        assertFalse(Rete.COTRAL.treni)
    }

    @Test
    fun leFermateComeLeChiamaTransitous() {
        assertEquals("it-Lazio-Rome_70078", fermataTransitous("70078"))
        assertEquals("it-Lazio-Cotral_12345", fermataTransitous("C:12345"))
        assertEquals("it-Lazio-Cotral-Ferrovie_PSP", fermataTransitous("F:PSP"))
        assertEquals("it-trenitalia_S08409", fermataTransitous("T:S08409"))
        assertEquals(Rete.COTRAL_TRENI, Rete.daTransitous("it-Lazio-Cotral-Ferrovie_RL"))
        assertEquals(Rete.COTRAL, Rete.daTransitous("it-Lazio-Cotral_45"))
        assertNull(Rete.daTransitous("de-DELFI_45"))
        assertTrue(urlPartenze("C:12345", 41.84, 12.58).contains("stopId=it-Lazio-Cotral_12345"))
    }

    @Test
    fun leLineeDelleAltreRetiNonDiventanoMaiQuelleDiRoma() {
        // il 45 di Cotral non e' il 45 di Roma
        assertEquals(listOf("C:45"), candidatiLinea("it-Lazio-Cotral_45"))
        assertEquals(listOf("T:R"), candidatiLinea("it-trenitalia_R"))
        assertEquals(listOf("45", "it-Lazio-Rome_45"), candidatiLinea("it-Lazio-Rome_45"))
        assertEquals(listOf("de-DELFI_45"), candidatiLinea("de-DELFI_45"))
        // ne' le corse
        assertNull(corsaDaTransitous("20261123_08:05_it-Lazio-Cotral_913_1_5"))
        assertEquals("913_1_5", corsaDaTransitous("20261123_08:05_it-Lazio-Rome_913_1_5"))
        assertEquals(listOf("20261123_08:05_it-Lazio-Cotral_913"), candidatiCorsa("20261123_08:05_it-Lazio-Cotral_913"))
    }

    @Test
    fun nomiDelleLineeFerroviarieCotral() {
        assertEquals("Metromare", nomeLineaTreniCotral("ROMALIDO"))
        assertEquals("Roma Nord", nomeLineaTreniCotral("rvurb"))
        assertEquals("Roma–Viterbo", nomeLineaTreniCotral("RVEXT"))
        assertEquals("ALTRA", nomeLineaTreniCotral(" ALTRA "))
        val linee = listOf(
            LineaFermata(Linea("F:L1", "Metromare", "ROMALIDO", 2, "", ""), 0, "Cristoforo Colombo"),
            LineaFermata(Linea("64", "64", "", 3, "", ""), 0, "Termini"),
        )
        assertEquals(mapOf("ROMALIDO" to "F:L1"), lineeGtfsTreniCotral(linee))
    }

    @Test
    fun leggeLeRisposteDiAstral() {
        val stazioni = leggiStazioni(
            """{"0":{"nomeFermata":"Porta San Paolo","codice":"PSP","ordine":"1"},
               "1":{"nomeFermata":"Basilica San Paolo","codice":"BSP","ordine":2},
               "2":{"codice":"X"}}""",
        )
        assertEquals(listOf("PSP", "BSP"), stazioni.map { it.codice })
        val transiti = leggiTransiti(
            """[{"corsa":"101","orario":"8:05","ritardo":"3","soppressa":"N","busSostitutivo":""},
                {"corsa":"103","orario":"8:20","ritardo":"","soppressa":"Y"},
                {"corsa":"105","orario":"ieri","ritardo":"0"}]""",
        )
        assertEquals(2, transiti.size)
        assertEquals(3, transiti[0].ritardo)
        assertNull(transiti[1].ritardo)
        assertTrue(transiti[1].soppresso)
    }

    @Test
    fun trovaLaStazioneDiAstralDalNome() {
        val stazioni = listOf(
            StazioneAstral("Acilia Sud", "ACS", 5),
            StazioneAstral("Acilia", "ACI", 4),
            StazioneAstral("Lido Centro", "LDC", 9),
        )
        assertEquals("ACI", codiceStazione(stazioni, "ACILIA"))
        assertEquals("ACS", codiceStazione(stazioni, "Acilia Sud"))
        assertEquals("LDC", codiceStazione(stazioni, "Lido Centro (Ostia)"))
        assertNull(codiceStazione(stazioni, "Flaminio"))
        assertNull(codiceStazione(stazioni, ""))
    }

    @Test
    fun iTreniDiAstralDiventanoPassaggiConIlRitardo() {
        val roma = ZoneId.of("Europe/Rome")
        val adesso = ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, roma).toInstant().toEpochMilli()
        val percorso = Astral.PERCORSI.first { it.codice == "RL_PSP-CC" }
        val voci = listOf(
            TransitoAstral("101", "8:05", 3, soppresso = false, busSostitutivo = false),
            TransitoAstral("103", "8:20", null, soppresso = true, busSostitutivo = false),
            TransitoAstral("099", "7:00", 0, soppresso = false, busSostitutivo = false), // gia' partito
            TransitoAstral("201", "11:00", null, soppresso = false, busSostitutivo = false), // oltre 90 minuti
        )
        val passaggi = passaggiDaTransiti(voci, percorso, "F:L1", adesso, 90)
        assertEquals(listOf("astral:RL_PSP-CC:101", "astral:RL_PSP-CC:103"), passaggi.map { it.passaggio.corsaId })
        val primo = passaggi[0]
        assertEquals("F:L1", primo.lineaId)
        assertEquals("Cristoforo Colombo", primo.capolinea)
        // 8:05 con 3 minuti di ritardo: tra 8 minuti, in tempo reale
        assertEquals(8, minutiAttesa(primo.passaggio.orario, adesso / 1000))
        assertTrue(primo.passaggio.tracciato)
        assertFalse(passaggi[1].passaggio.tracciato)
        assertEquals("soppresso", passaggi[1].passaggio.avviso)
    }
}
