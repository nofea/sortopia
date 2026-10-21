# CLAUDE.md

Sortopia is an offline, portrait Android tube-sorting puzzle game.

**`SPEC.md` is the source of truth for game design**: rules, mechanics, progression, scoring, economy, screens, and the security/privacy requirements. Read it before implementing a feature. When a design decision changes, update `SPEC.md` in the same change. Don't put game-design content in this file.

## Tech stack
- **Kotlin + LibGDX**, Gradle (Kotlin DSL) multi-module:
  - `core`: all game code, platform-independent
  - `android`: Android launcher, Keystore and other platform bindings, instrumented UI tests
  - `desktop` (LWJGL3): a launcher for fast local iteration only, never shipped
- Dependency versions are pinned in a Gradle version catalog (`gradle/libs.versions.toml`).

## Architecture
- Keep the **pure game logic** separate from rendering and platform code:
  - board model, move rules, special mechanics, win detection
  - solver, level generator
  - scoring, stars, economy, unlock rules
  - save model and validation

  This code must have no LibGDX graphics or Android imports, so it can be unit-tested headless.
- Platform services (Keystore HMAC key, file storage, haptics, clock, system font scale) go behind interfaces in `core`. They are implemented in `android` and `desktop`, and faked in tests.
- The level generator must be **deterministic across devices and app versions**:
  - use a seeded RNG implemented in our own code, never `java.util.Random` or platform RNGs whose behavior may differ
  - never let iteration order of hash-based collections affect output
- Rendering and screens read state from the logic layer and send player intents to it. They never mutate game state directly.

## Dev environment: devenv
All local work happens inside **devenv** ([devenv.sh](https://devenv.sh)). Nothing (JDK, Gradle, Android SDK, emulator) is installed globally.
- `devenv.nix` provides:
  - JDK 17
  - Android SDK platform 36, build tools 36.0.0 and platform tools
  - the emulator, with an API 36 `google_apis` x86_64 system image
  - `osv-scanner`
- Enter with `devenv shell`, or automatically through direnv (`.envrc` contains `use devenv`). One-off commands: `devenv shell -- <cmd>`.
- Run Gradle through the wrapper (`./gradlew`) inside the devenv shell.

### Command reference (devenv scripts)
| Script | What it does |
|---|---|
| `lint` | ktlint + detekt (all modules) + Android Lint (debug) |
| `fmt` | ktlint auto-format |
| `unit-test` | `:core:test`, the headless JUnit 5 suite |
| `build-debug` | `:android:assembleDebug` |
| `run-desktop` | Runs the desktop launcher (dev only; save in `~/.sortopia`) |
| `avd-create` | Creates the `sortopia` AVD if it's missing |
| `emulator-start` | Boots the AVD (pass `-no-window` for headless), waits for boot, disables animations |
| `emulator-stop` | Shuts the emulator down |
| `ui-test` | `:android:connectedDebugAndroidTest` on the running emulator |
| `vuln-scan` | OSV scan: fails on vulnerable **shipped** dependencies (`android/gradle.lockfile`); build tooling is informational only |

The scripts are not called `test`, because that would shadow the shell builtin.

Other useful commands:
- Generation survey: `./gradlew :core:test -Dsurvey=true --tests '*GenerationSurvey*'` prints par, attempts and timing for every level. `SpareSurveyTest` (same flag) is a solver diagnostic for sample big boards. Both are skipped without `-Dsurvey=true`.
- After changing dependencies:
  - Refresh checksums: `./gradlew --write-verification-metadata sha256 ktlintCheck detekt :core:test :desktop:installDist :android:assembleDebug :android:assembleRelease :android:lintDebug :android:connectedDebugAndroidTest`. This needs the emulator running so the test-runner artifacts get recorded.
  - Refresh the APK lockfile: `./gradlew :android:dependencies --configuration releaseRuntimeClasspath --write-locks`.

### Environment gotchas
- devenv's Android module puts the build-tools `lib64` on `LD_LIBRARY_PATH`, which breaks the emulator. `emulator-start` unsets it, so do the same if you run `emulator` by hand.
- LibGDX 1.14 pulls in `androidx.core` 1.17, which requires `compileSdk` 36.
- Android shows a "Viewing full screen" prompt for immersive apps. `ui-test` disables it with `adb shell settings put secure immersive_mode_confirmations confirmed`.

## Linting (from day one)
- **ktlint** for formatting. **detekt** for static analysis, with security-relevant rules enabled. **Android Lint** with security checks treated as errors.
- Baselines are not used to hide new issues. Fix them, or suppress them locally with a justification comment.
- **devenv `git-hooks`** run ktlint and detekt on pre-commit. Don't bypass the hooks (`--no-verify`).

## Testing
- **Unit tests** (JUnit 5, in `core`) for all pure logic:
  - move rules and every special mechanic in SPEC §6
  - win detection
  - solver correctness and par
  - generator determinism (golden seeds → expected layouts) and solvability
  - scoring and stars, economy and helper costs, unlock rules
  - save validation, HMAC verification, backup fallback, atomic write
- Write logic **test-first**, red → green → refactor.
- **Emulator UI tests** (`android` instrumented tests, UI Automator):
  - LibGDX renders to one GL surface, so tests go through a **debug-only test bridge**. The bridge exposes the on-screen bounds of UI elements and tubes, plus a snapshot of the game state.
  - Tests send real taps through UI Automator and assert on that state, using screenshots where useful.
  - The bridge lives only in the debug source set and must never compile into release.
  - Cover at least: the tutorial, solving a level to completion, helpers spending points, world unlocking, settings toggles, and save/restore across an app restart.
- A change is done when `lint` and `unit-test` pass. `ui-test` must also pass for changes to screens, input, or persistence.

## CI (GitHub Actions)
- Workflow: `.github/workflows/ci.yml`. All actions are pinned to commit SHAs.
- **Every PR:**
  - ktlint, detekt, Android Lint
  - unit tests
  - debug build
  - dependency vulnerability scan (`vuln-scan`, OSV)
  - Gradle wrapper validation
- **PRs to `main`:** also run the emulator UI tests.
- CI uses the same devenv environment as local development, for parity.

## Security practices
- **No secrets in the repo.** Signing keystores, `local.properties` and credentials are git-ignored. Release signing uses CI secrets.
- **Dependencies:**
  - keep them minimal and pinned in the version catalog
  - Gradle **dependency verification** is on (`gradle/verification-metadata.xml`, SHA-256)
  - the APK's runtime dependencies are locked (`android/gradle.lockfile`)
  - the Gradle wrapper checksum is pinned (`distributionSha256Sum`)
- **Manifest:**
  - no permissions beyond what SPEC §16 allows (no `INTERNET` in v1)
  - only the launcher activity is exported; library components we don't need (profileinstaller, androidx.startup) are removed with `tools:node="remove"`
  - explicit backup and data-extraction rules
  - after adding a library, check the merged manifest: `android/build/intermediates/merged_manifests/*/process*Manifest/AndroidManifest.xml`
- **Untrusted input:** treat the save file (and anything read from disk) as untrusted. Validate before use, and fail safe without crashing.
- **Release builds:** R8 shrinking and obfuscation on, no debug code, no logging of game or save internals.
- Think through the security impact of every new feature, and record any new requirement in SPEC §16.

## Project layout
- `core/src/main/kotlin/com/sortopia/`
  - `model`, `rules`, `solver`, `gen`, `game`, `save`: pure logic, unit-tested
  - `platform`: interfaces (`Platform`, `UiProbe`)
  - `ui`: LibGDX screens, the board view and rendering
- `core/src/test/kotlin/com/sortopia/`: unit tests, mirroring the logic packages (`TestBoards.kt` holds shared board builders)
- `android/src/main`: launcher, `AndroidPlatform`, and `KeystoreMac` (Keystore HMAC key, in `AndroidPlatform.kt`)
- `android/src/debug`: `DebugTestBridge` and its installer. `android/src/release` holds the no-op installer.
- `android/src/androidTest`: UI Automator tests (`GameDriver` reads the bridge and taps real coordinates)
- `assets/`: font (Nunito, OFL), `i18n/strings.properties` (all player-facing text), `sfx/`, `music/`
- `tools/gen_audio.py`: regenerates the placeholder audio deterministically
- `config/detekt/detekt.yml`: detekt rules (on top of the defaults)

## Current state / known gaps
- Art is placeholder: items are procedural shapes with faces (distinct shape + color per type), not per-world illustrated icons (SPEC §13).
- Audio is synthesized placeholder SFX and music loops (`tools/gen_audio.py`).
- Generation time: campaign levels take ≤ ~1.5 s on desktop. Some Endless levels take several seconds, so the game prefetches the next level in the background.
## Git
- Main branch: `main`. Work on feature branches and open PRs to `main`.
