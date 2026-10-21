# Sortopia

A cozy tube-sorting puzzle game for Android, built with Kotlin and LibGDX.

- **Game design:** [SPEC.md](SPEC.md)
- **Development setup, commands and conventions:** [CLAUDE.md](CLAUDE.md)

Quick start (requires [devenv](https://devenv.sh)):

```sh
devenv shell
unit-test        # core logic tests
lint             # ktlint, detekt, Android Lint
build-debug      # debug APK
run-desktop      # play on the desktop launcher (dev only)
emulator-start   # boot the emulator, then:
ui-test          # end-to-end UI tests
```
