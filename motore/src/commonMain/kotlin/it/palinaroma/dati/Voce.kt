package it.palinaroma.dati

import kotlinx.coroutines.CancellationException

/*
 * "Quanto manca al 64 a Lavoro?" con Siri e con l'assistente di Android: la
 * risposta a voce (la stessa frase dei lettori di schermo), dal motore.
 */

private fun normalizzaNome(testo: String): String =
    testo.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/**
 * La fermata detta a voce ([testo]): tra le preferite, per il nome che le hai
 * dato ("Lavoro") o per il suo nome ("piazza Venezia"). Null se non si trova.
 */
fun fermataDaNome(testo: String, preferiti: Collection<String>, nomi: Map<String, String>, orario: Orario?): String? {
    val cercato = normalizzaNome(testo).removePrefix("la ").removePrefix("il ").trim()
    if (cercato.isEmpty()) return null
    nomi.entries.firstOrNull { normalizzaNome(it.value) == cercato }?.let { return it.key }
    val conNome = preferiti.mapNotNull { id -> orario?.fermata(id)?.let { id to normalizzaNome(it.nome) } }
    conNome.firstOrNull { it.second == cercato }?.let { return it.first }
    nomi.entries.firstOrNull { normalizzaNome(it.value).contains(cercato) || cercato.contains(normalizzaNome(it.value)) }?.let { return it.key }
    return conNome.firstOrNull { it.second.contains(cercato) || cercato.contains(it.second) }?.first
}

/**
 * La risposta a voce per la fermata [fermataId] (chiamata [nome], quello dato o
 * il suo): le prime [quante] linee, prima quelle fissate; con [linea] solo quella.
 */
suspend fun rispostaQuantoManca(
    orario: Orario,
    fermataId: String,
    nome: String?,
    fissate: Set<String> = emptySet(),
    linea: String? = null,
    quante: Int = 3,
): String {
    val fermata = orario.fermata(fermataId) ?: return tx("Non trovo questa fermata.")
    val chi = nome?.takeIf { it.isNotBlank() } ?: fermata.nome
    return try {
        val arrivi = CaricatoreArrivi().aggiorna(orario, listOf(fermata), emptyList(), ArriviFermate())
        val adesso = adessoMs() / 1000
        val fisse = lineeFissate(fermataId, fissate)
        val tutte = arrivi.perFermata[fermataId].orEmpty().filter { it.passaggi.isNotEmpty() }
            .filter { linea == null || it.nomeLinea.equals(linea, ignoreCase = true) }
            .sortedWith(compareBy({ it.lineaId !in fisse }, { it.passaggi.first().orario }))
        when {
            arrivi.errore && tutte.isEmpty() -> tx("Non riesco a leggere i tempi di {0}. Controlla la connessione.", daLeggere(chi))
            tutte.isEmpty() && linea != null -> tx("A {0} il {1} non passa nei prossimi 90 minuti.", daLeggere(chi), linea)
            tutte.isEmpty() -> tx("A {0} non passa niente nei prossimi 90 minuti.", daLeggere(chi))
            else -> daLeggere(chi) + ". " + tutte.take(quante).joinToString(" ") { fraseArrivo(it, adesso) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        tx("Non riesco a leggere i tempi di {0}. Controlla la connessione.", daLeggere(chi))
    }
}
