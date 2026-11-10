# Palina Roma (Android)

App personale per i mezzi pubblici di Roma, con la grafica porpora e oro della skill Alexa "Palina Roma":

- **tempi d'attesa in tempo reale** alle fermate, con la direzione di ogni corsa;
- **linee**: tracciato sulla mappa, fermate e mezzi in movimento;
- **tragitti da A a B** con bus, tram, metro e treni: cambi, tratti a piedi con le indicazioni, ritardi in tempo reale e mappa del percorso.

## Installare l'app

Ogni modifica su `main` viene compilata da GitHub Actions (`.github/workflows/compila.yml`).
L'APK pronto si trova nella release **Ultima versione** del repository: aprila dal telefono,
scarica `app-release.apk` e installalo (Android chiede di consentire l'installazione da quella app).
Gli aggiornamenti si installano sopra la versione precedente solo se la firma è attiva (vedi sotto);
altrimenti va prima disinstallata quella vecchia.

L'APK contiene già l'orario di Roma Mobilità, preparato durante la compilazione: l'app funziona
dal primo avvio. Quando l'orario ha più di una settimana l'app lo riscarica da sola in Wi-Fi
(circa 50 MB); con i dati mobili si aggiorna a mano da *Orario e dati*. GitHub ricompila l'APK
ogni lunedì, così chi lo installa parte sempre da un orario recente. GitHub sospende le
compilazioni programmate dei repository fermi da 60 giorni: l'ultimo passo del flusso di lavoro
lo riattiva a ogni esecuzione; se si fermasse lo stesso, basta riabilitarlo dalla scheda *Actions*.

### Firma

`app/firma.p12` è la chiave che firma l'APK, cifrata con AES-256. La password è solo nel segreto
`FIRMA_PASSWORD` del repository (Settings → Secrets and variables → Actions), e nel flusso di
lavoro arriva solo al passo che compila. Senza il segreto
l'APK pubblicato è una versione di prova, firmata con una chiave temporanea diversa a ogni
compilazione: per installare un aggiornamento bisogna prima disinstallare la versione precedente
(si perdono i preferiti). Aggiungendo il segreto gli aggiornamenti si installano
sopra, ma solo a partire dalla prima versione firmata con la chiave.

L'esito di ogni compilazione (errori, avvisi, test, ultime righe del log) viene salvato nel ramo `esito`.

### Prova su telefoni simulati

Dopo la compilazione GitHub avvia due telefoni Android simulati (emulatori): Android 8, la
versione minima, e Android 17. Su ognuno `ProvaSchermate` (in `app/src/androidTest`) usa l'app
come una persona: schermata principale con l'orario incluso, fermate vicine (il telefono
simulato è in piazza Venezia), ricerca di una fermata con i tempi d'attesa e i preferiti, linea
sulla mappa con le vetture e il "sei qui", tragitto fino al Colosseo, *Orario e dati*. Esito,
registro dell'app e una schermata per ogni passaggio finiscono nel ramo `prova-emulatore`.
L'APK viene pubblicato nella release solo se la prova va bene (la compilazione settimanale del
lunedì salta la prova e pubblica direttamente). Lo script è `strumenti/prova-emulatore.sh`.

## Come funziona

- **Orario statico** (`rome_static_gtfs.zip`): `CostruttoreOrario` legge linee, fermate, corse,
  percorso tipico di ogni linea per direzione e tracciati, e `ScrittoreDb` li salva in SQLite.
  Durante la compilazione lo stesso codice gira sul server (`GeneraOrarioIncluso`, poi
  `strumenti/crea_orario.py`) e il database finisce tra gli asset dell'APK; se il sito di Roma
  Mobilità non risponde si usa la copia del [Mobility Database](https://mobilitydatabase.org/feeds/gtfs/mdb-1294).
- **Tempo reale**: i feed GTFS-Realtime (corse e posizione dei mezzi) sono letti da
  `FeedTempoReale`, un piccolo decodificatore protobuf senza librerie esterne, solo mentre
  una fermata o una linea è aperta. `FeedInMemoria` li tiene per pochi secondi, chiede al
  server se sono cambiati prima di riscaricarli (ETag / Last-Modified) e li decodifica fuori
  dal thread dell'interfaccia. Alle fermate si aggiungono le partenze programmate di Transitous.
- **Tragitti**: `Transitous` interroga [Transitous](https://transitous.org) (motore MOTIS),
  che include ATAC, Roma TPL, Cotral e Trenitalia con il tempo reale e calcola i tratti a piedi
  su OpenStreetMap. Le risposte JSON sono lette da `Json`, senza librerie esterne.
- **Posizione**: subito l'ultima nota, poi quella da Wi-Fi e rete dei servizi di localizzazione
  di Google e infine il GPS; sui telefoni senza servizi Google si usano rete e GPS di Android.
  Resta sul telefono, tranne quando si calcola un tragitto da o verso la propria posizione
  (le coordinate vanno a Transitous) e nella ricerca di indirizzi (solo la zona, arrotondata
  a circa un chilometro).
- **Mappe**: OpenStreetMap tramite osmdroid, senza chiavi da registrare.
- **Strumenti**: Kotlin 2.4, plugin Android 9.5 (che compila anche il Kotlin), Gradle 9.9,
  Compose (BOM 2026.09), OkHttp 5; l'app gira da Android 8 (API 26) ed è compilata per
  Android 17 (API 37).

## Struttura

```
app/src/main/java/it/palinaroma/
  dati/   TempoRealeFeed, TempoReale, CostruttoreOrario, Schema, Database, Archivio, Testo,
          Posizione, Transitous, Json
  ui/     Home, FermataSchermo, LineaSchermo, OrarioSchermo, Tragitto, DettaglioTragitto,
          MiaPosizione, Vetture, Tema
app/src/test/   test del decodificatore, dell'orario, dei testi e del client Transitous;
                GeneraOrarioIncluso prepara l'orario da includere nell'APK
app/src/androidTest/   ProvaSchermate: prova dell'app intera sull'emulatore
strumenti/      crea_orario.py: dalle istruzioni SQL al database incluso nell'APK;
                prova-emulatore.sh: avvia l'emulatore e prova l'app
```

## Fonti e licenza

Dati open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT), inclusi nell'APK
con attribuzione. Tragitti calcolati da
[Transitous](https://transitous.org/sources/) (uso non commerciale, con attribuzione).
Mappe © OpenStreetMap contributors. App personale, non affiliata ad ATAC, a Roma Servizi per la
Mobilità né a Transitous.

Codice rilasciato con licenza MIT (vedi `LICENSE`).
