package it.palinaroma

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
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
        // l'app ha animazioni continue (indicatori, mappa): non aspettare ogni volta 10 secondi che si fermino
        Configurator.getInstance().waitForIdleTimeout = 1_000
        telefono.executeShellCommand("mkdir -p $CARTELLA")
        telefono.executeShellCommand("pm grant $pacchetto android.permission.ACCESS_FINE_LOCATION")
        telefono.executeShellCommand("pm grant $pacchetto android.permission.ACCESS_COARSE_LOCATION")
        telefono.wakeUp()
        chiudiAvvisiDiSistema()
        val intento = strumenti.targetContext.packageManager.getLaunchIntentForPackage(pacchetto)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        strumenti.targetContext.startActivity(intento)
        telefono.wait(Until.hasObject(By.pkg(pacchetto).depth(0)), 30_000)
        // l'orario incluso nell'app si copia al primo avvio: poi compare la Home con la ricerca
        aspetta(By.res("cerca"), 60_000, "la schermata principale")
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
        attendi(6_000) // i minuti arrivano dal feed in tempo reale
        schermata("02-home-vicino-a-te")
    }

    @Test
    fun a3_cercaUnaFermataEApriLaConITempi() {
        tocca(By.res("cerca"))
        scrivi("Termini")
        aspetta(By.res("riga-fermata"), 20_000, "i risultati della ricerca").click()
        val esito = aspettaUnoDi(
            90_000,
            "i tempi d'attesa",
            By.textContains("Aggiornato"),
            By.textContains("Non riesco a leggere i passaggi"),
        )
        attendi(2_000)
        schermata("03-fermata-" + if (esito == 0) "con-tempi" else "senza-rete")

        // una linea fissata con la puntina va in cima, sotto "LE TUE LINEE"
        telefono.findObject(By.descStartsWith("Fissa il"))?.let { puntina ->
            puntina.click()
            aspetta(By.descStartsWith("Togli il"), 5_000, "la puntina piena")
            aspetta(By.text("LE TUE LINEE"), 5_000, "la sezione delle linee fissate")
            attendi(1_000)
            schermata("03b-fermata-linea-fissata")
        }

        // preferito: lo si aggiunge qui e lo si ritrova in cima alla Home, con i suoi tempi
        tocca(By.desc("Aggiungi ai preferiti"))
        aspetta(By.desc("Togli dai preferiti"), 5_000, "il cuore pieno")
        telefono.pressBack()
        // si torna alla ricerca, rimasta aperta: la si chiude con la freccia
        if (aspettaUnoDi(15_000, "la ricerca o la Home", By.desc("Chiudi la ricerca"), By.res("preferita")) == 0) {
            tocca(By.desc("Chiudi la ricerca"))
        }
        aspetta(By.res("preferita"), 15_000, "la fermata preferita in Home")
        attendi(6_000)
        schermata("04-home-con-preferita")
    }

    @Test
    fun a4_lineaSullaMappaConLeVetture() {
        tocca(By.res("cerca"))
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
        tocca(By.res("come-arrivo"))
        tocca(By.res("campo-a"))
        scrivi("Colosseo")
        aspettaUnoDi(30_000, "i luoghi trovati", By.text("FERMATE"), By.text("INDIRIZZI E LUOGHI"))
        attendi(1_500)
        aspetta(By.res("luogo"), 10_000, "un luogo da scegliere").click()
        // Le soluzioni stesse, non la riga "Calcolato alle" che con molte soluzioni
        // resta fuori dallo schermo; e non "Transitous" da solo, che c'e' sempre
        // nella riga dell'attribuzione in fondo.
        val esito = aspettaUnoDi(
            90_000,
            "i tragitti",
            By.res("tragitto"),
            By.res("tragitto-a-piedi"),
            By.textContains("Nessun tragitto"),
            By.textContains("Non riesco a contattare Transitous"),
            By.textContains("Transitous non"),
            By.textContains("Transitous ha"),
            By.textContains("Non riesco a leggere la tua posizione"),
        )
        attendi(1_500)
        schermata("08-tragitti")
        if (esito <= 1) {
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
    fun a6_impostazioniETemaScuro() {
        tocca(By.desc("Impostazioni"))
        aspetta(By.textStartsWith("Automatico"), 15_000, "la scelta dell'aspetto")
        schermata("10-impostazioni")

        // tema scuro: Home e fermata
        tocca(By.textStartsWith("Scuro"))
        attendi(1_000)
        schermata("11-impostazioni-scure")
        telefono.pressBack()
        aspetta(By.res("cerca"), 10_000, "la Home")
        attendi(6_000)
        schermata("12-home-scura")
        apriFermataConITempi()
        aspettaUnoDi(
            90_000,
            "i tempi d'attesa",
            By.textContains("Aggiornato"),
            By.textContains("Non riesco a leggere i passaggi"),
        )
        attendi(2_000)
        schermata("13-fermata-scura")
        telefono.pressBack()

        // si torna a come segue il telefono
        tocca(By.desc("Impostazioni"))
        tocca(By.textStartsWith("Automatico"))
        aspetta(By.textContains("incluso nell'app"), 15_000, "l'orario incluso nell'app")
        telefono.pressBack()
    }

    @Test
    fun a7_testoGrandeDiAndroid() {
        telefono.executeShellCommand("settings put system font_scale 1.6")
        try {
            attendi(2_000)
            aspetta(By.res("cerca"), 20_000, "la Home con il testo grande")
            attendi(4_000)
            schermata("14-home-testo-grande")
            apriFermataConITempi()
            aspettaUnoDi(
                90_000,
                "i tempi d'attesa",
                By.textContains("Aggiornato"),
                By.textContains("Non riesco a leggere i passaggi"),
            )
            attendi(2_000)
            schermata("15-fermata-testo-grande")
        } finally {
            telefono.executeShellCommand("settings put system font_scale 1.0")
        }
    }

    @Test
    fun a8_cotralETreni() {
        // una fermata dei bus Cotral, una stazione dei treni Cotral (tempo reale da ASTRAL),
        // una stazione Trenitalia: se una rete manca nell'APK la prova lo dice con una schermata
        apriFermataDellaRete("Frascati", "Cotral", "16-fermata-cotral")
        apriFermataDellaRete("Lido Centro", "Treni Cotral", "17-stazione-treni-cotral")
        apriFermataDellaRete("Roma Ostiense", "Trenitalia", "18-stazione-trenitalia")
    }

    // ---------------------------------------------------------------------

    /** Cerca [testo], apre il primo risultato della rete [etichetta] e fotografa i tempi. */
    private fun apriFermataDellaRete(testo: String, etichetta: String, nome: String) {
        tocca(By.res("cerca"))
        scrivi(testo)
        aspetta(By.res("riga-fermata"), 20_000, "i risultati per $testo")
        attendi(800)
        val cercata = By.res("riga-fermata").descContains(", $etichetta")
        var riga = telefono.findObject(cercata)
        var giri = 0
        while (riga == null && giri < 5) {
            telefono.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
            attendi(600)
            riga = telefono.findObject(cercata)
            giri++
        }
        if (riga == null) {
            schermata("$nome-NON-TROVATA")
        } else {
            riga.click()
            aspettaUnoDi(
                90_000,
                "i tempi a $testo",
                By.textContains("Aggiornato"),
                By.textContains("Non riesco a leggere i passaggi"),
            )
            attendi(4_000)
            schermata(nome)
            telefono.pressBack()
        }
        aspetta(By.desc("Chiudi la ricerca"), 10_000, "la ricerca").click()
        aspetta(By.res("cerca"), 10_000, "la Home")
    }

    /** Apre una fermata vicina: la seconda, perche' la prima in piazza Venezia ha solo una linea poco frequente. */
    private fun apriFermataConITempi() {
        aspetta(By.res("riga-fermata"), 60_000, "le fermate vicine")
        attendi(3_000)
        val righe = telefono.findObjects(By.res("riga-fermata"))
        (righe.getOrNull(1) ?: righe.first()).click()
    }

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
