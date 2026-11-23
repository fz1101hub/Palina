"""Sonda: fermate "di transito" nei GTFS dei treni, e una corsa reale da /v5/trip di Transitous."""
import csv, io, json, sys, time, urllib.parse, urllib.request, zipfile
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

uscita = Path(sys.argv[1]); uscita.mkdir(parents=True, exist_ok=True)
righe = []
def scrivi(t): print(t); righe.append(t)
UA = {"User-Agent": "PalinaRoma-CI (+https://github.com/fz1101hub/Palina)"}

def scarica(url, intestazioni=None, timeout=240):
    with urllib.request.urlopen(urllib.request.Request(url, headers={**UA, **(intestazioni or {})}), timeout=timeout) as r:
        return r.read()

def leggi(z, nome):
    with z.open(nome) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, "utf-8-sig"))

for nome, url in [("trenitalia", "https://proxy.busone.app/trenitalia/gtfs.zip"),
                  ("cotral-ferro", "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip")]:
    try:
        z = zipfile.ZipFile(io.BytesIO(scarica(url)))
        linee = {r["route_id"]: (r.get("route_short_name") or r.get("route_long_name") or "").strip() for r in leggi(z, "routes.txt")}
        corse = {r["trip_id"]: (linee.get(r["route_id"], "?"), r.get("trip_short_name", "")) for r in leggi(z, "trips.txt")}
        colonne = None; totali = Counter(); transito = Counter(); solo = Counter(); esempi = []
        for r in leggi(z, "stop_times.txt"):
            if colonne is None: colonne = list(r.keys())
            linea = corse.get(r["trip_id"], ("?", ""))[0]
            totali[linea] += 1
            su, giu = (r.get("pickup_type") or "0").strip(), (r.get("drop_off_type") or "0").strip()
            if su == "1" and giu == "1":
                transito[linea] += 1
                if len(esempi) < 3: esempi.append((r["trip_id"], r["stop_id"], linea))
            elif su == "1" or giu == "1":
                solo[linea] += 1
        scrivi(f"{nome}: colonne stop_times {colonne}")
        scrivi(f"  righe per linea {dict(totali.most_common(8))}")
        scrivi(f"  di transito (pickup=1 e drop_off=1) {dict(transito.most_common(8))}; esempi {esempi}")
        scrivi(f"  solo salita o solo discesa vietata {dict(solo.most_common(8))}")
        scrivi(f"  trip_short_name di esempio {[c[1] for c in list(corse.values())[:5]]}")
    except Exception as e:
        scrivi(f"{nome}: errore {type(e).__name__} {e}")

def transitous(percorso, parametri):
    url = "https://api.transitous.org/api" + percorso + "?" + urllib.parse.urlencode(parametri)
    return json.loads(scarica(url, timeout=60))

domani = (datetime.now(timezone.utc) + timedelta(days=1)).replace(hour=7, minute=0, second=0, microsecond=0)
for etichetta, fermata, cerca in [("ostiense", "it-trenitalia_S08409", "RV"), ("lido", "it-Lazio-Cotral-Ferrovie_", None)]:
    try:
        if fermata.endswith("_"):
            luoghi = transitous("/v1/geocode", {"text": "Lido Centro", "type": "STOP"})
            fermata = next(l["id"] for l in luoghi if str(l.get("id", "")).startswith("it-Lazio-Cotral-Ferrovie_"))
        dati = transitous("/v5/stoptimes", {"stopId": fermata, "n": 30, "time": domani.strftime("%Y-%m-%dT%H:%M:%SZ"), "language": "it"})
        partenze = dati.get("stopTimes", [])
        scrivi(f"{etichetta} {fermata}: {len(partenze)} partenze; " + "; ".join(
            f"{p.get('routeShortName')}/{p.get('tripShortName')}/{p.get('displayName')} {p['place'].get('scheduledDeparture','')[11:16]} -> {p.get('headsign')}" for p in partenze[:8]))
        scelta = next((p for p in partenze if cerca is None or (p.get("routeShortName") or "").upper() == cerca), partenze[0] if partenze else None)
        if scelta:
            corsa = transitous("/v5/trip", {"tripId": scelta["tripId"], "language": "it"})
            (uscita / f"trip-{etichetta}.json").write_text(json.dumps(corsa, ensure_ascii=False, indent=1))
            gambe = corsa.get("legs", [])
            g = gambe[0] if gambe else {}
            fermate = [g.get("from", {})] + g.get("intermediateStops", []) + [g.get("to", {})]
            scrivi(f"  trip {scelta['tripId']}: {len(gambe)} tratti, chiavi {sorted(g.keys())}")
            scrivi(f"  {len(fermate)} fermate: " + ", ".join(f"{f.get('name')} {str(f.get('scheduledArrival') or f.get('scheduledDeparture') or '')[11:16]} bin {f.get('track')}" for f in fermate[:40]))
            con_transito = transitous("/v5/trip", {"tripId": scelta["tripId"], "withScheduledSkippedStops": "true"})
            g2 = (con_transito.get("legs") or [{}])[0]
            scrivi(f"  con le fermate di transito: {len(g2.get('intermediateStops', [])) + 2} fermate")
    except Exception as e:
        scrivi(f"{etichetta}: errore {type(e).__name__} {e}")

# ASTRAL: i numeri di corsa a Lido Centro, da confrontare con trip_short_name
try:
    import ssl
    corpo = json.dumps({"percorso": "RL_PSP-CC", "fermata": ""}).encode()
    stazioni = json.loads(urllib.request.urlopen(urllib.request.Request(
        "https://gestionecorse.astralspa.it/api/fermate/RL_PSP-CC", data=b"{}", headers={**UA, "Content-Type": "application/json"}), timeout=60).read())
    codice = next((s.get("codice") for s in (stazioni if isinstance(stazioni, list) else stazioni.get("fermate", stazioni.get("data", []))) if "LIDO CENTRO" in str(s.get("nomeFermata", "")).upper()), None)
    transiti = json.loads(urllib.request.urlopen(urllib.request.Request(
        "https://gestionecorse.astralspa.it/api/transit", data=json.dumps({"percorso": "RL_PSP-CC", "fermata": codice}).encode(),
        headers={**UA, "Content-Type": "application/json"}), timeout=60).read())
    voci = transiti if isinstance(transiti, list) else next((v for v in transiti.values() if isinstance(v, list)), [])
    scrivi(f"astral lido centro {codice}: " + "; ".join(f"corsa {v.get('corsa')} {v.get('orario')} rit {v.get('ritardo')}" for v in voci[:8]))
except Exception as e:
    scrivi(f"astral: errore {type(e).__name__} {e}")

(uscita / "fermate.txt").write_text("\n".join(righe) + "\n")
