package it.palinaroma.dati

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File

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
        SCHEMA_ORARIO.forEach { db.execSQL(it) }
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
        insForma.bindString(2, testoForma(punti))
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
        INDICI_ORARIO.forEach { db.execSQL(it) }
        db.close()
    }

    fun annulla() {
        if (db.inTransaction()) db.endTransaction()
        db.close()
    }
}

/** Il tracciato salvato come testo: "lat,lon;lat,lon;...". */
internal fun testoForma(punti: List<Punto>): String =
    punti.joinToString(";") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lon) }
