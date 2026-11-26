package it.palinaroma.avviso

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Orario
import it.palinaroma.dati.haPermessoPosizione
import it.palinaroma.dati.posizioneAttuale
import it.palinaroma.ponte.FermataDiscesa
import it.palinaroma.ponte.MotoreSegui
import it.palinaroma.ponte.PropostaAvviso
import it.palinaroma.ponte.StatoSegui
import it.palinaroma.ponte.testoJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Avvisami" sul telefono Android: il calcolo e' nel motore condiviso con
 * l'iPhone ([MotoreSegui]); qui si tiene lo stato per le schermate, lo si
 * salva (per riprenderlo se Android chiude l'app) e si avvia il servizio che
 * aggiorna la notifica sulla schermata di blocco.
 */
object Avvisatore {
    private val _stato = MutableStateFlow<StatoSegui?>(null)
    val stato: StateFlow<StatoSegui?> = _stato.asStateFlow()

    private var motore: MotoreSegui? = null
    private var orarioDelMotore: Orario? = null

    private fun memoria(context: Context) = context.getSharedPreferences("palina", Context.MODE_PRIVATE)

    /** Il motore per l'orario aperto (un seguimento salvato si riprende). */
    fun motore(context: Context): MotoreSegui? {
        val orario = Archivio.orario.value ?: return null
        if (orarioDelMotore !== orario || motore == null) {
            val nuovo = MotoreSegui(orario)
            val prima = motore?.seguimentoAttuale()
            motore = nuovo
            orarioDelMotore = orario
            val json = prima?.testoJson() ?: memoria(context).getString("avviso", null)
            if (json != null) pubblica(context, nuovo.riprendi(json))
        }
        return motore
    }

    /** Lo stato nuovo: lo si salva e lo vedono schermate e notifica. */
    fun pubblica(context: Context, s: StatoSegui?) {
        _stato.value = s
        memoria(context).edit().apply {
            if (s == null) remove("avviso") else putString("avviso", s.json)
        }.apply()
    }

    /** Se c'era un avviso in corso quando Android ha chiuso l'app, il servizio riparte. */
    fun riprendiSeServe(context: Context) {
        if (memoria(context).getString("avviso", null) == null) return
        val s = motore(context)?.stato()
        pubblica(context, s)
        if (s != null) avviaServizio(context)
    }

    suspend fun proposta(context: Context, fermataId: String, lineaId: String, capolinea: String, quando: String): PropostaAvviso? {
        val m = motore(context) ?: return null
        val posizione = if (haPermessoPosizione(context)) posizioneAttuale(context, 5_000) else null
        return m.proposta(fermataId, lineaId, capolinea, quando, posizione != null, posizione?.latitude ?: 0.0, posizione?.longitude ?: 0.0)
    }

    /** Attiva "Avvisami" ([quando] "" = "Segui" con avviso 2 minuti prima). Null se non c'e' un passaggio da seguire. */
    suspend fun avvia(context: Context, fermataId: String, lineaId: String, capolinea: String, quando: String, scendi: Boolean, blocco: Boolean = true): StatoSegui? {
        val m = motore(context) ?: return null
        val posizione = if (quando == "uscire" && haPermessoPosizione(context)) posizioneAttuale(context, 5_000) else null
        val s = m.avvisami(fermataId, lineaId, capolinea, quando, posizione != null, posizione?.latitude ?: 0.0, posizione?.longitude ?: 0.0, scendi, blocco)
        pubblica(context, s)
        if (s != null) avviaServizio(context)
        return s
    }

    suspend fun prendiSuccessivo(context: Context) {
        val m = motore(context) ?: return
        pubblica(context, m.prendiSuccessivo())
        aggiornaServizio(context)
    }

    fun stoUscendo(context: Context) {
        val m = motore(context) ?: return
        pubblica(context, m.stoUscendo())
        aggiornaServizio(context)
    }

    suspend fun fermateDiscesa(context: Context): List<FermataDiscesa> = motore(context)?.fermateDiscesa().orEmpty()

    suspend fun scendiA(context: Context, fermataId: String) {
        val m = motore(context) ?: return
        pubblica(context, m.scendiA(fermataId))
        aggiornaServizio(context)
    }

    fun smetti(context: Context) {
        smettiQui(context)
        context.startService(Intent(context, ServizioAvviso::class.java).setAction(ServizioAvviso.FERMA))
    }

    /** Smette senza fermare il servizio (lo chiama il servizio stesso). */
    fun smettiQui(context: Context) {
        motore?.smetti()
        pubblica(context, null)
    }

    /** Le notifiche si possono mostrare (da Android 13 serve il permesso). */
    fun notificheConsentite(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun avviaServizio(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ServizioAvviso::class.java))
    }

    private fun aggiornaServizio(context: Context) {
        if (_stato.value != null) avviaServizio(context)
    }
}
