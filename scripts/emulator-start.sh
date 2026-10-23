#!/usr/bin/env bash
# Boots the AVD (pass -no-window for headless), waits for boot, disables animations.
set -euo pipefail
"$(dirname "$0")/avd-create.sh"
# The emulator ships its own libc++; the build-tools one on LD_LIBRARY_PATH breaks it.
# nohup keeps it running after this script's shell exits.
nohup env -u LD_LIBRARY_PATH emulator -avd "${SORTOPIA_AVD:-sortopia}" -no-snapshot -no-audio -no-boot-anim \
  -gpu swiftshader_indirect "$@" > "${TMPDIR:-/tmp}/sortopia-emulator.log" 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
echo "Emulator ready"
