#!/usr/bin/env bash
# Avvia un telefono Android simulato (emulatore) e ci prova l'app con
# app/src/androidTest/.../ProvaSchermate.kt. Usato dalla compilazione su GitHub.
#
# Uso: strumenti/prova-emulatore.sh "<immagine di sistema>" <cartella dei risultati>
#   es. strumenti/prova-emulatore.sh "system-images;android-37.0;google_apis;x86_64" risultati-37
#
# Nella cartella dei risultati: riassunto.txt, prova.log (Gradle), schermate/,
# logcat-app.txt (righe dell'app e degli errori), crash.txt, risultati JUnit.
set -uo pipefail

IMMAGINE="$1"
RISULTATI="$(mkdir -p "$2" && cd "$2" && pwd)"
SDK="${ANDROID_HOME:?serve ANDROID_HOME}"
STRUMENTI="$SDK/cmdline-tools/latest/bin"
[ -x "$STRUMENTI/sdkmanager" ] || STRUMENTI="$(dirname "$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager | sort -V | tail -1)")"
ADB="$SDK/platform-tools/adb"

echo "== installo emulatore e immagine $IMMAGINE"
yes | "$STRUMENTI/sdkmanager" --licenses > /dev/null 2>&1 || true
"$STRUMENTI/sdkmanager" --install "emulator" "platform-tools" "$IMMAGINE" > "$RISULTATI/sdkmanager.log" 2>&1 || {
    echo "installazione non riuscita"; tail -20 "$RISULTATI/sdkmanager.log"; exit 1
}

echo "== creo il telefono simulato"
echo no | "$STRUMENTI/avdmanager" create avd --force -n prova -k "$IMMAGINE" -d pixel_7 > /dev/null
CONFIG="$HOME/.android/avd/prova.avd/config.ini"
{
    echo "hw.ramSize=4096"
    echo "disk.dataPartition.size=6G"
} >> "$CONFIG"

echo "== avvio"
"$SDK/emulator/emulator" -avd prova -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    > "$RISULTATI/emulatore.log" 2>&1 &
EMULATORE=$!

"$ADB" wait-for-device
avviato=""
for _ in $(seq 1 120); do
    if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then avviato=1; break; fi
    if ! kill -0 "$EMULATORE" 2>/dev/null; then break; fi
    sleep 5
done
if [ -z "$avviato" ]; then
    echo "L'emulatore non si e' avviato"
    tail -40 "$RISULTATI/emulatore.log"
    exit 1
fi
# il gestore dei pacchetti a volte arriva qualche secondo dopo
for _ in $(seq 1 30); do "$ADB" shell pm path android > /dev/null 2>&1 && break; sleep 2; done
echo "avviato: Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'))"

echo "== preparo il telefono"
for a in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$a" 0
done
"$ADB" shell settings put system screen_off_timeout 1800000
"$ADB" shell svc power stayon true
"$ADB" shell input keyevent 82
"$ADB" shell wm dismiss-keyguard > /dev/null 2>&1 || true
# localizzazione accesa: il comando cambia con la versione di Android
"$ADB" shell settings put secure location_mode 3 > /dev/null 2>&1 || true
"$ADB" shell settings put secure location_providers_allowed +gps > /dev/null 2>&1 || true
"$ADB" shell cmd location set-location-enabled true > /dev/null 2>&1 || true
# la posizione del telefono simulato: piazza Venezia, ripetuta ogni 5 secondi
( while true; do "$ADB" emu geo fix 12.4823 41.8960 > /dev/null 2>&1; sleep 5; done ) &
POSIZIONE=$!
"$ADB" logcat -c || true

echo "== prova dell'app"
./gradlew --no-daemon --console=plain connectedDebugAndroidTest > "$RISULTATI/prova.log" 2>&1
ESITO=$?
kill "$POSIZIONE" 2>/dev/null || true

echo "== raccolgo i risultati"
mkdir -p "$RISULTATI/schermate"
"$ADB" pull /data/local/tmp/schermate/. "$RISULTATI/schermate/" > /dev/null 2>&1 || true
"$ADB" logcat -d -v time > "$RISULTATI/logcat.txt" 2>&1 || true
grep -E "palinaroma|AndroidRuntime|FATAL|ProvaSchermate|TestRunner|osmdroid|System.err" "$RISULTATI/logcat.txt" \
    > "$RISULTATI/logcat-app.txt" 2>/dev/null || true
"$ADB" logcat -d -b crash > "$RISULTATI/crash.txt" 2>&1 || true
cp -r app/build/outputs/androidTest-results "$RISULTATI/junit" 2>/dev/null || true
{
    echo "immagine: $IMMAGINE"
    echo "esito: $([ $ESITO -eq 0 ] && echo riuscita || echo fallita)"
    grep -E "Starting [0-9]+ tests|Tests on .* failed|tests completed|FAILED|BUILD (SUCCESSFUL|FAILED)|^e: |error:" "$RISULTATI/prova.log" | head -60
    grep -A15 "What went wrong" "$RISULTATI/prova.log" | head -40
} > "$RISULTATI/riassunto.txt"
cat "$RISULTATI/riassunto.txt"

"$ADB" emu kill > /dev/null 2>&1 || true
exit $ESITO
