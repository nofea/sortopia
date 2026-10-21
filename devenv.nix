{ pkgs, lib, config, ... }:

let
  avdName = "sortopia";
  apiLevel = "36";
in
{
  # JDK used by Gradle, AGP and LibGDX.
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  # Android SDK + emulator, provided by devenv (nothing installed globally).
  android = {
    enable = true;
    platforms.version = [ apiLevel ];
    buildTools.version = [ "36.0.0" ];
    systemImageTypes = [ "google_apis" ];
    abis = [ "x86_64" ];
    emulator.enable = true;
    ndk.enable = false;
    googleTVAddOns.enable = false;
    extras = [ ];
  };

  packages = [
    pkgs.git
    # Vulnerability scan of the locked dependencies (see the vuln-scan script).
    pkgs.osv-scanner
    # Asset generators in tools/ (dev only, never shipped): numpy for gen_audio.py,
    # rsvg-convert for gen_icon.py.
    (pkgs.python3.withPackages (ps: [ ps.numpy ]))
    pkgs.librsvg
  ];

  env.SORTOPIA_AVD = avdName;

  scripts = {
    lint.exec = ''./gradlew --console=plain ktlintCheck detekt :android:lintDebug "$@"'';
    fmt.exec = ''./gradlew --console=plain ktlintFormat "$@"'';
    unit-test.exec = ''./gradlew --console=plain :core:test "$@"'';
    run-desktop.exec = ''
      # The Nix JDK doesn't search the host's library dirs, so GLFW can't find the GPU driver's
      # libGL ("Couldn't create window"). Point it at whichever host driver dirs exist; this also
      # drops the build-tools lib64 that devenv puts on LD_LIBRARY_PATH.
      gl=""
      for d in /run/opengl-driver/lib /usr/lib64 /usr/lib/x86_64-linux-gnu /usr/lib; do
        [ -e "$d/libGL.so.1" ] || [ -e "$d/libEGL.so.1" ] && gl="''${gl:+$gl:}$d"
      done
      LD_LIBRARY_PATH="$gl" ./gradlew --console=plain :desktop:run "$@"
    '';
    build-debug.exec = ''./gradlew --console=plain :android:assembleDebug "$@"'';
    # Creates the upload keystore (README, "Release build"). keytool prompts for the passwords and
    # the certificate name. Refuses to overwrite: losing or replacing the key breaks app updates.
    keystore-create.exec = ''
      store="''${1:-$HOME/.config/sortopia/upload-keystore.jks}"
      if [ -e "$store" ]; then
        echo "$store already exists; not overwriting it" >&2
        exit 1
      fi
      mkdir -p "$(dirname "$store")"
      keytool -genkeypair -v -keystore "$store" -storetype PKCS12 -alias upload \
        -keyalg RSA -keysize 4096 -validity 10000
      chmod 600 "$store"
      echo "Created $store (alias: upload). Back it up somewhere safe."
    '';
    # Signed release APK + AAB, checked with verify-release. Old outputs are removed first so a
    # stale signed APK can't hide a build that came out unsigned.
    build-release.exec = ''
      set -e
      rm -rf android/build/outputs/apk/release android/build/outputs/bundle/release
      ./gradlew --console=plain :android:assembleRelease :android:bundleRelease "$@"
      verify-release
    '';
    # Checks that the release APK and AAB are signed, by the same key, and not with the debug key.
    # Set SORTOPIA_UPLOAD_CERT_SHA256 to also pin the expected upload certificate.
    verify-release.exec = ''
      set -euo pipefail
      apk=android/build/outputs/apk/release/android-release.apk
      aab=android/build/outputs/bundle/release/android-release.aab
      fail() { echo "verify-release: $*" >&2; exit 1; }
      [ -f "$apk" ] || fail "no signed APK at $apk (unsigned build? see README, \"Release build\")"
      [ -f "$aab" ] || fail "no AAB at $aab"

      certs=$("$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk") \
        || fail "APK signature is invalid"
      dn=$(sed -n 's/^Signer #1 certificate DN: //p' <<<"$certs")
      apk_sha=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")
      [ -n "$apk_sha" ] || fail "APK has no signer"
      [ "$dn" != "C=US, O=Android, CN=Android Debug" ] || fail "APK is signed with the debug key"

      jarsigner -verify "$aab" | grep -qx "jar verified." || fail "AAB is unsigned or its signature is invalid"
      aab_sha=$(keytool -printcert -jarfile "$aab" | sed -n 's/^\s*SHA256: //p' | head -1 | tr -d : | tr 'A-F' 'a-f')
      [ "$aab_sha" = "$apk_sha" ] || fail "AAB ($aab_sha) and APK ($apk_sha) are signed with different keys"

      expected=$(tr -d ': ' <<<"''${SORTOPIA_UPLOAD_CERT_SHA256:-}" | tr 'A-F' 'a-f')
      if [ -n "$expected" ]; then
        [ "$apk_sha" = "$expected" ] || fail "signed with $apk_sha, expected SORTOPIA_UPLOAD_CERT_SHA256=$expected"
        pinned=" (matches SORTOPIA_UPLOAD_CERT_SHA256)"
      else
        pinned=" (not pinned: set SORTOPIA_UPLOAD_CERT_SHA256 to check the key)"
      fi
      echo "Signed by: $dn"
      echo "SHA-256:   $apk_sha$pinned"
      echo "APK: $apk"
      echo "AAB: $aab"
    '';
    # Sideloads the verified release APK onto the connected device (ANDROID_SERIAL picks one when
    # several are attached). Never uninstalls: that would delete the player's save.
    install-release.exec = ''
      set -euo pipefail
      verify-release
      apk=android/build/outputs/apk/release/android-release.apk
      if out=$(adb install -r "$apk" 2>&1); then
        echo "$out"
        echo "Installed. Launch with: adb shell monkey -p com.sortopia -c android.intent.category.LAUNCHER 1"
      else
        echo "$out" >&2
        if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$out"; then
          echo "The installed app is signed with a different key (e.g. a debug build). Uninstalling" >&2
          echo "it deletes its save data; if that's fine: adb uninstall com.sortopia, then rerun." >&2
        fi
        exit 1
      fi
    '';
    avd-create.exec = ''
      if avdmanager list avd -c | grep -qx "${avdName}"; then
        echo "AVD ${avdName} already exists"
      else
        echo no | avdmanager create avd --force -n "${avdName}" \
          -k "system-images;android-${apiLevel};google_apis;x86_64" -d pixel_6
      fi
    '';
    emulator-start.exec = ''
      avd-create
      # The emulator ships its own libc++; the build-tools one on LD_LIBRARY_PATH breaks it.
      # nohup keeps it running after this script's shell exits.
      nohup env -u LD_LIBRARY_PATH emulator -avd "${avdName}" -no-snapshot -no-audio -no-boot-anim \
        -gpu swiftshader_indirect "$@" > "''${TMPDIR:-/tmp}/sortopia-emulator.log" 2>&1 &
      adb wait-for-device
      until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
      adb shell settings put global window_animation_scale 0
      adb shell settings put global transition_animation_scale 0
      adb shell settings put global animator_duration_scale 0
      echo "Emulator ready"
    '';
    ui-test.exec = ''
      # Android's "Viewing full screen" prompt would cover the game during tests.
      adb shell settings put secure immersive_mode_confirmations confirmed
      ./gradlew --console=plain :android:connectedDebugAndroidTest "$@"
    '';
    # Fails on any known vulnerability in what ships in the APK (android/gradle.lockfile);
    # build tooling (Gradle plugins, lint, test runners) is reported for information only.
    vuln-scan.exec = ''
      echo "== Shipped dependencies (release runtime) =="
      osv-scanner scan --lockfile android/gradle.lockfile
      echo "== Build tooling (informational) =="
      osv-scanner scan --lockfile gradle/verification-metadata.xml --format table > "''${TMPDIR:-/tmp}/osv-build-tooling.txt" 2>&1 \
        && echo "No issues in build tooling" \
        || echo "Build-tooling findings written to ''${TMPDIR:-/tmp}/osv-build-tooling.txt (not shipped in the app)"
    '';
    emulator-stop.exec = ''adb emu kill'';
    gen-icon.exec = ''python3 tools/gen_icon.py "$@"'';
  };

  # Linters run before every commit.
  git-hooks.hooks = {
    ktlint-detekt = {
      enable = true;
      name = "ktlint + detekt";
      # Pin the devenv JDK: `git commit` may run outside the dev shell, where a system JDK
      # (e.g. 25.0.4.1, whose version string the Kotlin compiler can't parse) would be used.
      entry = "env JAVA_HOME=${config.languages.java.jdk.package.home} ./gradlew --console=plain --quiet ktlintCheck detekt";
      files = "\\.(kt|kts)$";
      pass_filenames = false;
    };
  };

  enterShell = ''
    echo "Sortopia dev shell: java $(java -version 2>&1 | head -1 | cut -d'"' -f2), ANDROID_HOME=$ANDROID_HOME"
  '';
}
