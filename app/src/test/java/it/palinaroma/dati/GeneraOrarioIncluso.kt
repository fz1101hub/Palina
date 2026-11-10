package it.palinaroma.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Scrive l'orario come istruzioni SQL, con le stesse tabelle e gli stessi testi
 * di [ScrittoreDb]. Serve alla compilazione su GitHub: lo script
 * strumenti/crea_orario.py le trasforma nel database incluso nell'APK.
 */
class ScrittoreSql(private val uscita: Appendable) : DestinazioneOrario {
    var fermate = 0
        private set
    var linee = 0
        private set

    init {
        uscita.append("BEGIN;\n")
        SCHEMA_ORARIO.forEach { uscita.append(it).append(";\n") }
    }

    private fun valore(v: Any) {
        when (v) {
            is String -> uscita.append('\'').append(v.replace("\u0000", "").replace("'", "''")).append('\'')
            is Int, is Long -> uscita.append(v.toString())
            is Double -> {
                require(!v.isNaN() && !v.isInfinite()) { "numero non valido" }
                uscita.append(v.toString())
            }
            else -> error("tipo non previsto: ${v::class}")
        }
    }

    private fun riga(tabella: String, vararg valori: Any) {
        uscita.append("INSERT OR REPLACE INTO ").append(tabella).append(" VALUES(")
        valori.forEachIndexed { i, v ->
            if (i > 0) uscita.append(',')
            valore(v)
        }
        uscita.append(");\n")
    }

    override fun linea(id: String, nome: String, descrizione: String, tipo: Int, colore: String, coloreTesto: String) {
        riga("linee", id, nome, descrizione, tipo, colore, coloreTesto)
        linee++
    }

    override fun fermata(id: String, codice: String, nome: String, lat: Double, lon: Double) {
        val bello = bellaScrittura(nome)
        riga("fermate", id, codice, bello, normalizza(bello), lat, lon)
        fermate++
    }

    override fun corsa(id: String, linea: String, direzione: Int, capolinea: String) =
        riga("corse", id, linea, direzione, capolinea)

    override fun percorso(linea: String, direzione: Int, capolinea: String, forma: String, fermate: List<String>) {
        riga("percorsi", linea, direzione, capolinea, forma)
        fermate.forEachIndexed { i, fermata -> riga("percorso_fermate", linea, direzione, i, fermata) }
    }

    override fun fermataLinea(fermata: String, linea: String, direzione: Int, capolinea: String) =
        riga("fermata_linee", fermata, linea, direzione, capolinea)

    override fun forma(id: String, punti: List<Punto>) = riga("forme", id, testoForma(punti))

    fun completa(creato: Long) {
        listOf(
            "fermate" to fermate.toString(),
            "linee" to linee.toString(),
            "creato" to creato.toString(),
            "origine" to ORIGINE_INCLUSA,
        ).forEach { (chiave, v) -> riga("info", chiave, v) }
        uscita.append("COMMIT;\n")
        INDICI_ORARIO.forEach { uscita.append(it).append(";\n") }
    }
}

class GeneraOrarioIncluso {

    /**
     * Gira solo nella compilazione su GitHub, che scarica l'orario e imposta
     * ORARIO_GTFS (lo zip) e ORARIO_SQL (dove scrivere le istruzioni).
     */
    @Test
    fun generaDaGtfs() {
        val gtfs = System.getenv("ORARIO_GTFS").orEmpty()
        val sql = System.getenv("ORARIO_SQL").orEmpty()
        assumeTrue(gtfs.isNotEmpty() && sql.isNotEmpty())
        val uscita = File(sql)
        uscita.absoluteFile.parentFile?.mkdirs()
        uscita.bufferedWriter(Charsets.UTF_8).use { w ->
            val scrittore = ScrittoreSql(w)
            ZipFile(gtfs).use { z -> CostruttoreOrario(z, scrittore).costruisci() }
            scrittore.completa(System.currentTimeMillis())
            assertTrue("poche fermate: ${scrittore.fermate}", scrittore.fermate > 1000)
        }
    }

    @Test
    fun scriveSqlCorretto() {
        val file = File.createTempFile("orario", ".zip")
        ZipOutputStream(file.outputStream()).use { z ->
            mapOf(
                "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nR1,913,,3\n",
                "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon\n" +
                    "S1,70001,PIAZZA D'ARACOELI,41.89,12.48\nS2,70002,\"VIA \"\"NUOVA\"\"\",41.90,12.49\n",
                "trips.txt" to "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\nR1,X,T1,L'ARCO,0,F1\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,08:00:00,08:00:00,S1,1\nT1,08:05:00,08:05:00,S2,2\n",
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nF1,41.89,12.48,1\nF1,41.90,12.49,2\n",
            ).forEach { (nome, testo) ->
                z.putNextEntry(ZipEntry(nome))
                z.write(testo.toByteArray())
                z.closeEntry()
            }
        }
        val sql = StringBuilder()
        val scrittore = ScrittoreSql(sql)
        ZipFile(file).use { CostruttoreOrario(it, scrittore).costruisci() }
        scrittore.completa(1_700_000_000_000)
        file.delete()

        val testo = sql.toString()
        assertTrue(testo.startsWith("BEGIN;\nCREATE TABLE linee("))
        assertTrue(testo.contains("INSERT OR REPLACE INTO fermate VALUES('S1','70001','Piazza d''Aracoeli','piazza d aracoeli',41.89,12.48);"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO corse VALUES('T1','R1',0,'L''ARCO');"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO percorso_fermate VALUES('R1',0,1,'S2');"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO forme VALUES('F1','41.89000,12.48000;41.90000,12.49000');"))
        assertTrue(testo.contains("INSERT OR REPLACE INTO info VALUES('origine','app');"))
        assertTrue(testo.trimEnd().endsWith("CREATE INDEX fermate_codice ON fermate(codice);"))
        assertEquals(2, scrittore.fermate)
    }
}
