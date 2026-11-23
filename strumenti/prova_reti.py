#!/usr/bin/env python3
"""Prova dal vivo le fonti delle altre reti, con l'orario di domattina alle 8.

Uso: prova_reti.py CARTELLA_ASSET CERTIFICATO_ASTRAL.pem
Legge tre fermate da reti.db (una Cotral, una stazione dei treni Cotral, una
Trenitalia) e chiede le partenze a Transitous e, per i treni Cotral, ad ASTRAL.
Scrive solo un riassunto: serve a vedere nel registro della compilazione se le
fonti rispondono e cosa danno, anche quando la prova sull'emulatore cade di notte.
"""
import datetime
import json
import os
import sqlite3
import ssl
import sys
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

AGENTE = "PalinaRoma-CI (+https://github.com/fz1101hub/Palina)"
DATI = {"C:": "it-Lazio-Cotral", "F:": "it-Lazio-Cotral-Ferrovie", "T:": "it-trenitalia"}


def scrivi(testo):
    print("reti incluse: prova " + testo)


def leggi(url, corpo=None, contesto=None):
    richiesta = urllib.request.Request(url, data=corpo, headers={"User-Agent": AGENTE, "Content-Type": "application/json"})
    with urllib.request.urlopen(richiesta, timeout=30, context=contesto) as r:
        return json.loads(r.read().decode("utf-8"))


def fermata(con, condizione, argomento):
    return con.execute(f"SELECT id, nome, lat, lon FROM fermate WHERE {condizione} LIMIT 1", (argomento,)).fetchone()


def transitous(f):
    id_, nome, lat, lon = f
    prefisso = id_[:2]
    domani = (datetime.datetime.now(ZoneInfo("Europe/Rome")) + datetime.timedelta(days=1)).replace(
        hour=8, minute=0, second=0, microsecond=0)
    parametri = {
        "stopId": DATI[prefisso] + "_" + id_[2:],
        "center": f"{lat:.6f},{lon:.6f}", "radius": "30", "exactRadius": "true",
        "n": "12", "window": "5400", "language": "it",
        "time": domani.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    try:
        dati = leggi("https://api.transitous.org/api/v5/stoptimes?" + urllib.parse.urlencode(parametri))
    except Exception as e:
        scrivi(f"{nome}: Transitous non risponde ({e})")
        return
    voci = dati.get("stopTimes", [])
    rt = sum(1 for v in voci if v.get("realTime"))
    esempi = "; ".join(
        f"{v.get('routeShortName') or v.get('displayName')} -> {v.get('headsign')} ({v.get('place', {}).get('stopId')})"
        for v in voci[:4])
    scrivi(f"{nome} ({id_}), domani alle 8: {len(voci)} partenze, {rt} in tempo reale. {esempi}")


def astral(nome, certificato):
    contesto = ssl.create_default_context()
    contesto.load_verify_locations(cafile=certificato)
    base = "https://gestionecorse.astralspa.it/api/"
    try:
        stazioni = leggi(base + "fermate/RL_PSP-CC", b"{}", contesto)
        righe = list(stazioni.values()) if isinstance(stazioni, dict) else stazioni
        codice = next((r.get("codice") for r in righe if str(r.get("nomeFermata", "")).strip().lower() == nome.lower()), None)
        scrivi(f"ASTRAL: {len(righe)} stazioni sulla Metromare, {nome} = {codice}")
        if codice is None:
            return
        corpo = json.dumps({"percorso": "RL_PSP-CC", "fermata": str(codice)}).encode()
        transiti = leggi(base + "transit", corpo, contesto)
        voci = list(transiti.values()) if isinstance(transiti, dict) else transiti
        con_ritardo = sum(1 for v in voci if str(v.get("ritardo", "")).strip() != "")
        esempi = ", ".join(f"{v.get('orario')} +{v.get('ritardo')}" for v in voci[:5])
        scrivi(f"ASTRAL {nome}: {len(voci)} treni oggi verso Cristoforo Colombo, {con_ritardo} con il ritardo. {esempi}")
    except Exception as e:
        scrivi(f"ASTRAL non risponde ({e})")


def main():
    cartella, certificato = sys.argv[1], sys.argv[2]
    db = os.path.join(cartella, "reti.db")
    if not os.path.exists(db):
        scrivi("niente reti.db")
        return 0
    con = sqlite3.connect(db)
    for f in (
        fermata(con, "id LIKE 'C:%' AND nome_norm LIKE ?", "frascati%"),
        fermata(con, "id LIKE 'F:%' AND nome_norm LIKE ?", "lido centro%"),
        fermata(con, "id LIKE 'T:%' AND nome_norm LIKE ?", "roma ostiense%"),
    ):
        if f:
            transitous(f)
    astral("Lido Centro", certificato)
    return 0


if __name__ == "__main__":
    sys.exit(main())
