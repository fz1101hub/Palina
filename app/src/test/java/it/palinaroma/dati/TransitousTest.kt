package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitousTest {
    @Test
    fun leggeIlJson() {
        val v = Json.leggi("""{"a": [1, -2.5e2, true, null, "x\"y\\zè🚌"], "b": {}, "c": []}""").comeOggetto()
        val a = v["a"] as List<*>
        assertEquals(1.0, a[0])
        assertEquals(-250.0, a[1])
        assertEquals(true, a[2])
        assertNull(a[3])
        assertEquals("x\"y\\zè🚌", a[4])
        assertTrue(v.oggetto("b")!!.isEmpty())
        assertTrue(v.oggetti("c").isEmpty())
        assertTrue(runCatching { Json.leggi("{\"a\": }") }.isFailure)
        assertTrue(runCatching { Json.leggi("[1, 2") }.isFailure)
        assertTrue(runCatching { Json.leggi("{} x") }.isFailure)
    }

    @Test
    fun decodificaLePolilinee() {
        // esempio della documentazione di Google (precisione 5)
        val punti = decodificaPolilinea("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, punti.size)
        assertEquals(38.5, punti[0].lat, 1e-9)
        assertEquals(-120.2, punti[0].lon, 1e-9)
        assertEquals(43.252, punti[2].lat, 1e-9)
        assertEquals(-126.453, punti[2].lon, 1e-9)
        // stessa geometria a precisione 6 e 7: andata e ritorno
        listOf(6, 7).forEach { p ->
            val roma = listOf(Punto(41.901234, 12.501234), Punto(41.8986, 12.4768), Punto(41.890251, 12.492373))
            val ritorno = decodificaPolilinea(codificaPerTest(roma, p), p)
            assertEquals(roma.size, ritorno.size)
            roma.zip(ritorno).forEach { (x, y) ->
                assertEquals(x.lat, y.lat, 1e-6)
                assertEquals(x.lon, y.lon, 1e-6)
            }
        }
        // stringa troncata: niente eccezioni
        assertEquals(1, decodificaPolilinea("_p~iF~ps|U_ulL", 5).size)
    }

    @Test
    fun costruisceGliIndirizzi() {
        val da = Luogo("Casa", "", 41.9, 12.5, TipoLuogo.INDIRIZZO)
        val a = Luogo("Colosseo", "Roma", 41.890251, 12.492373, TipoLuogo.LUOGO)
        val url = urlTragitti(da, a, 1_793_740_320_456L, arrivaEntro = true, cursore = "abc=")
        assertTrue(url.startsWith("https://api.transitous.org/api/v5/plan?"))
        assertTrue(url.contains("fromPlace=41.900000%2C12.500000"))
        assertTrue(url.contains("toPlace=41.890251%2C12.492373"))
        assertTrue(url.contains("time=2026-11-03T21%3A12%3A00Z"))
        assertTrue(url.contains("arriveBy=true"))
        assertTrue(url.contains("pageCursor=abc%3D"))
        val adesso = urlTragitti(da, a, null, arrivaEntro = false, cursore = null)
        assertFalse(adesso.contains("time="))
        assertFalse(adesso.contains("arriveBy"))
        val cerca = urlLuoghi("via del corso 12", Punto(41.9028, 12.4964))
        assertEquals(
            "https://api.transitous.org/api/v1/geocode?text=via+del+corso+12&language=it&numResults=8&place=41.90280%2C12.49640",
            cerca,
        )
    }

    @Test
    fun coordinateSempreColPunto() {
        val prima = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ITALY)
            assertEquals("41.900000,12.500000", Luogo("x", "", 41.9, 12.5, TipoLuogo.LUOGO).coordinate)
        } finally {
            java.util.Locale.setDefault(prima)
        }
    }

    @Test
    fun leggeIRisultatiDellaRicerca() {
        val json = """[
          {"type":"ADDRESS","name":"Via del Corso","houseNumber":"12","id":"a1","lat":41.9,"lon":12.48,
           "tokens":[],"score":1,"areas":[{"name":"Italia","adminLevel":2,"matched":false},
           {"name":"Roma","adminLevel":8,"matched":false,"default":true},{"name":"Trevi","adminLevel":10,"matched":false,"unique":true}]},
          {"type":"STOP","name":"TERMINI (MA-MB-FS)","id":"s1","lat":41.901,"lon":12.501,"tokens":[],"score":1,
           "areas":[{"name":"Roma","adminLevel":8,"matched":false}]},
          {"type":"PLACE","name":"Colosseo","id":"p1","lat":41.8902,"lon":12.4922,"tokens":[],"score":1,"areas":[]},
          {"type":"PLACE","name":"Senza coordinate","id":"p2","tokens":[],"score":1,"areas":[]}
        ]"""
        val luoghi = leggiLuoghi(json)
        assertEquals(3, luoghi.size)
        assertEquals("Via del Corso, 12", luoghi[0].nome)
        assertEquals("Roma, Trevi", luoghi[0].dettaglio)
        assertEquals(TipoLuogo.INDIRIZZO, luoghi[0].tipo)
        assertEquals("Termini (MA-MB-FS)", luoghi[1].nome)
        assertEquals("Roma", luoghi[1].dettaglio)
        assertEquals(TipoLuogo.FERMATA, luoghi[1].tipo)
        assertEquals("", luoghi[2].dettaglio)
    }

    @Test
    fun leggeITragitti() {
        val r = leggiTragitti(RISPOSTA, "Casa", "Colosseo")
        assertEquals(1, r.tragitti.size)
        assertEquals("CUR-PRIMA", r.cursorePrima)
        assertEquals("CUR-DOPO", r.cursoreDopo)
        val t = r.tragitti[0]
        assertEquals(1, t.cambi)
        assertEquals(1980, t.durata)
        assertEquals(4, t.tratti.size)
        assertEquals(listOf("714", "Metro B"), t.mezzi.map { it.linea })
        assertEquals(8, t.minutiAPiedi)

        val piedi = t.tratti[0]
        assertTrue(piedi.aPiedi)
        assertEquals("Casa", piedi.da.nome)
        assertEquals("Termini (MA-MB-FS)", piedi.a.nome)
        assertEquals(2, piedi.passi.size)
        assertEquals("Parti in Via Marsala", piedi.passi[0].testo)
        assertEquals("Gira a destra", piedi.passi[1].testo)
        assertEquals(2, piedi.forma.size)

        val bus = t.tratti[1]
        assertEquals("Bus", bus.nomeMezzo)
        assertEquals("Palazzo dello Sport", bus.direzione)
        assertEquals(2, bus.ritardoMinuti)
        assertTrue(bus.tempoReale)
        assertEquals("70243", bus.da.codice)
        assertEquals(1, bus.intermedie.size)
        assertEquals(listOf("Deviazione per lavori"), bus.avvisi)
        assertEquals(0xFF862633.toInt(), bus.colore)

        val metro = t.tratti[2]
        assertEquals("Metro", metro.nomeMezzo)
        assertEquals("Colosseo", t.tratti[3].a.nome)

        val solo = r.soloAPiedi!!
        assertEquals(1, solo.tratti.size)
        assertEquals(2400.0, solo.metriAPiedi, 0.1)
    }

    @Test
    fun ricordaILuoghi() {
        val l = Luogo("Via A|B", "Roma", 41.9, 12.5, TipoLuogo.INDIRIZZO)
        val riletto = luogoDaRiga(l.inRiga())!!
        assertEquals("Via A/B", riletto.nome)
        assertEquals(41.9, riletto.lat, 1e-9)
        assertNull(luogoDaRiga(Luogo.posizione().inRiga()))
        assertNull(luogoDaRiga("rotta"))
    }

    @Test
    fun scriveDurateEDistanze() {
        assertEquals("1 min", testoDurata(20))
        assertEquals("35 min", testoDurata(35 * 60))
        assertEquals("1 h 05 min", testoDurata(65 * 60))
        assertEquals("450 m", testoMetri(456.0))
        assertEquals("3,2 km", testoMetri(3240.0))
        assertTrue(messaggioErrore(500, "").contains("non risponde"))
        assertTrue(messaggioErrore(400, """{"error":"no stop found"}""").contains("no stop found"))
        assertEquals("Metro A", nomeLinea("MEA", null, "SUBWAY"))
        assertEquals("MEA", nomeLinea("MEA", null, "BUS"))
        assertEquals("FL1", nomeLinea("FL1", null, "RAIL"))
        assertEquals("Termini-Lido", nomeLinea("", "Termini-Lido", "RAIL"))
    }

    private fun codificaPerTest(punti: List<Punto>, precisione: Int): String {
        val fattore = Math.pow(10.0, precisione.toDouble())
        val sb = StringBuilder()
        var latPrec = 0L
        var lonPrec = 0L
        fun valore(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x.toInt() + 63).toChar())
        }
        punti.forEach { p ->
            val lat = Math.round(p.lat * fattore)
            val lon = Math.round(p.lon * fattore)
            valore(lat - latPrec)
            valore(lon - lonPrec)
            latPrec = lat
            lonPrec = lon
        }
        return sb.toString()
    }

    private val RISPOSTA = """{
      "requestParameters": {}, "debugOutput": {},
      "from": {"name": "START", "lat": 41.9, "lon": 12.5},
      "to": {"name": "END", "lat": 41.8902, "lon": 12.4922},
      "previousPageCursor": "CUR-PRIMA", "nextPageCursor": "CUR-DOPO",
      "direct": [{"duration": 1800, "startTime": "2026-11-03T19:10:00Z", "endTime": "2026-11-03T19:40:00Z",
        "transfers": 0, "id": "d", "legs": [{"mode": "WALK", "duration": 1800, "distance": 2400,
          "startTime": "2026-11-03T19:10:00Z", "endTime": "2026-11-03T19:40:00Z",
          "scheduledStartTime": "2026-11-03T19:10:00Z", "scheduledEndTime": "2026-11-03T19:40:00Z",
          "realTime": false, "scheduled": true,
          "from": {"name": "START", "lat": 41.9, "lon": 12.5}, "to": {"name": "END", "lat": 41.8902, "lon": 12.4922},
          "legGeometry": {"points": "", "precision": 6, "length": 0}}]}],
      "itineraries": [{
        "duration": 1980, "startTime": "2026-11-03T19:12:00Z", "endTime": "2026-11-03T19:45:00Z",
        "transfers": 1, "id": "i1",
        "legs": [
          {"mode": "WALK", "duration": 300, "distance": 380,
           "startTime": "2026-11-03T19:12:00Z", "endTime": "2026-11-03T19:17:00Z",
           "scheduledStartTime": "2026-11-03T19:12:00Z", "scheduledEndTime": "2026-11-03T19:17:00Z",
           "realTime": false, "scheduled": true,
           "from": {"name": "START", "lat": 41.9, "lon": 12.5, "vertexType": "NORMAL"},
           "to": {"name": "TERMINI (MA-MB-FS)", "stopId": "it-rome_70243", "stopCode": "70243", "lat": 41.901, "lon": 12.501, "vertexType": "TRANSIT"},
           "legGeometry": {"points": "_}j|nA_a}yVo}@o}@", "precision": 6, "length": 2},
           "steps": [
             {"relativeDirection": "DEPART", "distance": 120, "streetName": "Via Marsala", "fromLevel": 0, "toLevel": 0, "exit": "", "stayOn": false, "area": false, "polyline": {"points": "", "precision": 6, "length": 0}},
             {"relativeDirection": "RIGHT", "distance": 260, "streetName": "", "fromLevel": 0, "toLevel": 0, "exit": "", "stayOn": false, "area": false, "polyline": {"points": "", "precision": 6, "length": 0}},
             {"relativeDirection": "CONTINUE", "distance": 0, "streetName": "x", "fromLevel": 0, "toLevel": 0, "exit": "", "stayOn": false, "area": false, "polyline": {"points": "", "precision": 6, "length": 0}}
           ]},
          {"mode": "BUS", "duration": 720, "routeShortName": "714", "headsign": "PALAZZO DELLO SPORT", "routeColor": "862633",
           "agencyName": "ATAC", "realTime": true, "scheduled": true,
           "startTime": "2026-11-03T19:20:00Z", "endTime": "2026-11-03T19:32:00Z",
           "scheduledStartTime": "2026-11-03T19:18:00Z", "scheduledEndTime": "2026-11-03T19:30:00Z",
           "from": {"name": "TERMINI (MA-MB-FS)", "stopId": "it-rome_70243", "stopCode": "70243", "lat": 41.901, "lon": 12.501,
                    "departure": "2026-11-03T19:20:00Z", "scheduledDeparture": "2026-11-03T19:18:00Z"},
           "to": {"name": "Piramide", "stopId": "it-rome_71111", "lat": 41.876, "lon": 12.481,
                  "arrival": "2026-11-03T19:32:00Z", "scheduledArrival": "2026-11-03T19:30:00Z"},
           "intermediateStops": [{"name": "Santa Maria Maggiore", "lat": 41.897, "lon": 12.498, "arrival": "2026-11-03T19:24:00Z"}],
           "alerts": [{"headerText": "Deviazione per lavori", "descriptionText": "..."}, {"headerText": "Deviazione per lavori", "descriptionText": "..."}],
           "legGeometry": {"points": "", "precision": 6, "length": 0}},
          {"mode": "SUBWAY", "duration": 360, "routeShortName": "MEB", "headsign": "Laurentina", "realTime": false, "scheduled": true,
           "startTime": "2026-11-03T19:35:00Z", "endTime": "2026-11-03T19:41:00Z",
           "scheduledStartTime": "2026-11-03T19:35:00Z", "scheduledEndTime": "2026-11-03T19:41:00Z",
           "from": {"name": "Piramide", "lat": 41.876, "lon": 12.481}, "to": {"name": "Colosseo", "lat": 41.8913, "lon": 12.4911},
           "legGeometry": {"points": "", "precision": 6, "length": 0}},
          {"mode": "WALK", "duration": 180, "distance": 200,
           "startTime": "2026-11-03T19:41:00Z", "endTime": "2026-11-03T19:45:00Z",
           "scheduledStartTime": "2026-11-03T19:41:00Z", "scheduledEndTime": "2026-11-03T19:45:00Z",
           "realTime": false, "scheduled": true,
           "from": {"name": "Colosseo", "lat": 41.8913, "lon": 12.4911}, "to": {"name": "END", "lat": 41.8902, "lon": 12.4922},
           "legGeometry": {"points": "", "precision": 6, "length": 0}}
        ]
      }]
    }"""
}
