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
)

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
                            RigaOrologio(
                                linea = r.optString("linea"),
                                colore = r.optInt("colore"),
                                capolinea = r.optString("capolinea"),
                                orari = (0 until orari.length()).map { k -> orari.getLong(k) },
                                tempoReale = r.optBoolean("tempoReale"),
                                avviso = r.optString("avviso").ifEmpty { null },
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
                RigaOrologio("MEB", 0xFF862633.toInt(), "Laurentina", listOf(adesso + 120, adesso + 540, adesso + 1080), false),
                RigaOrologio("Metromare", 0xFF1D4F91.toInt(), "Cristoforo Colombo", listOf(adesso + 300, adesso + 1200), true),
                RigaOrologio("MEB", 0xFF862633.toInt(), "Rebibbia", listOf(adesso + 420, adesso + 960), false),
            ),
        ),
        FermataOrologio(
            id = "70078",
            nome = "Piazza Venezia",
            sotto = "Palina 70078",
            preferita = true,
            distanza = 1800,
            righe = listOf(
                RigaOrologio("64", 0xFF862633.toInt(), "Stazione San Pietro", listOf(adesso + 60, adesso + 600), true),
                RigaOrologio("H", 0xFF862633.toInt(), "Capannelle", listOf(adesso + 240), true),
                RigaOrologio("8", 0xFF862633.toInt(), "Casaletto", listOf(adesso + 480, adesso + 1500), false, avviso = null),
            ),
        ),
        FermataOrologio(
            id = "C:f7414",
            nome = "Frascati · Piazza Marconi",
            sotto = "Cotral · palina f7414",
            preferita = false,
            distanza = 320,
            righe = listOf(
                RigaOrologio("SG335D", 0xFF1D4F91.toInt(), "Roma Anagnina", listOf(adesso + 360), true),
            ),
        ),
    ),
)
