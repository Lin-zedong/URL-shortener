# Настройка входной страницы edge

В конфигурации reverse proxy добавлено правило exact-match для корневого пути `/`.

Теперь запрос `https://localhost/` перенаправляется на `/app/login`, а публичные короткие ссылки по-прежнему обслуживаются redirect-service через catch-all маршрут `/{route_key}`.

После изменения конфигурации нужно перезапустить reverse-proxy:

```cmd
scripts\restart_infra.bat
```

Или полностью пересоздать инфраструктуру:

```cmd
scripts\reset_infra.bat
```
