#!/bin/sh
# Builds the macOS test publisher next to this script. Needs the Xcode command line tools.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
clang -fobjc-arc -O1 -Wall \
    -framework Foundation -framework AppKit -framework MediaPlayer \
    -sectcreate __TEXT __info_plist "$here/Info.plist" \
    "$here/main.m" -o "$here/orpheus-publisher"
echo "built $here/orpheus-publisher"
