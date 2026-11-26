package it.palinaroma.dati

/*
 * I nomi che dai alle fermate preferite ("Casa", "Lavoro"): in Home
 * ("Sei vicino a · Lavoro"), nei widget e nella loro scelta. Uguali su
 * Android e iPhone.
 */

/** Il nome, ripulito e accorciato (vuoto = senza nome). */
fun nomeFermataPulito(nome: String): String = nome.trim().replace(Regex("\\s+"), " ").take(24)

object CodificaNomi {
    fun scrivi(nomi: Map<String, String>): String = ScriviJson.testo(nomi.filterValues { it.isNotBlank() })

    fun leggi(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            Json.leggi(json).comeOggetto().mapNotNull { (id, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** Il nome della fermata [id] cambiato in [nome] (vuoto: lo toglie). */
fun conNomeFermata(nomi: Map<String, String>, id: String, nome: String): Map<String, String> {
    val pulito = nomeFermataPulito(nome)
    return if (pulito.isEmpty()) nomi - id else nomi + (id to pulito)
}

/** "Sei vicino a · Lavoro" o "Sei vicino a". */
fun titoloSeiVicino(nome: String?): String = if (nome.isNullOrBlank()) tx("Sei vicino a") else tx("Sei vicino a") + " · " + nome
