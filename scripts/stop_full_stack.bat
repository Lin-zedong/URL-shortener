@echo off
setlocal
cd /d %~dp0\..
docker compose -f deploy\docker-compose-full.yml down
