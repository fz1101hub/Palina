package it.palinaroma.dati

/*
 * Le lingue dell'app, uguali su Android, Wear OS, iPhone e Apple Watch.
 *
 * I testi si scrivono in italiano dentro tx("...") o txn("...", "...", n): il
 * testo italiano e' la chiave, e le traduzioni sono nei cataloghi (Inglese.kt,
 * Francese.kt, ...). I valori si mettono con {0}, {1}, ... perche' l'ordine
 * delle parole cambia da lingua a lingua. Se due testi italiani uguali vanno
 * tradotti in modo diverso (un plurale uguale al singolare, "A" come arrivo)
 * la chiave ha un contesto dopo "§", che in italiano non si vede:
 * txn("{0} bus", "{0} bus§molti", n). TraduzioniTest controlla che ogni testo
 * abbia tutte le traduzioni, con gli stessi segnaposto.
 */

enum class Lingua(val codice: String, val nome: String) {
    IT("it", "Italiano"),
    EN("en", "English"),
    FR("fr", "Français"),
    ES("es", "Español"),
    DE("de", "Deutsch"),
    PT("pt", "Português"),
}

object Lingue {
    /** La lingua dei testi in questo momento. */
    var attuale: Lingua = Lingua.IT
        private set

    /** La lingua scelta nelle impostazioni (null: quella del telefono). */
    var scelta: Lingua? = null
        private set

    /**
     * Con la lingua del telefono ([sistema], come "en-GB" o "pt_BR") e quella
     * scelta nelle impostazioni ([codiceScelto], null o "" per seguire il
     * telefono). Una lingua che l'app non ha diventa inglese.
     */
    fun imposta(sistema: String?, codiceScelto: String?) {
        scelta = codiceScelto?.takeIf { it.isNotBlank() }?.let { daCodice(it) }
        daCodiceSistema = sistema?.let { daCodice(it) }
        attuale = scelta ?: sistema?.let { daCodice(it) } ?: Lingua.EN
    }

    fun daCodice(codice: String): Lingua? {
        val c = codice.trim().lowercase()
        return Lingua.entries.firstOrNull { c == it.codice || c.startsWith(it.codice + "-") || c.startsWith(it.codice + "_") }
    }

    /** La lingua del telefono, se e' una di quelle dell'app (per la voce "Come il telefono"). */
    var daCodiceSistema: Lingua? = null
        private set

    /** La lingua da chiedere ai servizi esterni (Transitous): la nostra, o l'inglese. */
    val perServizi: String get() = attuale.codice
}

private fun catalogo(lingua: Lingua): Map<String, String>? = when (lingua) {
    Lingua.IT -> null
    Lingua.EN -> INGLESE
    Lingua.FR -> FRANCESE
    Lingua.ES -> SPAGNOLO
    Lingua.DE -> TEDESCO
    Lingua.PT -> PORTOGHESE
}

/** La traduzione di [italiano] in [lingua], o null se manca (per le prove). */
fun traduzione(lingua: Lingua, italiano: String): String? = if (lingua == Lingua.IT) italiano else catalogo(lingua)?.get(italiano)

/** Tutti i testi italiani che hanno una traduzione in [lingua] (per le prove). */
fun testiTradotti(lingua: Lingua): Set<String> = catalogo(lingua)?.keys ?: emptySet()

/** Il testo [italiano] nella lingua dell'app, con {0}, {1}, ... al posto dei valori. */
fun tx(italiano: String, vararg valori: Any?): String {
    val testo = catalogo(Lingue.attuale)?.get(italiano) ?: italiano.substringBefore('§')
    return if (valori.isEmpty()) testo else riempi(testo, valori)
}

/**
 * Singolare ([uno]) o plurale ([molti]) secondo [n], che va in {0}; gli altri
 * valori in {1}, {2}, ... In francese anche 0 vuole il singolare.
 */
fun txn(uno: String, molti: String, n: Int, vararg altri: Any?): String {
    val singolare = if (Lingue.attuale == Lingua.FR) n == 0 || n == 1 else n == 1
    val testo = catalogo(Lingue.attuale)?.get(if (singolare) uno else molti) ?: (if (n == 1) uno else molti).substringBefore('§')
    return riempi(testo, arrayOf<Any?>(n, *altri))
}

private fun riempi(testo: String, valori: Array<out Any?>): String {
    if ('{' !in testo) return testo
    val sb = StringBuilder(testo.length + 16)
    var i = 0
    while (i < testo.length) {
        val c = testo[i]
        if (c == '{') {
            val fine = testo.indexOf('}', i)
            val indice = if (fine > i) testo.substring(i + 1, fine).toIntOrNull() else null
            if (indice != null && indice in valori.indices) {
                sb.append(valori[indice]?.toString() ?: "")
                i = fine + 1
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Il separatore dei decimali: la virgola, il punto in inglese. */
fun separatoreDecimale(): Char = if (Lingue.attuale == Lingua.EN) '.' else ','
