package urlshortener.service;

import urlshortener.config.AppConfig;
import urlshortener.store.DataStore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Сервис предгенерации route_key, чтобы создание ссылки не зависело от долгой генерации ключа.
 */
public final class RouteKeyPoolService {

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final DataStore dataStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public RouteKeyPoolService(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    /**
     * Доводит пул ключей до целевого размера перед обработкой трафика.
     */
    public synchronized void ensurePoolTarget() {
        int currentSize = dataStore.routeKeyPoolSize();
        int missing = AppConfig.ROUTE_KEY_POOL_TARGET - currentSize;
        if (missing <= 0) {
            return;
        }
        List<String> generated = new ArrayList<>();
        while (generated.size() < missing) {
            String candidate = nextCandidate();
            if (!dataStore.routeKeyExistsOrReserved(candidate) && !generated.contains(candidate)) {
                generated.add(candidate);
            }
        }
        dataStore.addRouteKeys(generated);
    }

    /**
     * Фоново пополняет пул при достижении нижнего порога.
     */
    public synchronized void replenishIfNeeded() {
        if (dataStore.routeKeyPoolSize() <= AppConfig.ROUTE_KEY_POOL_LOW_WATERMARK) {
            ensurePoolTarget();
        }
    }

    /**
     * Берёт ключ из пула или генерирует уникальный ключ как fallback.
     */
    public synchronized String takeOrGenerate() {
        Optional<String> pooled = dataStore.pollRouteKey();
        return pooled.orElseGet(this::generateUniqueNow);
    }

    public synchronized String generateUniqueNow() {
        while (true) {
            String candidate = nextCandidate();
            if (!dataStore.routeKeyExistsOrReserved(candidate)) {
                return candidate;
            }
        }
    }

    private String nextCandidate() {
        StringBuilder builder = new StringBuilder(AppConfig.ROUTE_KEY_LENGTH);
        for (int i = 0; i < AppConfig.ROUTE_KEY_LENGTH; i++) {
            builder.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
