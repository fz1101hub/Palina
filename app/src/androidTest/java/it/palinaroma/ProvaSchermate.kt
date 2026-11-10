package it.palinaroma

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream

/**
 * Prova dell'app intera su un telefono simulato, come la userebbe una persona:
 * apre le schermate principali, tocca, scrive e salva una schermata per ogni
 * passaggio in /data/local/tmp/schermate (la compilazione su GitHub la scarica).
 *
 * Le parti che dipendono da servizi esterni (tempo reale, Transitous) accettano
 * anche il messaggio d'errore dell'app: si controlla che l'app non si blocchi e
 * che mostri qualcosa di sensato, non che quei servizi rispondano.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProvaSchermate {
    private val strumenti = InstrumentationRegistry.getInstrumentation()
    private val telefono = UiDevice.getInstance(strumenti)
    private val pacchetto = strumenti.targetContext.packageName
    private val prefisso = "api${Build.VERSION.SDK_INT}"

    /** Se una prova fallisce: schermata e struttura dello schermo nel registro, per capire cosa c'era. */
    @get:Rule
    val inCasoDiErrore = object : TestWatcher() {
        override fun failed(e: Throwable?, descrizione: Description) {
            schermata("ERRORE-" + descrizione.methodName)
            runCatching {
                val uscita = ByteArrayOutputStream()
                telefono.dumpWindowHierarchy(uscita)
                uscita.toString("UTF-8").chunked(3000).forEach { Log.i("ProvaSchermate", it) }
            }
        }
    }

    @Before
    fun avvia() {
        telefono.executeShellCommand("mkdir -p $CARTELLA")
        telefono.executeShellCommand("pm grant $pacchetto android.permission.ACCESS_FINE_LOCATION")
        telefono.executeShellCommand("pm grant $pacchetto android.permission.ACCESS_COARSE_LOCATION")
        telefono.wakeUp()
        chiudiAvvisiDiSistema()
        val intento = strumenti.targetContext.packageManager.getLaunchIntentForPackage(pacchetto)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        strumenti.targetContext.startActivity(intento)
        telefono.wait(Until.hasObject(By.pkg(pacchetto).depth(0)), 30_000)
        // l'orario incluso nell'app si copia al primo avvio: poi compaiono le schede
        aspetta(By.text("Vicino a me"), 60_000, "la schermata principale")
    }

    @Test
    fun a1_schermataPrincipaleConOrarioIncluso() {
        assertFalse("compare la richiesta di scaricare l'orario", telefono.hasObject(By.textContains("Benvenuto")))
        schermata("01-avvio")
    }

    @Test
    fun a2_fermateVicine() {
        // la posizione del telefono simulato e' in piazza Venezia
        aspetta(By.res("riga-fermata"), 90_000, "le fermate vicine")
        attendi(1_500)
        schermata("02-vicino-a-me")
    }

    @Test
    fun a3_cercaUnaFermataEApriLaConITempi() {
        tocca(By.text("Cerca"))
        scrivi("Termini")
        aspetta(By.res("riga-fermata"), 20_000, "i risultati della ricerca").click()
        val esito = aspettaUnoDi(
            90_000,
            "i tempi d'attesa",
            By.textContains("Aggiornato alle"),
            By.textContains("Non riesco a leggere i passaggi"),
        )
        attendi(2_000)
        schermata("03-fermata-" + if (esito == 0) "con-tempi" else "senza-rete")

        // preferito: lo si aggiunge qui e lo si ritrova nella scheda Preferiti
        tocca(By.desc("Aggiungi ai preferiti"))
        aspetta(By.desc("Togli dai preferiti"), 5_000, "il cuore pieno")
        telefono.pressBack()
        tocca(By.text("Preferiti"))
        aspetta(By.res("riga-fermata"), 10_000, "la fermata tra i preferiti")
        schermata("04-preferiti")
    }

    @Test
    fun a4_lineaSullaMappaConLeVetture() {
        tocca(By.text("Cerca"))
        scrivi("64")
        aspetta(By.res("linea"), 20_000, "la linea tra i risultati").click()
        aspetta(By.text("DIREZIONE"), 20_000, "la schermata della linea")
        aspettaUnoDi(
            60_000,
            "il conteggio delle vetture",
            By.textContains("GPS in questa direzione"),
        )
        attendi(4_000) // la mappa carica le immagini
        schermata("05-linea")

        telefono.findObject(By.text("Cambia"))?.let {
            it.click()
            attendi(4_000)
            schermata("06-linea-altra-direzione")
        }
        tocca(By.desc("Mostra dove sono"))
        attendi(4_000)
        schermata("07-linea-sei-qui")
    }

    @Test
    fun a5_tragittoFinoAlColosseo() {
        tocca(By.textStartsWith("Come arrivo"))
        tocca(By.res("campo-a"))
        scrivi("Colosseo")
        aspettaUnoDi(30_000, "i luoghi trovati", By.text("FERMATE"), By.text("INDIRIZZI E LUOGHI"))
        attendi(1_500)
        aspetta(By.res("luogo"), 10_000, "un luogo da scegliere").click()
        // non "Transitous" da solo: c'e' sempre, nella riga dell'attribuzione in fondo
        val esito = aspettaUnoDi(
            90_000,
            "i tragitti",
            By.textContains("Calcolato alle"),
            By.textContains("Non riesco a contattare Transitous"),
            By.textContains("Transitous non"),
            By.textContains("Transitous ha"),
            By.textContains("Non riesco a leggere la tua posizione"),
        )
        attendi(1_500)
        schermata("08-tragitti")
        if (esito == 0) {
            val scelta = telefono.findObject(By.res("tragitto")) ?: telefono.findObject(By.res("tragitto-a-piedi"))
            if (scelta != null) {
                scelta.click()
                aspetta(By.textStartsWith("Parti da"), 20_000, "il dettaglio del tragitto")
                attendi(4_000)
                schermata("09-tragitto-dettaglio")
            }
        }
    }

    @Test
    fun a6_orarioEDati() {
        tocca(By.desc("Orario e dati"))
        aspetta(By.textContains("incluso nell'app"), 15_000, "l'orario incluso nell'app")
        schermata("10-orario-e-dati")
    }

    // ---------------------------------------------------------------------

    private fun attendi(ms: Long) = Thread.sleep(ms)

    private fun schermata(nome: String) {
        attendi(500)
        telefono.executeShellCommand("screencap -p $CARTELLA/$prefisso-$nome.png")
    }

    /** I dialoghi "l'app non risponde" degli emulatori lenti: si aspetta invece di chiudere. */
    private fun chiudiAvvisiDiSistema() {
        listOf("Wait", "Attendi", "OK").forEach { t -> telefono.findObject(By.text(t))?.click() }
    }

    private fun aspetta(selettore: BySelector, ms: Long, cosa: String): UiObject2 {
        val inizio = System.currentTimeMillis()
        while (System.currentTimeMillis() - inizio < ms) {
            telefono.wait(Until.findObject(selettore), 2_000)?.let { return it }
            chiudiAvvisiDiSistema()
        }
        fail("Non compare $cosa dopo ${ms / 1000} secondi")
        error("irraggiungibile")
    }

    /** Aspetta il primo che compare tra i [selettori] e dice quale (0, 1, ...). */
    private fun aspettaUnoDi(ms: Long, cosa: String, vararg selettori: BySelector): Int {
        val inizio = System.currentTimeMillis()
        while (System.currentTimeMillis() - inizio < ms) {
            selettori.forEachIndexed { i, s -> if (telefono.hasObject(s)) return i }
            chiudiAvvisiDiSistema()
            attendi(1_000)
        }
        fail("Non compare $cosa dopo ${ms / 1000} secondi")
        error("irraggiungibile")
    }

    private fun tocca(selettore: BySelector) {
        aspetta(selettore, 20_000, selettore.toString()).click()
        attendi(800)
    }

    private fun scrivi(testo: String) {
        val campo = aspetta(By.clazz("android.widget.EditText"), 10_000, "il campo di ricerca")
        campo.click()
        campo.text = testo
        attendi(1_000)
    }

    private companion object {
        const val CARTELLA = "/data/local/tmp/schermate"
    }
}
