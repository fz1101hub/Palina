#!/usr/bin/env python3
"""Crea il database delle altre reti (Cotral bus e treni, stazioni Trenitalia del Lazio).

Uso: crea_reti.py ISTRUZIONI.sql CARTELLA_ASSET  (di solito app/src/main/assets)

Esegue le istruzioni SQL scritte da GeneraRetiIncluse (test JVM) e salva in CARTELLA_ASSET:
  - reti.db            stesse tabelle dell'orario di Roma, codici con il prefisso della rete
  - reti-incluse.txt   quando e' stato creato (millisecondi): l'app lo ricopia quando cambia
Se non c'e' nessuna rete con abbastanza fermate non lascia nessun file: l'app funziona con Roma.
"""
import os
import sqlite3
import sys
import time

RETI = {"C:": "Cotral bus", "F:": "Cotral treni", "T:": "Trenitalia"}
MINIMO_FERMATE = 10


def main():
    sql, cartella = sys.argv[1], sys.argv[2]
    os.makedirs(cartella, exist_ok=True)
    db = os.path.join(cartella, "reti.db")
    info = os.path.join(cartella, "reti-incluse.txt")
    for f in (db, info, db + "-journal"):
        if os.path.exists(f):
            os.remove(f)
    try:
        con = sqlite3.connect(db)
        with open(sql, encoding="utf-8") as testo:
            con.executescript(testo.read())
        con.executescript(
            "BEGIN; CREATE TABLE android_metadata (locale TEXT); "
            "INSERT INTO android_metadata VALUES ('it_IT'); COMMIT;"
        )
        righe = []
        buone = 0
        for prefisso, nome in RETI.items():
            simile = prefisso + "%"
            fermate = con.execute("SELECT count(*) FROM fermate WHERE id LIKE ?", (simile,)).fetchone()[0]
            linee = con.execute("SELECT count(*) FROM linee WHERE id LIKE ?", (simile,)).fetchone()[0]
            percorsi = con.execute("SELECT count(*) FROM percorsi WHERE linea LIKE ?", (simile,)).fetchone()[0]
            if fermate >= MINIMO_FERMATE:
                buone += 1
            righe.append(f"{nome} {fermate} fermate, {linee} linee, {percorsi} percorsi")
        if buone == 0:
            raise RuntimeError("nessuna rete: " + "; ".join(righe))
        assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        creato = int(con.execute("SELECT valore FROM info WHERE chiave = 'creato'").fetchone()[0])
        # un esempio di linee per rete, per controllare i nomi nel registro della compilazione
        for prefisso, nome in RETI.items():
            esempi = [r[0] for r in con.execute(
                "SELECT nome FROM linee WHERE id LIKE ? ORDER BY random() LIMIT 8", (prefisso + "%",))]
            if esempi:
                righe.append(f"{nome}, linee per esempio: " + ", ".join(esempi))
        con.execute("VACUUM")
        # quanto pesa ogni tabella (se questo sqlite ha dbstat)
        try:
            pesi = con.execute("SELECT name, sum(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC LIMIT 8").fetchall()
            righe.append("peso: " + ", ".join(f"{n} {b / 1e6:.1f} MB" for n, b in pesi))
        except sqlite3.Error:
            pass
        con.close()
    except Exception as e:
        for f in (db, info):
            if os.path.exists(f):
                os.remove(f)
        print(f"reti incluse: NO ({e})")
        return 1
    with open(info, "w") as f:
        f.write(str(creato))
    mb = os.path.getsize(db) / 1e6
    giorno = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(creato / 1000))
    print(f"reti incluse: si', {giorno}, {mb:.1f} MB")
    for r in righe:
        print("reti incluse: " + r)
    return 0


if __name__ == "__main__":
    sys.exit(main())
