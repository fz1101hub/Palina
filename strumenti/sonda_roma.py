"""Sonda: il flusso di Roma Mobilita' e' vuoto per tutti o solo per noi? E Transitous ha tempo reale a Roma?"""
import json, sys, time, urllib.parse, urllib.request, urllib.error
from pathlib import Path
from google.transit import gtfs_realtime_pb2

uscita = Path(sys.argv[1]); uscita.mkdir(parents=True, exist_ok=True)
righe = []
def scrivi(t): print(t); righe.append(t)

APP = "PalinaRoma/0.16 (+https://github.com/fz1101hub/Palina)"
BROWSER = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
BASE = "https://romamobilita.it"
varianti = [
    ("app", "/sites/default/files/rome_rtgtfs_trip_updates_feed.pb", {"User-Agent": APP}),
    ("browser", "/sites/default/files/rome_rtgtfs_trip_updates_feed.pb", {"User-Agent": BROWSER}),
    ("nocache", f"/sites/default/files/rome_rtgtfs_trip_updates_feed.pb?t={int(time.time())}", {"User-Agent": APP, "Cache-Control": "no-cache", "Pragma": "no-cache"}),
    ("diretto", "/wp-content/uploads/shared/rome_rtgtfs_trip_updates_feed.pb", {"User-Agent": APP}),
    ("mezzi", "/sites/default/files/rome_rtgtfs_vehicle_positions_feed.pb", {"User-Agent": BROWSER}),
    ("avvisi", "/sites/default/files/rome_rtgtfs_service_alerts_feed.pb", {"User-Agent": APP}),
]
for nome, percorso, intestazioni in varianti:
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE + percorso, headers=intestazioni), timeout=90) as r:
            dati = r.read(); h = {k: v for k, v in r.headers.items() if k.lower() in ("last-modified", "etag", "cache-control", "age", "server", "cf-cache-status", "x-cache", "content-length", "via")}
            finale = r.url
        m = gtfs_realtime_pb2.FeedMessage(); m.ParseFromString(dati)
        scrivi(f"roma {nome}: {len(dati)} byte, età {time.time() - m.header.timestamp:.0f} s, {len(m.entity)} entità, finale {finale}, {h}")
    except Exception as e:
        scrivi(f"roma {nome}: errore {type(e).__name__} {e}")

def transitous(percorso, parametri):
    url = "https://api.transitous.org/api" + percorso + "?" + urllib.parse.urlencode(parametri)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": APP}), timeout=60) as r:
        return json.load(r)

for testo in ["Termini", "Venezia", "Flaminio", "Cipro"]:
    try:
        luoghi = transitous("/v1/geocode", {"text": testo + " Roma", "type": "STOP"})
        fermate = [l for l in luoghi if str(l.get("id", "")).startswith("it-Lazio-Rome_")][:2]
        for f in fermate:
            dati = transitous("/v5/stoptimes", {"stopId": f["id"], "n": 12})
            orari = dati.get("stopTimes", [])
            rt = [s for s in orari if s.get("realTime")]
            scrivi(f"transitous {f['id']} {f.get('name')}: {len(orari)} partenze, {len(rt)} in tempo reale; "
                   + "; ".join(f"{s.get('routeShortName')} {s['place'].get('scheduledDeparture','')[11:16]}→{s['place'].get('departure','')[11:16]} rt={s.get('realTime')}" for s in orari[:6]))
    except Exception as e:
        scrivi(f"transitous {testo}: errore {type(e).__name__} {e}")

(uscita / "roma.txt").write_text("\n".join(righe) + "\n")
