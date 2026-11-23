package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Dettaglio delle corse dei treni (da /v5/trip di Transitous) e stazioni di
 * scambio tra Metromare/Roma Nord e metro.
 */
class CorsaTest {
    /** Risposta vera di /v5/trip per una corsa Metromare (24 novembre 2026), ridotta a 5 fermate. */
    private val metromare = """{"duration": 2220, "startTime": "2026-11-24T06:30:00Z", "endTime": "2026-11-24T07:07:00Z", "transfers": 0, "legs": [{"mode": "REGIONAL_RAIL", "startTime": "2026-11-24T06:30:00Z", "endTime": "2026-11-24T07:07:00Z", "scheduledStartTime": "2026-11-24T06:30:00Z", "scheduledEndTime": "2026-11-24T07:07:00Z", "realTime": false, "scheduled": true, "duration": 2220, "headsign": "Cristoforo Colombo", "routeShortName": "ROMALIDO", "routeLongName": "METROMARE", "displayName": "ROMALIDO", "tripShortName": "", "agencyName": "Cotral", "tripId": "20261124_07:30_it-Lazio-Cotral-Ferrovie_158_8957382", "routeId": "it-Lazio-Cotral-Ferrovie_ROMALIDO", "directionId": "0", "cancelled": false, "from": {"name": "Porta San Paolo", "stopId": "it-Lazio-Cotral-Ferrovie_RL_PORTASPAOLO", "lat": 41.875206, "lon": 12.48177, "departure": "2026-11-24T06:30:00Z", "scheduledDeparture": "2026-11-24T06:30:00Z", "cancelled": false, "pickupType": "NORMAL", "dropoffType": "NORMAL"}, "to": {"name": "Cristoforo Colombo", "stopId": "it-Lazio-Cotral-Ferrovie_RL_COLOMBO", "lat": 41.714565, "lon": 12.322038000000001, "arrival": "2026-11-24T07:07:00Z", "scheduledArrival": "2026-11-24T07:07:00Z", "cancelled": false, "pickupType": "NORMAL", "dropoffType": "NORMAL"}, "intermediateStops": [{"name": "San Paolo", "stopId": "it-Lazio-Cotral-Ferrovie_RL_SANPAOLO", "lat": 41.856033, "lon": 12.478271499999998, "arrival": "2026-11-24T06:32:00Z", "departure": "2026-11-24T06:33:00Z", "scheduledArrival": "2026-11-24T06:32:00Z", "scheduledDeparture": "2026-11-24T06:33:00Z", "cancelled": false, "pickupType": "NORMAL", "dropoffType": "NORMAL"}, {"name": "EUR Magliana", "stopId": "it-Lazio-Cotral-Ferrovie_RL_EURMAGLIANA", "lat": 41.839656999999995, "lon": 12.463320999999999, "arrival": "2026-11-24T06:35:00Z", "departure": "2026-11-24T06:36:00Z", "scheduledArrival": "2026-11-24T06:35:00Z", "scheduledDeparture": "2026-11-24T06:36:00Z", "cancelled": false, "pickupType": "NORMAL", "dropoffType": "NORMAL"}, {"name": "Tor Di Valle", "stopId": "it-Lazio-Cotral-Ferrovie_RL_TORDIVALLE", "lat": 41.819572, "lon": 12.436857, "arrival": "2026-11-24T06:39:00Z", "departure": "2026-11-24T06:40:00Z", "scheduledArrival": "2026-11-24T06:39:00Z", "scheduledDeparture": "2026-11-24T06:40:00Z", "cancelled": false, "pickupType": "NORMAL", "dropoffType": "NORMAL"}], "legGeometry": {"points": "", "precision": 6, "length": 0}}]}"""

    /** Un Regionale Veloce in tempo reale: 5 minuti di ritardo, binario cambiato, una fermata saltata. */
    private val regionaleVeloce = """{"legs": [{"mode": "REGIONAL_FAST_RAIL", "startTime": "2026-11-24T07:05:00Z", "endTime": "2026-11-24T08:05:00Z", "scheduledStartTime": "2026-11-24T07:00:00Z", "scheduledEndTime": "2026-11-24T08:00:00Z", "realTime": true, "duration": 3600, "headsign": "CIVITAVECCHIA", "routeShortName": "RV", "tripShortName": "4402", "agencyName": "Trenitalia", "tripId": "20261124_08:00_it-trenitalia_4402", "cancelled": false,
      "from": {"name": "ROMA TERMINI", "stopId": "it-trenitalia_S08409", "lat": 41.9010, "lon": 12.5011, "departure": "2026-11-24T07:05:00Z", "scheduledDeparture": "2026-11-24T07:00:00Z", "track": "12", "scheduledTrack": "12"},
      "intermediateStops": [
        {"name": "ROMA OSTIENSE", "stopId": "it-trenitalia_S08412", "lat": 41.8723, "lon": 12.4865, "arrival": "2026-11-24T07:15:00Z", "scheduledArrival": "2026-11-24T07:10:00Z", "track": "4", "scheduledTrack": "2"},
        {"name": "ROMA SAN PIETRO", "stopId": "it-trenitalia_S08414", "lat": 41.8955, "lon": 12.4547, "arrival": "2026-11-24T07:25:00Z", "scheduledArrival": "2026-11-24T07:20:00Z", "cancelled": true}
      ],
      "to": {"name": "CIVITAVECCHIA", "stopId": "it-trenitalia_S08501", "lat": 42.0934, "lon": 11.7914, "arrival": "2026-11-24T08:05:00Z", "scheduledArrival": "2026-11-24T08:00:00Z"},
      "legGeometry": {"points": "", "precision": 6, "length": 0}}]}"""

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun leggeLaCorsaDiTransitous() {
        val t = leggiCorsa(regionaleVeloce)!!
        assertEquals("RV", t.linea)
        assertEquals("4402", t.numero)
        assertTrue(t.tempoReale)
        assertEquals(2, t.intermedie.size)
        assertEquals("4", t.intermedie[0].binario)
        assertEquals("2", t.intermedie[0].binarioPrevisto)
        assertTrue(t.intermedie[1].cancellata)
        assertFalse(t.intermedie[0].cancellata)
        assertEquals("20261124_08:00_it-trenitalia_4402", t.corsaId)
        assertEquals(3, leggiCorsa(metromare)!!.intermedie.size)
    }

    @Test
    fun conIlTempoRealeDiTransitous() {
        val d = dettaglioCorsa(leggiCorsa(regionaleVeloce)!!, "T:S08412", 41.8723, 12.4865, ritardoSecondi = null)
        assertEquals(FonteCorsa.TEMPO_REALE, d.fonte)
        assertEquals(1, d.qui)
        assertEquals(4, d.fermate.size)
        val ostiense = d.fermate[1]
        assertEquals(5, ostiense.ritardoMinuti)
        assertEquals("4", ostiense.binario)
        assertTrue(ostiense.binarioCambiato)
        assertFalse(ostiense.stimato)
        assertTrue(d.fermate[2].soppressa)
        assertFalse(d.fermate[0].binarioCambiato)
    }

    @Test
    fun conIlRitardoDiAstral() {
        // il treno e' a EUR Magliana con 3 minuti di ritardo: prima l'orario, dopo una stima
        val d = dettaglioCorsa(leggiCorsa(metromare)!!, "F:RL_EURMAGLIANA", 41.8397, 12.4633, ritardoSecondi = 180)
        assertEquals(FonteCorsa.ASTRAL, d.fonte)
        assertEquals(2, d.qui)
        assertEquals(listOf(0, 0, 3, 3, 3), d.fermate.map { it.ritardoMinuti })
        assertEquals(listOf(false, false, false, true, true), d.fermate.map { it.stimato })
        assertEquals(ms("2026-11-24T06:38:00Z"), d.fermate[2].orario)
        // senza ritardo di ASTRAL: solo orario
        assertEquals(FonteCorsa.ORARIO, dettaglioCorsa(leggiCorsa(metromare)!!, "F:RL_EURMAGLIANA", Double.NaN, Double.NaN, null).fonte)
    }

    @Test
    fun laStazioneSiRiconosceAncheDallaPosizione() {
        val t = leggiCorsa(metromare)!!
        val tappe = listOf(t.da) + t.intermedie + listOf(t.a)
        assertEquals(1, indiceFermata(tappe, "F:RL_SANPAOLO", Double.NaN, Double.NaN))
        // un codice che Transitous non conosce, ma a 15 m dalla stazione
        assertEquals(1, indiceFermata(tappe, "BD17", 41.85614, 12.47836))
        assertNull(indiceFermata(tappe, "BD17", 41.95, 12.50))
        assertNull(indiceFermata(tappe, null, Double.NaN, Double.NaN))
    }

    @Test
    fun ilTrenoDiAstralSiTrovaTraLePartenze() {
        fun partenza(corsa: String, linea: String, direzione: Int?, previsto: String) =
            PartenzaTransitous(corsa, linea, "ROMALIDO", direzione, "", ms(previsto), ms(previsto), false, false, "")
        val partenze = listOf(
            partenza("a", "it-Lazio-Cotral-Ferrovie_ROMALIDO", 1, "2026-11-24T06:36:00Z"),
            partenza("b", "it-Lazio-Cotral-Ferrovie_ROMALIDO", 0, "2026-11-24T06:36:00Z"),
            partenza("c", "it-Lazio-Cotral-Ferrovie_ROMALIDO", 0, "2026-11-24T06:51:00Z"),
            partenza("d", "it-Lazio-Cotral-Ferrovie_RVURB", 0, "2026-11-24T06:36:00Z"),
        )
        assertEquals("b", scegliCorsa(partenze, "F:ROMALIDO", 0, ms("2026-11-24T06:36:00Z"))?.corsaId)
        assertEquals("a", scegliCorsa(partenze, "F:ROMALIDO", 1, ms("2026-11-24T06:36:30Z"))?.corsaId)
        assertEquals("c", scegliCorsa(partenze, "F:ROMALIDO", 0, ms("2026-11-24T06:50:00Z"))?.corsaId)
        assertNull(scegliCorsa(partenze, "F:ROMALIDO", 0, ms("2026-11-24T06:43:00Z")))  // troppo lontano
        assertEquals("d", scegliCorsa(partenze, "F:RVURB", null, ms("2026-11-24T06:36:00Z"))?.corsaId)
    }

    @Test
    fun codiciDiTransitousNellApp() {
        assertEquals("F:RL_SANPAOLO", fermataDaTransitous("it-Lazio-Cotral-Ferrovie_RL_SANPAOLO"))
        assertEquals("T:S08409", fermataDaTransitous("it-trenitalia_S08409"))
        assertEquals("70001", fermataDaTransitous("it-Lazio-Rome_70001"))
        assertNull(fermataDaTransitous("de-DELFI_1"))
        assertNull(fermataDaTransitous(null))
    }

    @Test
    fun nomiELineeDelleStazioniDiScambio() {
        assertEquals("Basilica San Paolo", nomeStazioneUnita(listOf("San Paolo", "Basilica San Paolo", "Basilica San Paolo")))
        assertEquals("Piramide / Porta San Paolo", nomeStazioneUnita(listOf("Porta San Paolo", "Piramide", "Piramide")))
        assertEquals("EUR Magliana", nomeStazioneUnita(listOf("EUR Magliana", "Eur Magliana", "Eur Magliana")))
        assertEquals("Flaminio", nomeStazioneUnita(listOf("Flaminio", "Flaminio")))
        assertEquals("Metro B/B1 · Metromare", lineeStazioneUnita(listOf("Metromare", "MEB", "MEB1", "MEB", "MEB1")))
        assertEquals("Metro A · Roma Nord · Roma–Viterbo", lineeStazioneUnita(listOf("Roma Nord", "Roma–Viterbo", "MEA")))
    }

    @Test
    fun ilTabelloneDiUnaStazioneDiScambioUnisceLeFermate() {
        val adesso = ms("2026-11-24T06:30:00Z") / 1000
        fun partenza(corsa: String, linea: String, fermata: String, minuti: Long) =
            PartenzaTransitous(corsa, linea, linea.substringAfter('_'), 0, "Capolinea", (adesso + minuti * 60) * 1000,
                (adesso + minuti * 60) * 1000, false, false, fermata)
        val fonti = mapOf(
            "F:RL_SANPAOLO" to FontiFermata(listOf(partenza("20261124_07:30_it-Lazio-Cotral-Ferrovie_1", "it-Lazio-Cotral-Ferrovie_ROMALIDO", "x", 6)), null),
            "BD17" to FontiFermata(listOf(partenza("20261124_07:30_it-Lazio-Rome_2", "it-Lazio-Rome_MEB", "x", 2)), null),
            "BP6" to FontiFermata(listOf(partenza("20261124_07:30_it-Lazio-Rome_3", "it-Lazio-Rome_MEB", "x", 9)), null),
        )
        val arrivi = arriviStazione(listOf("F:RL_SANPAOLO", "BD17", "BP6"), null, null, emptyList(), fonti, adesso)
        assertEquals(3, arrivi.size)
        assertEquals(listOf(2L, 6L, 9L), arrivi.map { (it.orari.first() - adesso) / 60 })
        // il treno porta con se' la corsa di Transitous, per il dettaglio
        assertEquals("20261124_07:30_it-Lazio-Cotral-Ferrovie_1", arrivi[1].passaggi.first().idTransitous)
        // una fermata sola: come prima
        assertEquals(1, arriviStazione(listOf("BD17"), null, null, emptyList(), fonti, adesso).size)
    }
}
