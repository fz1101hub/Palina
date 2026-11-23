package it.palinaroma.dati

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File
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

/**
 * Lettura dell'orario gia' trasformato: [db] e' quello di Roma (aggiornabile dal
 * telefono), [reti] quello delle altre reti incluso nell'APK (Cotral, Trenitalia),
 * con le stesse tabelle e i codici con il prefisso della rete (vedi [Rete]).
 */
class Orario(private val db: SQLiteDatabase, private val reti: SQLiteDatabase? = null) {

    private val basi = listOfNotNull(db, reti)

    /** Il database che contiene una fermata, una linea o un tracciato, dal prefisso del codice. */
    private fun baseDi(id: String): SQLiteDatabase = if (reti != null && Rete.di(id) != Rete.ROMA) reti else db

    private val linee: Map<String, Linea> by lazy {
        val mappa = HashMap<String, Linea>()
        for (base in basi) {
            base.rawQuery("SELECT id, nome, descrizione, tipo, colore, colore_testo FROM linee", null).use { c ->
                while (c.moveToNext()) {
                    val l = Linea(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
                    mappa[l.id] = l
                }
            }
        }
        mappa
    }

    /** Ci sono le fermate di Cotral e Trenitalia (l'APK le include). */
    val conAltreReti: Boolean get() = reti != null

    /**
     * Le stazioni di scambio (vedi [StazioneUnita]), per ognuna delle fermate che
     * le compongono: le stazioni dei treni Cotral con le banchine della metro di
     * Roma entro [METRI_SCAMBIO] metri.
     */
    private val scambi: Map<String, StazioneUnita> by lazy {
        val altre = reti ?: return@lazy emptyMap()
        val mappa = HashMap<String, StazioneUnita>()
        val stazioni = fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id LIKE 'F:%'", emptyArray(), altre)
        for (s in stazioni) {
            val metro = vicineIn(db, s.lat, s.lon, METRI_SCAMBIO)
                .filter { (f, _) -> lineeAllaFermata(f.id).any { it.linea.tipo == 1 } }
            if (metro.isEmpty()) continue
            val fermate = listOf(s) + metro.map { it.first }
            val unita = StazioneUnita(
                membri = fermate.map { it.id },
                nome = nomeStazioneUnita(fermate.map { it.nome }),
                linee = lineeStazioneUnita(fermate.flatMap { f -> lineeAllaFermata(f.id).map { it.linea.nome } }),
            )
            fermate.forEach { mappa.putIfAbsent(it.id, unita) }
        }
        mappa
    }

    /** Prepara le stazioni di scambio (da chiamare fuori dal thread dell'interfaccia). */
    fun preparaScambi() {
        scambi.size
    }

    /** Le fermate della stazione di cui [id] fa parte (la stazione Cotral per prima), o solo [id]. */
    fun stazioneUnita(id: String): List<String> = scambi[id]?.membri ?: listOf(id)

    /** La stazione unita di cui fa parte [id], con il suo nome; null se [id] non e' uno scambio. */
    fun fermataUnita(id: String): Fermata? {
        val unita = scambi[id] ?: return null
        return fermata(unita.membri.first())?.copy(nome = unita.nome)
    }

    /** "Metro B/B1 · Metromare" se [id] fa parte di una stazione di scambio. */
    fun lineeStazioneUnita(id: String): String? = scambi[id]?.linee

    /** Le fermate di una stazione di scambio diventano una sola, al posto della prima che compare. */
    private fun unisciScambi(fermate: Collection<Fermata>): List<Fermata> {
        if (scambi.isEmpty()) return fermate.toList()
        val viste = HashSet<String>()
        return fermate.mapNotNull { f ->
            val unita = scambi[f.id] ?: return@mapNotNull f.takeIf { viste.add(it.id) }
            if (viste.add(unita.membri.first())) fermataUnita(f.id) else null
        }
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
        // le "linee" Trenitalia sono categorie di treni (REG, RV): non si cercano
        return linee.values
            .filter { Rete.di(it.id) != Rete.TRENITALIA }
            .filter { it.nome.uppercase().startsWith(t) || it.id.uppercase() == t }
            .sortedWith(compareBy(ordineLinee) { it.nome })
            .take(20)
    }

    private fun Cursor.fermata() = Fermata(getString(0), getString(1), getString(2), getDouble(3), getDouble(4))

    private fun fermateDa(sql: String, argomenti: Array<String>, base: SQLiteDatabase = db): List<Fermata> =
        base.rawQuery(sql, argomenti).use { c ->
            val lista = ArrayList<Fermata>()
            while (c.moveToNext()) lista.add(c.fermata())
            lista
        }

    fun fermata(id: String): Fermata? =
        fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id = ?", arrayOf(id), baseDi(id)).firstOrNull()

    fun fermate(ids: Collection<String>): Map<String, Fermata> {
        val risultato = HashMap<String, Fermata>()
        ids.distinct().groupBy { baseDi(it) }.forEach { (base, elenco) ->
            elenco.chunked(400).forEach { blocco ->
                val segnaposto = blocco.joinToString(",") { "?" }
                fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE id IN ($segnaposto)", blocco.toTypedArray(), base)
                    .forEach { risultato[it.id] = it }
            }
        }
        return risultato
    }

    /** Prima le fermate di Roma, poi quelle delle altre reti. */
    fun cercaFermate(testo: String): List<Fermata> {
        val t = testo.trim()
        if (t.isEmpty()) return emptyList()
        val risultati = LinkedHashMap<String, Fermata>()
        for (base in basi) {
            if (t.all { it.isDigit() }) {
                fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE codice = ? OR id = ?", arrayOf(t, t), base)
                    .forEach { risultati[it.id] = it }
            }
            val parole = normalizza(t).split(" ").filter { it.isNotEmpty() }
            if (parole.isNotEmpty() && parole.joinToString("").length >= 2) {
                val condizioni = parole.joinToString(" AND ") { "nome_norm LIKE ?" }
                val frase = parole.joinToString(" ")
                // prima i nomi che cominciano con il testo cercato ("Termini..."), poi quelli
                // in cui e' una parola intera ("Siculiana/Termini Imerese"), poi gli altri
                fermateDa(
                    "SELECT id, codice, nome, lat, lon FROM fermate WHERE $condizioni " +
                        "ORDER BY CASE WHEN nome_norm LIKE ? THEN 0 WHEN nome_norm LIKE ? THEN 1 ELSE 2 END, " +
                        "length(nome), nome LIMIT 60",
                    (parole.map { "%$it%" } + listOf("$frase%", "% $frase%")).toTypedArray(),
                    base,
                ).forEach { risultati.putIfAbsent(it.id, it) }
            }
        }
        return unisciScambi(risultati.values)
    }

    /** Fermate entro [raggio] metri, dalla piu' vicina. */
    fun fermateVicine(lat: Double, lon: Double, raggio: Double = 700.0): List<Pair<Fermata, Double>> =
        basi.flatMap { vicineIn(it, lat, lon, raggio) }.sortedBy { it.second }.take(40)

    /** Come [fermateVicine], ma le fermate di una stazione di scambio diventano una sola. */
    fun fermateVicineUnite(lat: Double, lon: Double, raggio: Double = 700.0): List<Pair<Fermata, Double>> {
        val vicine = fermateVicine(lat, lon, raggio)
        val distanze = vicine.associate { it.first.id to it.second }
        return unisciScambi(vicine.map { it.first }).map { f ->
            f to (scambi[f.id]?.membri?.mapNotNull { distanze[it] }?.minOrNull() ?: distanze[f.id] ?: 0.0)
        }
    }

    private fun vicineIn(base: SQLiteDatabase, lat: Double, lon: Double, raggio: Double): List<Pair<Fermata, Double>> {
        val dLat = raggio / 111_320.0
        val dLon = raggio / (111_320.0 * cos(Math.toRadians(lat)))
        val limiti = arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())
        return fermateDa("SELECT id, codice, nome, lat, lon FROM fermate WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", limiti, base)
            .map { it to distanzaMetri(lat, lon, it.lat, it.lon) }
            .filter { it.second <= raggio }
            .sortedBy { it.second }
    }

    fun lineeAllaFermata(fermataId: String): List<LineaFermata> =
        baseDi(fermataId).rawQuery(
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
        baseDi(lineaId).rawQuery(
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
            baseDi(lineaId),
        )

    fun forma(id: String): List<Punto> {
        if (id.isEmpty()) return emptyList()
        val testo = baseDi(id).rawQuery("SELECT punti FROM forme WHERE id = ?", arrayOf(id)).use { c ->
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

    fun chiudi() {
        db.close()
        reti?.close()
    }
}
