#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
docker compose -f deploy/docker-compose-full.yml up -d --build
