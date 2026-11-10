package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Base64

/** Feed generati con la libreria ufficiale gtfs-realtime-bindings (Python). */
class FeedTempoRealeTest {
    private val t0 = 1793700000L

    private val corse = Base64.getDecoder().decode(
        "CgsKAzIuMBig4abXBhJ5CgExGnQKDgoFVDcxNEEqAzcxNDABEhwIAxIRCOL//////////wEQ6OKm1wYiBTcwMjQzEhEIBBoGEKTjptcG" +
            "IgU3MDI0NBITCAUSBhDg46bXBiIFNzAyNDUoARIJCAYiBTcwMjQ2GgYKBDQ1MjEo0///////////ARIkCgEyGh8KDAoEVDY0WCADKgI2" +
            "NBIPEgYQhOKm1wYiBTcwMjQzEi0KATMaKAoHCgJUOCoBOBIQEgYQlOWm1wYiBjA3MDI0MzILKglDQVNBTEVUVE8SBwoBNCoCMAE=",
    )

    private val mezzi = Base64.getDecoder().decode(
        "CgUKAzIuMBJGCgJ2MSJACg4KBVQ3MTRBKgM3MTQwABIYDZqZJ0IVAABIQR0AALRCIQAAAAAASpNAKKDhptcGQg4KBDQ1MjESBjQ1MjEg" +
            "QRIZCgJ2MiITCgkKA1Q2NCoCNjRCBgoEMzAwMQ==",
    )

    @Test
    fun leggeLeCorse() {
        val lista = FeedTempoReale.corse(corse)
        assertEquals(3, lista.size)

        val c714 = lista[0]
        assertEquals("T714A", c714.corsaId)
        assertEquals("714", c714.lineaId)
        assertEquals(1, c714.direzione)
        assertFalse(c714.cancellata)
        // la fermata senza orario e' scartata, quella saltata e' segnata
        assertEquals(listOf("70243", "70244", "70245"), c714.fermate.map { it.fermataId })
        assertEquals(t0 + 200, c714.fermate[0].orario)
        assertEquals(t0 + 260, c714.fermate[1].orario)  // solo partenza
        assertTrue(c714.fermate[2].saltata)
        assertEquals("4521", c714.veicolo)
        assertEquals("", lista[2].veicolo)

        assertTrue(lista[1].cancellata)
        assertNull(lista[1].direzione)
        assertEquals("CASALETTO", lista[2].capolinea)
    }

    @Test
    fun leggeIMezzi() {
        val lista = FeedTempoReale.mezzi(mezzi)
        assertEquals(1, lista.size)  // il mezzo senza posizione e' scartato
        val m = lista[0]
        assertEquals("714", m.lineaId)
        assertEquals(0, m.direzione)
        assertEquals(41.9, m.lat, 1e-4)
        assertEquals(12.5, m.lon, 1e-4)
        assertEquals("4521 A", m.etichetta)
        assertEquals(90f, m.rotta)
        assertEquals(t0, m.rilevato)
    }

    @Test
    fun distingueGPSEOrario() {
        val lista = FeedTempoReale.corse(corse)
        // la 714 ha la vettura nel feed; la 8 no, a meno che non compaia tra le vetture rilevate
        val senzaGps = arriviAllaFermata(lista, "70243", orario = null, adesso = t0)
        assertEquals(listOf(true, false), senzaGps.map { it.passaggi.first().tracciato })
        val conGps = arriviAllaFermata(lista, "70243", orario = null, adesso = t0, tracciate = setOf("T8"))
        assertEquals(listOf(true, true), conGps.map { it.passaggi.first().tracciato })
        assertEquals("T714A", conGps[0].passaggi.first().corsaId)
    }

    @Test
    fun uniscePassaggiInTempoRealeEProgrammati() {
        val rt = passaggiTempoReale(FeedTempoReale.corse(corse), "70243", orario = null, adesso = t0)
        fun partenza(corsa: String, linea: String, direzione: Int?, secondi: Long, tempoReale: Boolean = false) =
            PartenzaTransitous(corsa, "it-Lazio-Rome_$linea", linea, direzione, "Capolinea $linea",
                (t0 + secondi) * 1000, (t0 + secondi) * 1000, tempoReale, false, "it-Lazio-Rome_70243")
        val programmati = passaggiProgrammati(
            listOf(
                partenza("20261109_08:00_it-Lazio-Rome_T714A", "714", 1, 205), // stessa corsa della 714 in tempo reale
                partenza("altro-codice", "714", 1, 230),                         // stessa corsa, codice diverso: scartata
                partenza("T714Z", "714", 1, 1500),                                // corsa successiva, solo da orario
                partenza("N1", "n1", null, 900),                                  // linea notturna, solo da orario
                partenza("tardi", "n1", null, 6000),                              // oltre i 90 minuti
            ),
            orario = null, adesso = t0,
            mezzi = listOf(MezzoRT("N1", "n1", null, 41.9, 12.5, "7001")),
        )
        assertEquals(4, programmati.size)
        val uniti = uniscPassaggi(rt, programmati)
        val arrivi = raggruppa(uniti, orario = null)
        assertEquals(listOf("714", "8", "n1", "714"), arrivi.map { it.nomeLinea })
        assertEquals(listOf(t0 + 200), arrivi[0].orari)                     // vince il tempo reale
        assertEquals(listOf(t0 + 1500), arrivi[3].orari)                    // la successiva, con il capolinea di Transitous
        assertEquals("Capolinea 714", arrivi[3].capolinea)
        assertTrue(arrivi[2].passaggi.single().tracciato)                    // la n1 ha la vettura nel feed
        assertFalse(arrivi[3].passaggi.single().tracciato)
    }

    @Test
    fun trovaLeVettureDellaLinea() {
        val mezzi = listOf(
            MezzoRT("c1", "nMA", 0, 41.9, 12.5, "8001"),   // codice con le minuscole
            MezzoRT("c2", "NMA", 1, 41.9, 12.5, "8002"),   // direzione opposta
            MezzoRT("c3", "", null, 41.9, 12.5, "8003"),   // senza linea: la prende dalla corsa
            MezzoRT("", "", null, 41.9, 12.5, "8004"),     // senza corsa: la trova dal numero di vettura
            MezzoRT("c5", "64", 0, 41.9, 12.5, "1234"),    // altra linea
        )
        val corse = listOf(
            CorsaRT("c3", "NMA", 0, false, "", emptyList(), "8003"),
            CorsaRT("c4", "NMA", null, false, "", emptyList(), "8004"),
        )
        val completi = completaMezzi(mezzi, corse)
        assertEquals("NMA", completi[2].lineaId)
        assertEquals("c4", completi[3].corsaId)
        val v = vettureDellaLinea(completi, "NMA", "nMA", 0, orario = null)
        assertEquals(listOf("8001", "8003", "8004"), v.inDirezione.map { it.etichetta })
        assertEquals(listOf("8002"), v.altre.map { it.etichetta })
        assertEquals(4, vettureDellaLinea(completi, "nma", null, null, orario = null).inDirezione.size)
    }

    @Test
    fun nonAbbinaLeVettureAmbigue() {
        val corse = listOf(
            CorsaRT("a", "NMA", 0, false, "", emptyList(), "9000"),
            CorsaRT("b", "64", 0, false, "", emptyList(), "9000"),     // stesso numero, altra azienda
            CorsaRT("c", "NMA", 0, false, "", emptyList(), "7000"),
        )
        val mezzi = listOf(
            MezzoRT("", "", null, 41.9, 12.5, "9000"),    // numero in due corse: ambiguo
            MezzoRT("", "64", 0, 41.9, 12.5, "7000"),     // il feed dice 64, la corsa col suo numero e' NMA
            MezzoRT("x", "", null, 41.9, 12.5, "7000"),   // corsa sconosciuta: niente ripiego sul numero
        )
        val completi = completaMezzi(mezzi, corse)
        assertEquals(mezzi, completi)
        assertTrue(vettureDellaLinea(completi, "NMA", "nMA", 0, orario = null).let { it.inDirezione + it.altre }.isEmpty())
    }

    @Test
    fun misuraLaDistanzaDalTracciato() {
        // via Nazionale, da piazza della Repubblica verso piazza Venezia (circa)
        val forma = listOf(Punto(41.9025, 12.4960), Punto(41.8990, 12.4900), Punto(41.8960, 12.4830))
        assertTrue(distanzaDalTracciato(41.8990, 12.4900, forma) < 1.0)
        assertTrue(distanzaDalTracciato(41.9008, 12.4930, forma) < 15.0)          // sul tratto
        assertTrue(distanzaDalTracciato(41.9100, 12.4900, forma) in 900.0..1300.0) // un chilometro a nord
        assertEquals(Double.POSITIVE_INFINITY, distanzaDalTracciato(41.9, 12.5, emptyList()), 0.0)
    }

    @Test
    fun riconosceICodiciDiTransitous() {
        assertEquals("913_1_5", codiceDaTransitous("20261109_17:05_it-Lazio-Rome_913_1_5"))
        assertEquals("0#913-12", codiceDaTransitous("20261109_17:05_it-Lazio-Rome_0#913-12"))
        assertEquals("913", codiceDaTransitous("it-Lazio-Rome_913"))
        assertNull(codiceDaTransitous("913"))
        // nessun pezzo corto che potrebbe essere la corsa di un'altra linea
        assertEquals(listOf("913_1_5", "20261109_17:05_it-Lazio-Rome_913_1_5"), candidatiCorsa("20261109_17:05_it-Lazio-Rome_913_1_5"))
        // un codice dell'orario con i trattini bassi resta intero: "1_5" sarebbe un'altra corsa
        assertEquals(listOf("913_1_5"), candidatiCorsa("913_1_5"))
        assertNull(corsaDaTransitous("913_1_5"))
        assertEquals(listOf("913", "it-Lazio-Rome_913"), candidatiLinea("it-Lazio-Rome_913"))
        val vettura = MezzoRT("5", "90", 0, 41.9, 12.5, "1")
        assertNull(mezzoDellaCorsa(listOf(vettura), "20261109_17:05_it-Lazio-Rome_913_1_5"))
    }

    @Test
    fun scriveDaQuantoERilevata() {
        assertNull(etaRilevamento(0, t0))
        assertEquals("adesso", etaRilevamento(t0 - 5, t0))
        assertEquals("40 s fa", etaRilevamento(t0 - 42, t0))
        assertEquals("3 min fa", etaRilevamento(t0 - 200, t0))
        assertEquals("adesso", etaRilevamento(t0 + 30, t0))
    }

    @Test
    fun arriviRaggruppatiPerLineaEDirezione() {
        val lista = FeedTempoReale.corse(corse)
        val arrivi = arriviAllaFermata(lista, "70243", orario = null, adesso = t0)
        // la 64 e' cancellata; la 8 ha la fermata scritta con lo zero davanti
        assertEquals(listOf("714", "8"), arrivi.map { it.lineaId })
        assertEquals("Casaletto", arrivi[1].capolinea)
        assertEquals(listOf(t0 + 200), arrivi[0].orari)
    }

    @Test
    fun ignoraIPassaggiOltreLOrizzonte() {
        val lista = FeedTempoReale.corse(corse)
        assertTrue(arriviAllaFermata(lista, "70243", null, adesso = t0 + 5000).isEmpty())
        assertTrue(arriviAllaFermata(lista, "70243", null, adesso = t0 - 3 * 3600).isEmpty())
    }

    @Test
    fun nonUnisceUnaCorsaColPezzoDiUnAltra() {
        // in tempo reale c'e' la corsa "1_5" di un'altra linea; il programmato "913_1_5" deve restare
        val rt = listOf(PassaggioLinea("64", null, 0, "Termini", Passaggio(t0 + 600, "1_5", true)))
        val programmati = listOf(PassaggioLinea("913", null, 0, "Augusto Imperatore", Passaggio(t0 + 300, "913_1_5", false)))
        val uniti = uniscPassaggi(rt, programmati)
        assertEquals(listOf("1_5", "913_1_5"), uniti.map { it.passaggio.corsaId })
    }

    @Test
    fun unaVetturaSenzaCorsaNonSegnaComeTracciateLeAltre() {
        val senzaCodice = listOf(CorsaRT("", "64", 0, false, "", listOf(FermataRT("70243", t0 + 120, false))))
        val arrivi = arriviAllaFermata(senzaCodice, "70243", orario = null, adesso = t0, tracciate = setOf(""))
        assertFalse(arrivi.single().passaggi.single().tracciato)
    }

    @Test
    fun togliIPassaggiGiaAvvenuti() {
        val arrivi = listOf(
            Arrivo("64", "64", 0, "Termini", listOf(Passaggio(t0 - 300, "a", true), Passaggio(t0 + 600, "b", true))),
            Arrivo("40", "40", 0, "Termini", listOf(Passaggio(t0 - 200, "c", true))),
            Arrivo("8", "8", 0, "Casaletto", listOf(Passaggio(t0 - 30, "d", true), Passaggio(t0 + 400, "e", false))),
        )
        val rimasti = ancoraAttesi(arrivi, adesso = t0)
        // la 40 e' passata; della 64 resta la corsa successiva; la 8 e' appena passata (entro un minuto) e resta prima
        assertEquals(listOf("8", "64"), rimasti.map { it.lineaId })
        assertEquals(listOf("d", "e"), rimasti[0].passaggi.map { it.corsaId })
        assertEquals(listOf("b"), rimasti[1].passaggi.map { it.corsaId })
        assertTrue(ancoraAttesi(arrivi, adesso = t0 + 7200).isEmpty())
    }

    @Test
    fun rifiutaIFeedRovinatiSenzaUscireDaiDati() {
        // troncati in ogni punto: o si leggono (meno entita') o danno un errore previsto, mai un indice fuori dai dati
        for (dati in listOf(corse, mezzi)) {
            for (n in 0 until dati.size) {
                val pezzo = dati.copyOf(n)
                try {
                    FeedTempoReale.corse(pezzo)
                    FeedTempoReale.mezzi(pezzo)
                } catch (_: IllegalStateException) {
                    // previsto
                }
            }
        }
        // una lunghezza dichiarata enorme (2^31 - 1 e 2^35) non deve far saltare oltre la fine
        val enorme = byteArrayOf(0x12, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 1, 2, 3)
        val oltreInt = byteArrayOf(0x12, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01, 1, 2)
        val daSaltare = byteArrayOf(0x0A, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 1, 2, 3)
        for (dati in listOf(enorme, oltreInt, daSaltare)) {
            var errore: Exception? = null
            try {
                FeedTempoReale.corse(dati)
            } catch (e: Exception) {
                errore = e
            }
            assertTrue("atteso un errore previsto, non $errore", errore is IllegalStateException)
        }
    }

    @Test
    fun ilFeedInMemoriaNonRiscaricaSeNonServe(): Unit = runBlocking {
        var ora = 1_000_000L
        var richieste = 0
        var validatori: Pair<String?, String?>? = null
        var risposta: () -> RispostaFeed? = { RispostaFeed(byteArrayOf(1), "\"v1\"", "lun") }
        var decodifiche = 0
        val feed = FeedInMemoria(
            validitaMs = 20_000,
            orologio = { ora },
            scarica = { etag, modificato ->
                richieste++
                validatori = etag to modificato
                risposta()
            },
            decodifica = { dati ->
                decodifiche++
                dati.map { it.toInt() }
            },
        )
        assertEquals(listOf(1), feed.ottieni())
        assertEquals(null to null, validatori)
        ora += 5_000
        assertEquals(listOf(1), feed.ottieni())          // ancora valido: nessuna richiesta
        assertEquals(1, richieste)
        assertEquals(listOf(1), feed.ottieni(forza = true))
        assertEquals(2, richieste)
        assertEquals("\"v1\"" to "lun", validatori)        // chiede "e' cambiato?" con i validatori ricevuti

        // 304: restano i dati, senza decodificare di nuovo
        ora += 30_000
        risposta = { null }
        assertEquals(listOf(1), feed.ottieni())
        assertEquals(3, richieste)
        assertEquals(2, decodifiche)

        // un errore passeggero: valgono gli ultimi dati per due minuti, poi l'errore arriva a chi chiama
        ora += 30_000
        risposta = { throw java.io.IOException("senza rete") }
        assertEquals(listOf(1), feed.ottieni())
        ora += 200_000
        var errore: Exception? = null
        try {
            feed.ottieni()
        } catch (e: java.io.IOException) {
            errore = e
        }
        assertEquals("senza rete", errore?.message)

        // tornata la rete, i dati nuovi
        risposta = { RispostaFeed(byteArrayOf(7, 8), null, null) }
        assertEquals(listOf(7, 8), feed.ottieni())
    }

    @Test
    fun ilFeedInMemoriaSenzaDatiDaSubitoLErrore(): Unit = runBlocking {
        val feed = FeedInMemoria<Int>(
            validitaMs = 20_000,
            scarica = { _, _ -> throw java.io.IOException("senza rete") },
            decodifica = { emptyList() },
        )
        var errore: Exception? = null
        try {
            feed.ottieni()
        } catch (e: java.io.IOException) {
            errore = e
        }
        assertEquals("senza rete", errore?.message)
    }
}
