#!/usr/bin/env bash
# ktlint auto-format.
set -euo pipefail
exec ./gradlew --console=plain ktlintFormat "$@"
