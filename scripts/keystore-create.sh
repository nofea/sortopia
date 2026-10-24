#!/usr/bin/env bash
# Creates the upload keystore (README, "Release build"). keytool prompts for the passwords and
# the certificate name. Refuses to overwrite: losing or replacing the key breaks app updates.
set -euo pipefail
store="${1:-$HOME/.config/sortopia/upload-keystore.jks}"
if [ -e "$store" ]; then
  echo "$store already exists; not overwriting it" >&2
  exit 1
fi
mkdir -p "$(dirname "$store")"
keytool -genkeypair -v -keystore "$store" -storetype PKCS12 -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$store"
echo "Created $store (alias: upload). Back it up somewhere safe."
