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
    # Signed release APK + AAB. Fails if signing isn't configured (the Gradle build alone would
    # produce an unsigned APK) and prints the signing certificate of the result.
    build-release.exec = ''
      set -e
      ./gradlew --console=plain :android:assembleRelease :android:bundleRelease "$@"
      apk=android/build/outputs/apk/release/android-release.apk
      aab=android/build/outputs/bundle/release/android-release.aab
      if [ ! -f "$apk" ]; then
        echo "No signed APK at $apk: release signing isn't configured (see README, \"Release build\")" >&2
        exit 1
      fi
      "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk" | grep -E "Signer #1 certificate (DN|SHA-256)"
      echo "APK: $apk"
      echo "AAB: $aab"
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
