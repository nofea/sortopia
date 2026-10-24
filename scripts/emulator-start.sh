#!/usr/bin/env bash
# Boots the AVD (pass -no-window for headless), waits for boot, disables animations.
# Fails (printing the emulator log) if the emulator exits or doesn't boot within
# SORTOPIA_BOOT_TIMEOUT seconds (default 600).
set -euo pipefail
# Same AVD directory as avd-create.sh, so the emulator finds the AVD it made.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
"$(dirname "$0")/avd-create.sh"
log="${TMPDIR:-/tmp}/sortopia-emulator.log"
timeout="${SORTOPIA_BOOT_TIMEOUT:-600}"
# The emulator ships its own libc++; the build-tools one on LD_LIBRARY_PATH breaks it.
# nohup keeps it running after this script's shell exits.
nohup env -u LD_LIBRARY_PATH emulator -avd "${SORTOPIA_AVD:-sortopia}" -no-snapshot -no-audio -no-boot-anim \
  -gpu swiftshader_indirect "$@" > "$log" 2>&1 &
pid=$!

fail() {
  echo "$1" >&2
  echo "--- emulator log ($log) ---" >&2
  tail -n 200 "$log" >&2 || true
  kill "$pid" 2> /dev/null || true
  exit 1
}

start=$SECONDS
until [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; do
  kill -0 "$pid" 2> /dev/null || fail "Emulator exited before booting"
  [ $((SECONDS - start)) -lt "$timeout" ] || fail "Emulator didn't boot within ${timeout}s"
  sleep 2
done
echo "Booted in $((SECONDS - start))s"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
echo "Emulator ready"
