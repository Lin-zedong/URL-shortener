@echo off
setlocal
cd /d %~dp0\..
echo [1/2] Stopping PostgreSQL, Redis and reverse proxy...
docker compose -f deploy\docker-compose-infra.yml down -v --remove-orphans
echo [2/2] Starting PostgreSQL, Redis and reverse proxy with PostgreSQL password authentication on host port 15432...
docker compose -f deploy\docker-compose-infra.yml up -d --force-recreate
