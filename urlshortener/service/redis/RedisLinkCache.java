package urlshortener.service.redis;

import urlshortener.config.AppConfig;
import urlshortener.model.ShortLink;
import urlshortener.service.LinkCache;

import java.util.Optional;

/**
 * Redis-реализация hot-link cache для redirect path.
 */
public final class RedisLinkCache implements LinkCache {

    private final RedisClient redisClient;

    public RedisLinkCache() {
        this.redisClient = new RedisClient(
                AppConfig.REDIS_HOST,
                AppConfig.REDIS_PORT,
                AppConfig.REDIS_PASSWORD,
                AppConfig.REDIS_CACHE_DB,
                AppConfig.REDIS_TIMEOUT_MILLIS
        );
    }

    @Override
    public Optional<ShortLink> get(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return Optional.empty();
        }
        RedisNode reply = redisClient.command("GET", key(routeKey));
        if (reply == null || reply.isNull() || reply.asString() == null) {
            return Optional.empty();
        }
        ShortLink shortLink = RedisShortLinkCodec.decode(reply.asString());
        return Optional.ofNullable(shortLink);
    }

    @Override
    public void put(ShortLink shortLink) {
        if (shortLink == null) {
            return;
        }
        redisClient.command(
                "SET",
                key(shortLink.routeKey()),
                RedisShortLinkCodec.encode(shortLink),
                "EX",
                String.valueOf(Math.max(1L, AppConfig.HOT_CACHE_TTL.toSeconds()))
        );
    }

    @Override
    public void invalidate(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return;
        }
        redisClient.command("DEL", key(routeKey));
    }

    @Override
    public void clear() {
        redisClient.command("FLUSHDB");
    }

    private String key(String routeKey) {
        return AppConfig.REDIS_CACHE_KEY_PREFIX + routeKey;
    }
}
