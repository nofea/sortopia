#!/usr/bin/env bash
# Creates the emulator AVD if it's missing (name and API level from devenv.nix, or CI).
set -euo pipefail
avd="${SORTOPIA_AVD:-sortopia}"
# avdmanager writes to $ANDROID_USER_HOME/avd (or Java's user.home), but the emulator only searches
# $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and $HOME/.android/avd. Pin one directory for both.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
mkdir -p "$ANDROID_AVD_HOME"
api="${SORTOPIA_API:-36}"
if avdmanager list avd -c | grep -qx "$avd"; then
  echo "AVD $avd already exists"
else
  echo no | avdmanager create avd --force -n "$avd" \
    -k "system-images;android-$api;google_apis;x86_64" -d pixel_6
fi
[ -f "$ANDROID_AVD_HOME/$avd.ini" ] || {
  echo "AVD $avd is missing from $ANDROID_AVD_HOME" >&2
  exit 1
}
