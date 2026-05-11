package urlshortener.service.redis;

import urlshortener.model.LinkStatus;
import urlshortener.model.ShortLink;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Стабильный текстовый кодек short_link для хранения в Redis.
 */
public final class RedisShortLinkCodec {

    private RedisShortLinkCodec() {
    }

    public static String encode(ShortLink shortLink) {
        String expiresAt = shortLink.expiresAt() == null ? "" : shortLink.expiresAt().toString();
        return String.join("\n",
                shortLink.id().toString(),
                shortLink.ownerUserId().toString(),
                shortLink.routeKey(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(shortLink.originalUrl().getBytes(StandardCharsets.UTF_8)),
                shortLink.status().name(),
                shortLink.createdAt().toString(),
                shortLink.updatedAt().toString(),
                expiresAt,
                String.valueOf(shortLink.totalClicks()),
                String.valueOf(shortLink.customAlias())
        );
    }

    public static ShortLink decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String[] parts = payload.split("\\n", -1);
        if (parts.length < 10) {
            return null;
        }
        return new ShortLink(
                UUID.fromString(parts[0]),
                UUID.fromString(parts[1]),
                parts[2],
                new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8),
                LinkStatus.valueOf(parts[4]),
                Instant.parse(parts[5]),
                Instant.parse(parts[6]),
                parts[7].isBlank() ? null : Instant.parse(parts[7]),
                Long.parseLong(parts[8]),
                Boolean.parseBoolean(parts[9])
        );
    }
}
