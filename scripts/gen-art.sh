#!/usr/bin/env bash
# In-game art: themed items, tube dressing, overlays, particles and backgrounds (tools/gen_art.py).
set -euo pipefail
exec python3 tools/gen_art.py "$@"
