$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
Write-Host "[1/2] Stopping PostgreSQL, Redis and reverse proxy..."
docker compose -f deploy/docker-compose-infra.yml down -v --remove-orphans
Write-Host "[2/2] Starting PostgreSQL, Redis and reverse proxy with local trust authentication..."
docker compose -f deploy/docker-compose-infra.yml up -d --force-recreate
