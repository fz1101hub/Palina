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

L'APK contiene già l'orario di Roma Mobilità, preparato durante la compilazione: l'app funziona
dal primo avvio. Quando l'orario ha più di una settimana l'app lo riscarica da sola in Wi-Fi
(circa 50 MB); con i dati mobili si aggiorna a mano da *Orario e dati*. GitHub ricompila l'APK
ogni lunedì, così chi lo installa parte sempre da un orario recente.

### Firma

`app/firma.p12` è la chiave che firma l'APK, cifrata con AES-256. La password è solo nel segreto
`FIRMA_PASSWORD` del repository (Settings → Secrets and variables → Actions). Senza il segreto
l'APK pubblicato è una versione di prova, firmata con una chiave temporanea diversa a ogni
compilazione: per installare un aggiornamento bisogna prima disinstallare la versione precedente
(si perdono i preferiti). Aggiungendo il segreto gli aggiornamenti si installano
sopra, ma solo a partire dalla prima versione firmata con la chiave.

L'esito di ogni compilazione (errori, test, ultime righe del log) viene salvato nel ramo `esito`.

## Come funziona

- **Orario statico** (`rome_static_gtfs.zip`): `CostruttoreOrario` legge linee, fermate, corse,
  percorso tipico di ogni linea per direzione e tracciati, e `ScrittoreDb` li salva in SQLite.
  Durante la compilazione lo stesso codice gira sul server (`GeneraOrarioIncluso`, poi
  `strumenti/crea_orario.py`) e il database finisce tra gli asset dell'APK; se il sito di Roma
  Mobilità non risponde si usa la copia del [Mobility Database](https://mobilitydatabase.org/feeds/gtfs/mdb-1294).
- **Tempo reale**: i feed GTFS-Realtime (corse e posizione dei mezzi) sono letti da
  `FeedTempoReale`, un piccolo decodificatore protobuf senza librerie esterne, solo mentre
  una fermata o una linea è aperta.
- **Tragitti**: `Transitous` interroga [Transitous](https://transitous.org) (motore MOTIS),
  che include ATAC, Roma TPL, Cotral e Trenitalia con il tempo reale e calcola i tratti a piedi
  su OpenStreetMap. Le risposte JSON sono lette da `Json`, senza librerie esterne.
- **Posizione**: subito l'ultima nota, poi quella da Wi-Fi e rete dei servizi di localizzazione
  di Google e infine il GPS; sui telefoni senza servizi Google si usano rete e GPS di Android.
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

Dati open data di Roma Servizi per la Mobilità (licenza CC BY 3.0 IT), inclusi nell'APK
con attribuzione. Tragitti calcolati da
[Transitous](https://transitous.org/sources/) (uso non commerciale, con attribuzione).
Mappe © OpenStreetMap contributors. App personale, non affiliata ad ATAC, a Roma Servizi per la
Mobilità né a Transitous.

Codice rilasciato con licenza MIT (vedi `LICENSE`).
