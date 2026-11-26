package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'ultima corsa: quando si controlla, come si contano le corse rimaste. */
class UltimaCorsaTest {
    private fun alle(ore: Int, minuti: Int, giorniDopo: Int = 0) = istanteARoma(2026, 11, 26, ore, minuti, giorniDopo)

    @Test
    fun quandoSiControlla() {
        assertFalse(daControllareUltima(alle(14, 0)))
        assertTrue(daControllareUltima(alle(18, 0)))
        assertTrue(daControllareUltima(alle(23, 50)))
        assertTrue(daControllareUltima(alle(1, 30)))
        assertFalse(daControllareUltima(alle(4, 0)))
        // la giornata di servizio finisce alle 5 del mattino dopo
        assertEquals(alle(5, 0, 1), fineServizio(alle(21, 0)))
        assertEquals(alle(5, 0), fineServizio(alle(1, 0)))
    }

    @Test
    fun corseRimaste() {
        assertEquals(UltimaCorsa.ULTIMA, ultimaDaRimaste(0))
        assertEquals(UltimaCorsa.PENULTIMA, ultimaDaRimaste(1))
        assertNull(ultimaDaRimaste(2))
        assertEquals("ultima corsa", testoUltimaCorsa(UltimaCorsa.ULTIMA))
        assertEquals("penultima corsa", testoUltimaCorsa(UltimaCorsa.PENULTIMA))
    }

    private fun passaggio(secondi: Long, corsa: String) = Passaggio(secondi, corsa, false)

    @Test
    fun allaFermata() {
        val t = alle(23, 0) / 1000
        val rimaste = CorseRimaste(
            0L,
            listOf(
                Arrivo("64", "64", 0, "Stazione S. Pietro", listOf(passaggio(t, "a"), passaggio(t + 1200, "b"))),
                Arrivo("40", "40", 1, "Termini", listOf(passaggio(t + 600, "c"), passaggio(t + 1800, "d"), passaggio(t + 3000, "e"))),
            ),
        )
        // il 64 delle 23:00 (in tempo reale con 2 minuti di ritardo): dopo c'e' ancora una corsa
        val il64 = Arrivo("64", "64", 0, "Stazione S. Pietro", listOf(Passaggio(t + 120, "a", true)))
        assertEquals(UltimaCorsa.PENULTIMA, rimaste.per(il64))
        val ultimo64 = Arrivo("64", "64", 0, "Stazione S. Pietro", listOf(passaggio(t + 1200, "b")))
        assertEquals(UltimaCorsa.ULTIMA, rimaste.per(ultimo64))
        // senza lo stesso codice vale l'orario (a meno di 5 minuti)
        assertEquals(UltimaCorsa.ULTIMA, rimaste.per(Arrivo("64", "64", 0, "Stazione S. Pietro", listOf(passaggio(t + 1260, "x")))))
        // il 40 ne ha ancora due dopo
        assertNull(rimaste.per(Arrivo("40", "40", 1, "Termini", listOf(passaggio(t + 600, "c")))))
        // una linea che non c'e' fra le rimaste: non si sa
        assertNull(rimaste.per(Arrivo("8", "8", 0, "Casaletto", listOf(passaggio(t, "z")))))
        val scritte = ultimeDelleRighe(rimaste, listOf(il64))
        assertEquals("penultima corsa", scritte[chiaveArrivo(il64)])
    }

    private fun tappa(nome: String, fermata: String?) = Tappa(nome, 41.9, 12.5, fermata, null, null, null, null)

    private fun tratto(linea: String, partenza: Long, direzione: String, routeId: String = "", direzioneId: Int? = null, corsa: String = "t1") = Tratto(
        modo = "BUS", partenza = partenza, arrivo = partenza + 600_000, partenzaPrevista = partenza, arrivoPrevisto = partenza + 600_000,
        tempoReale = false, durata = 600, metri = null, da = tappa("P.za Venezia", "it-Lazio-Rome_70078"), a = tappa("Termini", null),
        linea = linea, direzione = direzione, colore = null, gestore = "", intermedie = emptyList(), forma = emptyList(), passi = emptyList(),
        cancellato = false, avvisi = emptyList(), corsaId = corsa, lineaId = routeId, direzioneId = direzioneId,
    )

    private fun partenza(linea: String, orario: Long, capolinea: String, routeId: String = "", direzione: Int? = null, corsa: String) =
        PartenzaTransitous(corsa, routeId, linea, direzione, capolinea, orario, orario, false, false, "it-Lazio-Rome_70078")

    @Test
    fun neiTragitti() {
        val t0 = alle(23, 10)
        val il64 = tratto("64", t0, "Stazione S. Pietro")
        val partenze = listOf(
            partenza("64", t0 + 20 * 60_000, "STAZIONE S.PIETRO", corsa = "t2"),
            partenza("64", t0 + 15 * 60_000, "Termini", corsa = "t9"),
            partenza("40", t0 + 5 * 60_000, "Stazione S. Pietro", corsa = "t3"),
        )
        // una sola corsa dopo, nella stessa direzione: penultima
        assertEquals(1, rimasteDopo(il64, partenze))
        assertEquals(UltimaCorsa.PENULTIMA, ultimaDaRimaste(rimasteDopo(il64, partenze)))
        // con routeId e directionId vale quello, non il nome del capolinea
        val conCodici = tratto("64", t0, "S. Pietro", routeId = "r64", direzioneId = 0)
        val altre = listOf(
            partenza("64", t0 + 20 * 60_000, "Stazione S. Pietro (limitata)", routeId = "r64", direzione = 0, corsa = "t2"),
            partenza("64", t0 + 25 * 60_000, "Termini", routeId = "r64", direzione = 1, corsa = "t5"),
        )
        assertEquals(1, rimasteDopo(conCodici, altre))
        assertEquals(0, rimasteDopo(conCodici, emptyList()))
        assertEquals("64: ultima corsa", testoUltimaDelTratto(il64, UltimaCorsa.ULTIMA))
    }

    @Test
    fun carteConLUltimaCorsa() {
        val t0 = alle(23, 10)
        val tr = Tragitto(t0, t0 + 1_800_000, 1800, 0, listOf(tratto("64", t0, "Stazione S. Pietro")))
        val carta = cartaTragitto(tr, t0 - 60_000, mapOf(tr.chiave to "64: ultima corsa"))
        assertEquals("64: ultima corsa", carta.ultimaCorsa)
        assertTrue(carta.perVoce.endsWith("64: ultima corsa."))
        assertNull(cartaTragitto(tr, t0 - 60_000).ultimaCorsa)
        // nel tragitto salvato: l'etichetta di "esci tra" avvisa
        val lavoro = Luogo("Lavoro", "Piazza Venezia", 41.8958, 12.4826, TipoLuogo.LUOGO)
        val casa = Luogo("Casa", "Via Merulana 10", 41.894, 12.503, TipoLuogo.INDIRIZZO)
        val salvato = TragittoSalvato("s1", "Lavoro → Casa", lavoro, casa)
        val partenze = partenzeDaTragitti("s1", listOf(tr), t0 - 10 * 60_000, mapOf(tr.chiave to listOf(tr.tratti[0] to UltimaCorsa.ULTIMA)))
        assertEquals("64: ultima corsa", partenze[0].ultima)
        assertTrue(partenze[0].ultimissima)
        val c = cartaTragittoSalvato(salvato, PartenzeTragitto("s1", t0, partenze, null), t0 - 10 * 60_000)
        assertEquals("ULTIMA CORSA: ESCI TRA", c.esci)
        assertEquals("64: ultima corsa", c.ultima)
        // si salva e si rilegge
        val riletto = CodificaPartenze.daJson(CodificaPartenze.inJson(PartenzeTragitto("s1", t0, partenze, null)))!!
        assertTrue(riletto.partenze[0].ultimissima)
    }
}
