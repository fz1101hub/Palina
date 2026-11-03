package it.palinaroma

import android.app.Application
import it.palinaroma.dati.Archivio
import org.osmdroid.config.Configuration
import java.io.File

class PalinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mappe = Configuration.getInstance()
        mappe.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        mappe.userAgentValue = Archivio.agenteUtente
        mappe.osmdroidBasePath = File(cacheDir, "osmdroid")
        mappe.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        Archivio.inizializza(this)
    }
}
