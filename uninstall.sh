#!/usr/bin/env bash
# Remove a spirex install made by install.sh.
set -euo pipefail

APP="spirex"
PREFIX="${PREFIX:-$HOME/.local}"

rm -f  "$PREFIX/bin/$APP"
rm -rf "$PREFIX/share/$APP"

echo "  removed $APP from $PREFIX"
echo "  (the PATH line added to your shell rc, if any, is harmless — remove it by hand if you like)"
