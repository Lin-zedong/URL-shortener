package urlshortener.service;

import urlshortener.model.ClickEvent;
import urlshortener.model.LinkStatus;
import urlshortener.model.RedirectResolution;
import urlshortener.model.ShortLink;
import urlshortener.store.DataStore;

import java.time.Instant;
import java.util.Optional;

/**
 * Сервис критичного пути редиректа: ищет ссылку, проверяет статус и публикует click event.
 */
public final class RedirectService {

    private final DataStore dataStore;
    private final LinkCache linkCache;
    private final ClickEventBus clickEventBus;

    public RedirectService(DataStore dataStore, LinkCache linkCache, ClickEventBus clickEventBus) {
        this.dataStore = dataStore;
        this.linkCache = linkCache;
        this.clickEventBus = clickEventBus;
    }

    /**
     * Разрешает route_key, применяет правила статуса и публикует событие клика.
     */
    public RedirectResolution resolve(String routeKey) {
        Instant now = Instant.now();

        Optional<ShortLink> cached = linkCache.get(routeKey);
        if (cached.isPresent()) {
            ShortLink shortLink = cached.get();
            LinkStatus effectiveStatus = shortLink.effectiveStatus(now);
            if (effectiveStatus == LinkStatus.ACTIVE) {
                clickEventBus.publish(new ClickEvent(shortLink.id(), shortLink.routeKey(), now));
                return RedirectResolution.success(shortLink);
            }
            return failureForStatus(effectiveStatus);
        }

        Optional<ShortLink> fromStore = dataStore.findShortLinkByRouteKey(routeKey);
        if (fromStore.isEmpty()) {
            return RedirectResolution.failure("NOT_FOUND", "Короткая ссылка не найдена");
        }

        ShortLink shortLink = fromStore.get();
        LinkStatus effectiveStatus = shortLink.effectiveStatus(now);
        if (effectiveStatus == LinkStatus.ACTIVE) {
            linkCache.put(shortLink);
            clickEventBus.publish(new ClickEvent(shortLink.id(), shortLink.routeKey(), now));
            return RedirectResolution.success(shortLink);
        }

        return failureForStatus(effectiveStatus);
    }

    private RedirectResolution failureForStatus(LinkStatus status) {
        return switch (status) {
            case DISABLED -> RedirectResolution.failure("DISABLED", "Короткая ссылка отключена");
            case EXPIRED -> RedirectResolution.failure("EXPIRED", "Срок действия короткой ссылки истёк");
            case DELETED, ACTIVE -> RedirectResolution.failure("NOT_FOUND", "Короткая ссылка не найдена");
        };
    }
}
