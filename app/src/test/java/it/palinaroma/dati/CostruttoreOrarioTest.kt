package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CostruttoreOrarioTest {

    private class Raccolta : DestinazioneOrario {
        val linee = mutableMapOf<String, String>()
        val fermate = mutableMapOf<String, String>()
        val corse = mutableMapOf<String, String>()
        val direzioni = mutableMapOf<String, Int>()
        val percorsi = mutableMapOf<Pair<String, Int>, Pair<String, List<String>>>()
        val fermataLinee = mutableSetOf<Triple<String, String, Int>>()
        val forme = mutableMapOf<String, List<Punto>>()

        override fun linea(id: String, nome: String, descrizione: String, tipo: Int, colore: String, coloreTesto: String) {
            linee[id] = nome
        }

        override fun fermata(id: String, codice: String, nome: String, lat: Double, lon: Double) {
            fermate[id] = nome
        }

        override fun corsa(id: String, linea: String, direzione: Int, capolinea: String) {
            corse[id] = capolinea
            direzioni[id] = direzione
        }

        override fun percorso(linea: String, direzione: Int, capolinea: String, forma: String, fermate: List<String>) {
            percorsi[linea to direzione] = capolinea to fermate
        }

        override fun fermataLinea(fermata: String, linea: String, direzione: Int, capolinea: String) {
            fermataLinee.add(Triple(fermata, linea, direzione))
        }

        override fun forma(id: String, punti: List<Punto>) {
            forme[id] = punti
        }
    }

    private fun zip(file: File, contenuti: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((nome, testo) in contenuti) {
                z.putNextEntry(ZipEntry(nome))
                z.write(testo.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test
    fun costruisceLeTabelle() {
        val file = File.createTempFile("orario", ".zip")
        zip(
            file,
            mapOf(
                "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n" +
                    "714,ATAC,714,,3\n8,ATAC,8,\"Casaletto - Venezia\",0\n",
                "stops.txt" to "﻿stop_id,stop_code,stop_name,stop_lat,stop_lon,location_type\n" +
                    "A,70001,TERMINI,41.90,12.50,\nB,70002,VIA NAZIONALE,41.90,12.49,0\n" +
                    "C,70003,PALASPORT,41.83,12.46,0\nS,,STAZIONE,41.90,12.50,1\n",
                "trips.txt" to "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\n" +
                    "714,F,t1,PALAZZO DELLO SPORT,0,s1\n714,F,t2,PALAZZO DELLO SPORT,0,s1\n" +
                    "714,F,t3,TERMINI,1,s2\n8,F,t4,CASALETTO,0,\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    // t1 e' corta, t2 e' la corsa completa: il percorso tipico e' quello di t2
                    "t1,08:00:00,08:00:00,B,1\nt1,08:10:00,08:10:00,C,2\n" +
                    "t2,09:00:00,09:00:00,C,3\nt2,08:50:00,08:50:00,B,2\nt2,08:40:00,08:40:00,A,1\n" +
                    "t3,10:00:00,10:00:00,C,1\nt3,10:20:00,10:20:00,A,2\n" +
                    "t4,11:00:00,11:00:00,B,1\n",
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n" +
                    "s1,41.83,12.46,3\ns1,41.90,12.50,1\ns1,41.90,12.49,2\ns1,41.900001,12.490001,2\n" +
                    "s9,1,1,1\n",
            ),
        )
        val raccolta = Raccolta()
        ZipFile(file).use { CostruttoreOrario(it, raccolta).costruisci() }
        file.delete()

        assertEquals(setOf("714", "8"), raccolta.linee.keys)
        assertEquals(setOf("A", "B", "C"), raccolta.fermate.keys)  // la stazione (tipo 1) non c'e'
        assertEquals(4, raccolta.corse.size)

        assertEquals(setOf("714" to 0, "714" to 1, "8" to 0), raccolta.percorsi.keys)
        val (capolinea, fermate) = raccolta.percorsi.getValue("714" to 0)
        assertEquals("PALAZZO DELLO SPORT", capolinea)
        assertEquals(listOf("A", "B", "C"), fermate)
        assertEquals(listOf("C", "A"), raccolta.percorsi.getValue("714" to 1).second)
        assertEquals(listOf("B"), raccolta.percorsi.getValue("8" to 0).second)

        assertTrue(Triple("B", "714", 0) in raccolta.fermataLinee)
        assertTrue(Triple("B", "8", 0) in raccolta.fermataLinee)
        assertTrue(Triple("A", "714", 1) in raccolta.fermataLinee)

        // solo i tracciati usati, ordinati e senza punti doppi
        assertEquals(setOf("s1"), raccolta.forme.keys)
        assertEquals(listOf(41.90, 41.90, 41.83), raccolta.forme.getValue("s1").map { it.lat })
    }

    @Test
    fun ricavaLaDirezioneSeManca() {
        val file = File.createTempFile("orario", ".zip")
        zip(
            file,
            mapOf(
                "routes.txt" to "route_id,route_short_name,route_type\n64,64,3\n3,3,0\n",
                "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\n" +
                    "T,TERMINI,41.900,12.500\nV,VENEZIA,41.896,12.482\nP,STAZ.NE S. PIETRO,41.893,12.454\n" +
                    "G,GIANICOLENSE,41.880,12.460\n",
                // niente direction_id per la 64; la 3 ce l'ha ma sempre uguale
                "trips.txt" to "route_id,service_id,trip_id,trip_headsign,shape_id\n" +
                    "64,F,a1,STAZ.NE S. PIETRO,\n64,F,a2,STAZ.NE S. PIETRO,\n64,F,a3,,\n" +
                    "64,F,r1,TERMINI,\n64,F,r2,,\n" +
                    "3,F,c1,GIANICOLENSE,\n",
                "stop_times.txt" to "trip_id,stop_id,stop_sequence\n" +
                    "a1,T,1\na1,V,2\na1,P,3\na2,T,1\na2,V,2\na2,P,3\n" +
                    // a3: corsa limitata che parte da Venezia, stessa direzione
                    "a3,V,1\na3,P,2\n" +
                    "r1,P,1\nr1,V,2\nr1,T,3\n" +
                    // r2: limitata al ritorno, senza capolinea scritto
                    "r2,P,1\nr2,V,2\n" +
                    "c1,T,1\nc1,G,2\n",
            ),
        )
        val raccolta = Raccolta()
        ZipFile(file).use { CostruttoreOrario(it, raccolta).costruisci() }
        file.delete()

        assertEquals(setOf("64" to 0, "64" to 1, "3" to 0), raccolta.percorsi.keys)
        assertEquals("STAZ.NE S. PIETRO" to listOf("T", "V", "P"), raccolta.percorsi.getValue("64" to 0))
        assertEquals("TERMINI" to listOf("P", "V", "T"), raccolta.percorsi.getValue("64" to 1))
        assertEquals(mapOf("a1" to 0, "a2" to 0, "a3" to 0, "r1" to 1, "r2" to 1, "c1" to 0), raccolta.direzioni)
        // senza trip_headsign il capolinea e' il nome dell'ultima fermata
        assertEquals("STAZ.NE S. PIETRO", raccolta.corse.getValue("a3"))
        assertEquals("VENEZIA", raccolta.corse.getValue("r2"))
        assertTrue(Triple("V", "64", 1) in raccolta.fermataLinee)
    }
}
