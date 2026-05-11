package urlshortener.service.redis;

import urlshortener.config.AppConfig;
import urlshortener.model.ClickEvent;
import urlshortener.service.ClickEventBus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream-реализация очереди событий кликов.
 */
public final class RedisClickEventBus implements ClickEventBus {

    private final RedisClient redisClient;

    public RedisClickEventBus() {
        this.redisClient = new RedisClient(
                AppConfig.REDIS_HOST,
                AppConfig.REDIS_PORT,
                AppConfig.REDIS_PASSWORD,
                AppConfig.REDIS_QUEUE_DB,
                AppConfig.REDIS_TIMEOUT_MILLIS
        );
    }

    @Override
    public void publish(ClickEvent event) {
        if (event == null) {
            return;
        }
        redisClient.command(
                "XADD",
                AppConfig.REDIS_CLICK_STREAM,
                "*",
                "short_link_id", event.shortLinkId().toString(),
                "route_key", event.routeKey(),
                "clicked_at", event.clickedAt().toString()
        );
    }

    @Override
    public List<Envelope> readBatch(int maxItems, Duration waitTime) {
        int safeMaxItems = Math.max(1, maxItems);
        int blockMillis = (int) Math.max(0L, waitTime == null ? 0L : waitTime.toMillis());
        RedisNode reply = redisClient.command(
                Math.max(AppConfig.REDIS_TIMEOUT_MILLIS, blockMillis + 1000),
                "XREAD",
                "COUNT", String.valueOf(safeMaxItems),
                "BLOCK", String.valueOf(blockMillis),
                "STREAMS", AppConfig.REDIS_CLICK_STREAM, "0-0"
        );
        return parseReadResult(reply);
    }

    @Override
    public void acknowledge(List<Envelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>();
        args.add("XDEL");
        args.add(AppConfig.REDIS_CLICK_STREAM);
        for (Envelope envelope : envelopes) {
            if (envelope != null && envelope.id() != null && !envelope.id().isBlank()) {
                args.add(envelope.id());
            }
        }
        if (args.size() > 2) {
            redisClient.command(args.toArray(String[]::new));
        }
    }

    @Override
    public int size() {
        RedisNode reply = redisClient.command("XLEN", AppConfig.REDIS_CLICK_STREAM);
        return (int) reply.asLong();
    }

    private List<Envelope> parseReadResult(RedisNode reply) {
        List<Envelope> batch = new ArrayList<>();
        if (reply == null || reply.isNull()) {
            return batch;
        }
        List<RedisNode> topLevel = reply.asArray();
        if (topLevel == null || topLevel.isEmpty()) {
            return batch;
        }
        for (RedisNode streamNode : topLevel) {
            List<RedisNode> streamParts = streamNode.asArray();
            if (streamParts == null || streamParts.size() < 2) {
                continue;
            }
            RedisNode messagesNode = streamParts.get(1);
            for (RedisNode messageNode : messagesNode.asArray()) {
                List<RedisNode> messageParts = messageNode.asArray();
                if (messageParts == null || messageParts.size() < 2) {
                    continue;
                }
                String messageId = messageParts.get(0).asString();
                Map<String, String> fields = toFieldMap(messageParts.get(1).asArray());
                String shortLinkId = fields.get("short_link_id");
                String routeKey = fields.get("route_key");
                String clickedAt = fields.get("clicked_at");
                if (shortLinkId == null || routeKey == null || clickedAt == null) {
                    continue;
                }
                batch.add(new Envelope(messageId, new ClickEvent(
                        UUID.fromString(shortLinkId),
                        routeKey,
                        Instant.parse(clickedAt)
                )));
            }
        }
        return batch;
    }

    private Map<String, String> toFieldMap(List<RedisNode> parts) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (parts == null) {
            return fields;
        }
        for (int i = 0; i + 1 < parts.size(); i += 2) {
            fields.put(parts.get(i).asString(), parts.get(i + 1).asString());
        }
        return fields;
    }
}
