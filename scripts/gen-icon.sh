#!/usr/bin/env bash
# Launcher icon layers, store icon and mask preview (tools/gen_icon.py).
set -euo pipefail
exec python3 tools/gen_icon.py "$@"
