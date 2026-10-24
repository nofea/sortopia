#!/usr/bin/env bash
# Instrumented UI tests on the running emulator, without the long level sweep (ui-sweep.sh).
set -euo pipefail
# Android's "Viewing full screen" prompt would cover the game during tests.
adb shell settings put secure immersive_mode_confirmations confirmed
exec ./gradlew --console=plain :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.sortopia.android.LevelSweepUiTest "$@"
