#!/usr/bin/env python3
"""Stampa l'ultima versione stabile di ogni dipendenza (solo per la ricerca, non fa parte dell'app)."""
import json, re, urllib.request, xml.etree.ElementTree as ET, os

GOOGLE = "https://dl.google.com/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
STABILE = re.compile(r"^\d+(\.\d+)*$")

def leggi(url, intestazioni=None):
    r = urllib.request.Request(url, headers=intestazioni or {"User-Agent": "versioni-palina"})
    with urllib.request.urlopen(r, timeout=60) as f:
        return f.read()

def chiave(v):
    return [int(x) for x in v.split(".")]

def versioni(repo, coord):
    g, a = coord.split(":")
    xml = leggi(f"{repo}/{g.replace('.', '/')}/{a}/maven-metadata.xml")
    radice = ET.fromstring(xml)
    tutte = [v.text for v in radice.iter("version")]
    agg = radice.findtext(".//lastUpdated")
    stabili = sorted([v for v in tutte if STABILE.match(v)], key=chiave)
    return tutte, stabili, agg

ARTEFATTI = [
    (GOOGLE, "com.android.tools.build:gradle"),
    (GOOGLE, "androidx.compose:compose-bom"),
    (GOOGLE, "androidx.core:core-ktx"),
    (GOOGLE, "androidx.activity:activity-compose"),
    (GOOGLE, "androidx.navigation:navigation-compose"),
    (GOOGLE, "androidx.lifecycle:lifecycle-runtime-compose"),
    (GOOGLE, "androidx.compose.material:material-icons-core"),
    (GOOGLE, "androidx.compose.material3:material3"),
    (GOOGLE, "com.google.android.gms:play-services-location"),
    (GOOGLE, "androidx.test:runner"),
    (GOOGLE, "androidx.test:rules"),
    (GOOGLE, "androidx.test:core"),
    (GOOGLE, "androidx.test.ext:junit"),
    (GOOGLE, "androidx.test.uiautomator:uiautomator"),
    (GOOGLE, "androidx.test:orchestrator"),
    (GOOGLE, "androidx.test.services:test-services"),
    (CENTRAL, "org.jetbrains.kotlin:kotlin-gradle-plugin"),
    (CENTRAL, "org.jetbrains.kotlin:compose-compiler-gradle-plugin"),
    (CENTRAL, "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
    (CENTRAL, "com.squareup.okhttp3:okhttp"),
    (CENTRAL, "com.squareup.okhttp3:okhttp-android"),
    (CENTRAL, "org.osmdroid:osmdroid-android"),
    (CENTRAL, "junit:junit"),
]

for repo, coord in ARTEFATTI:
    try:
        tutte, stabili, agg = versioni(repo, coord)
        print(f"{coord}: STABILE {stabili[-1] if stabili else '-'} | ultime {tutte[-4:]} | stabili recenti {stabili[-5:]} | agg {agg}")
    except Exception as e:
        print(f"{coord}: ERRORE {e}")

# cosa contiene l'ultimo BOM di Compose
try:
    _, stabili, _ = versioni(GOOGLE, "androidx.compose:compose-bom")
    bom = stabili[-1]
    pom = leggi(f"{GOOGLE}/androidx/compose/compose-bom/{bom}/compose-bom-{bom}.pom").decode()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    radice = ET.fromstring(pom)
    print(f"\nBOM {bom} contiene:")
    for d in radice.findall(".//m:dependencyManagement/m:dependencies/m:dependency", ns):
        g = d.findtext("m:groupId", namespaces=ns); a = d.findtext("m:artifactId", namespaces=ns); v = d.findtext("m:version", namespaces=ns)
        if a in ("ui", "material3", "material-icons-core", "material-icons-extended", "ui-test-junit4", "ui-test-manifest", "foundation", "ui-tooling", "ui-tooling-preview", "animation"):
            print(f"  {g}:{a} = {v}")
except Exception as e:
    print("BOM: ERRORE", e)

# Gradle
try:
    c = json.loads(leggi("https://services.gradle.org/versions/current"))
    print(f"\nGradle attuale: {c['version']} ({c['buildTime']}) {c['downloadUrl']} checksum {c.get('checksumUrl')}")
    try:
        print("  sha256 bin:", leggi(c['checksumUrl']).decode().strip())
    except Exception as e:
        print("  sha256: ERRORE", e)
except Exception as e:
    print("Gradle: ERRORE", e)

# azioni GitHub
tok = os.environ.get("GH_TOKEN", "")
for r in ["actions/checkout", "actions/setup-java", "gradle/actions", "actions/upload-artifact",
          "actions/download-artifact", "reactivecircus/android-emulator-runner", "osmdroid/osmdroid", "square/okhttp",
          "JetBrains/kotlin", "Kotlin/kotlinx.coroutines"]:
    try:
        d = json.loads(leggi(f"https://api.github.com/repos/{r}/releases/latest",
                             {"Authorization": f"Bearer {tok}", "User-Agent": "versioni-palina"}))
        print(f"{r}: {d['tag_name']} ({d['published_at']})")
    except Exception as e:
        print(f"{r}: ERRORE {e}")
