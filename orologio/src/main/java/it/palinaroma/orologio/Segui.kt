package it.palinaroma.orologio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import it.palinaroma.ponte.FermataOrologio
import it.palinaroma.ponte.LineaSeguita
import it.palinaroma.ponte.RigaOrologio
import it.palinaroma.ponte.TabelloneOrologio
import it.palinaroma.ponte.chiaveLinea
import it.palinaroma.ponte.oraBreve
import it.palinaroma.ponte.prossimi
import it.palinaroma.ponte.riga
import it.palinaroma.ponte.testoMinuti
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Per quanto si segue al massimo una linea (poi si smette da soli). */
const val DURATA_MASSIMA_MS = 2 * 60 * 60_000L

/** A quanti secondi dall'arrivo l'orologio vibra. */
const val AVVISO_SECONDI = 120L

/**
 * Una linea seguita sull'orologio: il prossimo passaggio a una fermata, con il
 * conto alla rovescia sul quadrante (Live Update da Wear OS 7, attivita' in
 * corso prima) finche' il mezzo non arriva.
 */
data class Seguimento(
    val fermataId: String,
    val fermata: String,
    /** Per ritrovare la riga nei tabelloni successivi (vedi [chiaveLinea]). */
    val chiaveLinea: String,
    val lineaId: String,
    val linea: String,
    val capolinea: String,
    val colore: Int,
    /** Il passaggio seguito, in secondi. */
    val obiettivo: Long,
    val tempoReale: Boolean,
    /** Quando si e' cominciato, in millisecondi. */
    val inizio: Long,
    /** Il tabellone da cui viene [obiettivo] ([TabelloneOrologio.creato]). */
    val aggiornato: Long,
    /** Ha gia' vibrato per l'arrivo. */
    val avvisato: Boolean = false,
) {
    /** Da chiedere al telefono, perche' la tenga nel tabellone anche lontano dalla fermata. */
    val lineaSeguita: LineaSeguita? get() = lineaId.takeIf { it.isNotEmpty() }?.let { LineaSeguita(fermataId, it) }

    fun stessaRiga(fermataId: String, chiaveLinea: String, capolinea: String): Boolean =
        this.fermataId == fermataId && this.chiaveLinea == chiaveLinea && this.capolinea == capolinea
}

/** Si comincia a seguire il prossimo passaggio della riga [r]; null se non ne ha. [adesso] in secondi. */
fun seguimento(f: FermataOrologio, r: RigaOrologio, t: TabelloneOrologio, adesso: Long): Seguimento? {
    val primo = r.prossimi(adesso).firstOrNull() ?: return null
    return Seguimento(
        fermataId = f.id,
        fermata = f.nome,
        chiaveLinea = r.chiaveLinea,
        lineaId = r.lineaId,
        linea = r.linea,
        capolinea = r.capolinea,
        colore = r.colore,
        obiettivo = primo,
        tempoReale = r.tempoReale,
        inizio = adesso * 1000,
        aggiornato = t.creato,
    )
}

/**
 * Il seguimento [s] con i tempi del tabellone [t]. Finche' il mezzo non e'
 * arrivato si segue il passaggio piu' vicino a quello di prima (il tempo reale
 * lo sposta); quando e' arrivato non si passa al successivo. Null quando e'
 * finito: passato da un minuto, o dopo [DURATA_MASSIMA_MS]. [adesso] in secondi.
 */
fun ricalcola(s: Seguimento, t: TabelloneOrologio?, adesso: Long): Seguimento? {
    if (adesso * 1000 - s.inizio > DURATA_MASSIMA_MS) return null
    var nuovo = s
    if (t != null && t.creato > s.aggiornato && s.obiettivo > adesso + 30) {
        val riga = t.riga(s.fermataId, s.chiaveLinea, s.capolinea)
        val prossimi = riga?.prossimi(adesso).orEmpty()
        val scelto = prossimi.minByOrNull { abs(it - s.obiettivo) }
        nuovo = if (riga != null && scelto != null) {
            s.copy(
                obiettivo = scelto,
                tempoReale = riga.tempoReale && scelto == prossimi.first(),
                aggiornato = t.creato,
                // se il mezzo e' in ritardo si vibra di nuovo quando si avvicina
                avvisato = s.avvisato && scelto - adesso <= AVVISO_SECONDI + 30,
            )
        } else {
            s.copy(aggiornato = t.creato)
        }
    }
    return nuovo.takeIf { it.obiettivo >= adesso - 60 }
}

/** "64 → Stazione San Pietro". */
fun titoloSegui(s: Seguimento): String = "${s.linea} → ${s.capolinea}"

/** "Piazza Venezia · tra 4' (14:05) · tempo reale". [adesso] in secondi. */
fun testoSegui(s: Seguimento, adesso: Long): String {
    val minuti = testoMinuti(s.obiettivo, adesso)
    val quando = if (minuti == "ora") "in arrivo" else "tra $minuti"
    return "${s.fermata} · $quando (${oraBreve(s.obiettivo * 1000)}) · " + if (s.tempoReale) "tempo reale" else "da orario"
}

/** Il seguimento in corso. */
object Seguito {
    private val _stato = MutableStateFlow<Seguimento?>(null)
    val stato: StateFlow<Seguimento?> = _stato.asStateFlow()

    internal fun imposta(s: Seguimento?) {
        _stato.value = s
    }

    /** Comincia (o cambia linea) e avvia il servizio che aggiorna il Live Update. */
    fun avvia(context: Context, s: Seguimento) {
        _stato.value = s
        ContextCompat.startForegroundService(context, Intent(context, ServizioSegui::class.java))
    }

    fun ferma(context: Context) {
        _stato.value = null
        context.startService(Intent(context, ServizioSegui::class.java).setAction(ServizioSegui.FERMA))
    }

    /** Le notifiche si possono mostrare (da Android 13 serve il permesso). */
    fun notificheConsentite(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** La notifica della linea seguita e l'avviso di arrivo. */
object NotificheSegui {
    const val CANALE_SEGUI = "segui"
    const val CANALE_ARRIVO = "arrivo"
    const val ID = 7
    const val ID_ARRIVO = 8

    fun preparaCanali(context: Context) {
        val gestore = context.getSystemService(NotificationManager::class.java) ?: return
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_SEGUI, context.getString(R.string.canale_segui), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.canale_segui_descrizione)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_ARRIVO, context.getString(R.string.canale_arrivo), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.canale_arrivo_descrizione)
                enableVibration(true)
                setShowBadge(false)
            },
        )
    }

    private fun apri(context: Context, s: Seguimento): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Principale.intentRiga(context, s.fermataId, s.chiaveLinea, s.capolinea),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * La notifica in corso. Da Wear OS 7 e' un Live Update: il sistema la mette
     * in evidenza e sul quadrante mostra il conto alla rovescia fino a "when"
     * (il passaggio), senza che l'app debba aggiornarla ogni minuto. Prima e'
     * un'attivita' in corso (Ongoing Activity), con l'icona sul quadrante.
     */
    fun notifica(context: Context, s: Seguimento, adesso: Long): Notification {
        val apri = apri(context, s)
        val smetti = PendingIntent.getService(
            context, 2,
            Intent(context, ServizioSegui::class.java).setAction(ServizioSegui.FERMA),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val inArrivo = s.obiettivo - adesso <= 60
        val b = NotificationCompat.Builder(context, CANALE_SEGUI)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titoloSegui(s))
            .setContentText(testoSegui(s, adesso))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setColor(s.colore)
            .setWhen(s.obiettivo * 1000)
            .setShowWhen(true)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(if (inArrivo) "ora" else null)
            .setContentIntent(apri)
            .addAction(NotificationCompat.Action.Builder(null, "Smetti", smetti).build())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT < 37) {
            val stato = Status.Builder()
                .addTemplate("#linea# · #tempo#")
                .addPart("linea", Status.TextPart(s.linea))
                .addPart("tempo", if (inArrivo) Status.TextPart("ora") else Status.TimerPart(s.obiettivo * 1000))
                .build()
            OngoingActivity.Builder(context, ID, b)
                .setStaticIcon(R.drawable.ic_notifica)
                .setTouchIntent(apri)
                .setStatus(stato)
                .setTitle(titoloSegui(s))
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .build()
                .apply(context)
        }
        return b.build()
    }

    /** La vibrazione quando il mezzo e' vicino. */
    fun avvisoArrivo(context: Context, s: Seguimento, adesso: Long): Notification {
        val minuti = testoMinuti(s.obiettivo, adesso)
        return NotificationCompat.Builder(context, CANALE_ARRIVO)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("${s.linea} in arrivo")
            .setContentText("${s.fermata}: " + (if (minuti == "ora") "ora" else "tra $minuti") + ", verso ${s.capolinea}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(apri(context, s))
            .build()
    }
}

/**
 * Tiene aggiornata la linea seguita: chiede i tempi al telefono ogni 30
 * secondi, aggiorna la notifica quando cambia qualcosa, vibra 2 minuti prima e
 * smette quando il mezzo e' passato. Servizio in primo piano (sincronizzazione
 * di dati con il telefono) solo per la durata dell'attesa.
 */
class ServizioSegui : Service() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lavoro: Job? = null
    private var ultimaNotifica: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Seguito.stato.value
        if (intent?.action == FERMA || s == null) {
            Seguito.imposta(null)
            chiudi()
            return START_NOT_STICKY
        }
        NotificheSegui.preparaCanali(this)
        val adesso = System.currentTimeMillis() / 1000
        ultimaNotifica = null
        ServiceCompat.startForeground(
            this, NotificheSegui.ID, NotificheSegui.notifica(this, s, adesso),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (lavoro?.isActive != true) lavoro = ambito.launch { segui() }
        return START_NOT_STICKY
    }

    private suspend fun segui() {
        val app = applicationContext
        val gestore = NotificationManagerCompat.from(app)
        var ultimaRichiesta = 0L
        while (true) {
            val oraMs = System.currentTimeMillis()
            if (oraMs - ultimaRichiesta >= 30_000) {
                ultimaRichiesta = oraMs
                ambito.launch { ArchivioOrologio.richiedi(app, forza = true) }
            }
            val adesso = oraMs / 1000
            val s = Seguito.stato.value ?: break
            var nuovo = ricalcola(s, ArchivioOrologio.tabellone.value, adesso) ?: break
            if (!nuovo.avvisato && nuovo.obiettivo - adesso <= AVVISO_SECONDI) {
                runCatching { gestore.notify(NotificheSegui.ID_ARRIVO, NotificheSegui.avvisoArrivo(app, nuovo, adesso)) }
                nuovo = nuovo.copy(avvisato = true)
            }
            if (Seguito.stato.value == s) Seguito.imposta(nuovo)
            // si ripubblica solo quando cambia quello che si vede
            val visibile = titoloSegui(nuovo) + testoSegui(nuovo, adesso)
            if (visibile != ultimaNotifica) {
                ultimaNotifica = visibile
                runCatching { gestore.notify(NotificheSegui.ID, NotificheSegui.notifica(app, nuovo, adesso)) }
            }
            // al prossimo tabellone, o tra 15 secondi
            withTimeoutOrNull(15_000) { ArchivioOrologio.tabellone.drop(1).first() }
        }
        Seguito.imposta(null)
        chiudi()
    }

    private fun chiudi() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Da Android 15 un servizio di sincronizzazione dura al massimo 6 ore: qui si smette molto prima, ma per sicurezza. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Seguito.imposta(null)
        chiudi()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    companion object {
        const val FERMA = "it.palinaroma.orologio.FERMA"
    }
}
