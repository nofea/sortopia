#!/usr/bin/env bash
set -euo pipefail
exec ./gradlew --console=plain :android:assembleDebug "$@"
