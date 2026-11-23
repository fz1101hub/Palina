package it.palinaroma.orologio

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.tabelloneDiEsempio
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Prova dell'app sull'orologio simulato, senza telefono: il tabellone di
 * esempio arriva come se l'avesse mandato il telefono. Schermate in
 * /data/local/tmp/schermate (la compilazione su GitHub le scarica).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProvaOrologio {
    private val strumenti = InstrumentationRegistry.getInstrumentation()
    private val orologio = UiDevice.getInstance(strumenti)
    private val contesto = strumenti.targetContext
    private val prefisso = "wear${Build.VERSION.SDK_INT}"

    /** La complicazione si costruisce (usa classi di Android: si prova qui, non sul computer). */
    @Test
    fun a0_complicazione() {
        val c = testoComplicazione("SG335D", System.currentTimeMillis() / 1000 + 300, "Frascati", null)
        assertTrue(c.title != null)
    }

    @Test
    fun a1_tabelloneDalTelefono() {
        orologio.executeShellCommand("mkdir -p $CARTELLA")
        orologio.wakeUp()
        ArchivioOrologio.salva(contesto, CodificaOrologio.scrivi(tabelloneDiEsempio(System.currentTimeMillis() / 1000)))
        contesto.startActivity(
            Intent(contesto, Principale::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("compare la fermata", orologio.wait(Until.hasObject(By.textContains("Basilica San Paolo")), 20_000) == true)
        Thread.sleep(1_500)
        schermata("01-tabellone")
        // scorre verso le altre fermate
        val larghezza = orologio.displayWidth
        val altezza = orologio.displayHeight
        repeat(2) { orologio.swipe(larghezza / 2, altezza * 3 / 4, larghezza / 2, altezza / 4, 20) }
        Thread.sleep(1_000)
        schermata("02-altre-fermate")
        repeat(3) { orologio.swipe(larghezza / 2, altezza * 3 / 4, larghezza / 2, altezza / 4, 20) }
        Thread.sleep(1_000)
        schermata("03-in-fondo")
        assertTrue("c'e' il pulsante per aggiornare", orologio.hasObject(By.text("Aggiorna")))
    }

    @Test
    fun a2_riquadroEComplicazione() {
        orologio.pressHome()
        Thread.sleep(1_500)
        // il riquadro aggiunto e mostrato con i comandi di prova di Wear OS
        val componente = "${contesto.packageName}/${Riquadro::class.java.name}"
        orologio.executeShellCommand(
            "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component $componente",
        )
        Thread.sleep(2_000)
        orologio.executeShellCommand(
            "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0",
        )
        Thread.sleep(4_000)
        schermata("04-riquadro")
        orologio.pressHome()
        Thread.sleep(1_000)
        // la complicazione sul quadrante (posizione 0, testo breve)
        val complicazione = "${contesto.packageName}/${Complicazione::class.java.name}"
        orologio.executeShellCommand(
            "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-complication " +
                "--ecn component $complicazione --ecn watchface com.google.android.wearable.app/" +
                "com.google.android.clockwork.sysui.experiences.defaultwatchface.DefaultWatchFace --ei slot 0 --ei type 3",
        )
        Thread.sleep(3_000)
        schermata("05-quadrante")
    }

    private fun schermata(nome: String) {
        orologio.executeShellCommand("screencap -p $CARTELLA/$prefisso-$nome.png")
    }

    private companion object {
        const val CARTELLA = "/data/local/tmp/schermate"
    }
}
