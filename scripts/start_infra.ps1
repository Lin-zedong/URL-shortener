Set-Location (Join-Path $PSScriptRoot '..')
docker compose -f deploy/docker-compose-infra.yml up -d
