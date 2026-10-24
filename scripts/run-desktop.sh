#!/usr/bin/env bash
# Runs the desktop launcher (dev only; save in ~/.sortopia).
set -euo pipefail
# The Nix JDK doesn't search the host's library dirs, so GLFW can't find the GPU driver's
# libGL ("Couldn't create window"). Point it at whichever host driver dirs exist; this also
# drops the build-tools lib64 that devenv puts on LD_LIBRARY_PATH.
gl=""
for d in /run/opengl-driver/lib /usr/lib64 /usr/lib/x86_64-linux-gnu /usr/lib; do
  if [ -e "$d/libGL.so.1" ] || [ -e "$d/libEGL.so.1" ]; then
    gl="${gl:+$gl:}$d"
  fi
done
LD_LIBRARY_PATH="$gl" exec ./gradlew --console=plain :desktop:run "$@"
