#!/usr/bin/env bash
# Signed release APK + AAB, checked with verify-release. Old outputs are removed first so a
# stale signed APK can't hide a build that came out unsigned.
set -euo pipefail
rm -rf android/build/outputs/apk/release android/build/outputs/bundle/release
./gradlew --console=plain :android:assembleRelease :android:bundleRelease "$@"
"$(dirname "$0")/verify-release.sh"
