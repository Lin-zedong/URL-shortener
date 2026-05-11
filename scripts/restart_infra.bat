@echo off
setlocal
cd /d %~dp0\..
docker compose -f deploy\docker-compose-infra.yml down --remove-orphans
docker compose -f deploy\docker-compose-infra.yml up -d --force-recreate
