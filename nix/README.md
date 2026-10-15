# Pinned Nix build (unsigned release)

`nix-build nix/default.nix` builds `:app:assembleRelease` inside the Nix sandbox, offline,
and outputs unsigned APKs (`app-*-release.apk`, `SHA256SUMS`, `mapping.txt`, `build.log`).
Signing is done outside the store with the existing key (`apksigner`), never as a build input.

Pins: nixpkgs `e7439b6b` (agent-server flake.lock), JDK 21 (`jdk21` 21.0.12), Gradle 9.8.1
(unpacked wrapper dist), SDK from nixpkgs androidenv (platform 36, build-tools 36.0.0,
NDK 29.0.14206865, nixpkgs aapt2) plus platform `android-37.2` (compileSdk 37.2 is not in
nixpkgs' repo.json) fetched as a fixed-output derivation from dl.google.com
(sha256 in `inputs.nix`; sha1 matches Google's repository2-3.xml). The `package.xml` that
sdkmanager normally writes is shipped in `platform-37.2-package.xml`.
Gradle heap is 8g (`-Xmx8g`); `unsigned-init.gradle` clears the release signingConfig (the
build otherwise falls back to the auto-generated debug keystore). `git` is stubbed with the
pinned HEAD metadata in `inputs.nix` (commit count/sha/time) because `.git` is not in the source.

## Reproducible input acquisition

No standard gradle2nix: the dependency set is a content-addressed snapshot instead.
`nix/make-inputs.sh <new-empty-dir>` writes three deterministic tars (sorted, mtime 0, uid 0):
- `mihon-suwayomi-src.tar`: `git ls-files -co --exclude-standard` (so no build dirs, `.gradle`,
  `local.properties`, `keystore.properties`, `.git`; `nix/` excluded)
- `gradle-modules-2.tar`: `~/.gradle/caches/modules-2` only (minus lock/gc files), populated by a
  normal online build of this source; no config cache, daemon logs, prefs or secrets
- `gradle-9.8.1-dist.tar`: the unpacked wrapper dist
Add each with `nix-store --add-fixed sha256 <file>`; `requireFile` fails the build if the
sha256 in `inputs.nix` does not match. Regenerating `modules-2` needs a populated Gradle cache,
so its hash may differ; update `inputs.nix` when the dependency set changes.
Cache status: pushing to the agent cache was attempted twice and the unsigned output and the
`modules-2` snapshot failed with HTTP 499. Only the Gradle dist, the source archive and some
SDK paths are cached, so the full input set is not cached. Pushing needs cache credentials
(not in git).
