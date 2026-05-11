# Исправление повторного запуска capacity-тестов

Причина ошибки `duplicate key value violates unique constraint short_links_route_key_key` заключалась в том, что внешняя распределённая БД PostgreSQL сохраняет данные между запусками, а генератор тестовых данных использовал фиксированные `route_key`: `lt-r-00000`, `lt-list-00000`, `lt-m-00000`, `lt-disabled00000`, `lt-expired00000`, а также alias `lt-c-*` для capacity-сценария.

При повторном запуске baseline/capacity-тестов без очистки volume эти route_key уже находились в таблице `short_links`, поэтому PostgreSQL корректно отклонял вставку по уникальному ограничению `short_links_route_key_key`.

В исправленной версии `LoadTestRunner` добавляет к тестовым alias уникальный суффикс запуска (`RUN_SUFFIX`). Это позволяет запускать baseline и capacity-сценарии последовательно без сброса БД.

Для полностью чистого стенда перед контрольным замером всё равно можно выполнять:

```cmd
scripts\reset_infra.bat
```
