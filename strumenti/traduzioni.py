#!/usr/bin/env python3
"""
Le traduzioni di Palina: un testo italiano per riga e le sue traduzioni.

    python3 strumenti/traduzioni.py controlla   # ogni tx()/txn() ha tutte le traduzioni
    python3 strumenti/traduzioni.py mancanti    # i testi nuovi da tradurre (righe TSV vuote)
    python3 strumenti/traduzioni.py genera      # scrive i cataloghi del motore e le risorse

La fonte e' motore/traduzioni.tsv (colonne it, en, fr, es, de, pt, separate da
tabulazioni; "\\n" per andare a capo). Nel codice i testi si scrivono in
italiano dentro tx("...") o txn("...", "...", n), in Kotlin e in Swift: il
testo italiano e' la chiave. "genera" scrive:
  - motore/src/commonMain/kotlin/it/palinaroma/dati/Traduzioni.kt (i cataloghi)
  - orologio/src/main/res/values-xx/strings.xml (nomi per il sistema Wear OS)
  - ios/*/xx.lproj (InfoPlist.strings e i testi dei widget, che non hanno il motore)
"""
import os
import re
import sys

RADICE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TSV = os.path.join(RADICE, "motore", "traduzioni.tsv")
LINGUE = ["it", "en", "fr", "es", "de", "pt"]
NOMI = {"en": "INGLESE", "fr": "FRANCESE", "es": "SPAGNOLO", "de": "TEDESCO", "pt": "PORTOGHESE"}
CARTELLE = ["motore/src", "app/src/main", "orologio/src/main", "ios"]

LETTERALE = r'"(?:[^"\\\n]|\\.)*"'
CONCATENATI = LETTERALE + r'(?:\s*\+\s*' + LETTERALE + r')*'
TX = re.compile(r'\btx\(\s*(' + CONCATENATI + r')')
TXN = re.compile(r'\btxn\(\s*(' + CONCATENATI + r')\s*,\s*(' + CONCATENATI + r')')
SEGNAPOSTO = re.compile(r'\{(\d+)\}')

# I nomi che il sistema Wear OS mostra (manifest, widget): risorsa -> testo italiano
RISORSE_OROLOGIO = {
    "app_name": None,  # "Palina" in tutte le lingue
    "riquadro": "Palina: prossimi passaggi",
    "riquadro_descrizione": "I prossimi bus e treni della fermata preferita più vicina",
    "complicazione": "Palina: prossimo passaggio",
}
# Le voci di Info.plist delle app per iPhone e Apple Watch
INFO_PLIST = {
    "Palina": {
        "NSLocationWhenInUseUsageDescription": "Palina usa la tua posizione per mostrarti le fermate vicine e per calcolare i tragitti.",
    },
}
# I testi dei widget dell'Apple Watch (l'estensione non ha il motore)
WIDGET_OROLOGIO = [
    "La prossima linea alla fermata preferita più vicina.",
    "Apri Palina per chiedere i tempi all'iPhone",
]


def unisci(concatenati):
    """Il testo di una o piu' stringhe unite da +, senza le sequenze di escape."""
    testo = "".join(m[1:-1] for m in re.findall(LETTERALE, concatenati))
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), testo)


def testi_nel_codice():
    trovati = {}
    for cartella in CARTELLE:
        for base, _, files in os.walk(os.path.join(RADICE, cartella)):
            if "/build" in base or "/test" in base.replace(RADICE, "") or "androidTest" in base:
                continue
            for nome in files:
                if not (nome.endswith(".kt") or nome.endswith(".swift")):
                    continue
                percorso = os.path.join(base, nome)
                if nome in ("Traduzioni.kt", "Lingua.kt", "Lingua.swift"):
                    continue
                s = open(percorso, encoding="utf-8").read()
                dove = os.path.relpath(percorso, RADICE)
                for m in TX.finditer(s):
                    trovati.setdefault(unisci(m.group(1)), dove)
                for m in TXN.finditer(s):
                    trovati.setdefault(unisci(m.group(1)), dove)
                    trovati.setdefault(unisci(m.group(2)), dove)
    for testo in list(RISORSE_OROLOGIO.values()) + WIDGET_OROLOGIO + [v for d in INFO_PLIST.values() for v in d.values()]:
        if testo:
            trovati.setdefault(testo, "risorse")
    return trovati


def leggi_tsv():
    righe = {}
    if not os.path.exists(TSV):
        return righe
    for n, riga in enumerate(open(TSV, encoding="utf-8").read().split("\n")):
        if n == 0 or not riga.strip():
            continue
        colonne = [c.replace("\\n", "\n") for c in riga.split("\t")]
        colonne += [""] * (len(LINGUE) - len(colonne))
        righe[colonne[0]] = dict(zip(LINGUE, colonne))
    return righe


def scrivi_tsv(righe):
    with open(TSV, "w", encoding="utf-8") as f:
        f.write("\t".join(LINGUE) + "\n")
        for chiave in sorted(righe, key=lambda k: k.lower()):
            f.write("\t".join(righe[chiave].get(l, "").replace("\n", "\\n") for l in LINGUE) + "\n")


def problemi(righe, trovati):
    errori = []
    for chiave, dove in sorted(trovati.items()):
        riga = righe.get(chiave)
        if riga is None:
            errori.append(f"senza traduzioni ({dove}): {chiave!r}")
            continue
        attesi = sorted(set(SEGNAPOSTO.findall(chiave.split("§")[0])))
        for l in LINGUE[1:]:
            t = riga.get(l, "")
            if not t:
                errori.append(f"manca {l} ({dove}): {chiave!r}")
            elif sorted(set(SEGNAPOSTO.findall(t))) != attesi:
                errori.append(f"segnaposto diversi in {l}: {chiave!r} -> {t!r}")
            elif "§" in t:
                errori.append(f"'§' nella traduzione {l}: {t!r}")
    return errori


def kotlin(testo):
    return '"' + testo.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


def genera(righe, trovati):
    usate = {k: v for k, v in righe.items() if k in trovati}
    righe_kt = [
        "// Generato da strumenti/traduzioni.py da motore/traduzioni.tsv: non modificare a mano.",
        "package it.palinaroma.dati",
        "",
    ]
    chiavi = sorted(usate)
    for l in LINGUE[1:]:
        nome = NOMI[l]
        pezzi = [chiavi[i:i + 150] for i in range(0, len(chiavi), 150)]
        righe_kt.append(f"internal val {nome}: Map<String, String> by lazy {{")
        righe_kt.append(f"    HashMap<String, String>({len(chiavi) * 2}).also {{")
        for i in range(len(pezzi)):
            righe_kt.append(f"        {nome.lower()}{i + 1}(it)")
        righe_kt.append("    }")
        righe_kt.append("}")
        righe_kt.append("")
        for i, pezzo in enumerate(pezzi):
            righe_kt.append(f"private fun {nome.lower()}{i + 1}(m: HashMap<String, String>) {{")
            for k in pezzo:
                righe_kt.append(f"    m[{kotlin(k)}] = {kotlin(usate[k][l])}")
            righe_kt.append("}")
            righe_kt.append("")
    percorso = os.path.join(RADICE, "motore/src/commonMain/kotlin/it/palinaroma/dati/Traduzioni.kt")
    open(percorso, "w", encoding="utf-8").write("\n".join(righe_kt).rstrip() + "\n")

    def xml(t):
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')

    for l in LINGUE[1:]:
        cartella = os.path.join(RADICE, f"orologio/src/main/res/values-{l}")
        os.makedirs(cartella, exist_ok=True)
        voci = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generato da strumenti/traduzioni.py: non modificare a mano. -->", "<resources>"]
        for nome, testo in RISORSE_OROLOGIO.items():
            if testo:
                voci.append(f'    <string name="{nome}">{xml(righe[testo][l])}</string>')
        voci.append("</resources>")
        open(os.path.join(cartella, "strings.xml"), "w", encoding="utf-8").write("\n".join(voci) + "\n")

    def stringa_apple(t):
        return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'

    for l in LINGUE:
        for app, voci in INFO_PLIST.items():
            cartella = os.path.join(RADICE, "ios", app, f"{l}.lproj")
            os.makedirs(cartella, exist_ok=True)
            testo = "/* Generato da strumenti/traduzioni.py: non modificare a mano. */\n"
            for chiave, it in voci.items():
                testo += f"{chiave} = {stringa_apple(righe[it][l] if l != 'it' else it)};\n"
            open(os.path.join(cartella, "InfoPlist.strings"), "w", encoding="utf-8").write(testo)
        # l'app per Apple Watch e la Live Activity: basta che la lingua ci sia
        for app in ("PalinaWatch", "PalinaWidget"):
            cartella = os.path.join(RADICE, "ios", app, f"{l}.lproj")
            os.makedirs(cartella, exist_ok=True)
            open(os.path.join(cartella, "InfoPlist.strings"), "w", encoding="utf-8").write(
                "/* Generato da strumenti/traduzioni.py: la lingua c'e', i testi vengono dal motore. */\n")
        cartella = os.path.join(RADICE, "ios", "PalinaWatchWidget", f"{l}.lproj")
        os.makedirs(cartella, exist_ok=True)
        testo = "/* Generato da strumenti/traduzioni.py: non modificare a mano. */\n"
        for it in WIDGET_OROLOGIO:
            testo += f"{stringa_apple(it)} = {stringa_apple(righe[it][l] if l != 'it' else it)};\n"
        open(os.path.join(cartella, "Localizable.strings"), "w", encoding="utf-8").write(testo)
    print(f"generati i cataloghi: {len(usate)} testi in {len(LINGUE) - 1} lingue")


def main():
    comando = sys.argv[1] if len(sys.argv) > 1 else "controlla"
    trovati = testi_nel_codice()
    righe = leggi_tsv()
    if comando == "mancanti":
        nuovi = [k for k in trovati if k not in righe]
        for k in nuovi:
            righe[k] = {"it": k}
        scrivi_tsv(righe)
        inutili = [k for k in righe if k not in trovati]
        print(f"{len(trovati)} testi nel codice, {len(nuovi)} nuovi, {len(inutili)} nel TSV ma non usati")
        for k in inutili:
            print("  non usato:", repr(k))
        return 0
    errori = problemi(righe, trovati)
    if comando == "genera":
        if errori:
            print("\n".join(errori[:50]))
            print(f"{len(errori)} problemi: niente generato")
            return 1
        genera(righe, trovati)
        return 0
    if errori:
        print("\n".join(errori))
        print(f"traduzioni: {len(errori)} problemi")
        return 1
    print(f"traduzioni: {len(trovati)} testi, tutti tradotti in {len(LINGUE) - 1} lingue")
    return 0


if __name__ == "__main__":
    sys.exit(main())
