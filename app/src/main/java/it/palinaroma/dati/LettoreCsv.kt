package it.palinaroma.dati

import java.io.BufferedReader
import java.io.Closeable

/** Lettore CSV per i file GTFS: veloce sulle righe senza virgolette. */
class LettoreCsv(private val lettore: BufferedReader) : Closeable {
    val intestazione: List<String>

    override fun close() = lettore.close()

    init {
        val prima = lettore.readLine() ?: ""
        intestazione = dividi(prima.removePrefix("\uFEFF")).map { it.trim() }
    }

    fun indice(nome: String): Int = intestazione.indexOf(nome)

    /** La prossima riga non vuota, oppure null alla fine del file. */
    fun prossima(): List<String>? {
        while (true) {
            val riga = lettore.readLine() ?: return null
            if (riga.isNotBlank()) return dividi(riga)
        }
    }

    companion object {
        fun dividi(riga: String): List<String> {
            if (riga.indexOf('"') < 0) return riga.split(',')
            val campi = ArrayList<String>()
            val campo = StringBuilder()
            var tra = false
            var i = 0
            while (i < riga.length) {
                val c = riga[i]
                when {
                    tra && c == '"' && i + 1 < riga.length && riga[i + 1] == '"' -> {
                        campo.append('"'); i++
                    }
                    c == '"' -> tra = !tra
                    c == ',' && !tra -> {
                        campi.add(campo.toString()); campo.setLength(0)
                    }
                    else -> campo.append(c)
                }
                i++
            }
            campi.add(campo.toString())
            return campi
        }
    }
}

/** Valore della colonna, stringa vuota se manca. */
fun List<String>.campo(indice: Int): String =
    if (indice in indices) this[indice].trim() else ""
