package urlshortener.store.pg;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Экранирование литералов для SQL-запросов прототипа.
 */
public final class PgEscaper {

    private PgEscaper() {
    }

    public static String text(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    public static String uuid(UUID value) {
        return value == null ? "NULL" : text(value.toString()) + "::uuid";
    }

    public static String instant(Instant value) {
        return value == null ? "NULL" : text(value.toString()) + "::timestamptz";
    }

    public static String date(LocalDate value) {
        return value == null ? "NULL" : text(value.toString()) + "::date";
    }

    public static String bool(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    public static String csvUuid(Collection<UUID> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(PgEscaper::uuid)
                .collect(Collectors.joining(", "));
    }
}
