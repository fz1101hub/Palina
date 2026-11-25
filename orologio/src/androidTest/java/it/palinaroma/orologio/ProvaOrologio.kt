package it.palinaroma.orologio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.BySelector
import it.palinaroma.ponte.CodificaCorsa
import it.palinaroma.ponte.CodificaOrologio
import it.palinaroma.ponte.corsaDiEsempio
import it.palinaroma.ponte.riga
import it.palinaroma.ponte.tabelloneDiEsempio
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Before
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

    /** L'emulatore e' in inglese: le prove guardano i testi italiani (una prova a parte e' in inglese). */
    @Before
    fun italiano() {
        linguaDelleProve("it")
    }

    private fun linguaDelleProve(codice: String) {
        contesto.getSharedPreferences("orologio", Context.MODE_PRIVATE).edit().putString("lingua", codice).commit()
        impostaLinguaOrologio(contesto)
    }

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

    /** L'app aperta da capo con un tabellone appena arrivato. */
    private fun apriConTabellone() {
        orologio.wakeUp()
        ArchivioOrologio.salva(contesto, CodificaOrologio.scrivi(tabelloneDiEsempio(System.currentTimeMillis() / 1000)))
        contesto.startActivity(
            Intent(contesto, Principale::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("compare la fermata", orologio.wait(Until.hasObject(By.textContains("Basilica San Paolo")), 20_000) == true)
        Thread.sleep(1_000)
    }

    /** Scorre la lista finche' non compare [cosa] (al massimo [volte] volte). */
    private fun scorriFino(cosa: BySelector, volte: Int = 6): Boolean {
        repeat(volte) {
            if (orologio.hasObject(cosa)) return true
            orologio.swipe(orologio.displayWidth / 2, orologio.displayHeight * 3 / 4, orologio.displayWidth / 2, orologio.displayHeight * 2 / 5, 25)
            Thread.sleep(600)
        }
        return orologio.hasObject(cosa)
    }

    @Test
    fun a3_lineaETreno() {
        apriConTabellone()
        // la riga della Metromare: i suoi treni
        assertTrue("la riga della Metromare", scorriFino(By.descContains("verso Cristoforo Colombo")))
        orologio.findObject(By.descContains("verso Cristoforo Colombo"))!!.click()
        assertTrue("i treni della linea", orologio.wait(Until.hasObject(By.descContains("vedi le fermate")), 10_000) == true)
        Thread.sleep(800)
        schermata("06-linea-treni")
        // le fermate del primo treno, come se le avesse mandate il telefono
        val riga = ArchivioOrologio.tabellone.value!!.riga("F:RL_SANPAOLO", "F:ROMALIDO", "Cristoforo Colombo")!!
        val treno = riga.treni.first()
        ArchivioOrologio.salvaCorsa(CodificaCorsa.scrivi(corsaDiEsempio(treno, System.currentTimeMillis() / 1000)))
        orologio.findObjects(By.descContains("vedi le fermate")).first().click()
        assertTrue("le fermate del treno", orologio.wait(Until.hasObject(By.textContains("Ritardo da ASTRAL")), 10_000) == true)
        Thread.sleep(1_500)
        schermata("07-treno")
        repeat(2) { orologio.swipe(orologio.displayWidth / 2, orologio.displayHeight * 3 / 4, orologio.displayWidth / 2, orologio.displayHeight / 4, 20) }
        Thread.sleep(800)
        schermata("08-treno-fermate")
        assertTrue("il capolinea", scorriFino(By.textContains("Cristoforo Colombo"), 8))
        orologio.pressBack()
        orologio.pressBack()
        assertTrue("di nuovo l'elenco", orologio.wait(Until.hasObject(By.text("Palina")), 10_000) == true)
    }

    @Test
    fun a4_seguireUnaLinea() {
        strumenti.uiAutomation.grantRuntimePermission(contesto.packageName, Manifest.permission.POST_NOTIFICATIONS)
        apriConTabellone()
        assertTrue("la riga dell'8", scorriFino(By.descContains("verso Casaletto")))
        orologio.findObject(By.descContains("verso Casaletto"))!!.click()
        assertTrue("la schermata della linea", orologio.wait(Until.hasObject(By.textContains("Casaletto")), 10_000) == true)
        Thread.sleep(800)
        schermata("09-linea-bus")
        assertTrue("il pulsante Segui", scorriFino(By.text("Segui")))
        orologio.findObject(By.text("Segui"))!!.click()
        assertTrue("si puo' smettere", orologio.wait(Until.hasObject(By.text("Smetti di seguire")), 10_000) == true)
        Thread.sleep(2_000)
        schermata("10-seguita")
        // la notifica in corso, e da Wear OS 7 il Live Update
        val gestore = contesto.getSystemService(NotificationManager::class.java)
        val notifica = gestore.activeNotifications.firstOrNull { it.id == NotificheSegui.ID }
        assertNotNull("la notifica della linea seguita", notifica)
        val n = notifica!!.notification
        assertTrue("in corso", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36) {
            Log.i(
                "ProvaOrologio",
                "Live Update: promuovibile=${n.hasPromotableCharacteristics()} promossa=${n.flags and Notification.FLAG_PROMOTED_ONGOING != 0} " +
                    "consentite=${NotificationManagerCompat.from(contesto).canPostPromotedNotifications()}",
            )
        }
        if (Build.VERSION.SDK_INT >= 37) assertTrue("ha i requisiti del Live Update", n.hasPromotableCharacteristics())
        orologio.pressBack()
        Thread.sleep(1_000)
        schermata("11-elenco-seguita")
        orologio.pressHome()
        Thread.sleep(3_000)
        schermata("12-quadrante-seguita")
        // le notifiche: dal basso verso l'alto sul quadrante
        orologio.swipe(orologio.displayWidth / 2, orologio.displayHeight - 10, orologio.displayWidth / 2, orologio.displayHeight / 3, 30)
        Thread.sleep(2_000)
        schermata("13-notifiche")
        orologio.pressHome()
        Seguito.ferma(contesto)
        Thread.sleep(2_000)
        assertTrue(
            "smesso: niente piu' notifica",
            gestore.activeNotifications.none { it.id == NotificheSegui.ID },
        )
    }

    @Test
    fun a5_widget() {
        if (Build.VERSION.SDK_INT < 37) return
        val renderer = orologio.executeShellCommand("dumpsys package com.google.android.wearable.protolayout.renderer")
            .lineSequence().firstOrNull { "versionName" in it }?.trim()
        Log.i("ProvaOrologio", "renderer dei riquadri: $renderer")
        ArchivioOrologio.salva(contesto, CodificaOrologio.scrivi(tabelloneDiEsempio(System.currentTimeMillis() / 1000)))
        orologio.pressHome()
        Thread.sleep(1_000)
        val componente = "${contesto.packageName}/${ServizioWidget::class.java.name}"
        // 0: a tutto schermo; poi gli altri formati (piccolo e grande)
        listOf(0, 1, 2).forEach { tipo ->
            val esito = orologio.executeShellCommand(
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component $componente --ei type $tipo",
            )
            Log.i("ProvaOrologio", "widget tipo $tipo: ${esito.trim()}")
            val indice = Regex("Index=\\[(\\d+)]").find(esito)?.groupValues?.get(1) ?: "0"
            Thread.sleep(2_000)
            orologio.executeShellCommand(
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index $indice",
            )
            Thread.sleep(4_000)
            schermata("14-widget-tipo$tipo")
            orologio.pressHome()
            Thread.sleep(1_000)
        }
    }

    private fun schermata(nome: String) {
        orologio.executeShellCommand("screencap -p $CARTELLA/$prefisso-$nome.png")
    }

    private companion object {
        const val CARTELLA = "/data/local/tmp/schermate"
    }
}
