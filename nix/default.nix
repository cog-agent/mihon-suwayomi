# Offline, sandboxed, unsigned release build of Mihon/Suwayomi (assembleRelease).
#
# Inputs (all hash-pinned, see README.md and inputs.nix):
#   - nixpkgs e7439b6b (same rev as agent-server flake.lock): JDK 21, androidenv SDK
#   - clean source tarball and Gradle dependency snapshot, added to the store with
#     `nix-store --add-fixed sha256` (requireFile: build fails if hash does not match)
#   - Gradle 9.8.1 distribution snapshot (the unpacked wrapper dist)
#   - platform android-37.2 (not in nixpkgs repo.json): fixed-output fetch from Google
# Signing is NOT done here; the output is app-release-unsigned.apk.
{ inputs ? import ./inputs.nix }:
let
  pkgs = import (builtins.fetchTree inputs.nixpkgs) {
    system = "x86_64-linux";
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };
  inherit (pkgs) lib;

  req = name: sha256: pkgs.requireFile {
    inherit name sha256;
    message = ''
      Input ${name} (sha256 ${sha256}) is not in the store. Create it with
      nix/make-inputs.sh and add it: nix-store --add-fixed sha256 ${name}
    '';
  };

  src = req "mihon-suwayomi-src.tar" inputs.src.sha256;
  depsSnapshot = req "gradle-modules-2.tar" inputs.modules2.sha256;
  gradleDist = req "gradle-9.8.1-dist.tar" inputs.gradleDist.sha256;

  platform372 = pkgs.fetchurl {
    url = "https://dl.google.com/android/repository/platform-37.2_r01.zip";
    sha256 = inputs.platform372.sha256; # Google manifest sha1 4bec12a0... also verified
  };

  sdk = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" ];
    buildToolsVersions = [ "36.0.0" ];
    includeNDK = true;
    ndkVersions = [ "29.0.14206865" ];
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
  };
  sdkRoot = "${sdk.androidsdk}/libexec/android-sdk";

  jdk = pkgs.jdk21;

  # Build metadata normally read from .git (absent in the sandbox); pinned, not faked per build.
  fakeGit = pkgs.writeShellScriptBin "git" ''
    case "$*" in
      "rev-list --count HEAD") echo ${inputs.git.count} ;;
      "rev-parse --short HEAD") echo ${inputs.git.sha} ;;
      "log -1 --format=%ct") echo ${inputs.git.time} ;;
      *) echo "unsupported git invocation: $*" >&2; exit 1 ;;
    esac
  '';
in
pkgs.stdenv.mkDerivation {
  pname = "mihon-suwayomi-unsigned";
  version = "0.21.2-code38";
  dontUnpack = true;
  dontFixup = true;
  nativeBuildInputs = [ jdk pkgs.unzip pkgs.gnutar pkgs.gzip fakeGit ];

  buildPhase = ''
    runHook preBuild
    export HOME=$TMPDIR/home; mkdir -p $HOME
    export JAVA_HOME=${jdk}
    export ANDROID_HOME=$TMPDIR/sdk
    export ANDROID_SDK_ROOT=$ANDROID_HOME
    export GRADLE_USER_HOME=$TMPDIR/guh
    export SOURCE_DATE_EPOCH=${inputs.git.time}
    export TZ=UTC LC_ALL=C.UTF-8

    # writable SDK farm: nixpkgs androidenv components + android-37.2 platform
    mkdir -p $ANDROID_HOME/platforms
    for e in ${sdkRoot}/*; do
      [ "$(basename $e)" = platforms ] || ln -s $e $ANDROID_HOME/$(basename $e)
    done
    for p in ${sdkRoot}/platforms/*; do ln -s $p $ANDROID_HOME/platforms/$(basename $p); done
    unzip -q ${platform372} -d $ANDROID_HOME/platforms
    # sdkmanager writes package.xml on install; AGP requires it to recognise the platform
    cp ${./platform-37.2-package.xml} $ANDROID_HOME/platforms/android-37.2/package.xml
    ls $ANDROID_HOME/platforms $ANDROID_HOME/build-tools $ANDROID_HOME/ndk

    mkdir -p $GRADLE_USER_HOME/caches $TMPDIR/dist $TMPDIR/work
    tar -xf ${depsSnapshot} -C $GRADLE_USER_HOME/caches
    tar -xf ${gradleDist} -C $TMPDIR/dist
    tar -xf ${src} -C $TMPDIR/work
    patchShebangs $TMPDIR/dist
    cat > $GRADLE_USER_HOME/gradle.properties <<PROPS
    org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8
    org.gradle.java.home=${jdk}
    org.gradle.daemon=false
    android.aapt2FromMavenOverride=${sdkRoot}/build-tools/36.0.0/aapt2
    kotlin.compiler.execution.strategy=in-process
    PROPS
    sed -i 's/^ *//' $GRADLE_USER_HOME/gradle.properties

    cd $TMPDIR/work/src
    $TMPDIR/dist/gradle-9.8.1/bin/gradle --version | tee $TMPDIR/gradle-version.txt
    $TMPDIR/dist/gradle-9.8.1/bin/gradle --offline --no-daemon --console=plain --stacktrace \
      -I ${./unsigned-init.gradle} :app:assembleRelease 2>&1 | tee $TMPDIR/build.log
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p $out
    ls -l $TMPDIR/work/src/app/build/outputs/apk/release/
    cp $TMPDIR/work/src/app/build/outputs/apk/release/*.apk $out/
    cp $TMPDIR/work/src/app/build/outputs/mapping/release/mapping.txt $out/ || true
    cp $TMPDIR/gradle-version.txt $TMPDIR/build.log $out/
    (cd $out && sha256sum *.apk > SHA256SUMS)
    runHook postInstall
  '';
}
