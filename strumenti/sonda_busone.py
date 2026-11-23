"""Sonda una tantum: cosa offre BusOne per Cotral (posizioni dei mezzi e tempi) e se combacia con il GTFS ufficiale.

Uso: python3 strumenti/sonda_busone.py <cartella di uscita>
Scrive esito.txt (riassunto leggibile) e le pagine di condizioni d'uso così come arrivano.
"""
import csv
import io
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from google.transit import gtfs_realtime_pb2

AGENTE = "PalinaRoma-CI (+https://github.com/fz1101hub/Palina)"
BUSONE = "https://proxy.busone.app"
GTFS_UFFICIALE = "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_COTRAL.zip"

uscita = Path(sys.argv[1])
uscita.mkdir(parents=True, exist_ok=True)
righe: list[str] = []


def scrivi(testo: str = "") -> None:
    print(testo)
    righe.append(testo)


def scarica(url: str, limite: int = 200 * 1024 * 1024) -> tuple[int, str, bytes]:
    richiesta = urllib.request.Request(url, headers={"User-Agent": AGENTE})
    try:
        with urllib.request.urlopen(richiesta, timeout=180) as risposta:
            return risposta.status, risposta.headers.get("Content-Type", ""), risposta.read(limite)
    except urllib.error.HTTPError as errore:
        return errore.code, errore.headers.get("Content-Type", ""), errore.read(20000)
    except Exception as errore:  # rete, timeout
        return -1, type(errore).__name__, str(errore).encode()


def testo_html(dati: bytes) -> str:
    testo = dati.decode("utf-8", "replace")
    testo = re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", testo)
    testo = re.sub(r"(?s)<[^>]+>", " ", testo)
    return re.sub(r"\s+", " ", testo).strip()


def trips_di(zip_dati: bytes) -> tuple[set[str], set[str], set[str]]:
    with zipfile.ZipFile(io.BytesIO(zip_dati)) as archivio:
        def leggi(nome):
            with archivio.open(nome) as f:
                return list(csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")))
        corse = leggi("trips.txt")
        fermate = leggi("stops.txt")
    return ({c["trip_id"] for c in corse}, {c["route_id"] for c in corse}, {f["stop_id"] for f in fermate})


scrivi(f"sonda busone: {datetime.now(timezone.utc).isoformat(timespec='seconds')}")

# 1. Condizioni d'uso
for percorso in ["/cotral/readme", "/"]:
    stato, tipo, dati = scarica(BUSONE + percorso)
    nome = "readme.txt" if "readme" in percorso else "home.txt"
    testo = testo_html(dati) if "html" in tipo else dati.decode("utf-8", "replace")
    (uscita / nome).write_text(testo)
    scrivi(f"pagina {percorso}: HTTP {stato}, {tipo}, {len(dati)} byte -> {nome}")

# 2. GTFS ufficiale e GTFS di BusOne, per confrontare gli id
riferimenti = {}
for nome, url in [("ufficiale", GTFS_UFFICIALE), ("busone", BUSONE + "/cotral/gtfs.zip")]:
    stato, tipo, dati = scarica(url)
    if stato == 200:
        try:
            riferimenti[nome] = trips_di(dati)
            corse, linee, fermate = riferimenti[nome]
            scrivi(f"gtfs {nome}: {len(corse)} corse, {len(linee)} linee, {len(fermate)} fermate")
        except Exception as errore:
            scrivi(f"gtfs {nome}: illeggibile ({errore})")
    else:
        scrivi(f"gtfs {nome}: HTTP {stato} {tipo} {dati[:200]!r}")

# 3. Flussi in tempo reale, senza chiavi
adesso = time.time()
for flusso in ["vehicle-positions", "trip-updates", "alerts", "service-alerts"]:
    url = f"{BUSONE}/cotral/gtfs-rt/{flusso}.pb"
    stato, tipo, dati = scarica(url)
    if stato != 200:
        scrivi(f"rt {flusso}: HTTP {stato} {tipo} {dati[:300]!r}")
        continue
    messaggio = gtfs_realtime_pb2.FeedMessage()
    try:
        messaggio.ParseFromString(dati)
    except Exception as errore:
        scrivi(f"rt {flusso}: HTTP 200 ma non è GTFS-RT ({errore}); inizio {dati[:200]!r}")
        continue
    eta = adesso - messaggio.header.timestamp if messaggio.header.timestamp else None
    entita = list(messaggio.entity)
    scrivi(f"rt {flusso}: HTTP 200, {len(dati)} byte, versione {messaggio.header.gtfs_realtime_version}, "
           f"età {eta:.0f} s, {len(entita)} entità" if eta is not None else
           f"rt {flusso}: HTTP 200, {len(dati)} byte, {len(entita)} entità, senza timestamp")
    if flusso == "vehicle-positions":
        veicoli = [e.vehicle for e in entita if e.HasField("vehicle")]
        con_pos = [v for v in veicoli if v.HasField("position")]
        con_corsa = [v for v in veicoli if v.trip.trip_id]
        eta_pos = sorted(adesso - v.timestamp for v in veicoli if v.timestamp)
        scrivi(f"  veicoli {len(veicoli)}, con posizione {len(con_pos)}, con corsa {len(con_corsa)}, "
               f"con direzione {sum(1 for v in con_pos if v.position.bearing)}, "
               f"con velocità {sum(1 for v in con_pos if v.position.speed)}, "
               f"con fermata {sum(1 for v in veicoli if v.stop_id or v.current_stop_sequence)}")
        if eta_pos:
            scrivi(f"  età delle posizioni: minima {eta_pos[0]:.0f} s, mediana {eta_pos[len(eta_pos)//2]:.0f} s, "
                   f"massima {eta_pos[-1]:.0f} s")
        scrivi(f"  stati: {dict(Counter(gtfs_realtime_pb2.VehiclePosition.VehicleStopStatus.Name(v.current_status) for v in veicoli))}")
        for v in veicoli[:4]:
            scrivi(f"  esempio: veicolo {v.vehicle.id!r} {v.vehicle.label!r}, corsa {v.trip.trip_id!r}, linea {v.trip.route_id!r}, "
                   f"inizio {v.trip.start_date} {v.trip.start_time}, pos {v.position.latitude:.5f},{v.position.longitude:.5f}, "
                   f"dir {v.position.bearing:.0f}, vel {v.position.speed:.1f}, fermata {v.stop_id!r}/{v.current_stop_sequence}, "
                   f"età {(adesso - v.timestamp) if v.timestamp else -1:.0f} s")
        corse_rt = {v.trip.trip_id for v in con_corsa}
        linee_rt = {v.trip.route_id for v in veicoli if v.trip.route_id}
    elif flusso == "trip-updates":
        aggiornamenti = [e.trip_update for e in entita if e.HasField("trip_update")]
        fermate_tu = [s for t in aggiornamenti for s in t.stop_time_update]
        scrivi(f"  corse {len(aggiornamenti)}, fermate aggiornate {len(fermate_tu)}, "
               f"con stop_id {sum(1 for s in fermate_tu if s.stop_id)}, con ritardo {sum(1 for s in fermate_tu if s.arrival.delay or s.departure.delay)}, "
               f"con orario {sum(1 for s in fermate_tu if s.arrival.time or s.departure.time)}, "
               f"con veicolo {sum(1 for t in aggiornamenti if t.vehicle.id)}")
        for t in aggiornamenti[:2]:
            s = t.stop_time_update[0] if t.stop_time_update else None
            scrivi(f"  esempio: corsa {t.trip.trip_id!r}, linea {t.trip.route_id!r}, veicolo {t.vehicle.id!r}, "
                   f"prima fermata {s.stop_id if s else None!r} seq {s.stop_sequence if s else None} ritardo {s.arrival.delay if s else None}")
        corse_rt = {t.trip.trip_id for t in aggiornamenti if t.trip.trip_id}
        linee_rt = {t.trip.route_id for t in aggiornamenti if t.trip.route_id}
    else:
        corse_rt, linee_rt = set(), set()
        for e in entita[:3]:
            if e.HasField("alert"):
                testo = e.alert.header_text.translation[0].text if e.alert.header_text.translation else ""
                scrivi(f"  avviso: {testo[:160]!r}, {len(e.alert.informed_entity)} entità")
    for nome, (corse, linee, _) in riferimenti.items():
        if corse_rt:
            scrivi(f"  corse trovate nel gtfs {nome}: {len(corse_rt & corse)}/{len(corse_rt)}; "
                   f"linee: {len(linee_rt & linee)}/{len(linee_rt)}; esempi assenti {sorted(corse_rt - corse)[:3]}")

(uscita / "esito.txt").write_text("\n".join(righe) + "\n")
