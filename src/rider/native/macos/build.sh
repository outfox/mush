#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
output=${1:-"$root/build/native"}
mkdir -p "$output"
: "${JAVA_HOME:?JAVA_HOME must point at a JDK, for its JNI and JAWT headers}"
# Universal, so one plugin build runs on Apple silicon and Intel. Only headers are taken from the JDK: libjawt
# itself is resolved with dlopen at runtime against whichever JVM loads this library, so the built dylib carries
# no path to the JDK that compiled it.
${CC:-clang} -O2 -dynamiclib -fobjc-arc -Wall -Wextra -Werror ${CPPFLAGS:-} \
  -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  "$root/src/rider/native/macos/mush_embed.m" -o "$output/libmush_embed.dylib" \
  -framework Foundation -framework CoreGraphics -framework QuartzCore
