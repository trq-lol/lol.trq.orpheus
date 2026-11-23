#!/bin/sh
# Builds the macOS helper (mediaremote-adapter, BSD-3-Clause) from a pinned upstream commit into
# native/dist/macos-universal, where the Gradle build picks it up. Needs git, cmake and the Xcode command line tools.
set -eu

repository=https://github.com/ungive/mediaremote-adapter.git
tag=v0.8.0
commit=a7cc273

here=$(cd "$(dirname "$0")" && pwd)
work=$here/build
out=$here/../dist/macos-universal

rm -rf "$work" "$out"
mkdir -p "$work" "$out/MediaRemoteAdapter.framework"
git clone --quiet --depth 1 --branch "$tag" "$repository" "$work/src"
found=$(git -C "$work/src" rev-parse HEAD)
case "$found" in
    "$commit"*) ;;
    *)
        echo "upstream tag $tag now points at $found, expected $commit; review it before changing the pin" >&2
        exit 1
        ;;
esac

cmake -S "$work/src" -B "$work/out" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 >/dev/null
cmake --build "$work/out" >/dev/null

# A jar cannot hold the symlinks of a framework bundle, so only the binary is kept, in a flat directory.
cp -L "$work/out/MediaRemoteAdapter.framework/MediaRemoteAdapter" "$out/MediaRemoteAdapter.framework/MediaRemoteAdapter"
cp "$work/src/bin/mediaremote-adapter.pl" "$out/mediaremote-adapter.pl"
cp "$work/src/LICENSE" "$out/LICENSE-mediaremote-adapter"
codesign --force --sign - "$out/MediaRemoteAdapter.framework/MediaRemoteAdapter"
lipo -archs "$out/MediaRemoteAdapter.framework/MediaRemoteAdapter"
echo "helper built into $out"
