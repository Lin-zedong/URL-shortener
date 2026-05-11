package urlshortener.service;

import urlshortener.model.ShortLink;

import java.util.Optional;

/**
 * Абстракция горячего кэша route_key → short_link.
 */
public interface LinkCache {

    Optional<ShortLink> get(String routeKey);

    void put(ShortLink shortLink);

    void invalidate(String routeKey);

    void clear();
}
