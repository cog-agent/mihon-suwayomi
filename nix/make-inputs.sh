#!/usr/bin/env bash
# Create the hash-pinned build inputs in $1 (new empty dir) and print their sha256.
# Source: tracked + untracked-not-ignored files of the work tree (no build dirs, .gradle,
# keystore.properties, local.properties, .git). Dependencies: ~/.gradle/caches/modules-2 only.
# SRC_ONLY=1 writes just the source tar (dependency snapshot and Gradle dist keep their pinned hashes).
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd); out=$1
[ -d "$out" ] && [ -z "$(ls -A "$out")" ] || { echo "need new empty dir" >&2; exit 1; }
tarflags=(--sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --format=gnu)
stage=$(mktemp -d); mkdir "$stage/src"
(cd "$repo" && git ls-files -z -co --exclude-standard | grep -zv '^nix/' | rsync -a --from0 --files-from=- ./ "$stage/src/")
tar "${tarflags[@]}" -C "$stage" -cf "$out/mihon-suwayomi-src.tar" src
if [ -n "${SRC_ONLY:-}" ]; then
  # Dependency snapshot and Gradle dist are unchanged when only the source changed; keep the pinned ones.
  rm -rf "$stage"; (cd "$out" && sha256sum *.tar); exit 0
fi
tar "${tarflags[@]}" -C "$HOME/.gradle/caches" --exclude=modules-2.lock --exclude=gc.properties \
  -cf "$out/gradle-modules-2.tar" modules-2
d=$(echo "$HOME"/.gradle/wrapper/dists/gradle-9.8.1-bin/*/gradle-9.8.1)
tar "${tarflags[@]}" -C "$(dirname "$d")" -cf "$out/gradle-9.8.1-dist.tar" gradle-9.8.1
rm -rf "$stage"; (cd "$out" && sha256sum *.tar)
