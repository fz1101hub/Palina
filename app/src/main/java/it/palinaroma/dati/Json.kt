package it.palinaroma.dati

/**
 * Lettore JSON minimo, senza librerie: oggetti -> Map, array -> List,
 * numeri -> Double, stringhe, true/false e null.
 */
object Json {
    fun leggi(testo: String): Any? {
        val lettore = Lettore(testo)
        val valore = lettore.valore()
        lettore.spazi()
        if (lettore.i < testo.length) lettore.errore("testo in più dopo il valore")
        return valore
    }

    /** Oltre questi livelli annidati il testo si rifiuta, invece di esaurire la memoria della pila. */
    private const val LIVELLI_MASSIMI = 200

    private class Lettore(val s: String) {
        var i = 0
        private var livello = 0

        fun errore(motivo: String): Nothing =
            throw IllegalArgumentException("JSON non valido: $motivo (posizione $i)")

        fun spazi() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun valore(): Any? {
            spazi()
            if (i >= s.length) errore("fine inattesa")
            return when (val c = s[i]) {
                '{' -> annidato { oggetto() }
                '[' -> annidato { array() }
                '"' -> stringa()
                't' -> parola("true", true)
                'f' -> parola("false", false)
                'n' -> parola("null", null)
                else -> if (c == '-' || c in '0'..'9') numero() else errore("carattere '$c'")
            }
        }

        private inline fun <T> annidato(leggi: () -> T): T {
            if (++livello > LIVELLI_MASSIMI) errore("troppi livelli annidati")
            return leggi().also { livello-- }
        }

        private fun parola(attesa: String, valore: Any?): Any? {
            if (!s.startsWith(attesa, i)) errore("atteso $attesa")
            i += attesa.length
            return valore
        }

        private fun oggetto(): Map<String, Any?> {
            i++
            val mappa = LinkedHashMap<String, Any?>()
            spazi()
            if (i < s.length && s[i] == '}') {
                i++
                return mappa
            }
            while (true) {
                spazi()
                if (i >= s.length || s[i] != '"') errore("attesa una chiave")
                val chiave = stringa()
                spazi()
                if (i >= s.length || s[i] != ':') errore("attesi i due punti")
                i++
                mappa[chiave] = valore()
                spazi()
                if (i >= s.length) errore("oggetto non chiuso")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return mappa
                    else -> errore("attesa una virgola o }")
                }
            }
        }

        private fun array(): List<Any?> {
            i++
            val lista = ArrayList<Any?>()
            spazi()
            if (i < s.length && s[i] == ']') {
                i++
                return lista
            }
            while (true) {
                lista.add(valore())
                spazi()
                if (i >= s.length) errore("array non chiuso")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return lista
                    else -> errore("attesa una virgola o ]")
                }
            }
        }

        private fun stringa(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                // copia in un colpo i tratti senza virgolette ne' escape
                var fine = i
                while (fine < s.length && s[fine] != '"' && s[fine] != '\\') fine++
                if (fine >= s.length) errore("stringa non chiusa")
                sb.append(s, i, fine)
                i = fine
                if (s[i] == '"') {
                    i++
                    return sb.toString()
                }
                i++ // la barra
                if (i >= s.length) errore("escape incompleto")
                when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) errore("escape \\u incompleto")
                        val codice = s.substring(i, i + 4).toIntOrNull(16) ?: errore("escape \\u non valido")
                        sb.append(codice.toChar())
                        i += 4
                    }
                    else -> errore("escape \\$e")
                }
            }
        }

        private fun numero(): Double {
            val inizio = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(inizio, i).toDoubleOrNull() ?: errore("numero non valido")
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.comeOggetto(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

fun Map<String, Any?>.testo(chiave: String): String? = (this[chiave] as? String)?.takeIf { it.isNotBlank() }

fun Map<String, Any?>.numero(chiave: String): Double? = (this[chiave] as? Number)?.toDouble()

fun Map<String, Any?>.vero(chiave: String): Boolean = this[chiave] == true

fun Map<String, Any?>.oggetto(chiave: String): Map<String, Any?>? = (this[chiave] as? Map<*, *>)?.comeOggetto()

fun Map<String, Any?>.oggetti(chiave: String): List<Map<String, Any?>> =
    (this[chiave] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.comeOggetto() } ?: emptyList()
