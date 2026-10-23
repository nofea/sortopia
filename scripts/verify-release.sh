#!/usr/bin/env bash
# Checks that the release APK and AAB are signed, by the same key, and not with the debug key.
# Set SORTOPIA_UPLOAD_CERT_SHA256 to also pin the expected upload certificate.
set -euo pipefail
apk=android/build/outputs/apk/release/android-release.apk
aab=android/build/outputs/bundle/release/android-release.aab
fail() { echo "verify-release: $*" >&2; exit 1; }
[ -f "$apk" ] || fail "no signed APK at $apk (unsigned build? see README, \"Release build\")"
[ -f "$aab" ] || fail "no AAB at $aab"

certs=$("$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk") \
  || fail "APK signature is invalid"
dn=$(sed -n 's/^Signer #1 certificate DN: //p' <<<"$certs")
apk_sha=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")
[ -n "$apk_sha" ] || fail "APK has no signer"
[ "$dn" != "C=US, O=Android, CN=Android Debug" ] || fail "APK is signed with the debug key"

jarsigner -verify "$aab" | grep -qx "jar verified." || fail "AAB is unsigned or its signature is invalid"
aab_sha=$(keytool -printcert -jarfile "$aab" | sed -n 's/^\s*SHA256: //p' | head -1 | tr -d : | tr 'A-F' 'a-f')
[ "$aab_sha" = "$apk_sha" ] || fail "AAB ($aab_sha) and APK ($apk_sha) are signed with different keys"

expected=$(tr -d ': ' <<<"${SORTOPIA_UPLOAD_CERT_SHA256:-}" | tr 'A-F' 'a-f')
if [ -n "$expected" ]; then
  [ "$apk_sha" = "$expected" ] || fail "signed with $apk_sha, expected SORTOPIA_UPLOAD_CERT_SHA256=$expected"
  pinned=" (matches SORTOPIA_UPLOAD_CERT_SHA256)"
else
  pinned=" (not pinned: set SORTOPIA_UPLOAD_CERT_SHA256 to check the key)"
fi
echo "Signed by: $dn"
echo "SHA-256:   $apk_sha$pinned"
echo "APK: $apk"
echo "AAB: $aab"
