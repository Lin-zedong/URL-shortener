@echo off
setlocal
cd /d %~dp0\..
docker compose -f deploy\docker-compose-infra.yml down
