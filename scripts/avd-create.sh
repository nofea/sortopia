#!/usr/bin/env bash
# Creates the emulator AVD if it's missing (name and API level from devenv.nix, or CI).
set -euo pipefail
avd="${SORTOPIA_AVD:-sortopia}"
api="${SORTOPIA_API:-36}"
if avdmanager list avd -c | grep -qx "$avd"; then
  echo "AVD $avd already exists"
else
  echo no | avdmanager create avd --force -n "$avd" \
    -k "system-images;android-$api;google_apis;x86_64" -d pixel_6
fi
