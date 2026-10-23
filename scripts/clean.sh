#!/usr/bin/env bash
# Deletes the build outputs of all modules.
set -euo pipefail
exec ./gradlew --console=plain clean "$@"
