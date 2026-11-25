package it.palinaroma.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import it.palinaroma.MainActivity
import it.palinaroma.R
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.tx
import it.palinaroma.ponte.CodificaWidget
import it.palinaroma.ponte.DatiWidget
import it.palinaroma.ponte.RigaWidgetTelefono
import it.palinaroma.ponte.calcolaDatiWidget
import it.palinaroma.ponte.widgetTabellone
import it.palinaroma.ponte.widgetTragitto
import it.palinaroma.ponte.widgetVicina
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * I widget del telefono: il tabellone della preferita (linee fissate), la
 * fermata piu' vicina e "esci tra" per un tragitto salvato. I contenuti sono
 * del motore (uguali ai widget dell'iPhone); qui si disegnano con RemoteViews.
 *
 * I tempi si ridisegnano ogni minuto dai dati salvati, senza rete e solo
 * mentre lo schermo e' acceso (allarme che non sveglia il telefono). I dati si
 * richiedono (Roma Mobilita', Transitous) al massimo ogni 15 minuti, quando
 * si tocca "aggiorna", e quando si esce da Palina.
 */

/** Il widget grande: la tua fermata. */
class WidgetTabellone : WidgetPalina()

/** Il widget piccolo: la fermata piu' vicina. */
class WidgetVicina : WidgetPalina()

/** Il widget "esci tra" del tragitto salvato. */
class WidgetTragitto : WidgetPalina()

abstract class WidgetPalina : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widget.disegnaTutti(context)
        Widget.programmaMinuto(context)
        Widget.chiediDati(context, forza = false)
    }

    override fun onEnabled(context: Context) {
        Widget.programmaMinuto(context)
        Widget.chiediDati(context, forza = true)
    }

    override fun onDisabled(context: Context) {
        if (!Widget.ciSonoWidget(context)) Widget.fermaMinuto(context)
    }
}

/** Ogni minuto (a schermo acceso) e al tocco di "aggiorna". */
class RicevitoreWidget : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Widget.MINUTO -> {
                if (!Widget.ciSonoWidget(context)) return
                Widget.disegnaTutti(context)
                Widget.programmaMinuto(context)
                // dati vecchi di un quarto d'ora: si richiedono
                val dati = CodificaWidget.leggi(Archivio.datiWidget())
                if (dati == null || System.currentTimeMillis() - dati.creato > 15 * 60_000L) Widget.chiediDati(context, forza = false)
            }
            Widget.TOCCA -> Widget.chiediDati(context, forza = true)
        }
    }
}

/** Il calcolo dei dati dei widget (rete e orario), fuori dal ricevitore che ha pochi secondi. */
class LavoroWidget : JobService() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(params: JobParameters): Boolean {
        ambito.launch {
            try {
                Widget.calcola(applicationContext, params.extras.getBoolean("forza", false))
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

object Widget {
    const val MINUTO = "it.palinaroma.widget.MINUTO"
    const val TOCCA = "it.palinaroma.widget.TOCCA"
    private const val LAVORO = 4711

    private val classi = listOf(WidgetTabellone::class.java, WidgetVicina::class.java, WidgetTragitto::class.java)

    private fun ids(context: Context, classe: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, classe))

    fun ciSonoWidget(context: Context): Boolean = classi.any { ids(context, it).isNotEmpty() }

    // --- quando

    private fun minuto(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 1, Intent(context, RicevitoreWidget::class.java).setAction(MINUTO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Il prossimo ridisegno tra un minuto, solo a telefono sveglio (ELAPSED_REALTIME non sveglia). */
    fun programmaMinuto(context: Context) {
        val allarmi = context.getSystemService(AlarmManager::class.java) ?: return
        val adesso = System.currentTimeMillis()
        val alProssimoMinuto = 60_000 - adesso % 60_000 + 500
        allarmi.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + alProssimoMinuto, minuto(context))
    }

    fun fermaMinuto(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(minuto(context))
    }

    /** Chiede di ricalcolare i dati (con la rete). [forza]: anche le partenze dei tragitti. */
    fun chiediDati(context: Context, forza: Boolean) {
        if (!ciSonoWidget(context)) return
        val pianificatore = context.getSystemService(JobScheduler::class.java) ?: return
        val lavoro = JobInfo.Builder(LAVORO, ComponentName(context, LavoroWidget::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putBoolean("forza", forza) })
        val subito = forza && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (subito) lavoro.setExpedited(true)
        val esito = runCatching { pianificatore.schedule(lavoro.build()) }
        // un lavoro "subito" puo' essere rifiutato (quote di Android): allora quando si puo'
        if (subito && esito.isFailure && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { pianificatore.schedule(lavoro.setExpedited(false).build()) }
        }
    }

    /** Calcola i dati dei widget con il motore e li ridisegna. */
    suspend fun calcola(context: Context, forza: Boolean) {
        withTimeoutOrNull(20_000) { Archivio.pronto.first { it } }
        val orario = Archivio.orario.value ?: return
        val prima = CodificaWidget.leggi(Archivio.datiWidget())
        val dati = calcolaDatiWidget(
            orario,
            Archivio.preferiti.value,
            Archivio.fissate.value,
            Archivio.posizioneRicordata(),
            Archivio.salvati.value,
            prima?.partenze,
            forza,
        )
        Archivio.salvaDatiWidget(CodificaWidget.scrivi(dati))
        disegnaTutti(context)
    }

    // --- cosa si disegna

    fun disegnaTutti(context: Context) {
        val dati = CodificaWidget.leggi(Archivio.datiWidget())
        val gestore = AppWidgetManager.getInstance(context)
        val adesso = System.currentTimeMillis()
        for (classe in classi) {
            val ids = ids(context, classe)
            if (ids.isEmpty()) continue
            val vista = when (classe) {
                WidgetTabellone::class.java -> tabellone(context, dati, adesso)
                WidgetVicina::class.java -> vicina(context, dati, adesso)
                else -> tragitto(context, dati, adesso)
            }
            runCatching { gestore.updateAppWidget(ids, vista) }
        }
    }

    /** Un widget disegnato ([tipo] "tabellone", "vicina", "tragitto"): per le prove. */
    internal fun vista(context: Context, tipo: String, dati: DatiWidget?, adessoMs: Long): RemoteViews = when (tipo) {
        "tabellone" -> tabellone(context, dati, adessoMs)
        "vicina" -> vicina(context, dati, adessoMs)
        else -> tragitto(context, dati, adessoMs)
    }

    private fun apri(context: Context, codice: Int, cosa: String): PendingIntent =
        PendingIntent.getActivity(
            context, codice,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.APRI, cosa),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun tocca(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2, Intent(context, RicevitoreWidget::class.java).setAction(TOCCA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private val righe = listOf(
        intArrayOf(R.id.riga1, R.id.fondo1, R.id.linea1, R.id.capolinea1, R.id.tra1),
        intArrayOf(R.id.riga2, R.id.fondo2, R.id.linea2, R.id.capolinea2, R.id.tra2),
        intArrayOf(R.id.riga3, R.id.fondo3, R.id.linea3, R.id.capolinea3, R.id.tra3),
    )

    private fun riempiRighe(context: Context, v: RemoteViews, elenco: List<RigaWidgetTelefono>, quante: Int, conCapolinea: Boolean) {
        val tenue = context.getColor(R.color.widget_tenue)
        for ((i, id) in righe.take(quante).withIndex()) {
            val r = elenco.getOrNull(i)
            if (r == null) {
                v.setViewVisibility(id[0], View.GONE)
                continue
            }
            v.setViewVisibility(id[0], View.VISIBLE)
            v.setInt(id[1], "setColorFilter", r.colore.toInt())
            v.setTextViewText(id[2], r.linea)
            if (conCapolinea) {
                val testo = SpannableStringBuilder(r.capolinea)
                if (r.poi.isNotEmpty()) {
                    val inizio = testo.length
                    testo.append(" · ").append(r.poi)
                    testo.setSpan(ForegroundColorSpan(tenue), inizio, testo.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.setTextViewText(id[3], testo)
            } else {
                v.setTextViewText(id[3], "")
            }
            v.setTextViewText(id[4], r.tra)
            v.setContentDescription(id[0], r.perVoce)
        }
    }

    private fun tabellone(context: Context, dati: DatiWidget?, adessoMs: Long): RemoteViews {
        val w = widgetTabellone(dati, adessoMs / 1000)
        val v = RemoteViews(context.packageName, R.layout.widget_tabellone)
        v.setTextViewText(R.id.etichetta, w.etichetta)
        v.setTextViewText(R.id.nome, w.nome)
        v.setTextViewText(R.id.ora, w.ora)
        v.setTextViewText(R.id.nota, w.nota)
        riempiRighe(context, v, w.righe, 3, conCapolinea = true)
        v.setContentDescription(R.id.aggiorna, tx("Aggiorna"))
        v.setOnClickPendingIntent(R.id.aggiorna, tocca(context))
        v.setOnClickPendingIntent(R.id.nota, tocca(context))
        v.setOnClickPendingIntent(R.id.radice, apri(context, 11, if (w.fermataId.isNotEmpty()) "fermata:" + w.fermataId else "home"))
        return v
    }

    private fun vicina(context: Context, dati: DatiWidget?, adessoMs: Long): RemoteViews {
        val w = widgetVicina(dati, adessoMs / 1000)
        val v = RemoteViews(context.packageName, R.layout.widget_vicina)
        v.setTextViewText(R.id.etichetta, tx("PIÙ VICINA"))
        v.setTextViewText(R.id.nome, if (w.distanza.isNotEmpty()) w.nome + "  " + w.distanza else w.nome)
        v.setTextViewText(R.id.nota, w.nota.orEmpty())
        v.setViewVisibility(R.id.nota, if (w.nota.isNullOrEmpty()) View.GONE else View.VISIBLE)
        riempiRighe(context, v, w.righe, 2, conCapolinea = false)
        v.setOnClickPendingIntent(R.id.radice, apri(context, 12, if (w.fermataId.isNotEmpty()) "fermata:" + w.fermataId else "vicina"))
        return v
    }

    private fun tragitto(context: Context, dati: DatiWidget?, adessoMs: Long): RemoteViews {
        val c = widgetTragitto(dati, adessoMs)
        val v = RemoteViews(context.packageName, R.layout.widget_tragitto)
        if (c == null) {
            v.setTextViewText(R.id.nome, tx("Esci tra"))
            v.setTextViewText(R.id.esci, "")
            v.setTextViewText(R.id.numero, "")
            v.setTextViewText(R.id.unita, "")
            v.setTextViewText(R.id.sotto, tx("Salva un tragitto in «Come arrivo» per vedere quando uscire."))
            v.setOnClickPendingIntent(R.id.radice, apri(context, 13, "tragitto"))
            return v
        }
        v.setTextViewText(R.id.nome, c.nome)
        v.setTextViewText(R.id.esci, c.esci)
        v.setTextViewText(R.id.numero, c.numero)
        v.setTextViewText(R.id.unita, c.unita)
        v.setTextViewText(R.id.sotto, c.sotto)
        v.setContentDescription(R.id.radice, c.perVoce)
        v.setOnClickPendingIntent(R.id.radice, apri(context, 13, "tragitto:" + c.id))
        return v
    }
}
