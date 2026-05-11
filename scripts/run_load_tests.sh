#!/usr/bin/env bash
set -euo pipefail

# Запускает полный набор нагрузочных сценариев по НФТ.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DURATION="${1:-60}"
cd "$ROOT_DIR"
"$ROOT_DIR/scripts/compile.sh"
java -Dfile.encoding=UTF-8 -cp build/classes urlshortener.loadtest.LoadTestRunner --duration="$DURATION" --list-rps=50
