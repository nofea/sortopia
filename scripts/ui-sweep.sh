#!/usr/bin/env bash
# The level sweep (LevelSweepUiTest) on the running emulator: plays a spread of levels from every
# world, the Daily and Endless through the UI (about 15 minutes). ui-test.sh runs everything else.
set -euo pipefail
# Android's "Viewing full screen" prompt would cover the game during tests.
adb shell settings put secure immersive_mode_confirmations confirmed
exec ./gradlew --console=plain :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sortopia.android.LevelSweepUiTest "$@"
