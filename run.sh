#!/usr/bin/env bash
# Convenience wrapper: build on first use, then forward all args to spirex.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/spirex.jar" ] || "$DIR/build.sh"
exec java -jar "$DIR/spirex.jar" "$@"
