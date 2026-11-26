package it.palinaroma.sfondo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.palinaroma.MainActivity
import it.palinaroma.R
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.NotificaAvviso
import it.palinaroma.dati.NotificaPromemoria
import it.palinaroma.dati.controllaAvvisiPerTe
import it.palinaroma.dati.leTueLinee
import it.palinaroma.dati.notificaPromemoria
import it.palinaroma.dati.promemoriaDaMostrare
import it.palinaroma.dati.promemoriaValidi
import it.palinaroma.dati.prossimoPromemoria
import it.palinaroma.dati.tx
import it.palinaroma.ponte.CodificaWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Il lavoro in sottofondo: i promemoria ricorrenti (all'ora scelta una notifica
 * con i tempi veri, da li' "Avvisami") e il controllo degli avvisi di Roma
 * Mobilita' sulle tue linee. Le regole e i testi sono nel motore, uguali
 * sull'iPhone.
 */

object Promemorie {
    const val CANALE = "promemoria"
    const val SCATTA = "it.palinaroma.promemoria.SCATTA"
    private const val LAVORO = 4712

    fun preparaCanale(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CANALE, tx("Promemoria ricorrenti"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tx("All'ora che hai scelto: quando passa la tua linea o quando uscire")
                setShowBadge(false)
            },
        )
    }

    private fun allarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 5, Intent(context, RicevitoreSfondo::class.java).setAction(SCATTA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Si possono usare le sveglie esatte (Android 12+ le fa consentire)? */
    fun oraEsatta(context: Context): Boolean {
        val allarmi = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()
    }

    /** Programma la prossima volta (una sola sveglia per tutti i promemoria). */
    fun programma(context: Context) {
        val allarmi = context.getSystemService(AlarmManager::class.java) ?: return
        val elenco = promemoriaValidi(Archivio.promemoria.value, Archivio.salvati.value)
        val prossima = prossimoPromemoria(elenco, System.currentTimeMillis())
        val intento = allarme(context)
        if (prossima == null) {
            allarmi.cancel(intento)
            return
        }
        runCatching {
            if (oraEsatta(context)) {
                allarmi.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prossima, intento)
            } else {
                allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prossima, intento)
            }
        }
    }

    /** All'ora del promemoria: i tempi si chiedono in un lavoro con la rete (il ricevitore ha pochi secondi). */
    fun scatta(context: Context) {
        val pianificatore = context.getSystemService(JobScheduler::class.java) ?: return
        fun lavoro() = JobInfo.Builder(LAVORO, ComponentName(context, LavoroSfondo::class.java))
            .setExtras(PersistableBundle().apply { putString("cosa", "promemoria") })
        // subito (un lavoro "urgente" non puo' avere una scadenza); se Android lo rifiuta, appena puo'
        val urgente = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { pianificatore.schedule(lavoro().setExpedited(true).build()) }.getOrDefault(JobScheduler.RESULT_FAILURE) == JobScheduler.RESULT_SUCCESS
        if (!urgente) runCatching { pianificatore.schedule(lavoro().setOverrideDeadline(0).build()) }
    }

    fun mostra(context: Context, n: NotificaPromemoria) {
        val apri = PendingIntent.getActivity(
            context, n.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.APRI, n.apri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(n.titolo)
            .setContentText(n.testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.testo))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(45 * 60_000L)
            .setContentIntent(apri)
        if (n.avvisami.isNotEmpty()) {
            val avvisa = PendingIntent.getActivity(
                context, n.id.hashCode() + 1,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.APRI, n.avvisami),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(NotificationCompat.Action.Builder(null, tx("Avvisami"), avvisa).build())
        }
        runCatching { NotificationManagerCompat.from(context).notify("promemoria", n.id.hashCode(), b.build()) }
    }

    /** Il lavoro: le notifiche dei promemoria di adesso, poi la prossima sveglia. */
    suspend fun esegui(context: Context) {
        withTimeoutOrNull(20_000) { Archivio.pronto.first { it } }
        val orario = Archivio.orario.value
        val adesso = System.currentTimeMillis()
        val daMostrare = promemoriaDaMostrare(promemoriaValidi(Archivio.promemoria.value, Archivio.salvati.value), adesso)
        if (orario != null) {
            for (p in daMostrare) {
                val n = withTimeoutOrNull(25_000) {
                    withContext(Dispatchers.IO) {
                        notificaPromemoria(p, orario, Archivio.nomi.value, Archivio.salvati.value, Archivio.posizioneRicordata())
                    }
                }
                mostra(context, n ?: NotificaPromemoria(p.id, adesso, p.nomeLinea, tx("Tocca per vedere i tempi."), "fermata:" + p.fermataId, ""))
            }
        }
        programma(context)
    }
}

/** Le sveglie dei promemoria e il riavvio del telefono (o l'ora cambiata): si riprogramma. */
class RicevitoreSfondo : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Promemorie.SCATTA -> Promemorie.scatta(context)
            else -> {
                Promemorie.programma(context)
                AvvisiLinee.programma(context)
            }
        }
    }
}

/** Il lavoro con la rete: i promemoria di adesso o il controllo degli avvisi. */
class LavoroSfondo : JobService() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(params: JobParameters): Boolean {
        ambito.launch {
            try {
                when (params.extras.getString("cosa")) {
                    "promemoria" -> Promemorie.esegui(applicationContext)
                    "avvisi" -> AvvisiLinee.esegui(applicationContext)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }
}

/**
 * "Avvisi sulle tue linee": ogni due ore circa (quando c'e' rete) si leggono gli
 * avvisi di Roma Mobilita'; quelli nuovi sulle tue linee e fermate, e lo
 * sciopero di domani, diventano una notifica.
 */
object AvvisiLinee {
    const val CANALE = "avvisi_linee"
    private const val LAVORO = 4713

    fun preparaCanale(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CANALE, tx("Avvisi sulle tue linee"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tx("Deviazioni, lavori e scioperi che riguardano le tue linee e fermate")
                setShowBadge(true)
            },
        )
    }

    /** Il controllo periodico, se e' acceso (altrimenti si toglie). */
    fun programma(context: Context) {
        val pianificatore = context.getSystemService(JobScheduler::class.java) ?: return
        if (!Archivio.avvisiLinee.value) {
            pianificatore.cancel(LAVORO)
            return
        }
        if (pianificatore.getPendingJob(LAVORO) != null) return
        val lavoro = JobInfo.Builder(LAVORO, ComponentName(context, LavoroSfondo::class.java))
            .setExtras(PersistableBundle().apply { putString("cosa", "avvisi") })
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(2 * 3600_000L, 30 * 60_000L)
            .setPersisted(true)
        runCatching { pianificatore.schedule(lavoro.build()) }
    }

    suspend fun esegui(context: Context) {
        if (!Archivio.avvisiLinee.value) return
        withTimeoutOrNull(20_000) { Archivio.pronto.first { it } }
        val orario = Archivio.orario.value
        val tue = withContext(Dispatchers.IO) {
            // le partenze dei tragitti salvati: in memoria, o quelle salvate per i widget
            val partenze = Archivio.partenze.value.values.toList().ifEmpty { CodificaWidget.leggi(Archivio.datiWidget())?.partenze?.values?.toList().orEmpty() }
            leTueLinee(orario, Archivio.preferiti.value, Archivio.fissate.value, partenze, Archivio.promemoria.value)
        }
        val visti = Archivio.avvisiVisti()
        val esito = withContext(Dispatchers.IO) { controllaAvvisiPerTe(orario, tue, visti) } ?: return
        Archivio.salvaAvvisiVisti(esito.visti)
        esito.notifiche.forEach { mostra(context, it) }
    }

    fun mostra(context: Context, n: NotificaAvviso) {
        val apri = PendingIntent.getActivity(
            context, n.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.APRI, n.apri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(n.titolo)
            .setContentText(n.testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.testo))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(apri)
        runCatching { NotificationManagerCompat.from(context).notify("avvisi", n.id.hashCode(), b.build()) }
    }
}
