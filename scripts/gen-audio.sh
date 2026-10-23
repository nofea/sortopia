#!/usr/bin/env bash
# Sound effects and the lo-fi music loops (tools/gen_audio.py); pass track names to make only those.
set -euo pipefail
exec python3 tools/gen_audio.py assets "$@"
