package it.palinaroma.ponte

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cosa si scambiano telefono e orologio. L'orologio non scarica i dati di Roma
 * Mobilita', Cotral e Trenitalia (pesano e consumano batteria): li calcola il
 * telefono e glieli manda gia' pronti, come tabellone delle fermate.
 */
object Ponte {
    /** L'orologio chiede al telefono un tabellone aggiornato (messaggio). */
    const val RICHIESTA = "/palina/richiesta"

    /** Il telefono pubblica il tabellone (dato condiviso, resta anche se l'orologio era spento). */
    const val TABELLONE = "/palina/tabellone"

    /** Il campo del dato condiviso con il tabellone in JSON. */
    const val CAMPO_JSON = "json"

    /** La "capacita'" che il telefono dichiara, per trovarlo tra i dispositivi collegati. */
    const val CAPACITA_TELEFONO = "palina_telefono"

    /** L'orologio chiede tutte le fermate di un treno (messaggio con il [TrenoOrologio] in JSON). */
    const val RICHIESTA_CORSA = "/palina/richiesta-corsa"

    /** Il telefono pubblica le fermate del treno chiesto (dato condiviso, [CorsaOrologio] in JSON). */
    const val CORSA = "/palina/corsa"
}

/**
 * Quello che serve al telefono per ritrovare un treno e leggerne le fermate
 * (come quando lo si tocca nel tabellone di una stazione sul telefono).
 */
data class TrenoOrologio(
    /** La corsa per Transitous, se la si sa gia'. */
    val corsaId: String?,
    /** La stazione dell'app da cui lo si guarda ("F:...", "T:..."). */
    val fermataId: String,
    val lineaId: String,
    val direzione: Int?,
    /** Orario programmato alla stazione, in secondi. */
    val previsto: Long,
    /** La corsa vista da ASTRAL (treni Cotral), per aggiornarne il ritardo. */
    val corsaAstral: String?,
    /** Ritardo in secondi, se la fonte lo dice a parte. */
    val ritardo: Int?,
) {
    /** Per riconoscere la risposta a questa richiesta. */
    val chiave: String get() = "$fermataId|$lineaId|$previsto"

    fun json(): JSONObject = JSONObject().apply {
        corsaId?.let { put("corsa", it) }
        put("fermata", fermataId)
        put("linea", lineaId)
        direzione?.let { put("direzione", it) }
        put("previsto", previsto)
        corsaAstral?.let { put("astral", it) }
        ritardo?.let { put("ritardo", it) }
    }

    companion object {
        fun da(o: JSONObject): TrenoOrologio = TrenoOrologio(
            corsaId = o.optString("corsa").ifEmpty { null },
            fermataId = o.optString("fermata"),
            lineaId = o.optString("linea"),
            direzione = if (o.has("direzione")) o.optInt("direzione") else null,
            previsto = o.optLong("previsto"),
            corsaAstral = o.optString("astral").ifEmpty { null },
            ritardo = if (o.has("ritardo")) o.optInt("ritardo") else null,
        )

        /** Null se il testo non e' una richiesta valida. */
        fun leggi(testo: String?): TrenoOrologio? =
            if (testo.isNullOrBlank()) {
                null
            } else {
                try {
                    da(JSONObject(testo)).takeIf { it.fermataId.isNotEmpty() && it.lineaId.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            }
    }
}

/** Una linea seguita sull'orologio (Live Update): il telefono la mette sempre nel tabellone, in cima alla sua fermata. */
data class LineaSeguita(val fermataId: String, val lineaId: String)

/** La richiesta di un tabellone: vuota, o con le linee seguite. */
object RichiestaOrologio {
    fun scrivi(seguite: List<LineaSeguita>): ByteArray =
        if (seguite.isEmpty()) {
            ByteArray(0)
        } else {
            JSONObject().put(
                "seguite",
                JSONArray().apply {
                    seguite.forEach { put(JSONObject().put("fermata", it.fermataId).put("linea", it.lineaId)) }
                },
            ).toString().toByteArray(Charsets.UTF_8)
        }

    fun leggi(dati: ByteArray?): List<LineaSeguita> {
        if (dati == null || dati.isEmpty()) return emptyList()
        return try {
            val a = JSONObject(String(dati, Charsets.UTF_8)).optJSONArray("seguite") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { LineaSeguita(it.optString("fermata"), it.optString("linea")) }
                .filter { it.fermataId.isNotEmpty() && it.lineaId.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Una fermata di un treno, per l'orologio. Orari in millisecondi. */
data class FermataTreno(
    val nome: String,
    val orario: Long?,
    val previsto: Long?,
    val binario: String?,
    val binarioCambiato: Boolean,
    val soppressa: Boolean,
    /** Orario stimato dal ritardo visto a un'altra stazione. */
    val stimato: Boolean,
) {
    /** Minuti di ritardo (negativi se in anticipo), se l'orario e' aggiornato. */
    val ritardoMinuti: Int?
        get() = if (orario != null && previsto != null) Math.round((orario - previsto) / 60_000.0).toInt() else null
}

/** Le fermate di un treno, calcolate dal telefono. */
data class CorsaOrologio(
    /** La [TrenoOrologio.chiave] della richiesta. */
    val chiave: String,
    /** Quando il telefono l'ha calcolato, in millisecondi. */
    val creato: Long,
    /** "FL1 12345", "Metromare". */
    val titolo: String,
    /** "verso Fiumicino Aeroporto · Trenitalia". */
    val verso: String?,
    /** "In tempo reale: 3 min di ritardo a Ostiense". */
    val fonte: String?,
    val fermate: List<FermataTreno>,
    /** La stazione da cui lo si guarda, se c'e' tra le fermate. */
    val qui: Int?,
    /** Se il telefono non e' riuscito a trovare il treno. */
    val errore: String? = null,
)

object CodificaCorsa {
    fun scrivi(c: CorsaOrologio): String = JSONObject().apply {
        put("v", 1)
        put("chiave", c.chiave)
        put("creato", c.creato)
        put("titolo", c.titolo)
        c.verso?.let { put("verso", it) }
        c.fonte?.let { put("fonte", it) }
        c.qui?.let { put("qui", it) }
        c.errore?.let { put("errore", it) }
        put("fermate", JSONArray().apply {
            c.fermate.forEach { f ->
                put(JSONObject().apply {
                    put("nome", f.nome)
                    f.orario?.let { put("orario", it) }
                    f.previsto?.let { put("previsto", it) }
                    f.binario?.let { put("binario", it) }
                    if (f.binarioCambiato) put("binarioCambiato", true)
                    if (f.soppressa) put("soppressa", true)
                    if (f.stimato) put("stimato", true)
                })
            }
        })
    }.toString()

    fun leggi(json: String?): CorsaOrologio? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val fermate = o.optJSONArray("fermate") ?: JSONArray()
            CorsaOrologio(
                chiave = o.optString("chiave"),
                creato = o.optLong("creato"),
                titolo = o.optString("titolo"),
                verso = o.optString("verso").ifEmpty { null },
                fonte = o.optString("fonte").ifEmpty { null },
                qui = if (o.has("qui")) o.optInt("qui") else null,
                errore = o.optString("errore").ifEmpty { null },
                fermate = (0 until fermate.length()).map { i ->
                    val f = fermate.getJSONObject(i)
                    FermataTreno(
                        nome = f.optString("nome"),
                        orario = if (f.has("orario")) f.optLong("orario") else null,
                        previsto = if (f.has("previsto")) f.optLong("previsto") else null,
                        binario = f.optString("binario").ifEmpty { null },
                        binarioCambiato = f.optBoolean("binarioCambiato"),
                        soppressa = f.optBoolean("soppressa"),
                        stimato = f.optBoolean("stimato"),
                    )
                },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Una riga del tabellone: una linea verso un capolinea, con i prossimi passaggi. */
data class RigaOrologio(
    val linea: String,
    /** Colore della linea (ARGB), come nel telefono. */
    val colore: Int,
    val capolinea: String,
    /** I prossimi passaggi, in secondi dal 1970. */
    val orari: List<Long>,
    /** Il primo passaggio e' in tempo reale (GPS o ritardo comunicato). */
    val tempoReale: Boolean,
    /** "soppresso", "bus sostitutivo"... */
    val avviso: String? = null,
    /** La linea per il telefono ("64", "C:...", "T:..."): per seguirla e chiedere i treni. */
    val lineaId: String = "",
    /** Per i treni: come chiedere al telefono le fermate di ciascun passaggio (uno per orario). */
    val treni: List<TrenoOrologio> = emptyList(),
) {
    /** E' una riga di treni: se ne possono vedere le fermate. */
    val diTreni: Boolean get() = treni.isNotEmpty()

    /** Il treno del passaggio delle [orario] (secondi), se e' una riga di treni. */
    fun treno(orario: Long): TrenoOrologio? = orari.indexOf(orario).takeIf { it >= 0 }?.let { treni.getOrNull(it) }
}

data class FermataOrologio(
    val id: String,
    val nome: String,
    /** "Palina 70078", "Stazione · Metro B/B1 · Metromare"... */
    val sotto: String,
    val preferita: Boolean,
    /** Metri dal telefono, se si sa dove si e'. */
    val distanza: Int?,
    val righe: List<RigaOrologio>,
)

data class TabelloneOrologio(
    /** Quando il telefono l'ha calcolato, in millisecondi. */
    val creato: Long,
    val fermate: List<FermataOrologio>,
    /** Una nota per tutto il tabellone: "Roma Mobilità ora non pubblica il tempo reale". */
    val nota: String? = null,
)

/** Il tabellone in JSON e ritorno. */
object CodificaOrologio {
    fun scrivi(t: TabelloneOrologio): String = JSONObject().apply {
        put("v", 1)
        put("creato", t.creato)
        t.nota?.let { put("nota", it) }
        put("fermate", JSONArray().apply {
            t.fermate.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id)
                    put("nome", f.nome)
                    put("sotto", f.sotto)
                    put("preferita", f.preferita)
                    f.distanza?.let { put("distanza", it) }
                    put("righe", JSONArray().apply {
                        f.righe.forEach { r ->
                            put(JSONObject().apply {
                                put("linea", r.linea)
                                put("colore", r.colore)
                                put("capolinea", r.capolinea)
                                put("orari", JSONArray().apply { r.orari.forEach { put(it) } })
                                put("tempoReale", r.tempoReale)
                                r.avviso?.let { put("avviso", it) }
                                if (r.lineaId.isNotEmpty()) put("lineaId", r.lineaId)
                                if (r.treni.isNotEmpty()) put("treni", JSONArray().apply { r.treni.forEach { put(it.json()) } })
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    /** Null se il testo non e' un tabellone (o e' rovinato). */
    fun leggi(json: String?): TabelloneOrologio? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val fermate = o.optJSONArray("fermate") ?: JSONArray()
            TabelloneOrologio(
                creato = o.optLong("creato"),
                nota = o.optString("nota").ifEmpty { null },
                fermate = (0 until fermate.length()).map { i ->
                    val f = fermate.getJSONObject(i)
                    val righe = f.optJSONArray("righe") ?: JSONArray()
                    FermataOrologio(
                        id = f.optString("id"),
                        nome = f.optString("nome"),
                        sotto = f.optString("sotto"),
                        preferita = f.optBoolean("preferita"),
                        distanza = if (f.has("distanza")) f.optInt("distanza") else null,
                        righe = (0 until righe.length()).map { j ->
                            val r = righe.getJSONObject(j)
                            val orari = r.optJSONArray("orari") ?: JSONArray()
                            val treni = r.optJSONArray("treni") ?: JSONArray()
                            RigaOrologio(
                                linea = r.optString("linea"),
                                colore = r.optInt("colore"),
                                capolinea = r.optString("capolinea"),
                                orari = (0 until orari.length()).map { k -> orari.getLong(k) },
                                tempoReale = r.optBoolean("tempoReale"),
                                avviso = r.optString("avviso").ifEmpty { null },
                                lineaId = r.optString("lineaId"),
                                treni = (0 until treni.length()).map { k -> TrenoOrologio.da(treni.getJSONObject(k)) },
                            )
                        },
                    )
                },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** I passaggi non ancora avvenuti (con mezzo minuto di tolleranza), in secondi. */
fun RigaOrologio.prossimi(adesso: Long): List<Long> = orari.filter { it >= adesso - 30 }

/** Minuti che mancano, per lo schermo piccolo: "ora", "3'", "1 h 05'". */
fun testoMinuti(orario: Long, adesso: Long): String {
    val minuti = ((orario - adesso) / 60).coerceAtLeast(0)
    return when {
        minuti < 1 -> "ora"
        minuti < 60 -> "$minuti'"
        else -> "${minuti / 60} h " + (minuti % 60).toString().padStart(2, '0') + "'"
    }
}

/** I prossimi passaggi in poco spazio: "3' · 12' · 25'". */
fun testoPassaggi(riga: RigaOrologio, adesso: Long, quanti: Int = 3): String =
    riga.prossimi(adesso).take(quanti).joinToString(" · ") { testoMinuti(it, adesso) }

/** Il primo passaggio tra tutte le righe di una fermata: per la complicazione. */
fun FermataOrologio.primoPassaggio(adesso: Long): Pair<RigaOrologio, Long>? =
    righe.mapNotNull { r -> r.prossimi(adesso).firstOrNull()?.let { r to it } }.minByOrNull { it.second }

/** L'ora di un orario in millisecondi: "14:05". */
fun oraBreve(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).format(java.util.Date(ms))

/** Come si riconosce una riga tra un tabellone e il successivo: la linea (o il suo nome, nei tabelloni vecchi). */
val RigaOrologio.chiaveLinea: String get() = lineaId.ifEmpty { linea }

/** La riga della linea [chiaveLinea] verso [capolinea] alla fermata [fermataId] (o la stessa linea, se il capolinea e' cambiato). */
fun TabelloneOrologio.riga(fermataId: String, chiaveLinea: String, capolinea: String): RigaOrologio? {
    val righe = fermate.firstOrNull { it.id == fermataId }?.righe ?: return null
    return righe.firstOrNull { it.chiaveLinea == chiaveLinea && it.capolinea == capolinea }
        ?: righe.firstOrNull { it.chiaveLinea == chiaveLinea }
}

/** "120 m", "1,2 km". */
fun testoDistanza(metri: Int): String =
    if (metri < 1000) "$metri m" else String.format(java.util.Locale.ITALY, "%.1f km", metri / 1000.0)

/** Un tabellone di esempio, per le prove e le anteprime. [adesso] in secondi. */
fun tabelloneDiEsempio(adesso: Long): TabelloneOrologio = TabelloneOrologio(
    creato = adesso * 1000,
    fermate = listOf(
        FermataOrologio(
            id = "F:RL_SANPAOLO",
            nome = "Basilica San Paolo",
            sotto = "Stazione · Metro B/B1 · Metromare",
            preferita = true,
            distanza = 140,
            righe = listOf(
                RigaOrologio("MEB", 0xFF862633.toInt(), "Laurentina", listOf(adesso + 120, adesso + 540, adesso + 1080), false, lineaId = "MEB"),
                RigaOrologio(
                    "Metromare", 0xFF1D4F91.toInt(), "Cristoforo Colombo", listOf(adesso + 300, adesso + 1200), true,
                    lineaId = "F:ROMALIDO",
                    treni = listOf(
                        TrenoOrologio(null, "F:RL_SANPAOLO", "F:ROMALIDO", 0, adesso + 120, "astral:1", 180),
                        TrenoOrologio(null, "F:RL_SANPAOLO", "F:ROMALIDO", 0, adesso + 1200, null, null),
                    ),
                ),
                RigaOrologio("MEB", 0xFF862633.toInt(), "Rebibbia", listOf(adesso + 420, adesso + 960), false, lineaId = "MEB"),
            ),
        ),
        FermataOrologio(
            id = "70078",
            nome = "Piazza Venezia",
            sotto = "Palina 70078",
            preferita = true,
            distanza = 1800,
            righe = listOf(
                RigaOrologio("64", 0xFF862633.toInt(), "Stazione San Pietro", listOf(adesso + 60, adesso + 600), true, lineaId = "64"),
                RigaOrologio("H", 0xFF862633.toInt(), "Capannelle", listOf(adesso + 240), true, lineaId = "H"),
                RigaOrologio("8", 0xFF862633.toInt(), "Casaletto", listOf(adesso + 480, adesso + 1500), false, lineaId = "8"),
            ),
        ),
        FermataOrologio(
            id = "C:f7414",
            nome = "Frascati · Piazza Marconi",
            sotto = "Cotral · palina f7414",
            preferita = false,
            distanza = 320,
            righe = listOf(
                RigaOrologio("SG335D", 0xFF1D4F91.toInt(), "Roma Anagnina", listOf(adesso + 360), true, lineaId = "C:SG335D"),
            ),
        ),
    ),
)

/** Le fermate di un treno di esempio (la Metromare da San Paolo), per le prove. [adesso] in secondi. */
fun corsaDiEsempio(treno: TrenoOrologio, adesso: Long): CorsaOrologio {
    val nomi = listOf(
        "Roma Porta San Paolo", "Basilica San Paolo", "EUR Magliana", "Tor di Valle", "Vitinia",
        "Casal Bernocchi", "Acilia", "Ostia Antica", "Lido Nord", "Lido Centro", "Stella Polare", "Castel Fusano", "Cristoforo Colombo",
    )
    val partenza = (adesso - 180) * 1000
    return CorsaOrologio(
        chiave = treno.chiave,
        creato = adesso * 1000,
        titolo = "Metromare",
        verso = "verso Cristoforo Colombo · Cotral",
        fonte = "Ritardo da ASTRAL: 3 min a Basilica San Paolo",
        fermate = nomi.mapIndexed { i, n ->
            val previsto = partenza + i * 150_000L
            FermataTreno(n, previsto + if (i >= 1) 180_000 else 0, previsto, null, false, false, i > 1)
        },
        qui = 1,
    )
}
