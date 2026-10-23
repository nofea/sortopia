#!/usr/bin/env bash
# Fails on any known vulnerability in what ships in the APK (android/gradle.lockfile);
# build tooling (Gradle plugins, lint, test runners) is reported for information only.
set -euo pipefail
echo "== Shipped dependencies (release runtime) =="
osv-scanner scan --lockfile android/gradle.lockfile
echo "== Build tooling (informational) =="
report="${TMPDIR:-/tmp}/osv-build-tooling.txt"
if osv-scanner scan --lockfile gradle/verification-metadata.xml --format table > "$report" 2>&1; then
  echo "No issues in build tooling"
else
  echo "Build-tooling findings written to $report (not shipped in the app)"
fi
