#!/usr/bin/env bash
# Compile spirex and package it into a runnable jar. Pure JDK, no dependencies.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/out"

rm -rf "$OUT"
mkdir -p "$OUT"

find "$DIR/src" -name '*.java' > "$OUT/sources.txt"
javac -d "$OUT" @"$OUT/sources.txt"

printf 'Main-Class: spirex.Main\n' > "$OUT/manifest.txt"
jar cfm "$DIR/spirex.jar" "$OUT/manifest.txt" -C "$OUT" spirex

echo "Built $DIR/spirex.jar"
echo "Run:   java -jar $DIR/spirex.jar -u https://example.com"
