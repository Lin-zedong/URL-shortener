package urlshortener.store;

import java.time.ZoneId;

/**
 * Единое место выбора Moscow time zone для срока действия ссылок.
 */
public final class ZoneHelper {

    private ZoneHelper() {
    }

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public static ZoneId moscow() {
        return MOSCOW;
    }
}
