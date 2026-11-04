#!/usr/bin/env bash
# Runs inside the Android TV emulator step of .github/workflows/weekly-qa.yml.
# Collects everything into qa-out/ for qa/make_report.py. Always exits 0: the report decides.
set -u
OUT=qa-out
mkdir -p "$OUT/video"
unset GITHUB_RUN_NUMBER   # test builds get a high build number, so the app never offers an "update"
PKG=com.osama.xtreamtv

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP
adb shell settings put global stay_on_while_plugged_in 7
adb shell settings put system screen_off_timeout 2147483647
{
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "screen=$(adb shell wm size | tr -d '\r' | sed 's/.*: //')"
  echo "density=$(adb shell wm density | tr -d '\r' | sed 's/.*: //')"
  echo "started=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OUT/device.txt"
cat "$OUT/device.txt"

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

# Screen recording in 3-minute pieces (the emulator's limit per file).
rm -f "$OUT/.stop"
(
  i=0
  while [ ! -f "$OUT/.stop" ]; do
    name=$(printf "part%03d_%s" "$i" "$(date -u +%H%M%S)")
    echo "$name $(date -u +%s)" >> "$OUT/video/parts.txt"
    adb shell screenrecord --bit-rate 900000 --size 960x540 --time-limit 180 "/sdcard/$name.mp4" >/dev/null 2>&1 || sleep 10
    i=$((i + 1))
  done
) &
REC_PID=$!

EXTRA=()
if [ -n "${QA_TESTS:-}" ]; then
  EXTRA+=("-Pandroid.testInstrumentationRunnerArguments.class=${QA_TESTS}")
fi
./gradlew connectedDebugAndroidTest \
  -PbuildNumber=999999 \
  -Pandroid.testInstrumentationRunnerArguments.server=http://10.0.2.2:8080 \
  "${EXTRA[@]}" --continue --stacktrace 2>&1 | tee "$OUT/gradle.txt"
echo "${PIPESTATUS[0]}" > "$OUT/gradle_exit.txt"

touch "$OUT/.stop"
adb shell pkill -2 screenrecord || true
sleep 4
kill "$REC_PID" 2>/dev/null || true

# ---- Random remote presses (Android "monkey"), logged in to the demo account.
if [ "${QA_MONKEY:-1}" = "1" ]; then
  adb install -r -g app/build/outputs/apk/debug/app-debug.apk
  PL='[{&quot;id&quot;:&quot;qa_demo&quot;,&quot;name&quot;:&quot;Demo TV&quot;,&quot;base&quot;:&quot;http://10.0.2.2:8080&quot;,&quot;user&quot;:&quot;demo&quot;,&quot;pass&quot;:&quot;demo&quot;}]'
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"playlists\">%s</string>\n<string name=\"active\">qa_demo</string>\n</map>\n" "$PL" > /tmp/xtream.xml
  adb shell "run-as $PKG mkdir -p shared_prefs"
  adb shell "run-as $PKG sh -c 'cat > shared_prefs/xtream.xml'" < /tmp/xtream.xml
  adb shell "run-as $PKG cat shared_prefs/xtream.xml" | head -3
  adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
  sleep 8
  adb shell screenrecord --bit-rate 900000 --size 960x540 --time-limit 180 /sdcard/monkey.mp4 >/dev/null 2>&1 &
  adb shell monkey -p "$PKG" -s 20261104 --throttle 120 \
    --pct-touch 0 --pct-motion 0 --pct-trackball 0 --pct-pinchzoom 0 --pct-rotation 0 --pct-flip 0 \
    --pct-syskeys 0 --pct-anyevent 0 --pct-permission 0 --pct-appswitch 2 --pct-nav 60 --pct-majornav 38 \
    --ignore-security-exceptions -v -v 4000 > "$OUT/monkey.txt" 2>&1
  adb exec-out screencap -p > "$OUT/monkey_end.png" || true
  sleep 2
fi

for f in $(adb shell 'ls /sdcard/*.mp4' 2>/dev/null | tr -d '\r'); do
  adb pull "$f" "$OUT/video/" >/dev/null 2>&1 || true
done

kill "$LOGCAT_PID" 2>/dev/null || true
cp -r app/build/outputs/connected_android_test_additional_output "$OUT/additional" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results "$OUT/results" 2>/dev/null || true
cp -r app/build/reports/androidTests "$OUT/gradle-report" 2>/dev/null || true
echo "finished=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/device.txt"
exit 0
