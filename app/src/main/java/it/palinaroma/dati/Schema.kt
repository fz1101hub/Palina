package it.palinaroma.dati

/**
 * Le tabelle del database dell'orario. Le usano sia il telefono quando scarica
 * l'orario ([ScrittoreDb]) sia la compilazione su GitHub, che prepara l'orario
 * da includere nell'APK.
 */
internal val SCHEMA_ORARIO = listOf(
    "CREATE TABLE linee(id TEXT PRIMARY KEY, nome TEXT, descrizione TEXT, tipo INTEGER, colore TEXT, colore_testo TEXT)",
    "CREATE TABLE fermate(id TEXT PRIMARY KEY, codice TEXT, nome TEXT, nome_norm TEXT, lat REAL, lon REAL)",
    "CREATE TABLE corse(id TEXT PRIMARY KEY, linea TEXT, direzione INTEGER, capolinea TEXT) WITHOUT ROWID",
    "CREATE TABLE percorsi(linea TEXT, direzione INTEGER, capolinea TEXT, forma TEXT, PRIMARY KEY(linea, direzione))",
    "CREATE TABLE percorso_fermate(linea TEXT, direzione INTEGER, posizione INTEGER, fermata TEXT, " +
        "PRIMARY KEY(linea, direzione, posizione))",
    "CREATE TABLE fermata_linee(fermata TEXT, linea TEXT, direzione INTEGER, capolinea TEXT, " +
        "PRIMARY KEY(fermata, linea, direzione))",
    "CREATE TABLE forme(id TEXT PRIMARY KEY, punti TEXT)",
    "CREATE TABLE info(chiave TEXT PRIMARY KEY, valore TEXT)",
)

/** Indici creati dopo aver inserito tutti i dati. */
internal val INDICI_ORARIO = listOf(
    "CREATE INDEX fermate_lat ON fermate(lat)",
    "CREATE INDEX fermate_codice ON fermate(codice)",
)

/** Valore della chiave "origine" in info per l'orario incluso nell'APK. */
const val ORIGINE_INCLUSA = "app"

/** Il tracciato salvato come testo: "lat,lon;lat,lon;...". */
internal fun testoForma(punti: List<Punto>): String =
    punti.joinToString(";") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lon) }
