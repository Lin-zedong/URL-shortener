package urlshortener.service.redis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Узел RESP-ответа Redis.
 */
public final class RedisNode {

    public enum Type {
        SIMPLE_STRING,
        BULK_STRING,
        INTEGER,
        ARRAY,
        NULL
    }

    private final Type type;
    private final String stringValue;
    private final Long integerValue;
    private final List<RedisNode> children;

    private RedisNode(Type type, String stringValue, Long integerValue, List<RedisNode> children) {
        this.type = type;
        this.stringValue = stringValue;
        this.integerValue = integerValue;
        this.children = children == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(children));
    }

    public static RedisNode simple(String value) {
        return new RedisNode(Type.SIMPLE_STRING, value, null, List.of());
    }

    public static RedisNode bulk(String value) {
        return value == null ? nil() : new RedisNode(Type.BULK_STRING, value, null, List.of());
    }

    public static RedisNode integer(long value) {
        return new RedisNode(Type.INTEGER, null, value, List.of());
    }

    public static RedisNode array(List<RedisNode> children) {
        return new RedisNode(Type.ARRAY, null, null, children);
    }

    public static RedisNode nil() {
        return new RedisNode(Type.NULL, null, null, List.of());
    }

    public Type type() {
        return type;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    public String asString() {
        return stringValue;
    }

    public long asLong() {
        return integerValue == null ? 0L : integerValue;
    }

    public List<RedisNode> asArray() {
        return children;
    }
}
