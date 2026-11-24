package it.palinaroma.dati

/** Una riga del risultato di una query, per indice di colonna (da 0). */
interface Riga {
    fun testo(colonna: Int): String
    fun intero(colonna: Int): Int
    fun decimale(colonna: Int): Double
}

/**
 * Un database SQLite in sola lettura: su Android quello del sistema
 * (android.database), su iPhone la libreria SQLite di iOS. L'orario e' lo
 * stesso file in tutti e due.
 */
interface BaseDati {
    /** Esegue [sql] con gli [argomenti] (tutti testo, come rawQuery) e legge ogni riga con [leggi]. */
    fun <T> elenco(sql: String, argomenti: List<String>, leggi: (Riga) -> T): List<T>
    fun chiudi()
}
