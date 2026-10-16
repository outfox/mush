#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
output=${1:-"$root/build/native"}
mkdir -p "$output"
${CC:-cc} -std=c11 -O2 -fPIC -shared -Wall -Wextra -Werror ${CPPFLAGS:-} \
  "$root/spike/native/mush_gpu.c" -o "$output/libmush_gpu.so" \
  -l:libEGL.so.1 -l:libGLESv2.so.2 -l:libvulkan.so.1 -ldl
