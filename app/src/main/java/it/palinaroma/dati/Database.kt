package it.palinaroma.dati

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File
import java.util.Locale
import kotlin.math.cos

data class Fermata(val id: String, val codice: String, val nome: String, val lat: Double, val lon: Double)

data class Linea(
    val id: String,
    val nome: String,
    val descrizione: String,
    val tipo: Int,
    val colore: String,
    val coloreTesto: String,
)

data class Percorso(val lineaId: String, val direzione: Int, val capolinea: String, val forma: String)

data class LineaFermata(val linea: Linea, val direzione: Int, val capolinea: String)

data class InfoCorsa(val lineaId: String, val direzione: Int, val capolinea: String)

private val SCHEMA = listOf(
    "CREATE TABLE linee(id TEXT PRIMARY KEY, nome TEXT, descrizione TEXT, tipo INTEGER, colore TEXT, colore_testo TEXT)",
    "CREATE TABLE fermate(id TEXT PRIMARY KEY, codice TEXT, nome TEXT, nome_norm TEXT, lat REAL, lon REAL)",
    "CREATE TABLE corse(id TEXT PRIMARY KEY, linea TEXT, direzione INTEGER, capolinea TEXT) WITHOUT ROWID",
    "CREATE TABLE percorsi(linea TEXT, direzione INTEGER, capolinea TEXT, forma TEXT, PRIMARY KEY(linea, direzione))",
    "CREATE TABLE percorso_fermate(linea TEXT, direzione INTEGER, posizione INTEGER, fermata TEXT, " +
        "PRIMARY KEY(linea, direzione, posizione))",
    "CREATE TABLE fermata_linee(fermata TEXT, linea TEXT, direzione INTEGER, capolinea TEXT, " +
        "PRIMARY KEY(fermata, linea, direzione))",
    "CREATE TABLE forme(id TEXT PRIMARY KEY, punti TEXT)",
    "CREATE TABLE info(chiave TEXT PRIMARY KEY, valore TEXT)",
)

/** Scrive l'orario in un nuovo file SQLite, tutto in una transazione. */
class ScrittoreDb(file: File) : DestinazioneOrario {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
    private val insLinea: SQLiteStatement
    private val insFermata: SQLiteStatement
    private val insCorsa: SQLiteStatement
    private val insPercorso: SQLiteStatement
    private val insPercorsoFermata: SQLiteStatement
    private val insFermataLinea: SQLiteStatement
    private val insForma: SQLiteStatement
    private var fermate = 0
    private var linee = 0

    init {
        SCHEMA.forEach { db.execSQL(it) }
        db.beginTransaction()
        insLinea = db.compileStatement("INSERT OR REPLACE INTO linee VALUES (?,?,?,?,?,?)")
        insFermata = db.compileStatement("INSERT OR REPLACE INTO fermate VALUES (?,?,?,?,?,?)")
        insCorsa = db.compileStatement("INSERT OR REPLACE INTO corse VALUES (?,?,?,?)")
        insPercorso = db.compileStatement("INSERT OR REPLACE INTO percorsi VALUES (?,?,?,?)")
        insPercorsoFermata = db.compileStatement("INSERT OR REPLACE INTO percorso_fermate VALUES (?,?,?,?)")
        insFermataLinea = db.compileStatement("INSERT OR REPLACE INTO fermata_linee VALUES (?,?,?,?)")
        insForma = db.compileStatement("INSERT OR REPLACE INTO forme VALUES (?,?)")
    }

    override fun linea(id: String, nome: String, descrizione: String, tipo: Int, colore: String, coloreTesto: String) {
        insLinea.clearBindings()
        insLinea.bindString(1, id)
        insLinea.bindString(2, nome)
        insLinea.bindString(3, descrizione)
        insLinea.bindLong(4, tipo.toLong())
        insLinea.bindString(5, colore)
        insLinea.bindString(6, coloreTesto)
        insLinea.executeInsert()
        linee++
    }

    override fun fermata(id: String, codice: String, nome: String, lat: Double, lon: Double) {
        val bello = bellaScrittura(nome)
        insFermata.clearBindings()
        insFermata.bindString(1, id)
        insFermata.bindString(2, codice)
        insFermata.bindString(3, bello)
        insFermata.bindString(4, normalizza(bello))
        insFermata.bindDouble(5, lat)
        insFermata.bindDouble(6, lon)
        insFermata.executeInsert()
        fermate++
    }

    override fun corsa(id: String, linea: String, direzione: Int, capolinea: String) {
        insCorsa.clearBindings()
        insCorsa.bindString(1, id)
        insCorsa.bindString(2, linea)
        insCorsa.bindLong(3, direzione.toLong())
        insCorsa.bindString(4, capolinea)
        insCorsa.executeInsert()
    }

    override fun percorso(linea: String, direzione: Int, capolinea: String, forma: String, fermate: List<String>) {
        insPercorso.clearBindings()
        insPercorso.bindString(1, linea)
        insPercorso.bindLong(2, direzione.toLong())
        insPercorso.bindString(3, capolinea)
        insPercorso.bindString(4, forma)
        insPercorso.executeInsert()
        fermate.forEachIndexed { i, fermata ->
            insPercorsoFermata.clearBindings()
            insPercorsoFermata.bindString(1, linea)
            insPercorsoFermata.bindLong(2, direzione.toLong())
            insPercorsoFermata.bindLong(3, i.toLong())
            insPercorsoFermata.bindString(4, fermata)
            insPercorsoFermata.executeInsert()
        }
    }

    override fun fermataLinea(fermata: String, linea: String, direzione: Int, capolinea: String) {
        insFermataLinea.clearBindings()
        insFermataLinea.bindString(1, fermata)
        insFermataLinea.bindString(2, linea)
        insFermataLinea.bindLong(3, direzione.toLong())
        insFermataLinea.bindString(4, capolinea)
        insFermataLinea.executeInsert()
    }

    override fun forma(id: String, punti: List<Punto>) {
        insForma.clearBindings()
        insForma.bindString(1, id)
        insForma.bindString(2, punti.joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) })
        insForma.executeInsert()
    }

    fun completa() {
        val info = db.compileStatement("INSERT OR REPLACE INTO info VALUES (?,?)")
        for ((chiave, valore) in listOf(
            "fermate" to fermate.toString(),
            "linee" to linee.toString(),
            "creato" to System.currentTimeMillis().toString(),
        )) {
            info.clearBindings()
            info.bindString(1, chiave)
            info.bindString(2, valore)
            info.executeInsert()
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        db.execSQL("CREATE INDEX fermate_lat ON fermate(lat)")
        db.execSQL("CREATE INDEX fermate_codice ON fermate(codice)")
        db.close()
    }

    fun annulla() {
        if (db.inTransaction()) db.endTransaction()
        db.close()
    }
}

/** Lettura dell'orario gia' trasformato. */
class Orario(private val db: SQLiteDatabase) {

    private val linee: Map<String, Linea> by lazy {
        val mappa = HashMap<String, Linea>()
        db.rawQuery("SELECT id, nome, descrizione, tipo, colore, colore_testo FROM linee", null).use { c ->
            while (c.moveToNext()) {
                val l = Linea(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
                mappa[l.id] = l
            }
        }
        mappa
    }

    fun info(chiave: String): String? =
        db.rawQuery("SELECT valore FROM info WHERE chiave = ?", arrayOf(chiave)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    val numeroFermate: Int get() = DatabaseUtils.queryNumEntries(db, "fermate").toInt()

    fun linea(id: String): Linea? = linee[id]

    fun cercaLinee(testo: String): List<Linea> {
        val t = testo.trim().uppercase()
        if (t.isEmpty()) return emptyList()
        return linee.values
            .filter { it.nome.uppercase().startsWith(t) || it.id.uppercase() == t }
            .sortedWith(compareBy(ordineLinee) { it.nome })
            .take(20)
    }

    private fun Cursor.fermata() = Fermata(getString(0), getString(1), getString(2), getDouble(3), getDouble(4))

    private fun fermateDa(sql: String, argomenti: Array<String>): List<Fermata> =
        db.rawQuery(sql, argomenti).use { c ->
            val lista = ArrayList<Fermata>()
            while (c.moveToNext()) lista.add(c.fermata())
            lista
        }

    fun fermata(id: String): Fermata? =
        fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id = ?", arrayOf(id)).firstOrNull()

    fun fermate(ids: Collection<String>): Map<String, Fermata> {
        val risultato = HashMap<String, Fermata>()
        ids.distinct().chunked(400).forEach { blocco ->
            val segnaposto = blocco.joinToString(",") { "?" }
            fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id IN ($segnaposto)", blocco.toTypedArray())
                .forEach { risultato[it.id] = it }
        }
        return risultato
    }

    fun cercaFermate(testo: String): List<Fermata> {
        val t = testo.trim()
        if (t.isEmpty()) return emptyList()
        val risultati = LinkedHashMap<String, Fermata>()
        if (t.all { it.isDigit() }) {
            fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE codice = ? OR id = ?", arrayOf(t, t))
                .forEach { risultati[it.id] = it }
        }
        val parole = normalizza(t).split(" ").filter { it.isNotEmpty() }
        if (parole.isNotEmpty() && parole.joinToString("").length >= 2) {
            val condizioni = parole.joinToString(" AND ") { "nome_norm LIKE ?" }
            fermateDa(
                "SELECT id, codice, nome, lat, lon FROM fermate WHERE $condizioni ORDER BY nome LIMIT 60",
                parole.map { "%$it%" }.toTypedArray(),
            ).forEach { risultati.putIfAbsent(it.id, it) }
        }
        return risultati.values.toList()
    }

    /** Fermate entro [raggio] metri, dalla piu' vicina. */
    fun fermateVicine(lat: Double, lon: Double, raggio: Double = 700.0): List<Pair<Fermata, Double>> {
        val dLat = raggio / 111_320.0
        val dLon = raggio / (111_320.0 * cos(Math.toRadians(lat)))
        return fermateDa(
            "SELECT id, codice, nome, lat, lon FROM fermate WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString()),
        ).map { it to distanzaMetri(lat, lon, it.lat, it.lon) }
            .filter { it.second <= raggio }
            .sortedBy { it.second }
            .take(40)
    }

    fun lineeAllaFermata(fermataId: String): List<LineaFermata> =
        db.rawQuery(
            "SELECT linea, direzione, capolinea FROM fermata_linee WHERE fermata = ?", arrayOf(fermataId),
        ).use { c ->
            val lista = ArrayList<LineaFermata>()
            while (c.moveToNext()) {
                val linea = linee[c.getString(0)] ?: Linea(c.getString(0), c.getString(0), "", 3, "", "")
                lista.add(LineaFermata(linea, c.getInt(1), bellaScrittura(c.getString(2))))
            }
            lista.sortedWith(compareBy(ordineLinee) { it.linea.nome })
        }

    fun percorsi(lineaId: String): List<Percorso> =
        db.rawQuery(
            "SELECT linea, direzione, capolinea, forma FROM percorsi WHERE linea = ? ORDER BY direzione", arrayOf(lineaId),
        ).use { c ->
            val lista = ArrayList<Percorso>()
            while (c.moveToNext()) {
                lista.add(Percorso(c.getString(0), c.getInt(1), bellaScrittura(c.getString(2)), c.getString(3)))
            }
            lista
        }

    fun fermateDelPercorso(lineaId: String, direzione: Int): List<Fermata> =
        fermateDa(
            "SELECT f.id, f.codice, f.nome, f.lat, f.lon FROM percorso_fermate p JOIN fermate f ON f.id = p.fermata " +
                "WHERE p.linea = ? AND p.direzione = ? ORDER BY p.posizione",
            arrayOf(lineaId, direzione.toString()),
        )

    fun forma(id: String): List<Punto> {
        if (id.isEmpty()) return emptyList()
        val testo = db.rawQuery("SELECT punti FROM forme WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return emptyList()
        return testo.split(';').mapNotNull { coppia ->
            val lat = coppia.substringBefore(',').toDoubleOrNull()
            val lon = coppia.substringAfter(',').toDoubleOrNull()
            if (lat != null && lon != null) Punto(lat, lon) else null
        }
    }

    fun corse(ids: Collection<String>): Map<String, InfoCorsa> {
        val risultato = HashMap<String, InfoCorsa>()
        ids.filter { it.isNotEmpty() }.distinct().chunked(400).forEach { blocco ->
            val segnaposto = blocco.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT id, linea, direzione, capolinea FROM corse WHERE id IN ($segnaposto)", blocco.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    risultato[c.getString(0)] = InfoCorsa(c.getString(1), c.getInt(2), bellaScrittura(c.getString(3)))
                }
            }
        }
        return risultato
    }

    fun chiudi() = db.close()
}
