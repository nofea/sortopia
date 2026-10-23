#!/usr/bin/env bash
# The headless JUnit 5 suite.
set -euo pipefail
exec ./gradlew --console=plain :core:test "$@"
