package urlshortener.store.pg;

import urlshortener.config.AppConfig;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Небольшой пул соединений к PostgreSQL без сторонних библиотек.
 */
public final class PgPool implements Closeable {

    private final BlockingQueue<PgConnection> queue;
    private final List<PgConnection> allConnections = new ArrayList<>();

    public PgPool(int poolSize) {
        int safePoolSize = Math.max(1, poolSize);
        this.queue = new ArrayBlockingQueue<>(safePoolSize);
        for (int i = 0; i < safePoolSize; i++) {
            PgConnection connection = new PgConnection(
                    AppConfig.DB_HOST,
                    AppConfig.DB_PORT,
                    AppConfig.DB_NAME,
                    AppConfig.DB_USER,
                    AppConfig.DB_PASSWORD,
                    AppConfig.DB_CONNECT_TIMEOUT_MILLIS
            );
            allConnections.add(connection);
            queue.offer(connection);
        }
    }

    public <T> T withConnection(PgWork<T> work) {
        PgConnection connection = null;
        try {
            connection = queue.take();
            return work.execute(connection);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание соединения PostgreSQL было прервано", ex);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Ошибка работы с пулом PostgreSQL", ex);
        } finally {
            if (connection != null) {
                queue.offer(connection);
            }
        }
    }

    @Override
    public void close() {
        for (PgConnection connection : allConnections) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Ошибки при закрытии не должны прерывать остановку приложения.
            }
        }
    }

    @FunctionalInterface
    public interface PgWork<T> {
        T execute(PgConnection connection) throws Exception;
    }
}
