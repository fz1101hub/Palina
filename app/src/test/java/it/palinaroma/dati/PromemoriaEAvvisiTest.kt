package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Promemoria ricorrenti, avvisi sulle tue linee e scioperi. */
class PromemoriaEAvvisiTest {
    // giovedi' 26 novembre 2026
    private fun alle(ore: Int, minuti: Int, giorniDopo: Int = 0) = istanteARoma(2026, 11, 26, ore, minuti, giorniDopo)

    private val il64 = promemoriaPerLinea("70078", "64", "Stazione S. Pietro", "64", "cinque", 8 * 60 + 10)

    @Test
    fun quandoScatta() {
        // giovedi' alle 7: oggi alle 8:10
        assertEquals(alle(8, 10), prossimeVolte(il64, alle(7, 0)).first())
        // giovedi' alle 9: venerdi' alle 8:10, poi lunedi'
        assertEquals(listOf(alle(8, 10, 1), alle(8, 10, 4)), prossimeVolte(il64, alle(9, 0), 2))
        // solo la domenica
        val domenica = il64.copy(giorni = setOf(7))
        assertEquals(alle(8, 10, 3), prossimoPromemoria(listOf(domenica), alle(9, 0)))
        // spento: mai
        assertTrue(prossimeVolte(il64.copy(attivo = false), alle(7, 0)).isEmpty())
        // adesso (anche con qualche minuto di ritardo della sveglia)
        assertEquals(listOf(il64), promemoriaDaMostrare(listOf(il64), alle(8, 12)))
        assertTrue(promemoriaDaMostrare(listOf(il64), alle(8, 40)).isEmpty())
        assertTrue(promemoriaDaMostrare(listOf(domenica), alle(8, 10)).isEmpty())
    }

    @Test
    fun testiEJson() {
        assertEquals("nei giorni feriali alle 08:10", testoQuandoPromemoria(il64))
        assertEquals("tutti i giorni", testoGiorni(Promemoria.TUTTI))
        assertEquals("sabato e domenica", testoGiorni(setOf(6, 7)))
        assertEquals("lun, mer, ven", testoGiorni(setOf(5, 1, 3)))
        assertEquals("Lavoro · 64 → Stazione S. Pietro", titoloPromemoria(il64, "Lavoro", emptyList()))
        val elenco = listOf(il64, promemoriaPerTragitto("t1", 18 * 60).copy(id = "p2"))
        assertEquals(elenco, CodificaPromemoria.leggi(CodificaPromemoria.scrivi(elenco)))
        // il promemoria di un tragitto che non c'e' piu' non vale
        assertEquals(listOf(il64), promemoriaValidi(elenco, emptyList()))
        assertEquals(listOf("70078", "64", "Stazione S. Pietro", "cinque"), leggiRichiestaAvvisami("avvisami:70078|64|Stazione S. Pietro|cinque"))
        assertNull(leggiRichiestaAvvisami("fermata:70078"))
        assertEquals(setOf(1, 3), promemoriaModificato(il64, listOf(3, 1, 9), 600, "due", true).giorni)
        assertEquals(485, minutiProposti(alle(8, 7)))
    }

    private fun avviso(id: String, linee: Set<String>, fermate: Set<String> = emptySet(), titolo: String = "Deviazione", testo: String = "", causa: Int = 0, inizio: Long = 0L) =
        AvvisoServizio(id, if (inizio > 0) listOf(inizio to 0L) else emptyList(), linee, fermate, false, 4, mapOf("" to titolo), mapOf("" to testo), null, causa)

    @Test
    fun avvisiSulleTueLinee() {
        val adesso = alle(12, 0) / 1000
        val tue = LeTueLinee(setOf("64"), setOf("70078"))
        val avvisi = listOf(avviso("a1", setOf("64")), avviso("a2", setOf("40")), avviso("a3", emptySet(), setOf("70078")))
        // la prima volta si ricordano tutti, senza avvisare
        val prima = nuoviAvvisiPerTe(avvisi, tue, mapOf("64" to "64"), null, adesso)
        assertTrue(prima.notifiche.isEmpty())
        assertEquals(setOf("a1", "a2", "a3"), prima.visti)
        // poi solo i nuovi, sulle tue linee o fermate
        val nuovi = avvisi + avviso("a4", setOf("64"), titolo = "Lavori in via Nazionale") + avviso("a5", setOf("8"))
        val dopo = nuoviAvvisiPerTe(nuovi, tue, mapOf("64" to "64"), prima.visti, adesso)
        assertEquals(1, dopo.notifiche.size)
        assertEquals("64 · Deviazione", dopo.notifiche[0].titolo)
        assertEquals("linea:64", dopo.notifiche[0].apri)
        assertTrue("a4" in dopo.visti)
        // gli avvisi tolti dal feed si dimenticano
        assertFalse("a2" in nuoviAvvisiPerTe(listOf(avvisi[0]), tue, emptyMap(), dopo.visti, adesso).visti)
    }

    @Test
    fun scioperi() {
        val adesso = alle(18, 30) / 1000
        val testo = "Venerdì 27 novembre sciopero di 24 ore. Il servizio è garantito dall'inizio alle 8.30 e dalle 17 alle 20. Possibili ripercussioni."
        val sciopero = avviso("s1", emptySet(), titolo = "Sciopero di 24 ore", testo = testo, inizio = alle(0, 0, 1) / 1000)
        val trovati = scioperi(listOf(sciopero, avviso("a1", setOf("64"))), adesso)
        assertEquals(1, trovati.size)
        assertTrue(trovati[0].domani)
        assertEquals("venerdì 27 novembre", trovati[0].giorno)
        assertEquals("Il servizio è garantito dall'inizio alle 8.30 e dalle 17 alle 20", trovati[0].fasce)
        assertEquals("Sciopero domani", titoloSciopero(trovati[0]))
        // la sera prima, una volta sola
        val n = notificaSciopero(trovati, emptySet(), adesso)!!
        assertEquals("Sciopero domani", n.titolo)
        assertNull(notificaSciopero(trovati, setOf(n.id), adesso))
        assertNull(notificaSciopero(trovati, emptySet(), alle(12, 0) / 1000))
        // anche dalla causa del feed (4 = sciopero), senza la parola nel titolo
        assertTrue(eUnoSciopero(avviso("s2", emptySet(), titolo = "Agitazione sindacale", causa = 4)))
        // tra una settimana: non ancora
        assertTrue(scioperi(listOf(sciopero.copy(periodi = listOf(alle(0, 0, 7) / 1000 to 0L))), adesso).isEmpty())
    }
}
