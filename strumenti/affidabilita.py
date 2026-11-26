#!/usr/bin/env python3
"""
L'affidabilita' delle linee di Roma: ogni ora si legge il tempo reale di Roma
Mobilita' (trip updates) e, per ogni corsa, il ritardo alla prossima fermata
rispetto all'orario. I ritardi si sommano per linea, tipo di giorno (feriale,
sabato, festivo) e fascia oraria, giorno per giorno, per le ultime 4 settimane.
Ne esce affidabilita.json: per ogni linea e fascia quante corse sono in orario
(da 1 minuto in anticipo a 5 di ritardo), il ritardo medio e quante sono oltre
i 5 minuti. L'app lo scarica e lo mostra sulla linea.

Uso: affidabilita.py STATO.json.gz GTFS.zip USCITA.json [FEED.pb]
"""
import csv
import datetime as dt
import gzip
import io
import json
import sys
import time
import urllib.request
import zipfile
from zoneinfo import ZoneInfo

from google.transit import gtfs_realtime_pb2

FEED = "https://romamobilita.it/sites/default/files/rome_rtgtfs_trip_updates_feed.pb"
AGENTE = "PalinaRoma-CI (+https://github.com/fz1101hub/Palina)"
ROMA = ZoneInfo("Europe/Rome")
GIORNI_TENUTI = 28
MINIMO_CORSE = 60

# feste nazionali (piu' i santi Pietro e Paolo, patroni di Roma): si contano come domeniche
FESTE = {(1, 1), (1, 6), (4, 25), (5, 1), (6, 2), (6, 29), (8, 15), (11, 1), (12, 8), (12, 25), (12, 26)}


def pasqua(anno):
    a = anno % 19
    b, c = divmod(anno, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    mese = (h + l - 7 * m + 114) // 31
    giorno = ((h + l - 7 * m + 114) % 31) + 1
    return dt.date(anno, mese, giorno)


def tipo_giorno(d):
    """F feriale, S sabato, D domenica e festivi (anche Pasquetta)."""
    if (d.month, d.day) in FESTE or d == pasqua(d.year) + dt.timedelta(days=1) or d.weekday() == 6:
        return "D"
    return "S" if d.weekday() == 5 else "F"


def fascia(quando):
    """La fascia oraria: 05 (5-9), 09 (9-13), 13, 17, 21 (21-1), 01 (notte)."""
    ora = quando.hour
    for inizio in (21, 17, 13, 9, 5):
        if ora >= inizio:
            return "%02d" % inizio
    return "21" if ora < 1 else "01"


def leggi_feed(origine):
    if origine.startswith("http"):
        richiesta = urllib.request.Request(origine, headers={"User-Agent": AGENTE})
        dati = urllib.request.urlopen(richiesta, timeout=60).read()
    else:
        dati = open(origine, "rb").read()
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString(dati)
    return feed


def prossima_fermata(tu, adesso):
    """La prima fermata non ancora passata: (stop_sequence, stop_id, istante previsto, ritardo se il feed lo dice)."""
    for stu in tu.stop_time_update:
        evento = stu.departure if stu.HasField("departure") else stu.arrival
        if not evento.HasField("time") and not evento.HasField("delay"):
            continue
        istante = evento.time if evento.HasField("time") else None
        if istante is not None and istante < adesso - 60:
            continue
        ritardo = evento.delay if evento.HasField("delay") else None
        return stu.stop_sequence if stu.HasField("stop_sequence") else None, stu.stop_id, istante, ritardo
    return None


def orari_previsti(gtfs, corse):
    """Per le corse richieste: (trip_id, stop_sequence) e (trip_id, stop_id) -> secondi dalla mezzanotte."""
    previsti = {}
    with zipfile.ZipFile(gtfs) as z, z.open("stop_times.txt") as f:
        lettore = csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig"))
        for r in lettore:
            corsa = r["trip_id"]
            if corsa not in corse:
                continue
            orario = r.get("departure_time") or r.get("arrival_time")
            if not orario:
                continue
            h, m, s = (int(x) for x in orario.split(":"))
            secondi = h * 3600 + m * 60 + s
            previsti[(corsa, r["stop_sequence"])] = secondi
            # per fermata solo se la corsa ci passa una volta (le circolari ci ripassano)
            chiave = (corsa, "id:" + r["stop_id"])
            previsti[chiave] = None if chiave in previsti else secondi
    return previsti


def istante_previsto(secondi, previsto_reale):
    """L'istante dell'orario (secondi dalla mezzanotte del giorno di servizio), sul giorno piu' vicino al previsto in tempo reale."""
    giorno = dt.datetime.fromtimestamp(previsto_reale, ROMA).date()
    candidati = []
    for scarto in (-1, 0):
        d = giorno + dt.timedelta(days=scarto)
        # mezzogiorno meno 12 ore, come dice GTFS (giusto anche nei giorni del cambio d'ora)
        mezzogiorno = dt.datetime(d.year, d.month, d.day, 12, tzinfo=ROMA).timestamp()
        candidati.append(mezzogiorno - 12 * 3600 + secondi)
    return min(candidati, key=lambda c: abs(c - previsto_reale))


def campiona(feed, gtfs, adesso):
    """I ritardi (secondi) di questo momento: [(linea, ritardo)]."""
    da_cercare = {}
    pronti = []
    for e in feed.entity:
        if not e.HasField("trip_update"):
            continue
        tu = e.trip_update
        linea = tu.trip.route_id
        if not linea or tu.trip.schedule_relationship == gtfs_realtime_pb2.TripDescriptor.CANCELED:
            continue
        prossima = prossima_fermata(tu, adesso)
        if prossima is None:
            continue
        sequenza, fermata, istante, ritardo = prossima
        if ritardo is not None:
            pronti.append((linea, ritardo))
        elif istante is not None and istante <= adesso + 30 * 60:
            da_cercare[tu.trip.trip_id] = (linea, sequenza, fermata, istante)
    dal_feed = len(pronti)
    per_sequenza = per_fermata = 0
    if da_cercare and gtfs:
        previsti = orari_previsti(gtfs, set(da_cercare))
        for corsa, (linea, sequenza, fermata, istante) in da_cercare.items():
            secondi = previsti.get((corsa, str(sequenza))) if sequenza is not None else None
            if secondi is not None:
                per_sequenza += 1
            else:
                secondi = previsti.get((corsa, "id:" + fermata))
                if secondi is None:
                    continue
                per_fermata += 1
            pronti.append((linea, round(istante - istante_previsto(secondi, istante))))
    # un ritardo di piu' di un'ora (o un anticipo di oltre 15 minuti) e' quasi sempre un errore del feed
    buoni = [(l, r) for l, r in pronti if -900 <= r <= 3600]
    ritardi = sorted(r for _, r in buoni)
    if ritardi:
        q = lambda x: ritardi[min(len(ritardi) - 1, int(x * len(ritardi)))]
        print("ritardi: %d dal feed, %d per sequenza, %d per fermata, %d scartati; 10%%=%ds mediana=%ds 90%%=%ds"
              % (dal_feed, per_sequenza, per_fermata, len(pronti) - len(buoni), q(0.1), q(0.5), q(0.9)))
    return buoni


def aggiungi(stato, adesso, campioni):
    quando = dt.datetime.fromtimestamp(adesso, ROMA)
    # dopo l'una di notte la corsa e' ancora del giorno prima
    giorno = (quando - dt.timedelta(hours=1)).date() if quando.hour < 1 else quando.date()
    chiave_fascia = tipo_giorno(giorno) + fascia(quando)
    oggi = stato.setdefault("giorni", {}).setdefault(giorno.isoformat(), {})
    for linea, ritardo in campioni:
        conti = oggi.setdefault(linea, {}).setdefault(chiave_fascia, [0, 0, 0, 0])
        conti[0] += 1
        conti[1] += ritardo
        conti[2] += 1 if -60 <= ritardo <= 300 else 0
        conti[3] += 1 if ritardo > 300 else 0
    limite = (quando.date() - dt.timedelta(days=GIORNI_TENUTI)).isoformat()
    for vecchio in [g for g in stato["giorni"] if g < limite]:
        del stato["giorni"][vecchio]


def riassunto(stato, adesso):
    totali = {}
    for linee in stato.get("giorni", {}).values():
        for linea, fasce in linee.items():
            for f, conti in fasce.items():
                t = totali.setdefault(linea, {}).setdefault(f, [0, 0, 0, 0])
                for i in range(4):
                    t[i] += conti[i]
    linee = {}
    for linea, fasce in totali.items():
        voci = {}
        for f, (n, somma, puntuali, oltre5) in fasce.items():
            if n >= MINIMO_CORSE:
                voci[f] = [n, round(somma / n), round(100 * puntuali / n), round(100 * oltre5 / n)]
        if voci:
            linee[linea] = voci
    return {"v": 1, "aggiornato": int(adesso * 1000), "giorni": len(stato.get("giorni", {})), "linee": linee}


def main():
    percorso_stato, gtfs, uscita = sys.argv[1:4]
    origine = sys.argv[4] if len(sys.argv) > 4 else FEED
    try:
        with gzip.open(percorso_stato, "rt") as f:
            stato = json.load(f)
    except (OSError, ValueError):
        stato = {}
    adesso = time.time()
    feed = leggi_feed(origine)
    campioni = campiona(feed, gtfs if gtfs and gtfs != "-" else None, adesso)
    aggiungi(stato, adesso, campioni)
    with gzip.open(percorso_stato, "wt") as f:
        json.dump(stato, f, separators=(",", ":"))
    r = riassunto(stato, adesso)
    with open(uscita, "w") as f:
        json.dump(r, f, separators=(",", ":"))
    print("affidabilita': %d campioni ora, %d linee con abbastanza corse, %d giorni" % (len(campioni), len(r["linee"]), r["giorni"]))


if __name__ == "__main__":
    main()
