$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose -f deploy/docker-compose-infra.yml down --remove-orphans
docker compose -f deploy/docker-compose-infra.yml up -d --force-recreate
