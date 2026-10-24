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
ui-sweep         # plays sample levels of every world through the UI (~15 min)
emulator-stop    # shut the emulator down
clean            # delete all build outputs
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
# Absolute, relative to the repo root, or starting with "~/".
storeFile=~/.config/sortopia/upload-keystore.jks
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

This builds both artifacts, then runs `verify-release` on them:

- `android/build/outputs/bundle/release/android-release.aab`: upload this to Google Play
- `android/build/outputs/apk/release/android-release.apk`: for sideloading

Without signing configured, Gradle still builds the release variant, but unsigned (`android-release-unsigned.apk`). `build-release` treats that as an error.

### 4. Verify the signature

`build-release` already does this, but you can rerun it on its own:

```sh
verify-release
```

It fails unless:

- the APK has a valid signature (`apksigner`) and the AAB has one too (`jarsigner`)
- both are signed by the same certificate
- that certificate isn't the Android debug key

It prints the signer and the certificate's SHA-256. To also check that the build was signed by **your** upload key, pin that fingerprint (it's public, not a secret):

```sh
keytool -list -v -keystore ~/.config/sortopia/upload-keystore.jks | grep SHA256   # once
export SORTOPIA_UPLOAD_CERT_SHA256=AB:CD:...   # colons and case don't matter
verify-release
```

### 5. Sideload onto a device

Enable USB debugging on the phone (or start the emulator with `emulator-start`), connect it, then:

```sh
install-release
```

This verifies the APK, then installs it with `adb install -r`, which updates an existing install and keeps its save. If the same phone shows up twice in `adb devices` (common with wireless debugging), the script uses one of its connections. With several different devices attached, choose one with `ANDROID_SERIAL=<serial from adb devices> install-release`.

If a **debug** build is already installed, the install fails: Android won't update an app with one signed by a different key. The script never uninstalls for you, because uninstalling deletes the save. If losing it is fine, run `adb uninstall com.sortopia` and then `install-release` again.

### Releasing from CI

`.github/workflows/release.yml` makes the same signed build on GitHub Actions and publishes it as a **GitHub Release**. It has two jobs:

- **build** runs lint, unit tests and the vulnerability scan, then `build-release`. It's the only job with the signing secrets, and it has read-only access to the repo.
- **publish** creates the GitHub Release. It runs only for tags, can write to the repo, and never sees the secrets.

The release has these files attached:

- `sortopia-<tag>.apk`: for sideloading
- `sortopia-<tag>.aab`: upload this to Google Play
- `sortopia-<tag>-mapping.txt`: the R8 mapping for this exact build. You need it to read obfuscated crash stack traces (`retrace`, or upload it to Play Console). Every build has its own mapping, so keeping it on the release means it never gets lost.

One-time setup: add these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `SORTOPIA_KEYSTORE_BASE64` | the keystore file, base64-encoded: `base64 -w0 ~/.config/sortopia/upload-keystore.jks` |
| `SORTOPIA_KEYSTORE_PASSWORD` | keystore password |
| `SORTOPIA_KEY_ALIAS` | `upload` |
| `SORTOPIA_KEY_PASSWORD` | key password |

Optionally, add the upload certificate's SHA-256 as a repository **variable** (Variables tab, not Secrets) named `SORTOPIA_UPLOAD_CERT_SHA256`. The build then fails unless it was signed by that key.

To release, bump `versionCode` and `versionName`, merge to `main`, then tag a commit on `main` and push the tag:

```sh
git switch main && git pull
git tag v0.1.0 && git push origin v0.1.0
```

Before the slow steps, the build fails if:

- the tagged commit isn't on `main` (for example, you tagged a feature branch)
- the tag doesn't match `versionName`

If it all passes, the release is published right away, under **Releases** on GitHub:

- its notes are generated from the PRs and commits since the previous tag
- `v0.*` tags are marked as **pre-releases**, and `v1.0.0` onwards are normal releases

The publish job never overwrites a release. If a release for the tag already exists, for example because an earlier run created it and then failed while uploading files, the job fails. Delete that release on GitHub (keep the tag), then re-run the job.

You can also start the workflow by hand from the Actions tab (**Release → Run workflow**). A manual run builds and verifies the signed APK and AAB, but skips the tag checks and **never creates a release**. Download its files from the run's **Artifacts** section. They are named after the branch and kept for 7 days.
