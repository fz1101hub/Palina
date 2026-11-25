package it.palinaroma.avviso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import it.palinaroma.MainActivity
import it.palinaroma.R
import it.palinaroma.dati.tx
import it.palinaroma.ponte.AvvisoOra
import it.palinaroma.ponte.StatoSegui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Le notifiche di "Avvisami": quella in corso (schermata di blocco) e gli avvisi con suono. */
object NotificheAvviso {
    const val CANALE_IN_CORSO = "avviso_in_corso"
    const val CANALE_AVVISO = "avviso"
    const val ID = 21
    private const val ID_AVVISO = 22

    fun preparaCanali(context: Context) {
        val gestore = context.getSystemService(NotificationManager::class.java) ?: return
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_IN_CORSO, tx("Avviso in corso"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tx("Quanto manca al bus o al treno, sulla schermata di blocco")
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_AVVISO, tx("Avvisi per uscire e scendere"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tx("«Esci ora», il mezzo in arrivo e «Scendi alla prossima»")
                enableVibration(true)
                setShowBadge(false)
            },
        )
    }

    /** Apre Palina (sulla scelta della fermata dove scendere, se serve). */
    fun apri(context: Context, scendi: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, if (scendi) 31 else 30,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.APRI, if (scendi) "scendi" else "home"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun azione(context: Context, codice: Int, azione: String): PendingIntent =
        PendingIntent.getService(
            context, codice,
            Intent(context, ServizioAvviso::class.java).setAction(azione),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * La notifica in corso: titolo ("Esci ora", "64 tra 3 minuti", "Scendi alla
     * prossima"), il testo, la barra fino al passaggio e il conto alla rovescia.
     * Da Android 16 e' un Live Update in evidenza (anche nella barra di stato).
     */
    fun inCorso(context: Context, s: StatoSegui): Notification {
        val adesso = System.currentTimeMillis()
        val progresso = if (s.fineMs > s.inizioMs) {
            (((adesso - s.inizioMs).toDouble() / (s.fineMs - s.inizioMs)) * 100).toInt().coerceIn(0, 100)
        } else {
            100
        }
        val b = NotificationCompat.Builder(context, CANALE_IN_CORSO)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(s.titoloScheda)
            .setContentText(s.testoScheda)
            .setSubText(listOf(s.linea, s.dettaglio).filter { it.isNotBlank() }.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.testoScheda))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(s.colore.toInt())
            .setRequestPromotedOngoing(true)
            .setContentIntent(apri(context, s.mostraScegli))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s.contoMs > adesso) {
            b.setWhen(s.contoMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            b.setShortCriticalText(if (s.numero.isNotEmpty()) s.numero + " " + s.unita else s.unita)
        } else if (s.unita.isNotEmpty()) {
            b.setShortCriticalText(s.unita)
        }
        if (s.fineMs > s.inizioMs) b.setProgress(100, progresso, false)
        if (s.mostraUscito) b.addAction(NotificationCompat.Action.Builder(null, tx("Sto uscendo"), azione(context, 41, ServizioAvviso.USCITO)).build())
        if (s.mostraSuccessivo) b.addAction(NotificationCompat.Action.Builder(null, tx("Prendo il successivo"), azione(context, 42, ServizioAvviso.SUCCESSIVO)).build())
        if (s.mostraScegli) b.addAction(NotificationCompat.Action.Builder(null, tx("Scegli dove scendi"), apri(context, true)).build())
        b.addAction(
            NotificationCompat.Action.Builder(null, if (s.mostraFine) tx("Fine viaggio") else tx("Smetti"), azione(context, 43, ServizioAvviso.FERMA)).build(),
        )
        return b.build()
    }

    /** L'avviso con suono e vibrazione. */
    fun avviso(context: Context, a: AvvisoOra, scendi: Boolean): Notification =
        NotificationCompat.Builder(context, CANALE_AVVISO)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(a.titolo)
            .setContentText(a.testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.testo))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(apri(context, scendi))
            .build()

    fun mostra(context: Context, a: AvvisoOra, scendi: Boolean) {
        runCatching { NotificationManagerCompat.from(context).notify(ID_AVVISO, avviso(context, a, scendi)) }
    }
}

/**
 * Tiene aggiornato "Avvisami": i tempi ogni 30 secondi (il mezzo seguito si
 * sposta col tempo reale), la notifica sulla schermata di blocco, gli avvisi
 * all'ora giusta. Servizio in primo piano solo finche' dura l'attesa o il viaggio.
 */
class ServizioAvviso : Service() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lavoro: Job? = null
    private var ultimaNotifica: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificheAvviso.preparaCanali(this)
        val app = applicationContext
        when (intent?.action) {
            FERMA -> {
                Avvisatore.smettiQui(app)
                chiudi()
                return START_NOT_STICKY
            }
            USCITO -> Avvisatore.stoUscendo(app)
            SUCCESSIVO -> ambito.launch { Avvisatore.prendiSuccessivo(app) }
        }
        val s = Avvisatore.motore(app)?.stato() ?: Avvisatore.stato.value
        if (s == null) {
            chiudi()
            return START_NOT_STICKY
        }
        ultimaNotifica = null
        ServiceCompat.startForeground(
            this, NotificheAvviso.ID, NotificheAvviso.inCorso(this, s),
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
            val motore = Avvisatore.motore(app) ?: break
            val oraMs = System.currentTimeMillis()
            val s = (
                if (oraMs - ultimaRichiesta >= 30_000) {
                    ultimaRichiesta = oraMs
                    motore.aggiorna()
                } else {
                    motore.stato()
                }
                ) ?: break
            for (a in motore.avvisiDaMostrare()) NotificheAvviso.mostra(app, a, scendi = a.id == "scendi")
            val dopo = motore.stato() ?: break
            Avvisatore.pubblica(app, dopo)
            // si ripubblica solo quando cambia quello che si vede (il conto alla rovescia va da solo)
            val visibile = dopo.titoloScheda + dopo.testoScheda + dopo.dettaglio + dopo.fase + (System.currentTimeMillis() / 60_000)
            if (visibile != ultimaNotifica) {
                ultimaNotifica = visibile
                runCatching { gestore.notify(NotificheAvviso.ID, NotificheAvviso.inCorso(app, dopo)) }
            }
            // fino al prossimo avviso, ma al massimo 15 secondi
            val prossimo = listOf(dopo.avvisoAlleMs, dopo.discesaAlleMs).filter { it > System.currentTimeMillis() }.minOrNull()
            val attesa = prossimo?.let { (it - System.currentTimeMillis()).coerceIn(1_000, 15_000) } ?: 15_000
            if (s.fase != dopo.fase) continue
            delay(attesa)
        }
        Avvisatore.pubblica(app, null)
        chiudi()
    }

    private fun chiudi() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Da Android 15 un servizio di sincronizzazione dura al massimo 6 ore: qui si smette molto prima. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Avvisatore.smettiQui(applicationContext)
        chiudi()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    companion object {
        const val FERMA = "it.palinaroma.avviso.FERMA"
        const val USCITO = "it.palinaroma.avviso.USCITO"
        const val SUCCESSIVO = "it.palinaroma.avviso.SUCCESSIVO"
    }
}
