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
    # Asset generators in tools/ (dev only, never shipped): numpy and oggenc for gen_audio.py,
    # rsvg-convert for gen_icon.py and gen_art.py.
    (pkgs.python3.withPackages (ps: [ ps.numpy ]))
    pkgs.librsvg
    pkgs.vorbis-tools
  ];

  env.SORTOPIA_AVD = avdName;
  env.SORTOPIA_API = apiLevel;

  # Thin wrappers: the commands live in scripts/, which CI runs directly (without devenv).
  scripts = lib.genAttrs [
    "lint"
    "fmt"
    "unit-test"
    "run-desktop"
    "build-debug"
    "keystore-create"
    "build-release"
    "verify-release"
    "install-release"
    "avd-create"
    "emulator-start"
    "emulator-stop"
    "ui-test"
    "vuln-scan"
    "gen-icon"
    "gen-art"
    "gen-audio"
  ] (name: { exec = ''${config.devenv.root}/scripts/${name}.sh "$@"''; });

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
