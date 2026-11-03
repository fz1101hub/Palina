# Palina Roma (Android)

App personale per i mezzi pubblici di Roma, con la grafica porpora e oro della skill Alexa "Palina Roma":

- **tempi d'attesa in tempo reale** alle fermate, con la direzione di ogni corsa;
- **linee**: tracciato sulla mappa, fermate e mezzi in movimento;
- **tragitti da A a B** con bus, tram, metro e treni: cambi, tratti a piedi con le indicazioni, ritardi in tempo reale e mappa del percorso.

## Installare l'app

Ogni modifica su `main` viene compilata da GitHub Actions (`.github/workflows/compila.yml`).
L'APK pronto si trova nella release **Ultima versione** del repository: aprila dal telefono,
scarica `app-release.apk` e installalo (Android chiede di consentire l'installazione da quella app).
Gli aggiornamenti si installano sopra la versione precedente.

Al primo avvio l'app scarica l'orario di Roma Mobilità (circa 50 MB, meglio in Wi-Fi) e lo
trasforma in un piccolo database: ci vuole qualche minuto. Conviene aggiornarlo una volta a settimana.

### Firma

`app/firma.p12` è la chiave che firma l'APK, cifrata con AES-256. La password è solo nel segreto
`FIRMA_PASSWORD` del repository (Settings → Secrets and variables → Actions). Senza il segreto la
compilazione produce solo un APK di prova, non pubblicato nella release.

L'esito di ogni compilazione (errori, test, ultime righe del log) viene salvato nel ramo `esito`.

## Come funziona

- **Orario statico** (`rome_static_gtfs.zip`): `CostruttoreOrario` legge linee, fermate, corse,
  percorso tipico di ogni linea per direzione e tracciati, e `ScrittoreDb` li salva in SQLite.
- **Tempo reale**: i feed GTFS-Realtime (corse e posizione dei mezzi) sono letti da
  `FeedTempoReale`, un piccolo decodificatore protobuf senza librerie esterne, solo mentre
  una fermata o una linea è aperta.
- **Tragitti**: `Transitous` interroga [Transitous](https://transitous.org) (motore MOTIS),
  che include ATAC, Roma TPL, Cotral e Trenitalia con il tempo reale e calcola i tratti a piedi
  su OpenStreetMap. Le risposte JSON sono lette da `Json`, senza librerie esterne.
- **Mappe**: OpenStreetMap tramite osmdroid, senza chiavi da registrare.

## Struttura

```
app/src/main/java/it/palinaroma/
  dati/   TempoRealeFeed, TempoReale, CostruttoreOrario, Database, Archivio, Testo, Posizione,
          Transitous, Json
  ui/     Home, FermataSchermo, LineaSchermo, OrarioSchermo, Tragitto, DettaglioTragitto, Tema
app/src/test/   test del decodificatore, dell'orario, dei testi e del client Transitous
```

## Fonti e licenza

Dati open data di Roma Servizi per la Mobilità. Tragitti calcolati da
[Transitous](https://transitous.org/sources/) (uso non commerciale, con attribuzione).
Mappe © OpenStreetMap contributors. App personale, non affiliata ad ATAC, a Roma Servizi per la
Mobilità né a Transitous.

Codice rilasciato con licenza MIT (vedi `LICENSE`).
