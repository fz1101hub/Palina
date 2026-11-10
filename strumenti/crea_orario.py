#!/usr/bin/env python3
"""Crea il database dell'orario da includere nell'APK.

Uso: crea_orario.py ISTRUZIONI.sql CARTELLA_ASSET

Esegue le istruzioni SQL scritte da GeneraOrarioIncluso (test JVM), controlla
che l'orario sia completo e salva in CARTELLA_ASSET:
  - orario.db           il database, nello stesso formato di quello scaricato dall'app
  - orario-incluso.txt  quando e' stato creato (millisecondi), letto dall'app al primo avvio
Se qualcosa non torna non lascia nessun file: l'app scarichera' l'orario al primo avvio.
"""
import os
import sqlite3
import sys
import time

MINIMI = {"linee": 100, "fermate": 3000, "corse": 5000, "percorsi": 100, "fermata_linee": 3000, "forme": 50}


def main():
    sql, cartella = sys.argv[1], sys.argv[2]
    os.makedirs(cartella, exist_ok=True)
    db = os.path.join(cartella, "orario.db")
    info = os.path.join(cartella, "orario-incluso.txt")
    for f in (db, info, db + "-journal"):
        if os.path.exists(f):
            os.remove(f)
    try:
        con = sqlite3.connect(db)
        with open(sql, encoding="utf-8") as testo:
            con.executescript(testo.read())
        # come i database creati da Android: evita che provi a riscriverlo
        con.executescript(
            "BEGIN; CREATE TABLE android_metadata (locale TEXT); "
            "INSERT INTO android_metadata VALUES ('it_IT'); COMMIT;"
        )
        conteggi = {t: con.execute(f"SELECT count(*) FROM {t}").fetchone()[0] for t in MINIMI}
        mancanti = [f"{t} {n} < {MINIMI[t]}" for t, n in conteggi.items() if n < MINIMI[t]]
        if mancanti:
            raise RuntimeError("orario incompleto: " + ", ".join(mancanti))
        assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        creato = int(con.execute("SELECT valore FROM info WHERE chiave = 'creato'").fetchone()[0])
        con.execute("VACUUM")
        con.close()
    except Exception as e:
        for f in (db, info):
            if os.path.exists(f):
                os.remove(f)
        print(f"orario incluso: NO ({e})")
        return 1
    with open(info, "w") as f:
        f.write(str(creato))
    mb = os.path.getsize(db) / 1e6
    giorno = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(creato / 1000))
    print(f"orario incluso: si', {giorno}, {mb:.1f} MB, " + ", ".join(f"{t} {n}" for t, n in conteggi.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
