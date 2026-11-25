package it.palinaroma.dati

import it.palinaroma.ponte.CodificaWidget
import it.palinaroma.ponte.DatiWidget
import it.palinaroma.ponte.Discesa
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.QuandoAvvisare
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.Seguimento
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.finito
import it.palinaroma.ponte.minutiAPiedi
import it.palinaroma.ponte.propostaAvviso
import it.palinaroma.ponte.ricalcola
import it.palinaroma.ponte.scorciatoie
import it.palinaroma.ponte.seguimentoDaJson
import it.palinaroma.ponte.statoSegui
import it.palinaroma.ponte.testoJson
import it.palinaroma.ponte.vociWidget
import it.palinaroma.ponte.widgetTabellone
import it.palinaroma.ponte.widgetTragitto
import it.palinaroma.ponte.widgetVicina
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Un piccolo scrittore protobuf, per costruire feed di prova. */
private class Proto {
    val dati = ByteArrayOutputStream()

    fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                dati.write(x.toInt())
                return
            }
            dati.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun tag(campo: Int, tipo: Int) = varint(((campo shl 3) or tipo).toLong())

    fun numero(campo: Int, v: Long) {
        tag(campo, 0)
        varint(v)
    }

    fun testo(campo: Int, s: String) {
        val b = s.encodeToByteArray()
        tag(campo, 2)
        varint(b.size.toLong())
        dati.write(b)
    }

    fun messaggio(campo: Int, m: Proto.() -> Unit) {
        val b = Proto().apply(m).dati.toByteArray()
        tag(campo, 2)
        varint(b.size.toLong())
        dati.write(b)
    }
}

private fun proto(f: Proto.() -> Unit): ByteArray = Proto().apply(f).dati.toByteArray()

class AvvisamiETragittiTest {
    private val t0 = 1793700000L

    @After
    fun italiano() {
        Lingue.imposta("it", "")
    }

    // --- avvisi di Roma Mobilita'

    private val feedAvvisi = proto {
        messaggio(1) { testo(1, "2.0") }
        messaggio(2) {
            testo(1, "a1")
            messaggio(5) {
                messaggio(1) {
                    numero(1, t0 - 3600)
                    numero(2, t0 + 86400)
                }
                messaggio(5) { testo(2, "64") }
                messaggio(5) { testo(5, "70078") }
                numero(7, 4)
                messaggio(10) {
                    messaggio(1) {
                        testo(1, "Linea 64 deviata")
                        testo(2, "it")
                    }
                    messaggio(1) {
                        testo(1, "Line 64 detour")
                        testo(2, "en")
                    }
                }
                messaggio(11) { messaggio(1) { testo(1, "Per lavori in via Nazionale.<br/>Fermate &amp; capolinea spostati.") } }
            }
        }
        messaggio(2) {
            testo(1, "a2")
            messaggio(5) {
                messaggio(1) { numero(1, t0 + 3 * 86400) }
                messaggio(5) { testo(2, "40") }
                messaggio(10) { messaggio(1) { testo(1, "Sciopero") } }
            }
        }
        // finito: non si mostra
        messaggio(2) {
            testo(1, "a3")
            messaggio(5) {
                messaggio(1) { numero(2, t0 - 10) }
                messaggio(5) { testo(2, "64") }
                messaggio(10) { messaggio(1) { testo(1, "Vecchio") } }
            }
        }
    }

    @Test
    fun leggeGliAvvisi() {
        val avvisi = FeedAvvisi.leggi(feedAvvisi)
        assertEquals(3, avvisi.size)
        val a = avvisi[0]
        assertEquals("a1", a.id)
        assertEquals(setOf("64"), a.linee)
        assertEquals(setOf("70078"), a.fermate)
        assertEquals(4, a.effetto)
        assertTrue(a.attivo(t0))
        assertFalse(avvisi[1].attivo(t0))
        assertTrue(avvisi[1].futuro(t0))
        assertFalse(avvisi[2].attivo(t0))
    }

    @Test
    fun avvisiPerLineaEFermata() {
        val avvisi = FeedAvvisi.leggi(feedAvvisi)
        val linea = avvisiDellaLinea(avvisi, "64", t0)
        assertEquals(1, linea.size)
        assertEquals("Linea 64 deviata", linea[0].titolo)
        assertEquals("Deviazione", linea[0].tipo)
        assertEquals("Per lavori in via Nazionale.\nFermate & capolinea spostati.", linea[0].testo)
        assertTrue(linea[0].periodo!!.startsWith("fino al "))

        // alla fermata: quello sulla fermata e quello futuro sul 40 che ci passa, il futuro in fondo
        val fermata = avvisiDellaFermata(avvisi, listOf("70078"), mapOf("64" to "64", "40" to "40"), t0)
        assertEquals(listOf("a1", "a2"), fermata.map { it.id })
        assertTrue(fermata[1].futuro)
        assertEquals(listOf("40"), fermata[1].linee)
        assertTrue(fermata[1].periodo!!.startsWith("dal "))

        // nella lingua dell'app, se il feed ce l'ha
        Lingue.imposta("it", "en")
        assertEquals("Line 64 detour", avvisiDellaLinea(avvisi, "64", t0)[0].titolo)
    }

    // --- Avvisami

    private fun riga(vararg tra: Long) = RigaOrologio(
        linea = "64", colore = 0xFF862633.toInt(), capolinea = "Stazione S. Pietro",
        orari = tra.map { t0 + it }, tempoReale = true, lineaId = "64", corse = tra.map { "c$it" },
    )

    @Test
    fun propostaQuandoNonFaiInTempo() {
        // il prossimo tra 1 minuto, a piedi ne servono 2: si propone quello dopo
        val p = propostaAvviso(riga(60, 600, 1200), "P.za Venezia", 2, QuandoAvvisare.USCIRE, t0)
        assertTrue(p.possibile)
        assertEquals(t0 + 600, p.passaggio)
        assertNotNull(p.nota)
        assertTrue(p.nota!!.startsWith("Quello tra 1 minuto non fai in tempo"))
        assertTrue(p.spiegazione.startsWith("Ti avviso verso le "))
        assertEquals("uscire", p.scelta)
        assertEquals("Avvisami per il 64", p.titolo)
    }

    @Test
    fun propostaSenzaPosizione() {
        val p = propostaAvviso(riga(60, 600), "P.za Venezia", null, QuandoAvvisare.USCIRE, t0)
        // senza i minuti a piedi "ora di uscire" non si puo': si avvisa a 5 minuti, per il primo
        assertEquals("cinque", p.scelta)
        assertFalse(p.opzioni.first { it.codice == "uscire" }.abilitata)
        assertEquals(t0 + 60, p.passaggio)
        assertNull(p.nota)
        assertTrue(p.spiegazione.contains("ti avviso subito"))
    }

    @Test
    fun propostaSenzaPassaggi() {
        val p = propostaAvviso(riga(60), "P.za Venezia", 5, QuandoAvvisare.CINQUE, t0)
        assertFalse(p.possibile)
    }

    @Test
    fun minutiAPiediDallaDistanza() {
        assertEquals(1, minutiAPiedi(20.0))
        assertEquals(2, minutiAPiedi(102.0))
        assertEquals(17, minutiAPiedi(1000.0))
    }

    private fun seguimento() = Seguimento(
        fermataId = "70078", fermata = "P.za Venezia", chiaveLinea = "64", lineaId = "64", linea = "64",
        capolinea = "Stazione S. Pietro", colore = 0xFF862633.toInt(), obiettivo = t0 + 600, tempoReale = true,
        inizio = t0 * 1000, aggiornato = t0 * 1000, anticipo = 180, quando = "uscire", aPiedi = 2, corsaId = "c600",
        scendiAttivo = true,
    )

    @Test
    fun fasiDellAvviso() {
        val s = seguimento()
        assertEquals("attesa", statoSegui(s, t0, 3).fase)
        assertEquals("Esci alle " + oraARoma((t0 + 420) * 1000), statoSegui(s, t0, 3).titoloScheda)
        assertEquals("a 3 fermate · tempo reale", statoSegui(s, t0, 3).dettaglio)
        val esci = statoSegui(s, t0 + 450, null)
        assertEquals("esci", esci.fase)
        assertEquals("Esci ora", esci.titoloScheda)
        assertTrue(esci.mostraUscito)
        assertTrue(esci.mostraSuccessivo)
        assertEquals("2", esci.numero)
        // "Sto uscendo": resta il conto alla rovescia del mezzo
        val uscito = statoSegui(s.copy(uscito = true), t0 + 450, null)
        assertEquals("arrivo", uscito.fase)
        assertEquals("64 tra 2 minuti", uscito.titoloScheda)
        // passato il mezzo, con "poi avvisami quando scendere": dove scendi?
        val scegli = statoSegui(s, t0 + 700, null)
        assertEquals("scegli", scegli.fase)
        assertTrue(scegli.mostraScegli)
        assertFalse(finito(s, t0 + 700))
        assertTrue(finito(s, t0 + 600 + 16 * 60))
        // a bordo
        val bordo = s.copy(discesa = Discesa("70100", "Colosseo", t0 + 1200, t0 + 1110, true))
        assertEquals("bordo", statoSegui(bordo, t0 + 800, null).fase)
        assertEquals("Verso Colosseo", statoSegui(bordo, t0 + 800, null).titoloScheda)
        assertEquals("scendi", statoSegui(bordo, t0 + 1120, null).fase)
        assertEquals("Scendi alla prossima", statoSegui(bordo, t0 + 1120, null).titoloScheda)
        assertFalse(finito(bordo, t0 + 1200))
        assertTrue(finito(bordo, t0 + 1300))
    }

    @Test
    fun seguimentoInJsonConLaDiscesa() {
        val s = seguimento().copy(discesa = Discesa("70100", "Colosseo", t0 + 1200, t0 + 1110, true, avvisato = true))
        assertEquals(s, seguimentoDaJson(s.testoJson()))
        assertEquals(seguimento(), seguimentoDaJson(seguimento().testoJson()))
    }

    @Test
    fun ricalcolaSegueLaStessaCorsa() {
        val s = seguimento()
        val t = TabelloneOrologio(
            creato = (t0 + 30) * 1000,
            fermate = listOf(FermataOrologio("70078", "P.za Venezia", "", true, null, listOf(riga(300, 700, 1300)))),
        )
        // la corsa c600 non c'e' piu': si prende il passaggio piu' vicino (700)
        assertEquals(t0 + 700, ricalcola(s, t, t0 + 30)!!.obiettivo)
        val conCorsa = s.copy(corsaId = "c1300")
        assertEquals(t0 + 1300, ricalcola(conCorsa, t, t0 + 30)!!.obiettivo)
    }

    // --- tragitti salvati

    private val casa = Luogo("Casa", "Via Merulana 10", 41.894, 12.503, TipoLuogo.INDIRIZZO)
    private val lavoro = Luogo("Lavoro", "Piazza Venezia", 41.8958, 12.4826, TipoLuogo.LUOGO)

    @Test
    fun salvaRitrovaERimuove() {
        var elenco = aggiungiTragittoSalvato(emptyList(), lavoro, casa, "", "t1")
        assertEquals("Lavoro → Casa", elenco[0].nome)
        elenco = aggiungiTragittoSalvato(elenco, Luogo.posizione(), casa, "A casa", "t2")
        // lo stesso tragitto: cambia solo il nome
        elenco = aggiungiTragittoSalvato(elenco, lavoro, casa.copy(nome = "Casa mia"), "Rientro", "t3")
        assertEquals(listOf("t2", "t1"), elenco.map { it.id })
        assertEquals("Rientro", elenco[1].nome)
        val letti = CodificaTragittiSalvati.leggi(CodificaTragittiSalvati.scrivi(elenco))
        assertEquals(elenco.map { it.id to it.nome }, letti.map { it.id to it.nome })
        assertEquals(TipoLuogo.POSIZIONE, letti[0].partenza.tipo)
        assertEquals(lavoro, letti[1].partenza)
        assertNotNull(tragittoGiaSalvato(letti, lavoro, casa))
        assertEquals(casa to lavoro, tragittoDiRitorno(letti[1]))
        assertNull(tragittoDiRitorno(letti[0]))
        assertEquals(listOf("t1"), rimuoviTragittoSalvato(letti, "t2").map { it.id })
        // nel widget: quello che parte vicino a dove sei
        assertEquals("t1", tragittoPerWidget(letti, Punto(41.8960, 12.4830))!!.id)
        assertEquals("t2", tragittoPerWidget(letti, Punto(45.0, 9.0))!!.id)
    }

    @Test
    fun esciTra() {
        val t = TragittoSalvato("t1", "Lavoro → Casa", lavoro, casa)
        val adesso = t0 * 1000
        val partenze = PartenzeTragitto(
            "t1", adesso,
            listOf(
                PartenzaSalvata(adesso + 4 * 60_000, adesso + 30 * 60_000, "60", Colori.PORPORA, adesso + 7 * 60_000, "P.za Venezia", true),
                PartenzaSalvata(adesso + 16 * 60_000, adesso + 42 * 60_000, "85", Colori.PORPORA, adesso + 19 * 60_000, "P.za Venezia", false),
            ),
            null,
        )
        val c = cartaTragittoSalvato(t, partenze, adesso)
        assertEquals("ESCI TRA", c.esci)
        assertEquals("4", c.numero)
        assertEquals("60 alle " + oraARoma(adesso + 7 * 60_000) + " · arrivi " + oraARoma(adesso + 30 * 60_000), c.sotto)
        assertEquals("oppure esci alle " + oraARoma(adesso + 16 * 60_000), c.poi)
        assertEquals("ESCI ORA", cartaTragittoSalvato(t, partenze, adesso + 4 * 60_000 - 10_000).esci)
        // dopo la prima partenza si passa alla seconda
        assertEquals("85", cartaTragittoSalvato(t, partenze, adesso + 5 * 60_000).linea)
        assertEquals(partenze, CodificaPartenze.daJson(Json.leggi(ScriviJson.testo(CodificaPartenze.inJson(partenze))).comeOggetto()))
    }

    private fun tragitto(partenza: Long, cambi: Int, aPiedi: Int): Tragitto {
        val tappa = Tappa("A", 41.9, 12.5, null, null, null, null, null)
        val piedi = Tratto("WALK", partenza, partenza + aPiedi * 60_000L, partenza, partenza, false, aPiedi * 60, 100.0, tappa, tappa, "", "", null, "", emptyList(), emptyList(), emptyList(), false, emptyList())
        return Tragitto(partenza, partenza + 1_800_000, 1800, cambi, listOf(piedi))
    }

    @Test
    fun ordineDeiRisultati() {
        val a = tragitto(1000, 2, 3)
        val b = tragitto(2000, 0, 9)
        val c = tragitto(3000, 1, 1)
        assertEquals(listOf(a, b, c), ordinaTragitti(listOf(c, a, b), ""))
        assertEquals(listOf(b, c, a), ordinaTragitti(listOf(c, a, b), "cambi"))
        assertEquals(listOf(c, a, b), ordinaTragitti(listOf(c, a, b), "piedi"))
        assertEquals("parti tra 4 min", testoPartiTra(tragitto(t0 * 1000 + 4 * 60_000 + 5_000, 0, 1), t0 * 1000))
    }

    // --- widget

    @Test
    fun widgetDelTelefono() {
        val adesso = t0
        val fermata = FermataOrologio("70078", "P.za Venezia", "palina 70078", true, 102, listOf(riga(60, 540), riga(240, 780).copy(linea = "40", lineaId = "40")))
        val vicina = FermataOrologio("70001", "Plebiscito", "palina 70001", false, 121, listOf(riga(300).copy(linea = "87", lineaId = "87")))
        val t = TabelloneOrologio(adesso * 1000, listOf(fermata, vicina))
        val salvato = TragittoSalvato("t1", "Lavoro → Casa", lavoro, casa)
        val altro = TragittoSalvato("t2", "Casa → Lavoro", casa, lavoro)
        val ms = adesso * 1000
        val partenze = PartenzeTragitto(
            "t2", ms,
            listOf(PartenzaSalvata(ms + 6 * 60_000, ms + 38 * 60_000, "64", Colori.PORPORA, ms + 9 * 60_000, "Merulana", true, listOf("64", "Metro B"), 1, 32 * 60)),
            null,
        )
        val d = DatiWidget(ms, t, setOf(chiaveFissata("70078", "40")), listOf(salvato, altro), mapOf("t2" to partenze), "t1")
        val tab = widgetTabellone(d, adesso)
        assertEquals("PREFERITA", tab.etichetta)
        // solo le linee fissate
        assertEquals(listOf("40"), tab.righe.map { it.linea })
        assertEquals("4'", tab.righe[0].tra)
        assertEquals("poi 13'", tab.righe[0].poi)
        assertEquals("Solo le linee che hai fissato · tocca per aggiornare", tab.nota)
        // la piu' vicina e' la preferita (102 m)
        assertEquals("P.za Venezia", widgetVicina(d, adesso).nome)
        assertEquals("102 m", widgetVicina(d, adesso).distanza)
        // il tragitto automatico non ha ancora partenze
        assertEquals("Calcolo le partenze…", widgetTragitto(d, ms)!!.sotto)
        // quello scelto: mezzi, cambi e tempo di percorrenza
        val scelto = widgetTragitto(d, ms, "t2")!!
        assertEquals("Casa → Lavoro", scelto.nome)
        assertEquals("6", scelto.numero)
        assertEquals("64 → Metro B · 1 cambio · 32 min", scelto.viaggio)
        assertNull(widgetTragitto(d, ms, "t9"))
        // la fermata scelta, anche se non e' la prima
        val plebiscito = widgetTabellone(d, adesso, fermataId = "70001")
        assertEquals("FERMATA", plebiscito.etichetta)
        assertEquals("Plebiscito", plebiscito.nome)
        assertEquals(listOf("87"), plebiscito.righe.map { it.linea })
        assertEquals("Apri Palina per aggiornare", widgetTabellone(d, adesso, fermataId = "99999").nota)
        // andata e ritorno in JSON
        val letto = CodificaWidget.leggi(CodificaWidget.scrivi(d))!!
        assertEquals(d.fissate, letto.fissate)
        assertEquals(d.salvati, letto.salvati)
        assertEquals(d.partenze, letto.partenze)
        assertEquals("t1", letto.automatico)
        assertEquals(d.tabellone!!.fermate.map { it.id }, letto.tabellone!!.fermate.map { it.id })
        // un'ora di voci per WidgetKit
        val voci = Json.leggi(vociWidget(d, adesso * 1000)).comeOggetto().oggetti("voci")
        assertEquals(60, voci.size)
        assertEquals("P.za Venezia", voci[0].oggetto("tabellone")!!.testo("nome"))
        // per la scelta nei widget: le preferite e i tragitti salvati
        assertEquals(setOf("70078"), voci[0].oggetto("fermate")!!.keys)
        assertEquals("64 → Metro B · 1 cambio · 32 min", voci[0].oggetto("tragitti")!!.oggetto("t2")!!.testo("viaggio"))
        val elenco = Json.leggi(vociWidget(d, ms)).comeOggetto().oggetto("elenco")!!
        assertEquals(listOf("t1", "t2"), elenco.oggetti("tragitti").map { it.testo("id") })
        // le scorciatoie dell'icona
        assertEquals(listOf("vicina", "tragitto:t1", "palina", "tragitto:t2"), scorciatoie(listOf(salvato, altro)).map { it.id })
    }
}
