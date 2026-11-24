package it.palinaroma.dati

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Il database SQLite di Android dietro a [BaseDati]. */
class BaseAndroid(val db: SQLiteDatabase) : BaseDati {
    override fun <T> elenco(sql: String, argomenti: List<String>, leggi: (Riga) -> T): List<T> =
        db.rawQuery(sql, argomenti.toTypedArray()).use { c ->
            val riga = RigaCursore(c)
            val lista = ArrayList<T>()
            while (c.moveToNext()) lista.add(leggi(riga))
            lista
        }

    override fun chiudi() = db.close()
}

private class RigaCursore(private val c: Cursor) : Riga {
    override fun testo(colonna: Int): String = c.getString(colonna) ?: ""
    override fun intero(colonna: Int): Int = c.getInt(colonna)
    override fun decimale(colonna: Int): Double = c.getDouble(colonna)
}

/** L'orario dai database SQLite di Android (Roma e, se c'e', le altre reti). */
fun Orario(db: SQLiteDatabase, reti: SQLiteDatabase? = null): Orario =
    Orario(BaseAndroid(db), reti?.let { BaseAndroid(it) })
