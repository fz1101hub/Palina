package it.palinaroma.dati

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.NativeSQLiteDriver
import platform.Foundation.NSRecursiveLock

/**
 * Un file SQLite su iPhone (l'orario incluso nell'app) dietro a [BaseDati].
 * La connessione non si puo' usare da due thread insieme (tabellone, widget,
 * avvisi lavorano in parallelo): una domanda alla volta, come fa Android.
 */
class BaseIos(percorso: String) : BaseDati {
    private val connessione: SQLiteConnection = NativeSQLiteDriver().open(percorso)
    private val blocco = NSRecursiveLock()

    override fun <T> elenco(sql: String, argomenti: List<String>, leggi: (Riga) -> T): List<T> {
        blocco.lock()
        try {
            val istruzione = connessione.prepare(sql)
            try {
                argomenti.forEachIndexed { i, a -> istruzione.bindText(i + 1, a) }
                val riga = RigaIstruzione(istruzione)
                val lista = ArrayList<T>()
                while (istruzione.step()) lista.add(leggi(riga))
                return lista
            } finally {
                istruzione.close()
            }
        } finally {
            blocco.unlock()
        }
    }

    override fun chiudi() {
        blocco.lock()
        try {
            connessione.close()
        } finally {
            blocco.unlock()
        }
    }
}

private class RigaIstruzione(private val s: SQLiteStatement) : Riga {
    override fun testo(colonna: Int): String = if (s.isNull(colonna)) "" else s.getText(colonna)
    override fun intero(colonna: Int): Int = s.getLong(colonna).toInt()
    override fun decimale(colonna: Int): Double = s.getDouble(colonna)
}

/** L'orario dai file SQLite inclusi nell'app per iPhone. */
fun orarioDaFile(roma: String, reti: String?): Orario =
    Orario(BaseIos(roma), reti?.let { BaseIos(it) }).also { it.preparaScambi() }
