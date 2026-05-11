package urlshortener.service;

import urlshortener.model.ShortLink;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Внутрипроцессный LRU-кэш горячих ссылок для демо-режима и стенда без внешнего Redis.
 */
public final class HotLinkCache implements LinkCache {

    private final int maxSize;
    private final Duration ttl;
    private final Map<String, CacheEntry> delegate;

    public HotLinkCache(int maxSize, Duration ttl) {
        this.maxSize = Math.max(100, maxSize);
        this.ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
        this.delegate = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > HotLinkCache.this.maxSize;
            }
        };
    }

    @Override
    public synchronized Optional<ShortLink> get(String routeKey) {
        CacheEntry entry = delegate.get(routeKey);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            delegate.remove(routeKey);
            return Optional.empty();
        }
        return Optional.of(entry.shortLink());
    }

    @Override
    public synchronized void put(ShortLink shortLink) {
        if (shortLink == null) {
            return;
        }
        delegate.put(shortLink.routeKey(), new CacheEntry(shortLink, Instant.now().plus(ttl)));
    }

    @Override
    public synchronized void invalidate(String routeKey) {
        if (routeKey != null) {
            delegate.remove(routeKey);
        }
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
    }

    private record CacheEntry(ShortLink shortLink, Instant expiresAt) {
    }
}
