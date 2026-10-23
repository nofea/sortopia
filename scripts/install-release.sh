#!/usr/bin/env bash
# Sideloads the verified release APK onto the connected device (ANDROID_SERIAL picks one when
# several are attached). Never uninstalls: that would delete the player's save.
set -euo pipefail
"$(dirname "$0")/verify-release.sh"
if [ -z "${ANDROID_SERIAL:-}" ]; then
  # Wireless debugging can list one phone twice (adb connect plus mDNS auto-connect).
  # Keep one connection per hardware serial so a duplicate doesn't make adb refuse.
  declare -A seen=()
  picks=()
  while read -r conn state _; do
    [ "$state" = device ] || continue
    hw=$(adb -s "$conn" shell getprop ro.serialno </dev/null 2>/dev/null | tr -d '\r' || true)
    hw=${hw:-$conn}
    [ -z "${seen[$hw]:-}" ] || continue
    seen[$hw]=1
    picks+=("$conn")
  done < <(adb devices | tail -n +2)
  if [ "${#picks[@]}" -gt 1 ]; then
    echo "More than one device is connected. Choose one with ANDROID_SERIAL=<serial> install-release:" >&2
    printf '  %s\n' "${picks[@]}" >&2
    exit 1
  fi
  if [ "${#picks[@]}" -eq 1 ]; then
    export ANDROID_SERIAL="${picks[0]}"
  fi
fi
apk=android/build/outputs/apk/release/android-release.apk
if out=$(adb install -r "$apk" 2>&1); then
  echo "$out"
  echo "Installed. Launch with: adb shell monkey -p com.sortopia -c android.intent.category.LAUNCHER 1"
else
  echo "$out" >&2
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$out"; then
    echo "The installed app is signed with a different key (e.g. a debug build). Uninstalling" >&2
    echo "it deletes its save data; if that's fine: adb uninstall com.sortopia, then rerun." >&2
  fi
  exit 1
fi
