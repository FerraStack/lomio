#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner. Installs the debug + test APKs, takes a
# launch screenshot, runs every instrumentation test with a tiny GGUF and fails on any crash in
# logcat. Outputs land in ./e2e-out (uploaded as an artifact by CI).
set -euo pipefail

APK=apk/app-debug.apk
TEST_APK=apk/app-debug-androidTest.apk
MODEL=${E2E_MODEL:?path to the tiny GGUF}
VISION_MODEL=${E2E_VISION_MODEL:?path to the vision GGUF}
VISION_MMPROJ=${E2E_VISION_MMPROJ:?path to the vision projector GGUF}
OUT=e2e-out
mkdir -p "$OUT"

adb wait-for-device
adb logcat -c || true
# The emulator's own apps (Pixel Launcher) hit ANRs while llama.cpp keeps the CPU busy; their
# "isn't responding" dialogs would cover the screenshots.
adb shell settings put global hide_error_dialogs 1 || true
adb install -r "$APK"
adb install -r "$TEST_APK"

# 1. Launch screenshot (fresh install, no models).
adb shell am start -W -n dev.lomio/.MainActivity
sleep 6
adb exec-out screencap -p > "$OUT/emulator_launch.png"
adb shell am force-stop dev.lomio

# 2. End-to-end chat with the real native engine, then the Storage screen on real files.
adb push "$MODEL" /data/local/tmp/e2e-model.gguf
adb shell chmod 644 /data/local/tmp/e2e-model.gguf
adb push "$VISION_MODEL" /data/local/tmp/e2e-vision.gguf
adb push "$VISION_MMPROJ" /data/local/tmp/e2e-vision-mmproj.gguf
adb shell chmod 644 /data/local/tmp/e2e-vision.gguf /data/local/tmp/e2e-vision-mmproj.gguf
set +e
# Every instrumentation test in the test APK: the UI chat flow, a 20-round streaming stress test
# and engine-level checks.
adb shell am instrument -w -r \
  dev.lomio.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrumentation.txt"
set -e

for shot in e2e_1_loading e2e_2_streaming e2e_3_finished e2e_4_storage e2e_5_storage_cleaned e2e_vision_1_attached e2e_vision_2_sent e2e_vision_3_answer e2e_gpu_1_notice e2e_gpu_2_benchmark; do
  adb pull "/data/local/tmp/e2e/$shot.png" "$OUT/$shot.png" || echo "missing screenshot: $shot"
done

adb logcat -d > "$OUT/logcat-full.txt" || true
grep -E "lomio|llama\.cpp|AndroidRuntime|DEBUG|libc|FATAL|Fatal signal|TestRunner" "$OUT/logcat-full.txt" > "$OUT/logcat-filtered.txt" || true

status=0
# Only crashes of our process count (the emulator's own system apps crash now and then).
if grep -A3 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | grep -q "Process: dev.lomio" \
  || grep -qE "Fatal signal [0-9]+.*\(dev\.lomio" "$OUT/logcat-full.txt"; then
  echo "::error::crash found in logcat"
  grep -nE -A20 "FATAL EXCEPTION|Fatal signal [0-9]+" "$OUT/logcat-full.txt" | head -80
  status=1
fi
if ! grep -qE "^OK \([0-9]+ tests?\)" "$OUT/instrumentation.txt"; then
  echo "::error::end-to-end instrumentation test failed"
  status=1
fi
exit $status
