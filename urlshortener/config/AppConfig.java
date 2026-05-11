package urlshortener.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Центральное место хранения параметров приложения: порты, каталоги, TTL и ограничения валидации.
 */
public final class AppConfig {

    /**
     * Режим запуска: demo, distributed-idea, distributed-container.
     */
    public static final String DEPLOYMENT_MODE = stringProperty("app.mode", "APP_MODE", "demo");

    /**
     * Внешний входной порт, который соответствует edge / reverse proxy.
     */
    public static final int EDGE_PORT = intProperty("app.edgePort", "APP_EDGE_PORT", 8088);

    /**
     * Порт сервиса управления ссылками и личным кабинетом.
     */
    public static final int MANAGEMENT_PORT = intProperty("app.managementPort", "APP_MANAGEMENT_PORT", 8080);

    /**
     * Порт публичного сервиса редиректа.
     */
    public static final int REDIRECT_PORT = intProperty("app.redirectPort", "APP_REDIRECT_PORT", 8081);

    /**
     * Размер пулов потоков HTTP-сервисов; значения можно увеличить при нагрузочном тестировании.
     */
    public static final int MANAGEMENT_THREADS = intProperty("app.managementThreads", "APP_MANAGEMENT_THREADS", 32);
    public static final int REDIRECT_THREADS = intProperty("app.redirectThreads", "APP_REDIRECT_THREADS", 64);
    public static final int EDGE_THREADS = intProperty("app.edgeThreads", "APP_EDGE_THREADS", 64);

    /**
     * Время жизни пользовательской сессии.
     */
    public static final Duration SESSION_TTL = Duration.ofHours(12);

    /**
     * Время жизни cookie с выбранным языком интерфейса.
     */
    public static final Duration LANGUAGE_TTL = Duration.ofDays(365);

    /**
     * Размер и TTL горячего кэша route_key.
     */
    public static final int HOT_CACHE_SIZE = intProperty("app.hotCacheSize", "APP_HOT_CACHE_SIZE", 5_000);
    public static final Duration HOT_CACHE_TTL = Duration.ofMinutes(intProperty("app.hotCacheTtlMinutes", "APP_HOT_CACHE_TTL_MINUTES", 10));

    /**
     * Параметры внешнего PostgreSQL.
     */
    public static final String DB_HOST = stringProperty("app.dbHost", "APP_DB_HOST", "localhost");
    public static final int DB_PORT = intProperty("app.dbPort", "APP_DB_PORT", 5432);
    public static final String DB_NAME = stringProperty("app.dbName", "APP_DB_NAME", "url_shortener");
    public static final String DB_USER = stringProperty("app.dbUser", "APP_DB_USER", "url_shortener");
    public static final String DB_PASSWORD = stringProperty("app.dbPassword", "APP_DB_PASSWORD", "url_shortener");
    public static final int DB_CONNECT_TIMEOUT_MILLIS = intProperty("app.dbConnectTimeoutMillis", "APP_DB_CONNECT_TIMEOUT_MILLIS", 5_000);
    public static final int DB_POOL_SIZE = intProperty("app.dbPoolSize", "APP_DB_POOL_SIZE", 8);

    /**
     * Параметры внешнего Redis.
     */
    public static final String REDIS_HOST = stringProperty("app.redisHost", "APP_REDIS_HOST", "localhost");
    public static final int REDIS_PORT = intProperty("app.redisPort", "APP_REDIS_PORT", 6379);
    public static final String REDIS_PASSWORD = stringProperty("app.redisPassword", "APP_REDIS_PASSWORD", "");
    public static final int REDIS_TIMEOUT_MILLIS = intProperty("app.redisTimeoutMillis", "APP_REDIS_TIMEOUT_MILLIS", 5_000);
    public static final int REDIS_CACHE_DB = intProperty("app.redisCacheDb", "APP_REDIS_CACHE_DB", 0);
    public static final int REDIS_QUEUE_DB = intProperty("app.redisQueueDb", "APP_REDIS_QUEUE_DB", 1);
    public static final String REDIS_CACHE_KEY_PREFIX = stringProperty("app.redisCacheKeyPrefix", "APP_REDIS_CACHE_KEY_PREFIX", "urls:cache:route:");
    public static final String REDIS_CLICK_STREAM = stringProperty("app.redisClickStream", "APP_REDIS_CLICK_STREAM", "urls:click-events");

    /**
     * Длина автоматически генерируемого route_key.
     */
    public static final int ROUTE_KEY_LENGTH = 7;

    /**
     * Целевой и нижний порог пула предгенерированных ключей.
     */
    public static final int ROUTE_KEY_POOL_TARGET = intProperty("app.routeKeyPoolTarget", "APP_ROUTE_KEY_POOL_TARGET", 2_000);
    public static final int ROUTE_KEY_POOL_LOW_WATERMARK = intProperty("app.routeKeyPoolLowWatermark", "APP_ROUTE_KEY_POOL_LOW_WATERMARK", 500);

    /**
     * Статистика хранится 90 дней согласно НФТ.
     */
    public static final int DAILY_STATS_RETENTION_DAYS = 90;

    /**
     * Размер страницы по умолчанию для списка ссылок.
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Регулярные выражения для username и alias.
     */
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{3,32}$");
    public static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{3,32}$");

    /**
     * Зарезервированные пути, чтобы короткие ссылки не конфликтовали со служебными маршрутами.
     */
    public static final Set<String> RESERVED_ROUTE_KEYS = Set.of("app", "api", "health");

    /**
     * Файлы данных и логов. Каталоги можно переопределить для изолированного запуска нагрузочных тестов.
     */
    public static final Path DATA_DIR = pathProperty("app.dataDir", "APP_DATA_DIR", "data");
    public static final Path LOG_DIR = pathProperty("app.logDir", "APP_LOG_DIR", "logs");
    public static final Path SNAPSHOT_FILE = DATA_DIR.resolve("database.bin");
    public static final Path APP_LOG_FILE = LOG_DIR.resolve("application.log");

    /**
     * Внешний base URL по умолчанию указывает на edge-вход.
     */
    public static final String BASE_URL = stringProperty("app.baseUrl", "APP_BASE_URL", "http://localhost:" + EDGE_PORT);

    /**
     * Версия статических ассетов для cache busting.
     */
    public static final String ASSET_VERSION = stringProperty("app.assetVersion", "APP_ASSET_VERSION", "20260423-distributed-1");

    /**
     * Имена cookie, используемые сервисом.
     */
    public static final String SESSION_COOKIE_NAME = "URLS_SESSION";
    public static final String LANGUAGE_COOKIE_NAME = "URLS_LANG";

    private static int intProperty(String key, String envKey, int defaultValue) {
        String rawValue = firstNonBlank(System.getProperty(key), System.getenv(envKey));
        if (rawValue == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String stringProperty(String key, String envKey, String defaultValue) {
        String rawValue = firstNonBlank(System.getProperty(key), System.getenv(envKey));
        if (rawValue == null) {
            return defaultValue;
        }
        return rawValue.trim();
    }

    private static Path pathProperty(String key, String envKey, String defaultValue) {
        String rawValue = firstNonBlank(System.getProperty(key), System.getenv(envKey));
        if (rawValue == null) {
            return Path.of(defaultValue);
        }
        return Path.of(rawValue.trim());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private AppConfig() {
    }
}
