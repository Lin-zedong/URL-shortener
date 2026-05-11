#!/usr/bin/env bash
set -euo pipefail

# Компилирует все Java-файлы проекта в build/classes.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p build/classes
find urlshortener -name "*.java" > build/sources.txt
javac -encoding UTF-8 -d build/classes @build/sources.txt

echo "Компиляция завершена: $ROOT_DIR/build/classes"
