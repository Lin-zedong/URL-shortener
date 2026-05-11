# PostgreSQL local authentication fix

The lightweight Java PostgreSQL client in this educational prototype supports trust/no-auth, cleartext and MD5 authentication. It does not implement SCRAM-SHA-256.

For the local distributed stand the PostgreSQL container is therefore configured with a mounted `deploy/postgres/pg_hba.conf` file and trust authentication. This is suitable only for a local course prototype and must not be used in production.

If IDEA shows:

```text
PostgreSQL требует SCRAM-SHA-256
```

then an old Docker volume was probably initialized before the trust configuration was applied. Run from the project root:

```cmd
scripts\reset_infra.bat
```

This command removes the local PostgreSQL/Redis Docker volumes and recreates the stand. All local test data will be deleted.

After that, run in IDEA:

```text
Management Service URL Shortener
Redirect Service URL Shortener
Background Worker URL Shortener
```
