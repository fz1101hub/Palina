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
import android.graphics.Typeface
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
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
 * si tocca il widget ("tocca per aggiornare"), e quando si esce da Palina.
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

    /** Ridimensionato: quante righe ci stanno, quanto grande il numero. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, opzioni: android.os.Bundle) {
        Widget.disegnaTutti(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Widget.salvaScelta(context, it, null) }
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

    // --- la scelta di ogni widget: la fermata o il tragitto ("" = automatica)

    private fun memoria(context: Context) = context.getSharedPreferences("palina", Context.MODE_PRIVATE)

    fun scelta(context: Context, id: Int): String = memoria(context).getString("widget_scelta_$id", "").orEmpty()

    fun salvaScelta(context: Context, id: Int, valore: String?) {
        memoria(context).edit().apply {
            if (valore == null) remove("widget_scelta_$id") else putString("widget_scelta_$id", valore)
        }.apply()
    }

    /** "tabellone", "vicina" o "tragitto". */
    fun tipo(context: Context, id: Int): String = when (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className) {
        WidgetTragitto::class.java.name -> "tragitto"
        WidgetVicina::class.java.name -> "vicina"
        else -> "tabellone"
    }

    /** Le scelte dei widget di una classe (le fermate o i tragitti), senza le automatiche. */
    private fun scelte(context: Context, classe: Class<*>): List<String> =
        ids(context, classe).map { scelta(context, it) }.filter { it.isNotEmpty() }.distinct()

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
            prima?.partenze.orEmpty(),
            forza,
            fermateWidget = scelte(context, WidgetTabellone::class.java),
            tragittiWidget = scelte(context, WidgetTragitto::class.java),
            nomi = Archivio.nomi.value,
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
            for (id in ids(context, classe)) {
                val scelta = scelta(context, id)
                val vista = when (classe) {
                    WidgetTabellone::class.java -> tabellone(context, dati, adesso, scelta, id)
                    WidgetVicina::class.java -> vicina(context, dati, adesso)
                    else -> tragitto(context, dati, adesso, scelta, id)
                }
                runCatching { gestore.updateAppWidget(id, vista) }
            }
        }
    }

    /** Un widget disegnato ([tipo] "tabellone", "vicina", "tragitto"): per le prove. */
    internal fun vista(context: Context, tipo: String, dati: DatiWidget?, adessoMs: Long, scelta: String = ""): RemoteViews = when (tipo) {
        "tabellone" -> tabellone(context, dati, adessoMs, scelta, 0)
        "vicina" -> vicina(context, dati, adessoMs)
        else -> tragitto(context, dati, adessoMs, scelta, 0)
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

    /** L'altezza del widget in dp (in verticale), 0 se non si sa (prove, launcher vecchi). */
    private fun altezza(context: Context, id: Int): Int =
        if (id == 0) 0 else runCatching { AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) }.getOrDefault(0)

    /** Le righe da 46dp del disegno che stanno nel widget: testata, tratteggio e nota prendono circa 82dp. */
    private fun righeCheStanno(altezza: Int): Int = if (altezza <= 0) 2 else ((altezza - 82) / 47).coerceIn(1, 3)

    private val righe = listOf(
        intArrayOf(R.id.riga1, R.id.fondo1, R.id.linea1, R.id.capolinea1, R.id.tra1),
        intArrayOf(R.id.riga2, R.id.fondo2, R.id.linea2, R.id.capolinea2, R.id.tra2),
        intArrayOf(R.id.riga3, R.id.fondo3, R.id.linea3, R.id.capolinea3, R.id.tra3),
    )

    private val tratti = intArrayOf(R.id.tratto1, R.id.tratto2)

    private fun riempiRighe(context: Context, v: RemoteViews, elenco: List<RigaWidgetTelefono>, quante: Int, conCapolinea: Boolean) {
        val tenue = context.getColor(R.color.widget_tenue)
        if (conCapolinea) tratti.forEach { v.setViewVisibility(it, View.GONE) }
        for ((i, id) in righe.withIndex()) {
            if (i >= quante) {
                if (conCapolinea || i < 2) v.setViewVisibility(id[0], View.GONE)
                continue
            }
            val r = elenco.getOrNull(i)
            if (r == null) {
                v.setViewVisibility(id[0], View.GONE)
                continue
            }
            v.setViewVisibility(id[0], View.VISIBLE)
            // il tratteggio sopra la riga, come nel disegno
            if (i > 0 && conCapolinea) v.setViewVisibility(tratti[i - 1], View.VISIBLE)
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

    private fun tabellone(context: Context, dati: DatiWidget?, adessoMs: Long, scelta: String, id: Int): RemoteViews {
        val w = widgetTabellone(dati, adessoMs / 1000, fermataId = scelta)
        val v = RemoteViews(context.packageName, R.layout.widget_tabellone)
        v.setTextViewText(R.id.etichetta, w.etichetta)
        v.setTextViewText(R.id.nome, w.nome)
        v.setTextViewText(R.id.ora, w.ora)
        v.setTextViewText(R.id.nota, w.nota)
        riempiRighe(context, v, w.righe, righeCheStanno(altezza(context, id)), conCapolinea = true)
        // come nel disegno: la testata e le righe aprono la fermata, il resto ("tocca per aggiornare") aggiorna
        val apriFermata = apri(context, 1000 + id, if (w.fermataId.isNotEmpty()) "fermata:" + w.fermataId else "home")
        v.setOnClickPendingIntent(R.id.testata, apriFermata)
        v.setOnClickPendingIntent(R.id.righe, apriFermata)
        v.setOnClickPendingIntent(R.id.radice, tocca(context))
        return v
    }

    private fun vicina(context: Context, dati: DatiWidget?, adessoMs: Long): RemoteViews {
        val w = widgetVicina(dati, adessoMs / 1000)
        val v = RemoteViews(context.packageName, R.layout.widget_vicina)
        v.setTextViewText(R.id.etichetta, tx("PIÙ VICINA"))
        // "Plebiscito 121 m": il nome in grassetto, la distanza piu' piccola e tenue
        val nome = SpannableStringBuilder(w.nome)
        nome.setSpan(StyleSpan(Typeface.BOLD), 0, nome.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (w.distanza.isNotEmpty()) {
            val inizio = nome.length
            nome.append(" ").append(w.distanza)
            nome.setSpan(ForegroundColorSpan(context.getColor(R.color.widget_tenue)), inizio, nome.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            nome.setSpan(AbsoluteSizeSpan(12, true), inizio, nome.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.setTextViewText(R.id.nome, nome)
        v.setTextViewText(R.id.nota, w.nota.orEmpty())
        v.setViewVisibility(R.id.nota, if (w.nota.isNullOrEmpty()) View.GONE else View.VISIBLE)
        riempiRighe(context, v, w.righe, 2, conCapolinea = false)
        v.setOnClickPendingIntent(R.id.radice, apri(context, 12, if (w.fermataId.isNotEmpty()) "fermata:" + w.fermataId else "vicina"))
        return v
    }

    private fun tragitto(context: Context, dati: DatiWidget?, adessoMs: Long, scelta: String, id: Int): RemoteViews {
        val c = widgetTragitto(dati, adessoMs, scelta)
        val v = RemoteViews(context.packageName, R.layout.widget_tragitto)
        if (c == null) {
            v.setTextViewText(R.id.nome, tx("Esci tra"))
            v.setTextViewText(R.id.esci, "")
            v.setTextViewText(R.id.numero, "")
            v.setTextViewText(R.id.unita, "")
            v.setTextViewText(R.id.viaggio, "")
            v.setTextViewText(
                R.id.sotto,
                if (scelta.isNotEmpty() && dati != null) tx("Apri Palina per aggiornare") else tx("Salva un tragitto in «Come arrivo» per vedere quando uscire."),
            )
            v.setOnClickPendingIntent(R.id.radice, apri(context, 13, "tragitto"))
            return v
        }
        v.setTextViewText(R.id.nome, c.nome)
        // il numero grande del disegno (52) se il widget e' alto abbastanza
        val alto = altezza(context, id).let { it == 0 || it >= 160 }
        v.setTextViewTextSize(R.id.numero, TypedValue.COMPLEX_UNIT_SP, if (alto) 52f else 40f)
        v.setTextViewText(R.id.esci, c.esci)
        v.setTextViewText(R.id.numero, c.numero)
        v.setTextViewText(R.id.unita, c.unita)
        v.setTextViewText(R.id.sotto, c.sotto)
        v.setTextViewText(R.id.viaggio, c.viaggio)
        v.setContentDescription(R.id.radice, c.perVoce)
        v.setOnClickPendingIntent(R.id.radice, apri(context, 2000 + id, "tragitto:" + c.id))
        return v
    }
}
