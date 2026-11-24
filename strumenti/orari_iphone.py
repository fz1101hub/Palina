#!/usr/bin/env python3
"""
Prepara l'orario per l'aggiornamento dell'app per iPhone.

    python3 strumenti/orari_iphone.py CARTELLA_ASSET CARTELLA_USCITA

Da orario.db e reti.db (con orario-incluso.txt e reti-incluse.txt, cioe'
quando sono stati creati) scrive in CARTELLA_USCITA:
  - orario.db.deflate, reti.db.deflate  compressi con DEFLATE senza intestazione
    (il formato "zlib" di Apple: l'iPhone li apre con NSData.decompressed)
  - orari-iphone.json  {"orario": ms, "reti": ms, "orarioByte": n, "retiByte": n}
L'iPhone legge orari-iphone.json e scarica solo cio' che e' piu' nuovo.
"""
import json
import os
import sqlite3
import sys
import zlib


def comprimi(sorgente: str, destinazione: str) -> int:
    compressore = zlib.compressobj(9, zlib.DEFLATED, -15)
    with open(sorgente, "rb") as dentro, open(destinazione, "wb") as fuori:
        while True:
            pezzo = dentro.read(1 << 20)
            if not pezzo:
                break
            fuori.write(compressore.compress(pezzo))
        fuori.write(compressore.flush())
    # controllo: si decomprime e torna uguale
    with open(destinazione, "rb") as f:
        assert zlib.decompress(f.read(), -15) == open(sorgente, "rb").read(), destinazione
    return os.path.getsize(destinazione)


def creato(cartella: str, nome: str) -> int:
    with open(os.path.join(cartella, nome), encoding="utf-8") as f:
        return int(f.read().strip())


def main() -> int:
    asset, uscita = sys.argv[1], sys.argv[2]
    os.makedirs(uscita, exist_ok=True)
    roma = os.path.join(asset, "orario.db")
    if not os.path.exists(roma):
        print("orari per iPhone: manca orario.db, niente da pubblicare")
        return 0
    fermate = sqlite3.connect(roma).execute("SELECT COUNT(*) FROM fermate").fetchone()[0]
    if fermate < 1000:
        print(f"orari per iPhone: orario.db ha solo {fermate} fermate, non lo pubblico")
        return 0
    elenco = {"orario": creato(asset, "orario-incluso.txt")}
    elenco["orarioByte"] = comprimi(roma, os.path.join(uscita, "orario.db.deflate"))
    reti = os.path.join(asset, "reti.db")
    if os.path.exists(reti) and os.path.exists(os.path.join(asset, "reti-incluse.txt")):
        elenco["reti"] = creato(asset, "reti-incluse.txt")
        elenco["retiByte"] = comprimi(reti, os.path.join(uscita, "reti.db.deflate"))
    with open(os.path.join(uscita, "orari-iphone.json"), "w", encoding="utf-8") as f:
        json.dump(elenco, f)
    print("orari per iPhone:", json.dumps(elenco))
    return 0


if __name__ == "__main__":
    sys.exit(main())
