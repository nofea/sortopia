#!/usr/bin/env bash
# ktlint + detekt (all modules) + Android Lint (debug).
set -euo pipefail
exec ./gradlew --console=plain ktlintCheck detekt :android:lintDebug "$@"
