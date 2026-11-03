# Palina Roma (Android)

App personale per i mezzi pubblici di Roma: tempi d'attesa in tempo reale alle fermate,
direzione di ogni corsa, tracciato delle linee sulla mappa e mezzi in movimento.
Stessa grafica porpora e oro della skill Alexa "Palina Roma".

## Installare l'app

Ogni modifica su `main` viene compilata da GitHub Actions (`.github/workflows/compila.yml`).
L'APK pronto si trova nella release **Ultima versione** del repository: aprila dal telefono,
scarica `app-release.apk` e installalo (Android chiede di consentire l'installazione da quella app).
Gli aggiornamenti si installano sopra la versione precedente.

Al primo avvio l'app scarica l'orario di Roma Mobilità (circa 50 MB, meglio in Wi-Fi) e lo
trasforma in un piccolo database: ci vuole qualche minuto. Conviene aggiornarlo una volta a settimana.

## Come funziona

- **Orario statico** (`rome_static_gtfs.zip`): `CostruttoreOrario` legge linee, fermate, corse,
  percorso tipico di ogni linea per direzione e tracciati, e `ScrittoreDb` li salva in SQLite.
- **Tempo reale**: i feed GTFS-Realtime (corse e posizione dei mezzi) sono letti da
  `FeedTempoReale`, un piccolo decodificatore protobuf senza librerie esterne, solo mentre
  una fermata o una linea è aperta.
- **Mappe**: OpenStreetMap tramite osmdroid, senza chiavi da registrare.
- Nessun server: tutto gira sul telefono. Il calcolo dei percorsi da A a B arriverà con un
  server dedicato (per esempio OpenTripPlanner).

## Struttura

```
app/src/main/java/it/palinaroma/
  dati/   TempoRealeFeed, TempoReale, CostruttoreOrario, Database, Archivio, Testo, Posizione
  ui/     Home (vicino, cerca, preferiti), FermataSchermo, LineaSchermo, OrarioSchermo, Tema
app/src/test/   test del decodificatore, della costruzione dell'orario e dei testi
```

`app/firma-personale.jks` è una chiave di firma personale salvata nel repository solo perché
ogni APK compilato si installi sopra il precedente. Non usarla per app pubblicate.

## Fonti

Dati open data di Roma Servizi per la Mobilità. Mappe © OpenStreetMap contributors.
App personale, non affiliata ad ATAC né a Roma Servizi per la Mobilità.
