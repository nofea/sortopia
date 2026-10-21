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

## Release build

Release builds are signed with an **upload key**. Google Play re-signs the app with its own app signing key (Play App Signing), so a lost upload key can be reset through Play Console, but keep it backed up anyway. The key never goes in the repo.

### 1. Create the upload keystore (once)

```sh
keystore-create   # default: ~/.config/sortopia/upload-keystore.jks, alias "upload"
# or: keystore-create /some/other/path.jks
```

`keytool` asks for a keystore password and the certificate details. Back up the `.jks` file and its password somewhere safe (a password manager, for example). The script won't overwrite an existing keystore.

### 2. Tell the build where the key is

Either create `keystore.properties` in the repo root (git-ignored):

```properties
# Absolute path, or relative to the repo root. "~" is not expanded.
storeFile=/home/you/.config/sortopia/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

or set environment variables, which take precedence over the file (use these in CI, from secrets):

| Variable | Meaning |
|---|---|
| `SORTOPIA_KEYSTORE_FILE` | path to the `.jks` file |
| `SORTOPIA_KEYSTORE_PASSWORD` | keystore password |
| `SORTOPIA_KEY_ALIAS` | key alias (`upload`) |
| `SORTOPIA_KEY_PASSWORD` | key password (the same as the keystore password for PKCS12 keystores) |

### 3. Build

Bump `versionCode` (and `versionName`) in `android/build.gradle.kts` first. Play rejects a `versionCode` it has already seen.

```sh
build-release
```

This builds both artifacts and prints the signing certificate:

- `android/build/outputs/bundle/release/android-release.aab`: upload this to Google Play
- `android/build/outputs/apk/release/android-release.apk`: for sideloading (`adb install`)

Without signing configured, Gradle still builds the release variant, but unsigned (`android-release-unsigned.apk`). `build-release` treats that as an error.

### Releasing from CI

`.github/workflows/release.yml` makes the same signed build on GitHub Actions. It runs lint, unit tests and the vulnerability scan first, then uploads the AAB, the APK and the R8 `mapping.txt` (needed to read obfuscated crash stack traces) as a workflow artifact.

One-time setup: add these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `SORTOPIA_KEYSTORE_BASE64` | the keystore file, base64-encoded: `base64 -w0 ~/.config/sortopia/upload-keystore.jks` |
| `SORTOPIA_KEYSTORE_PASSWORD` | keystore password |
| `SORTOPIA_KEY_ALIAS` | `upload` |
| `SORTOPIA_KEY_PASSWORD` | key password |

To release, bump `versionCode` and `versionName`, merge to `main`, then push a matching tag:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

The job fails if the tag doesn't match `versionName`. You can also start it by hand from the Actions tab (**Release → Run workflow**), which skips the tag check. Download the result from the run's **Artifacts** section.
