package it.palinaroma

import android.app.Application
import android.content.res.Configuration
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Collegamento
import it.palinaroma.dati.HttpAndroid
import it.palinaroma.sfondo.AvvisiLinee
import it.palinaroma.sfondo.Promemorie
import it.palinaroma.widget.Widget
import org.osmdroid.config.Configuration as ConfigurazioneMappe
import java.io.File

class PalinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // la rete del motore condiviso con l'app per iPhone
        Collegamento.http = HttpAndroid
        Collegamento.agenteUtente = Archivio.agenteUtente
        val mappe = ConfigurazioneMappe.getInstance()
        mappe.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        mappe.userAgentValue = Archivio.agenteUtente
        mappe.osmdroidBasePath = File(cacheDir, "osmdroid")
        mappe.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        // le mappe gia' viste restano sul telefono, ma al massimo per 120 MB (di serie sarebbero 600)
        mappe.tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        mappe.tileFileSystemCacheTrimBytes = 100L * 1024 * 1024
        Archivio.inizializza(this)
        // preferiti, linee fissate e tragitti salvati: scorciatoie dell'icona e widget
        Archivio.quandoCambia = { context ->
            Scorciatoie.aggiorna(context)
            Widget.chiediDati(context, forza = true)
        }
        Scorciatoie.aggiorna(this)
        // promemoria ricorrenti e avvisi sulle tue linee: le notifiche anche ad app chiusa
        Promemorie.preparaCanale(this)
        AvvisiLinee.preparaCanale(this)
        Archivio.quandoCambianoPromemoria = { context ->
            Promemorie.programma(context)
            AvvisiLinee.programma(context)
        }
        Promemorie.programma(this)
        AvvisiLinee.programma(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Archivio.linguaDelSistemaCambiata(this)
    }
}
