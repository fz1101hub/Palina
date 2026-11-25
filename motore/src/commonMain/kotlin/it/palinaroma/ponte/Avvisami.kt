package it.palinaroma.ponte

import it.palinaroma.dati.Orario
import it.palinaroma.dati.Rete
import it.palinaroma.dati.TempoReale
import it.palinaroma.dati.adessoMs
import it.palinaroma.dati.distanzaMetri
import it.palinaroma.dati.dispatcherDati
import it.palinaroma.dati.normalizza
import it.palinaroma.dati.oraARoma
import it.palinaroma.dati.tx
import it.palinaroma.dati.txn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/*
 * "Avvisami": quando uscire per prendere una linea a una fermata (contando i
 * minuti a piedi), il conto alla rovescia sulla schermata di blocco e poi,
 * a bordo, "Scendi alla prossima". Lo stesso calcolo e gli stessi testi per
 * Android (notifica in corso) e iPhone (Live Activity).
 */

/** Quando avvisare: all'ora di uscire, quando il mezzo e' a 5 minuti, o a 2. */
enum class QuandoAvvisare(val codice: String) {
    USCIRE("uscire"), CINQUE("cinque"), DUE("due");

    companion object {
        fun da(codice: String?): QuandoAvvisare = entries.firstOrNull { it.codice == codice } ?: CINQUE
    }
}

/** Velocita' a piedi (metri al minuto) e quanto si allunga la strada rispetto alla linea d'aria. */
private const val METRI_AL_MINUTO = 80.0
private const val GIRI = 1.3

/** I minuti a piedi per [metri] in linea d'aria (almeno 1). */
fun minutiAPiedi(metri: Double): Int = max(1, ceil(metri * GIRI / METRI_AL_MINUTO).toInt())

/** Quanti secondi prima del passaggio avvisare. */
fun anticipoAvviso(quando: QuandoAvvisare, aPiedi: Int): Long = when (quando) {
    QuandoAvvisare.USCIRE -> (aPiedi + 1) * 60L
    QuandoAvvisare.CINQUE -> 300L
    QuandoAvvisare.DUE -> 120L
}

/** Una scelta nel foglio "Avvisami". */
data class OpzioneAvviso(val codice: String, val etichetta: String, val abilitata: Boolean)

/** Il foglio "Avvisami per il 64": cosa propone l'app. */
data class PropostaAvviso(
    /** "Avvisami per il 64". */
    val titolo: String,
    /** "verso Stazione S. Pietro, da P.za Venezia". */
    val sotto: String,
    /** "Quello tra 1 minuto non fai in tempo a prenderlo..." o null. */
    val nota: String?,
    val opzioni: List<OpzioneAvviso>,
    /** La scelta (codice), che puo' differire da quella chiesta se "uscire" non si puo'. */
    val scelta: String,
    /** "Ti avviso verso le 18:40: 2 minuti a piedi piu' 1 di margine...". */
    val spiegazione: String,
    /** Il passaggio per cui si avvisa (secondi), 0 se non ce n'e' uno. */
    val passaggio: Long,
    val aPiedi: Int,
    val possibile: Boolean,
    val linea: String,
    val colore: Long,
)

/** "1 minuto", "12 minuti". */
fun testoMinutiLungo(minuti: Int): String = txn("{0} minuto", "{0} minuti", minuti)

/**
 * Cosa propone "Avvisami" per la riga [riga] della fermata [fermata]:
 * [aPiedi] minuti a piedi (null se la posizione non si sa), la scelta
 * [quando]. [adesso] in secondi.
 */
fun propostaAvviso(riga: RigaOrologio, fermata: String, aPiedi: Int?, quando: QuandoAvvisare, adesso: Long): PropostaAvviso {
    val prossimi = riga.prossimi(adesso).filter { it > adesso + 30 }
    val scelta = if (quando == QuandoAvvisare.USCIRE && aPiedi == null) QuandoAvvisare.CINQUE else quando
    val opzioni = listOf(
        OpzioneAvviso(QuandoAvvisare.USCIRE.codice, tx("Ora di uscire"), aPiedi != null),
        OpzioneAvviso(QuandoAvvisare.CINQUE.codice, tx("A 5 minuti"), true),
        OpzioneAvviso(QuandoAvvisare.DUE.codice, tx("A 2 minuti"), true),
    )
    val base = PropostaAvviso(
        titolo = tx("Avvisami per il {0}", riga.linea),
        sotto = tx("verso {0}, da {1}", riga.capolinea, fermata),
        nota = null, opzioni = opzioni, scelta = scelta.codice, spiegazione = "", passaggio = 0L,
        aPiedi = aPiedi ?: 0, possibile = false, linea = riga.linea, colore = riga.colore.toLong() and 0xFFFFFFFFL,
    )
    // il primo passaggio che si fa in tempo a prendere, contando la strada a piedi
    val raggiungibile = if (aPiedi != null) prossimi.firstOrNull { it - adesso >= aPiedi * 60L } else prossimi.firstOrNull()
    if (raggiungibile == null) {
        return base.copy(
            spiegazione = if (prossimi.isNotEmpty() && aPiedi != null) {
                tx("Nei prossimi 90 minuti non c'è un passaggio che fai in tempo a prendere: fino alla fermata servono {0} a piedi.", testoMinutiLungo(aPiedi))
            } else {
                tx("Nessun passaggio nei prossimi 90 minuti.")
            },
        )
    }
    val primo = prossimi.first()
    val nota = if (raggiungibile != primo && aPiedi != null) {
        tx(
            "Quello tra {0} non fai in tempo a prenderlo: fino alla fermata servono {1} a piedi. Ti avviso per il successivo, alle {2}.",
            testoMinutiLungo(((primo - adesso) / 60).toInt().coerceAtLeast(1)), testoMinutiLungo(aPiedi), oraARoma(raggiungibile * 1000),
        )
    } else {
        null
    }
    val anticipo = anticipoAvviso(scelta, aPiedi ?: 0)
    val alle = raggiungibile - anticipo
    val spiegazione = when {
        alle <= adesso + 30 -> tx("Il {0} arriva tra {1}: ti avviso subito.", riga.linea, testoMinutiLungo(((raggiungibile - adesso) / 60).toInt().coerceAtLeast(1)))
        scelta == QuandoAvvisare.USCIRE -> tx(
            "Ti avviso verso le {0}: {1} a piedi più 1 di margine. Se il {2} rallenta, l'avviso si sposta con lui.",
            oraARoma(alle * 1000), testoMinutiLungo(aPiedi ?: 0), riga.linea,
        )
        scelta == QuandoAvvisare.CINQUE -> tx("Ti avviso quando il {0} è a 5 minuti dalla fermata, verso le {1}.", riga.linea, oraARoma(alle * 1000))
        else -> tx("Ti avviso quando il {0} è a 2 minuti, verso le {1}: dovrai essere già alla fermata.", riga.linea, oraARoma(alle * 1000))
    }
    return base.copy(nota = nota, spiegazione = spiegazione, passaggio = raggiungibile, possibile = true)
}

/** Una fermata dove si puo' scendere, con l'ora stimata se il tempo reale la dice. */
data class FermataDiscesa(val id: String, val nome: String, val ora: String)

/** Un avviso da mostrare adesso (notifica con suono), con un identificativo per non ripeterlo. */
data class AvvisoOra(val id: String, val titolo: String, val testo: String)

/** Cosa mostrare di una linea seguita o di un "Avvisami". */
data class StatoSegui(
    val fermataId: String,
    val lineaId: String,
    val fermata: String,
    val linea: String,
    val capolinea: String,
    val colore: Long,
    /** Il passaggio seguito, in millisecondi. */
    val arrivoMs: Long,
    val tempoReale: Boolean,
    /** "64 → Stazione San Pietro". */
    val titolo: String,
    /** "Piazza Venezia · tra 4' (14:05) · tempo reale". */
    val testo: String,
    /** "tempo reale" o "da orario". */
    val fonte: String,
    /** Il primo avviso (uscire, o il mezzo vicino): quando (millisecondi), titolo e testo. */
    val avvisoAlleMs: Long,
    val avvisoTitolo: String,
    val avvisoTesto: String,
    /** Per riprendere il seguimento se l'app si riapre. */
    val json: String,
    /** "attesa", "esci", "arrivo", "scegli" (dove scendi?), "bordo", "scendi". */
    val fase: String,
    /** La scheda della schermata di blocco: "Esci ora", "64 tra 3 min", "Scendi alla prossima". */
    val titoloScheda: String,
    val testoScheda: String,
    /** Il numero grande e la sua unita': "3" "min", oppure "" "ora". */
    val numero: String,
    val unita: String,
    /** "a 2 fermate · tempo reale". */
    val dettaglio: String,
    /** Il momento a cui va il conto alla rovescia, in millisecondi (0: nessuno). */
    val contoMs: Long,
    /** La barra di avanzamento: da quando a quando (millisecondi). */
    val inizioMs: Long,
    val fineMs: Long,
    val mostraSuccessivo: Boolean,
    val mostraUscito: Boolean,
    val mostraScegli: Boolean,
    val mostraFine: Boolean,
    /** Il secondo avviso ("Scendi alla prossima"): quando (millisecondi, 0 se non c'e'), titolo e testo. */
    val discesaAlleMs: Long,
    val discesaTitolo: String,
    val discesaTesto: String,
    /** Dove si scende, se scelto. */
    val discesa: String?,
    /** La scelta di "Avvisami" ("" per "Segui"). */
    val quando: String,
)

/** Il testo dell'avviso per uscire o per il mezzo vicino. [adesso] in secondi. */
fun titoloPrimoAvviso(s: Seguimento): String = when {
    s.quando == QuandoAvvisare.USCIRE.codice -> tx("Esci ora: il {0} passa alle {1}", s.linea, oraARoma(s.obiettivo * 1000))
    else -> titoloAvvisoSegui(s)
}

fun testoPrimoAvviso(s: Seguimento, adesso: Long): String = when {
    s.quando == QuandoAvvisare.USCIRE.codice ->
        tx("A piedi fino a {0} servono {1}. Il {2} va verso {3}.", s.fermata, testoMinutiLungo(s.aPiedi), s.linea, s.capolinea)
    else -> testoAvvisoSegui(s, adesso)
}

/**
 * Lo stato da mostrare per il seguimento [s] adesso ([adesso] in secondi);
 * [fermateMancanti] se il tempo reale dice a quante fermate e' il mezzo.
 */
fun statoSegui(s: Seguimento, adesso: Long, fermateMancanti: Int?): StatoSegui {
    val fonte = if (s.tempoReale) tx("tempo reale") else tx("da orario")
    val ora = oraARoma(s.obiettivo * 1000)
    val discesa = s.discesa
    val passato = s.obiettivo < adesso - 60
    val minutiA = { alle: Long -> ((alle - adesso) / 60).toInt() }
    var mostraSuccessivo = false
    var mostraUscito = false
    var mostraScegli = false
    var mostraFine = false
    val fase: String
    val titoloScheda: String
    val testoScheda: String
    val conto: Long
    val inizio: Long
    val fine: Long
    var dettaglio = listOfNotNull(
        fermateMancanti?.takeIf { it > 0 && !passato }?.let { txn("a {0} fermata", "a {0} fermate", it) },
        fonte,
    ).joinToString(" · ")
    if (discesa != null) {
        fase = if (adesso >= discesa.avvisoAlle) "scendi" else "bordo"
        titoloScheda = if (fase == "scendi") tx("Scendi alla prossima") else tx("Verso {0}", discesa.nome)
        testoScheda = if (fase == "scendi") {
            tx("{0} · prenota la fermata", discesa.nome)
        } else {
            tx("Arrivi a {0} alle {1}. Ti avviso alla fermata prima.", discesa.nome, oraARoma(discesa.arrivo * 1000))
        }
        conto = discesa.arrivo
        inizio = s.obiettivo
        fine = discesa.arrivo
        mostraFine = true
        dettaglio = tx("arrivi {0}", oraARoma(discesa.arrivo * 1000)) + " · " + (if (discesa.tempoReale) tx("tempo reale") else tx("da orario"))
    } else if (passato && s.scendiAttivo) {
        fase = "scegli"
        titoloScheda = tx("Sei sul {0}?", s.linea)
        testoScheda = tx("Scegli dove scendi: ti avviso alla fermata prima.")
        conto = 0L
        inizio = s.obiettivo
        fine = s.obiettivo
        mostraScegli = true
        mostraFine = true
        dettaglio = tx("verso {0}", s.capolinea)
    } else {
        val alle = s.obiettivo - s.anticipo
        val uscire = s.quando == QuandoAvvisare.USCIRE.codice && !s.uscito
        fase = when {
            uscire && adesso >= alle -> "esci"
            uscire -> "attesa"
            else -> "arrivo"
        }
        titoloScheda = when {
            fase == "esci" -> tx("Esci ora")
            fase == "attesa" -> tx("Esci alle {0}", oraARoma(alle * 1000))
            s.obiettivo - adesso < 60 -> tx("{0} in arrivo", s.linea)
            else -> tx("{0} tra {1}", s.linea, testoMinutiLungo(minutiA(s.obiettivo)))
        }
        testoScheda = if (s.quando == QuandoAvvisare.USCIRE.codice) {
            tx("Il {0} per {1} arriva a {2} alle {3}. A piedi ti servono {4}.", s.linea, s.capolinea, s.fermata, ora, testoMinutiLungo(s.aPiedi))
        } else {
            tx("Il {0} per {1} arriva a {2} alle {3}.", s.linea, s.capolinea, s.fermata, ora)
        }
        conto = s.obiettivo
        inizio = s.inizio / 1000
        fine = s.obiettivo
        mostraSuccessivo = s.quando.isNotEmpty()
        mostraUscito = uscire
    }
    val minuti = if (conto > 0) minutiA(conto) else -1
    val avviso = s.copy(avvisato = true)
    return StatoSegui(
        fermataId = s.fermataId,
        lineaId = s.lineaId,
        fermata = s.fermata,
        linea = s.linea,
        capolinea = s.capolinea,
        colore = s.colore.toLong() and 0xFFFFFFFFL,
        arrivoMs = s.obiettivo * 1000,
        tempoReale = s.tempoReale,
        titolo = titoloSegui(s),
        testo = if (discesa != null || fase == "scegli") "${s.linea} · $titoloScheda" else testoSegui(s, adesso),
        fonte = fonte,
        avvisoAlleMs = (s.obiettivo - s.anticipo) * 1000,
        avvisoTitolo = titoloPrimoAvviso(avviso),
        avvisoTesto = testoPrimoAvviso(avviso, max(adesso, s.obiettivo - s.anticipo)),
        json = s.testoJson(),
        fase = fase,
        titoloScheda = titoloScheda,
        testoScheda = testoScheda,
        numero = if (minuti >= 1) "$minuti" else "",
        unita = when {
            minuti >= 1 -> "min"
            conto > 0 -> tx("ora§adesso")
            else -> ""
        },
        dettaglio = dettaglio,
        contoMs = conto * 1000,
        inizioMs = inizio * 1000,
        fineMs = fine * 1000,
        mostraSuccessivo = mostraSuccessivo,
        mostraUscito = mostraUscito,
        mostraScegli = mostraScegli,
        mostraFine = mostraFine,
        discesaAlleMs = discesa?.let { it.avvisoAlle * 1000 } ?: 0L,
        discesaTitolo = tx("Scendi alla prossima"),
        discesaTesto = discesa?.let { tx("{0} · prenota la fermata", it.nome) }.orEmpty(),
        discesa = discesa?.nome,
        quando = s.quando,
    )
}

/**
 * "Segui" e "Avvisami" sul telefono, per Android e iPhone: tiene il
 * seguimento, chiede i tempi della fermata (e poi di quella dove si scende) e
 * dice cosa mostrare e quando avvisare.
 */
class MotoreSegui(private val orario: Orario) {
    private var seguito: Seguimento? = null
    private var mancanti: Int? = null

    /** Riprende un seguimento salvato (null se e' gia' finito). */
    fun riprendi(json: String): StatoSegui? {
        seguito = seguimentoDaJson(json)?.let { ricalcola(it, null, adessoMs() / 1000) }
        return stato()
    }

    /** Il seguimento in corso (per l'orologio e le prove). */
    fun seguimentoAttuale(): Seguimento? = seguito

    /**
     * Cosa propone "Avvisami" per la linea [lineaId] verso [capolinea] alla
     * fermata [fermataId], con la posizione (se [conPosizione]) per i minuti a piedi.
     */
    suspend fun proposta(
        fermataId: String,
        lineaId: String,
        capolinea: String,
        quando: String,
        conPosizione: Boolean,
        lat: Double,
        lon: Double,
    ): PropostaAvviso? = withContext(Dispatchers.Main) {
        val adesso = adessoMs()
        val t = tabellone(fermataId, lineaId, adesso) ?: return@withContext null
        val f = t.fermate.firstOrNull { it.id == fermataId } ?: return@withContext null
        val r = riga(f, lineaId, capolinea) ?: return@withContext null
        val aPiedi = if (conPosizione) minutiAPiediFino(fermataId, lat, lon) else null
        propostaAvviso(r, f.nome, aPiedi, QuandoAvvisare.da(quando), adesso / 1000)
    }

    /** "Segui" (avviso 2 minuti prima, come sull'orologio). Null se la linea non ha passaggi. */
    suspend fun inizia(fermataId: String, lineaId: String, capolinea: String): StatoSegui? =
        avvisami(fermataId, lineaId, capolinea, "", false, 0.0, 0.0, false)

    /**
     * "Avvisami" con la scelta [quando] ("" = come "Segui"), la posizione per i
     * minuti a piedi e [scendi] = "Poi avvisami quando scendere".
     */
    suspend fun avvisami(
        fermataId: String,
        lineaId: String,
        capolinea: String,
        quando: String,
        conPosizione: Boolean,
        lat: Double,
        lon: Double,
        scendi: Boolean,
    ): StatoSegui? = withContext(Dispatchers.Main) {
        val adesso = adessoMs()
        val t = tabellone(fermataId, lineaId, adesso)
        val f = t?.fermate?.firstOrNull { it.id == fermataId }
        val r = f?.let { riga(it, lineaId, capolinea) }
        if (t == null || f == null || r == null) {
            seguito = null
            return@withContext null
        }
        val base = seguimento(f, r, t, adesso / 1000) ?: run {
            seguito = null
            return@withContext null
        }
        seguito = if (quando.isEmpty()) {
            base
        } else {
            val aPiedi = if (conPosizione) minutiAPiediFino(fermataId, lat, lon) else null
            val scelta = QuandoAvvisare.da(quando)
            val p = propostaAvviso(r, f.nome, aPiedi, scelta, adesso / 1000)
            if (!p.possibile) {
                null
            } else {
                base.copy(
                    obiettivo = p.passaggio,
                    tempoReale = r.tempoReale && p.passaggio == r.prossimi(adesso / 1000).firstOrNull(),
                    corsaId = r.corsa(p.passaggio),
                    quando = p.scelta,
                    aPiedi = p.aPiedi,
                    anticipo = anticipoAvviso(QuandoAvvisare.da(p.scelta), p.aPiedi),
                    scendiAttivo = scendi,
                    // se l'ora di avvisare e' gia' passata si avvisa subito
                    avvisato = false,
                )
            }
        }
        mancanti = null
        seguito?.let { mancanti = fermateMancanti(it) }
        stato()
    }

    /** Chiede di nuovo i tempi: il passaggio seguito si sposta col tempo reale. Null quando e' finito. */
    suspend fun aggiorna(): StatoSegui? = withContext(Dispatchers.Main) {
        val s = seguito ?: return@withContext null
        val adesso = adessoMs()
        val d = s.discesa
        if (d != null) {
            val stima = stimaArrivo(s, d.fermataId, adesso / 1000)
            seguito = seguito?.let { attuale ->
                val dd = attuale.discesa ?: return@let attuale
                if (stima == null) {
                    attuale
                } else {
                    attuale.copy(
                        discesa = dd.copy(
                            arrivo = stima.arrivo,
                            avvisoAlle = stima.avvisoAlle,
                            tempoReale = stima.tempoReale,
                            // se il mezzo rallenta si avvisa di nuovo quando si avvicina
                            avvisato = dd.avvisato && stima.avvisoAlle <= adesso / 1000 + 30,
                        ),
                    )
                }
            }
        } else if (s.obiettivo > adesso / 1000 - 60) {
            val t = tabellone(s.fermataId, s.lineaId, adesso)
            seguito = seguito?.let { ricalcola(it, t, adesso / 1000) }
            mancanti = seguito?.let { fermateMancanti(it) }
        }
        stato()
    }

    fun smetti() {
        seguito = null
        mancanti = null
    }

    /** "Sto uscendo": niente piu' "Esci ora", resta il conto alla rovescia del mezzo. */
    fun stoUscendo(): StatoSegui? {
        seguito = seguito?.copy(uscito = true, avvisato = true)
        return stato()
    }

    /** "Prendo il successivo": si segue il passaggio dopo quello di adesso. */
    suspend fun prendiSuccessivo(): StatoSegui? = withContext(Dispatchers.Main) {
        val s = seguito ?: return@withContext null
        val adesso = adessoMs()
        val t = tabellone(s.fermataId, s.lineaId, adesso)
        val r = t?.riga(s.fermataId, s.chiaveLinea, s.capolinea)
        val dopo = r?.prossimi(adesso / 1000)?.firstOrNull { it > s.obiettivo + 60 }
        if (r != null && dopo != null) {
            seguito = s.copy(
                obiettivo = dopo,
                tempoReale = false,
                corsaId = r.corsa(dopo),
                aggiornato = t.creato,
                avvisato = false,
                uscito = false,
                inizio = adesso,
            )
            mancanti = null
        }
        stato()
    }

    /** Le fermate dove si puo' scendere: quelle della linea dopo dove si e' saliti. */
    suspend fun fermateDiscesa(): List<FermataDiscesa> = withContext(dispatcherDati) {
        val s = seguito ?: return@withContext emptyList()
        val direzione = orario.corse(listOf(s.corsaId).filter { it.isNotEmpty() })[s.corsaId]?.direzione
            ?: orario.percorsi(s.lineaId).firstOrNull { normalizza(it.capolinea) == normalizza(s.capolinea) }?.direzione
            ?: orario.percorsi(s.lineaId).firstOrNull()?.direzione
            ?: return@withContext emptyList()
        val fermate = orario.fermateDelPercorso(s.lineaId, direzione)
        val qui = orario.stazioneUnita(s.fermataId).toSet() + s.fermataId
        val da = fermate.indexOfFirst { it.id in qui }
        val dopo = if (da >= 0) fermate.drop(da + 1) else fermate
        val tempi = tempiDellaCorsa(s)
        dopo.map { f -> FermataDiscesa(f.id, f.nome, tempi[f.id]?.let { oraARoma(it * 1000) }.orEmpty()) }
    }

    /** Si scende a [fermataId]: avviso alla fermata prima. */
    suspend fun scendiA(fermataId: String): StatoSegui? = withContext(Dispatchers.Main) {
        val s = seguito ?: return@withContext null
        val f = withContext(dispatcherDati) { orario.fermata(fermataId) } ?: return@withContext stato()
        val adesso = adessoMs() / 1000
        val stima = stimaArrivo(s, fermataId, adesso) ?: stimaDalPercorso(s, fermataId, adesso)
        seguito = s.copy(
            scendiAttivo = true,
            discesa = Discesa(fermataId, f.nome, stima.arrivo, stima.avvisoAlle, stima.tempoReale),
        )
        stato()
    }

    /** Lo stato per adesso (null se non si segue niente o e' finito). */
    fun stato(): StatoSegui? {
        val adesso = adessoMs() / 1000
        val s = seguito?.let { ricalcola(it, null, adesso) } ?: run {
            seguito = null
            return null
        }
        return statoSegui(s, adesso, mancanti)
    }

    /**
     * Gli avvisi da mostrare adesso (Android li mostra cosi'; l'iPhone li
     * programma prima con [StatoSegui.avvisoAlleMs]). Ognuno una volta sola.
     */
    fun avvisiDaMostrare(): List<AvvisoOra> {
        val s = seguito ?: return emptyList()
        val adesso = adessoMs() / 1000
        val avvisi = ArrayList<AvvisoOra>()
        var nuovo = s
        val d = s.discesa
        if (d != null) {
            if (!d.avvisato && adesso >= d.avvisoAlle && adesso <= d.arrivo + 60) {
                avvisi.add(AvvisoOra("scendi", tx("Scendi alla prossima"), tx("{0} · prenota la fermata", d.nome)))
                nuovo = nuovo.copy(discesa = d.copy(avvisato = true))
            }
        } else if (!s.avvisato && adesso >= s.obiettivo - s.anticipo && adesso <= s.obiettivo + 30 && !s.uscito) {
            val avviso = s.copy(avvisato = true)
            avvisi.add(AvvisoOra("arrivo", titoloPrimoAvviso(avviso), testoPrimoAvviso(avviso, adesso)))
            nuovo = nuovo.copy(avvisato = true)
        }
        seguito = nuovo
        return avvisi
    }

    // --- calcoli

    private fun riga(f: FermataOrologio, lineaId: String, capolinea: String): RigaOrologio? =
        f.righe.firstOrNull { it.lineaId == lineaId && it.capolinea == capolinea } ?: f.righe.firstOrNull { it.lineaId == lineaId }

    private suspend fun minutiAPiediFino(fermataId: String, lat: Double, lon: Double): Int? {
        val f = withContext(dispatcherDati) { orario.fermata(fermataId) } ?: return null
        val metri = distanzaMetri(lat, lon, f.lat, f.lon)
        // oltre 3 km non e' "uscire di casa": i minuti a piedi non servono
        return if (metri > 3000) null else minutiAPiedi(metri)
    }

    /** Il tabellone della sola fermata (come lo chiede l'orologio Wear OS); null senza rete. */
    private suspend fun tabellone(fermataId: String, lineaId: String, adesso: Long): TabelloneOrologio? = try {
        calcolaTabelloneOrologio(orario, emptySet(), emptySet(), null, adesso, listOf(LineaSeguita(fermataId, lineaId)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** I tempi della corsa seguita alle sue prossime fermate (id -> secondi), dal tempo reale di Roma. */
    private suspend fun tempiDellaCorsa(s: Seguimento): Map<String, Long> {
        if (s.corsaId.isEmpty() || Rete.di(s.lineaId) != Rete.ROMA) return emptyMap()
        val corse = try {
            TempoReale.corse()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyMap()
        }
        return corse.firstOrNull { it.corsaId == s.corsaId }?.fermate?.associate { it.fermataId to it.orario }.orEmpty()
    }

    /** A quante fermate e' il mezzo seguito (dal tempo reale di Roma), se si sa. */
    private suspend fun fermateMancanti(s: Seguimento): Int? {
        if (s.corsaId.isEmpty() || Rete.di(s.lineaId) != Rete.ROMA) return null
        val corse = try {
            TempoReale.corse()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val c = corse.firstOrNull { it.corsaId == s.corsaId } ?: return null
        val qui = orario.stazioneUnita(s.fermataId).toSet() + s.fermataId
        val i = c.fermate.indexOfFirst { it.fermataId in qui }
        if (i < 0) return null
        val adesso = adessoMs() / 1000
        return c.fermate.take(i).count { it.orario >= adesso - 20 }
    }

    private data class Stima(val arrivo: Long, val avvisoAlle: Long, val tempoReale: Boolean)

    /**
     * Quando la corsa seguita arriva alla fermata [destinazione]: dal tempo
     * reale (e la fermata prima per l'avviso), o dal tabellone di quella fermata.
     */
    private suspend fun stimaArrivo(s: Seguimento, destinazione: String, adesso: Long): Stima? {
        val tempi = tempiDellaCorsa(s)
        val qui = orario.stazioneUnita(destinazione).toSet() + destinazione
        val arrivoRT = tempi.entries.firstOrNull { it.key in qui }?.value
        if (arrivoRT != null) {
            val ordinati = tempi.values.sorted()
            val prima = ordinati.lastOrNull { it < arrivoRT }
            return Stima(arrivoRT, avvisoPrima(prima, arrivoRT), true)
        }
        val t = tabellone(destinazione, s.lineaId, adessoMs()) ?: return null
        val r = t.riga(destinazione, s.chiaveLinea, s.capolinea) ?: return null
        val prossimi = r.prossimi(adesso)
        val passaggio = prossimi.firstOrNull { s.corsaId.isNotEmpty() && r.corsa(it) == s.corsaId }
            ?: prossimi.firstOrNull { it > s.obiettivo }
            ?: return null
        return Stima(passaggio, avvisoPrima(null, passaggio), r.tempoReale && passaggio == prossimi.firstOrNull())
    }

    /** Senza tempi: un minuto e mezzo per fermata dal passaggio dove si e' saliti. */
    private suspend fun stimaDalPercorso(s: Seguimento, destinazione: String, adesso: Long): Stima {
        val fermate = fermateDiscesa()
        val n = fermate.indexOfFirst { it.id == destinazione }.let { if (it < 0) 5 else it + 1 }
        val arrivo = max(adesso + 60, s.obiettivo + n * 90L)
        return Stima(arrivo, avvisoPrima(null, arrivo), false)
    }

    /** "Scendi alla prossima" quando il mezzo lascia la fermata prima, o un minuto e mezzo prima dell'arrivo. */
    private fun avvisoPrima(fermataPrima: Long?, arrivo: Long): Long =
        fermataPrima?.takeIf { arrivo - it in 20..600 } ?: (arrivo - 90)
}
