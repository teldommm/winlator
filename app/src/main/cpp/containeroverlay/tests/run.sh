#!/bin/sh
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/containeroverlay-test"
rm -rf "$OUT"
mkdir -p "$OUT"
CC="${CC:-cc}"
EXTRA=""
case "$(uname)" in Darwin) EXTRA="-D_DARWIN_C_SOURCE" ;; *) EXTRA="-D_GNU_SOURCE" ;; esac
$CC -std=gnu11 $EXTRA -Wall -Wextra -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -o "$OUT/test_core" ../containeroverlay_core.c test_core.c
OVL_TEST_TMP="$OUT" "$OUT/test_core"
