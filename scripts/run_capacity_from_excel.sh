#!/usr/bin/env bash
set -euo pipefail

# Запускает дополнительные capacity-сценарии по расчёту ресурсов Excel.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DURATION="${1:-30}"
cd "$ROOT_DIR"
"$ROOT_DIR/scripts/compile.sh"
java -Dfile.encoding=UTF-8 -cp build/classes urlshortener.loadtest.LoadTestRunner --duration="$DURATION" --list-rps=50 --capacity=true
