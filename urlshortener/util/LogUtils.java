package urlshortener.util;

import urlshortener.config.AppConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import urlshortener.store.ZoneHelper;

import java.time.format.DateTimeFormatter;

/**
 * Простой файловый логгер для событий приложения и безопасных сообщений об ошибках.
 */
public final class LogUtils {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneHelper.moscow());

    private LogUtils() {
    }

    /**
     * Создаёт каталог логов и файл приложения.
     */
    public static void init() {
        try {
            Files.createDirectories(AppConfig.LOG_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось инициализировать каталог логов", e);
        }
    }

    public static synchronized void info(String message) {
        write("INFO", null, null, message, null);
    }

    /**
     * Пишет безопасную ошибку с кодом, временем и route_key.
     */
    public static synchronized void error(String errorCode, String routeKey, String message, Throwable throwable) {
        write("ERROR", errorCode, routeKey, message, throwable);
    }

    private static void write(String level, String errorCode, String routeKey, String message, Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append(FORMATTER.format(Instant.now()))
                .append(" [").append(level).append("] ");
        if (errorCode != null) {
            builder.append("code=").append(errorCode).append(' ');
        }
        if (routeKey != null) {
            builder.append("routeKey=").append(routeKey).append(' ');
        }
        builder.append(message);
        if (throwable != null) {
            builder.append(" | exception=").append(throwable.getClass().getSimpleName())
                    .append(": ").append(throwable.getMessage());
        }
        String line = builder + System.lineSeparator();
        System.out.print(line);
        try {
            Files.writeString(
                    AppConfig.APP_LOG_FILE,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("Не удалось записать лог: " + e.getMessage());
        }
    }
}
