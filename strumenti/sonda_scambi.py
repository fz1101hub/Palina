"""Sonda: le fermate di Roma (metro) vicine alle stazioni dei treni Cotral, per unire le stazioni di scambio."""
import csv, io, math, sys, urllib.request, zipfile
from collections import defaultdict
from pathlib import Path

uscita = Path(sys.argv[1]); uscita.mkdir(parents=True, exist_ok=True)
righe = []
def scrivi(t): print(t); righe.append(t)
UA = {"User-Agent": "PalinaRoma-CI (+https://github.com/fz1101hub/Palina)"}
def scarica(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r: return r.read()
def leggi(z, nome):
    with z.open(nome) as f: yield from csv.DictReader(io.TextIOWrapper(f, "utf-8-sig"))
def metri(a, b, c, d):
    x = math.radians(d - b) * math.cos(math.radians((a + c) / 2)); y = math.radians(c - a)
    return 6371000 * math.hypot(x, y)

ferro = zipfile.ZipFile(io.BytesIO(scarica("http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip")))
stazioni = [(r["stop_id"], r["stop_name"], float(r["stop_lat"]), float(r["stop_lon"])) for r in leggi(ferro, "stops.txt")]
scrivi(f"stazioni cotral: {len(stazioni)}")
roma = zipfile.ZipFile(io.BytesIO(scarica("https://romamobilita.it/sites/default/files/rome_static_gtfs.zip")))
linee = {r["route_id"]: (r.get("route_short_name", ""), r.get("route_type", "")) for r in leggi(roma, "routes.txt")}
fermate = {r["stop_id"]: r for r in leggi(roma, "stops.txt")}
scrivi(f"colonne stops roma: {list(next(iter(fermate.values())).keys())}")
corsa_linea = {r["trip_id"]: r["route_id"] for r in leggi(roma, "trips.txt")}
vicine = {}
for sid, nome, lat, lon in stazioni:
    for f in fermate.values():
        try: d = metri(lat, lon, float(f["stop_lat"]), float(f["stop_lon"]))
        except ValueError: continue
        if d <= 400: vicine.setdefault(f["stop_id"], []).append((sid, round(d)))
linee_fermata = defaultdict(set)
for r in leggi(roma, "stop_times.txt"):
    if r["stop_id"] in vicine: linee_fermata[r["stop_id"]].add(corsa_linea.get(r["trip_id"], "?"))
per_stazione = defaultdict(list)
for fid, lista in vicine.items():
    for sid, d in lista: per_stazione[sid].append((d, fid))
for sid, nome, lat, lon in stazioni:
    voci = sorted(per_stazione.get(sid, []))
    metro = [(d, fid) for d, fid in voci if any(linee.get(l, ("", ""))[1] in ("1", "2", "12") for l in linee_fermata[fid])]
    if not metro: continue
    scrivi(f"{sid} {nome!r} ({lat:.5f},{lon:.5f}):")
    for d, fid in metro:
        f = fermate[fid]
        nomi = sorted({linee.get(l, ('?', '?'))[0] + '/' + linee.get(l, ('?', '?'))[1] for l in linee_fermata[fid]})
        scrivi(f"   {d:4d} m  {fid!r} codice {f.get('stop_code')!r} nome {f.get('stop_name')!r} tipo {f.get('location_type')!r} padre {f.get('parent_station')!r} linee {nomi[:8]}")
(uscita / "scambi.txt").write_text("\n".join(righe) + "\n")
