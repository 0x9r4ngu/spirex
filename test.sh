#!/usr/bin/env bash
# Compile src + test and run the dependency-free test suite. Pure JDK.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/out-test"

rm -rf "$OUT"
mkdir -p "$OUT"

find "$DIR/src" "$DIR/test" -name '*.java' > "$OUT/sources.txt"
javac -d "$OUT" @"$OUT/sources.txt"

java -cp "$OUT" spirex.Tests
