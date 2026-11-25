package it.palinaroma.dati

/*
 * I tragitti salvati ("Lavoro → Casa") con "esci tra", l'ordine dei
 * risultati (meno cambi, meno a piedi) e il testo per condividere un
 * tragitto: uguali su Android e iPhone.
 */

/** Un tragitto salvato: da dove a dove, con un nome. La partenza puo' essere "La mia posizione". */
data class TragittoSalvato(val id: String, val nome: String, val partenza: Luogo, val arrivo: Luogo)

fun luogoInJson(l: Luogo): Map<String, Any?> =
    mapOf("nome" to l.nome, "dettaglio" to l.dettaglio, "lat" to l.lat.takeIf { !it.isNaN() }, "lon" to l.lon.takeIf { !it.isNaN() }, "tipo" to l.tipo.name)

fun luogoDaJson(o: Map<String, Any?>): Luogo? {
    val tipo = runCatching { TipoLuogo.valueOf(o["tipo"] as? String ?: "") }.getOrNull() ?: return null
    if (tipo == TipoLuogo.POSIZIONE) return Luogo.posizione()
    val lat = o.numero("lat") ?: return null
    val lon = o.numero("lon") ?: return null
    return Luogo(o["nome"] as? String ?: return null, o["dettaglio"] as? String ?: "", lat, lon, tipo)
}

object CodificaTragittiSalvati {
    fun scrivi(elenco: List<TragittoSalvato>): String = ScriviJson.testo(
        elenco.map { mapOf("id" to it.id, "nome" to it.nome, "da" to luogoInJson(it.partenza), "a" to luogoInJson(it.arrivo)) },
    )

    fun leggi(json: String?): List<TragittoSalvato> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            (Json.leggi(json) as? List<*>).orEmpty().mapNotNull { v ->
                val o = v.comeOggetto()
                val da = o.oggetto("da")?.let { luogoDaJson(it) } ?: return@mapNotNull null
                val a = o.oggetto("a")?.let { luogoDaJson(it) } ?: return@mapNotNull null
                TragittoSalvato(o.testo("id") ?: return@mapNotNull null, o.testo("nome") ?: nomeTragittoSalvato(da, a), da, a)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** "Lavoro → Casa": i nomi dei due luoghi, accorciati. */
fun nomeTragittoSalvato(partenza: Luogo, arrivo: Luogo): String {
    fun breve(l: Luogo) = if (l.tipo == TipoLuogo.POSIZIONE) tx("Qui") else l.nome.substringBefore(",").trim().take(28)
    return breve(partenza) + " → " + breve(arrivo)
}

private fun stessoLuogo(a: Luogo, b: Luogo): Boolean =
    a.tipo == b.tipo && (a.tipo == TipoLuogo.POSIZIONE || (a.haCoordinate && b.haCoordinate && distanzaMetri(a.lat, a.lon, b.lat, b.lon) < 30))

/** Il tragitto salvato da [partenza] ad [arrivo], se c'e'. */
fun tragittoGiaSalvato(elenco: List<TragittoSalvato>, partenza: Luogo?, arrivo: Luogo?): TragittoSalvato? {
    if (partenza == null || arrivo == null) return null
    return elenco.firstOrNull { stessoLuogo(it.partenza, partenza) && stessoLuogo(it.arrivo, arrivo) }
}

/** Aggiunge (in cima) il tragitto; se c'era gia' ne cambia solo il nome. Al massimo 8. */
fun aggiungiTragittoSalvato(elenco: List<TragittoSalvato>, partenza: Luogo, arrivo: Luogo, nome: String, id: String): List<TragittoSalvato> {
    val nomeFinale = nome.trim().ifEmpty { nomeTragittoSalvato(partenza, arrivo) }.take(40)
    val esistente = tragittoGiaSalvato(elenco, partenza, arrivo)
    if (esistente != null) return elenco.map { if (it.id == esistente.id) it.copy(nome = nomeFinale) else it }
    return (listOf(TragittoSalvato(id, nomeFinale, partenza, arrivo)) + elenco).take(8)
}

fun rimuoviTragittoSalvato(elenco: List<TragittoSalvato>, id: String): List<TragittoSalvato> = elenco.filter { it.id != id }

/** Il ritorno: stessi luoghi al contrario (solo se la partenza non e' "La mia posizione"). */
fun tragittoDiRitorno(t: TragittoSalvato): Pair<Luogo, Luogo>? =
    if (t.partenza.tipo == TipoLuogo.POSIZIONE) null else t.arrivo to t.partenza

// --- le prossime partenze di un tragitto salvato

/** Una partenza: quando uscire, il primo mezzo e quando si arriva (millisecondi). */
data class PartenzaSalvata(
    val partenza: Long,
    val arrivo: Long,
    /** Il primo mezzo ("60", "Metro B"), vuoto se e' tutto a piedi. */
    val linea: String,
    val colore: Long,
    /** Quando parte il primo mezzo. */
    val oraMezzo: Long,
    /** Dove si prende il primo mezzo. */
    val da: String,
    val tempoReale: Boolean,
    /** I mezzi in ordine ("64", "Metro B"), i cambi e la durata porta a porta (secondi). */
    val linee: List<String> = emptyList(),
    val cambi: Int = 0,
    val durata: Int = 0,
)

/** Le prossime partenze di un tragitto salvato, o perche' non si sanno. */
data class PartenzeTragitto(val id: String, val calcolato: Long, val partenze: List<PartenzaSalvata>, val errore: String?)

fun partenzeDaTragitti(id: String, tragitti: List<Tragitto>, adessoMs: Long): List<PartenzaSalvata> =
    tragitti.filter { it.partenza >= adessoMs - 60_000 }
        .sortedBy { it.partenza }
        .map { t ->
            val primo = t.mezzi.firstOrNull()
            PartenzaSalvata(
                partenza = t.partenza,
                arrivo = t.arrivo,
                linea = primo?.let { it.linea.ifEmpty { it.nomeMezzo } }.orEmpty(),
                colore = primo?.let { coloreTratto(it) } ?: Colori.PORPORA,
                oraMezzo = primo?.partenza ?: t.partenza,
                da = primo?.da?.nome.orEmpty(),
                tempoReale = primo?.tempoReale == true,
                linee = t.mezzi.map { it.linea.ifEmpty { it.nomeMezzo } },
                cambi = t.cambi,
                durata = t.durata,
            )
        }

/**
 * Le prossime partenze del tragitto [t] con Transitous; [posizione] serve se
 * si parte da "La mia posizione".
 */
suspend fun partenzeTragittoSalvato(t: TragittoSalvato, posizione: Punto?): PartenzeTragitto {
    val partenza = if (t.partenza.tipo == TipoLuogo.POSIZIONE) {
        posizione?.let { Luogo(tx("La mia posizione"), "", it.lat, it.lon, TipoLuogo.POSIZIONE) }
    } else {
        t.partenza
    }
    val esito = cercaTragitti(partenza, t.arrivo, null, false)
    val adesso = adessoMs()
    val partenze = partenzeDaTragitti(t.id, esito.tragitti, adesso).ifEmpty {
        esito.soloAPiedi?.let { partenzeDaTragitti(t.id, listOf(it), adesso) }.orEmpty()
    }
    return PartenzeTragitto(t.id, adesso, partenze, if (partenze.isEmpty()) esito.errore ?: tx("Nessuna partenza trovata.") else null)
}

object CodificaPartenze {
    fun inJson(p: PartenzeTragitto): Map<String, Any?> = mapOf(
        "id" to p.id, "calcolato" to p.calcolato, "errore" to p.errore,
        "partenze" to p.partenze.map {
            mapOf(
                "partenza" to it.partenza, "arrivo" to it.arrivo, "linea" to it.linea, "colore" to it.colore,
                "oraMezzo" to it.oraMezzo, "da" to it.da, "tempoReale" to it.tempoReale,
                "linee" to it.linee, "cambi" to it.cambi, "durata" to it.durata,
            )
        },
    )

    fun daJson(o: Map<String, Any?>?): PartenzeTragitto? {
        if (o == null) return null
        return PartenzeTragitto(
            id = o.testo("id") ?: return null,
            calcolato = o.numero("calcolato")?.toLong() ?: 0L,
            partenze = o.oggetti("partenze").mapNotNull {
                PartenzaSalvata(
                    partenza = it.numero("partenza")?.toLong() ?: return@mapNotNull null,
                    arrivo = it.numero("arrivo")?.toLong() ?: return@mapNotNull null,
                    linea = it.testo("linea").orEmpty(),
                    colore = it.numero("colore")?.toLong() ?: Colori.PORPORA,
                    oraMezzo = it.numero("oraMezzo")?.toLong() ?: 0L,
                    da = it.testo("da").orEmpty(),
                    tempoReale = it.vero("tempoReale"),
                    linee = (it["linee"] as? List<*>).orEmpty().mapNotNull { l -> l as? String },
                    cambi = it.numero("cambi")?.toInt() ?: 0,
                    durata = it.numero("durata")?.toInt() ?: 0,
                )
            },
            errore = o.testo("errore"),
        )
    }
}

/** La carta di un tragitto salvato: "Lavoro → Casa · ESCI TRA 4 min · 60 alle 18:41 · arrivi 19:05". */
data class CartaSalvato(
    val id: String,
    val nome: String,
    /** "ESCI TRA", "ESCI ORA" o vuoto (quando non si sa). */
    val esci: String,
    val numero: String,
    val unita: String,
    /** "60 alle 18:41 · arrivi 19:05", o il messaggio ("Calcolo…", l'errore). */
    val sotto: String,
    val linea: String,
    val colore: Long,
    /** La partenza dopo: "oppure esci alle 18:53". */
    val poi: String?,
    val perVoce: String,
    /** Il viaggio: "64 → Metro B · 1 cambio · 32 min" (vuoto quando non si sa). */
    val viaggio: String = "",
)

/** "64 → Metro B · 1 cambio · 32 min": i mezzi, i cambi e il tempo di percorrenza stimato. */
fun testoViaggio(p: PartenzaSalvata): String {
    val durata = (if (p.durata > 0) p.durata else ((p.arrivo - p.partenza) / 1000).toInt()).coerceAtLeast(60)
    val mezzi = if (p.linee.isEmpty()) tx("a piedi") else p.linee.joinToString(" → ")
    val cambi = when {
        p.linee.isEmpty() -> null
        p.cambi == 0 -> tx("nessun cambio")
        else -> txn("{0} cambio", "{0} cambi", p.cambi)
    }
    return listOfNotNull(mezzi, cambi, testoDurata(durata)).joinToString(" · ")
}

fun cartaTragittoSalvato(t: TragittoSalvato, partenze: PartenzeTragitto?, adessoMs: Long): CartaSalvato {
    val vuota = CartaSalvato(t.id, t.nome, "", "", "", "", "", Colori.PORPORA, null, t.nome)
    if (partenze == null) return vuota.copy(sotto = tx("Calcolo le partenze…"), perVoce = t.nome + ". " + tx("Calcolo le partenze…"))
    val prossime = partenze.partenze.filter { it.partenza >= adessoMs - 30_000 }
    val p = prossime.firstOrNull()
        ?: return vuota.copy(
            sotto = partenze.errore ?: tx("Tocca per cercare le prossime partenze."),
            perVoce = t.nome + ". " + (partenze.errore ?: tx("Tocca per cercare le prossime partenze.")),
        )
    val minuti = ((p.partenza - adessoMs) / 60_000).toInt()
    val sotto = if (p.linea.isEmpty()) {
        tx("A piedi · arrivi {0}", oraARoma(p.arrivo))
    } else {
        tx("{0} alle {1} · arrivi {2}", p.linea, oraARoma(p.oraMezzo), oraARoma(p.arrivo))
    }
    val ora = minuti < 1
    val viaggio = testoViaggio(p)
    val poi = prossime.getOrNull(1)?.let { tx("oppure esci alle {0}", oraARoma(it.partenza)) }
    return CartaSalvato(
        id = t.id,
        nome = t.nome,
        esci = if (ora) tx("ESCI ORA") else tx("ESCI TRA"),
        numero = if (ora) "" else "$minuti",
        unita = if (ora) "" else "min",
        sotto = sotto,
        linea = p.linea,
        colore = p.colore,
        poi = poi,
        perVoce = t.nome + ". " + (if (ora) tx("Esci ora.") else tx("Esci tra {0}.", txn("{0} minuto", "{0} minuti", minuti))) + " " +
            sotto + ". " + viaggio + ".",
        viaggio = viaggio,
    )
}

/** Il tragitto salvato da mostrare nel widget: quello che parte piu' vicino a dove sei, o il primo. */
fun tragittoPerWidget(elenco: List<TragittoSalvato>, posizione: Punto?): TragittoSalvato? {
    if (elenco.isEmpty()) return null
    if (posizione == null) return elenco.first()
    return elenco.filter { it.partenza.tipo != TipoLuogo.POSIZIONE && it.partenza.haCoordinate }
        .map { it to distanzaMetri(posizione.lat, posizione.lon, it.partenza.lat, it.partenza.lon) }
        .filter { it.second < 1500 }
        .minByOrNull { it.second }?.first
        ?: elenco.firstOrNull { it.partenza.tipo == TipoLuogo.POSIZIONE }
        ?: elenco.first()
}

// --- l'ordine dei risultati e la condivisione

/** Gli ordini dei risultati: prima le partenze piu' vicine, meno cambi, meno strada a piedi. */
fun ordiniTragitti(): List<Pair<String, String>> =
    listOf("" to tx("Prima"), "cambi" to tx("Meno cambi"), "piedi" to tx("Meno a piedi"))

fun ordinaTragitti(tragitti: List<Tragitto>, ordine: String): List<Tragitto> = when (ordine) {
    "cambi" -> tragitti.sortedWith(compareBy({ it.cambi }, { it.arrivo }, { it.partenza }))
    "piedi" -> tragitti.sortedWith(compareBy({ it.minutiAPiedi }, { it.arrivo }, { it.partenza }))
    else -> tragitti.sortedBy { it.partenza }
}

/** "parti tra 4 min", "parti ora", o null se manca piu' di un'ora. */
fun testoPartiTra(t: Tragitto, adessoMs: Long): String? {
    val minuti = ((t.partenza - adessoMs) / 60_000).toInt()
    return when {
        minuti < 0 -> null
        minuti < 1 -> tx("parti ora")
        minuti < 60 -> tx("parti tra {0} min", minuti)
        else -> null
    }
}

/** Il tragitto in testo semplice, da mandare con un messaggio. */
fun testoCondivisione(t: Tragitto, da: String, a: String): String = buildString {
    appendLine("Palina Roma · $da → $a")
    val cambi = if (t.cambi == 0) tx("nessun cambio") else txn("{0} cambio", "{0} cambi", t.cambi)
    appendLine("${giornoEOra(t.partenza)} – ${oraARoma(t.arrivo)} · ${testoDurata(t.durata)} · $cambi")
    for (tr in t.tratti) {
        if (tr.aPiedi) {
            appendLine("• " + tx("A piedi {0} fino a {1}", testoDurata(tr.durata), tr.a.nome))
        } else {
            val linea = listOf(tr.nomeMezzo, tr.linea).filter { it.isNotBlank() }.distinct().joinToString(" ")
            appendLine(
                "• " + tx("{0} da {1} ({2}) a {3} ({4})", linea, tr.da.nome, oraARoma(tr.partenza), tr.a.nome, oraARoma(tr.arrivo)) +
                    (if (tr.direzione.isNotBlank()) " · " + tx("verso {0}", tr.direzione) else ""),
            )
        }
    }
    append(tx("Arrivi alle {0}.", oraARoma(t.arrivo)))
}
