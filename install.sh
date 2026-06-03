#!/usr/bin/env bash
# Builds devhub and symlinks the launcher onto your PATH so you can just run `devhub`.
# Usage: ./install.sh [target-bin-dir]   (default: ~/.local/bin)
set -euo pipefail
cd "$(dirname "$0")"

echo "Building distribution…"
./gradlew installDist -q

BIN_DIR="${1:-$HOME/.local/bin}"
mkdir -p "$BIN_DIR"
ln -sf "$PWD/build/install/devhub/bin/devhub" "$BIN_DIR/devhub"
echo "Linked $BIN_DIR/devhub -> $PWD/build/install/devhub/bin/devhub"

case ":$PATH:" in
  *":$BIN_DIR:"*) echo "✓ $BIN_DIR is on your PATH — run: devhub" ;;
  *) echo "NOTE: add $BIN_DIR to your PATH, e.g.  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.zshrc" ;;
esac
