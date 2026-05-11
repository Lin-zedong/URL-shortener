package urlshortener.service;

import urlshortener.model.LinkStatus;
import urlshortener.model.Page;
import urlshortener.model.ServiceResult;
import urlshortener.model.ShortLink;
import urlshortener.store.DataStore;
import urlshortener.util.ValidationUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Сервис управления ссылками: создание, список, переключение статуса, удаление и истечение.
 */
public final class ShortLinkService {

    private final DataStore dataStore;
    private final RouteKeyPoolService routeKeyPoolService;
    private final LinkCache linkCache;

    public ShortLinkService(DataStore dataStore, RouteKeyPoolService routeKeyPoolService, LinkCache linkCache) {
        this.dataStore = dataStore;
        this.routeKeyPoolService = routeKeyPoolService;
        this.linkCache = linkCache;
    }

    /**
     * Валидирует URL/alias/expires_at и создаёт новую короткую ссылку.
     */
    public ServiceResult<ShortLink> createLink(UUID ownerUserId, String originalUrl, String alias, String expiresAtMoscowRaw) {
        try {
            String normalizedUrl = ValidationUtils.normalizeHttpUrl(originalUrl);
            String normalizedAlias = ValidationUtils.normalizeAlias(alias);
            Instant expiresAt = ValidationUtils.parseOptionalExpiresAtMoscow(expiresAtMoscowRaw);
            Instant now = Instant.now();

            if (expiresAt != null && !expiresAt.isAfter(now)) {
                return ServiceResult.failure("ExpiresAt must be later than current Moscow time");
            }

            String routeKey;
            boolean customAlias;
            if (normalizedAlias != null) {
                if (dataStore.routeKeyExistsOrReserved(normalizedAlias)) {
                    return ServiceResult.failure("Alias is already taken");
                }
                routeKey = normalizedAlias;
                customAlias = true;
            } else {
                routeKey = routeKeyPoolService.takeOrGenerate();
                customAlias = false;
            }

            ShortLink shortLink = new ShortLink(
                    UUID.randomUUID(),
                    ownerUserId,
                    routeKey,
                    normalizedUrl,
                    LinkStatus.ACTIVE,
                    now,
                    now,
                    expiresAt,
                    0L,
                    customAlias
            );
            dataStore.saveShortLink(shortLink);
            linkCache.put(shortLink);
            return ServiceResult.success("Short link created", shortLink);
        } catch (IllegalArgumentException ex) {
            return ServiceResult.failure(ex.getMessage());
        }
    }

    /**
     * Фильтрует и пагинирует ссылки только текущего владельца.
     */
    public Page<ShortLink> listLinks(UUID ownerUserId, String query, String statusFilter, int pageNumber, int pageSize) {
        Instant now = Instant.now();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = statusFilter == null ? "" : statusFilter.trim().toUpperCase(Locale.ROOT);

        List<ShortLink> filtered = new ArrayList<>();
        for (ShortLink shortLink : dataStore.findShortLinksByOwner(ownerUserId)) {
            LinkStatus effectiveStatus = shortLink.effectiveStatus(now);
            boolean matchesQuery = normalizedQuery.isBlank()
                    || shortLink.routeKey().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    || shortLink.originalUrl().toLowerCase(Locale.ROOT).contains(normalizedQuery);
            boolean matchesStatus = normalizedStatus.isBlank() || effectiveStatus.name().equals(normalizedStatus);
            if (matchesQuery && matchesStatus) {
                filtered.add(shortLink);
            }
        }

        filtered.sort(Comparator.comparing(ShortLink::createdAt).reversed());

        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int totalItems = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) safePageSize));
        int safePageNumber = Math.max(1, Math.min(pageNumber, totalPages));
        int fromIndex = Math.min(totalItems, (safePageNumber - 1) * safePageSize);
        int toIndex = Math.min(totalItems, fromIndex + safePageSize);

        return new Page<>(filtered.subList(fromIndex, toIndex), safePageNumber, safePageSize, totalItems, totalPages);
    }

    /**
     * Включает или отключает ссылку с очисткой hot cache.
     */
    public ServiceResult<ShortLink> toggle(UUID ownerUserId, UUID linkId, String targetState) {
        ShortLink shortLink = dataStore.findShortLinkById(linkId).orElse(null);
        if (shortLink == null || !shortLink.ownerUserId().equals(ownerUserId)) {
            return ServiceResult.failure("Link not found or access denied");
        }

        Instant now = Instant.now();
        LinkStatus effectiveStatus = shortLink.effectiveStatus(now);
        if ("disable".equalsIgnoreCase(targetState)) {
            if (effectiveStatus == LinkStatus.DELETED) {
                return ServiceResult.failure("Deleted link cannot be disabled");
            }
            ShortLink updated = shortLink.withStatus(LinkStatus.DISABLED, now);
            dataStore.saveShortLink(updated);
            linkCache.invalidate(updated.routeKey());
            return ServiceResult.success("Link disabled", updated);
        }

        if ("enable".equalsIgnoreCase(targetState)) {
            if (effectiveStatus == LinkStatus.EXPIRED) {
                return ServiceResult.failure("Expired link cannot be re-enabled");
            }
            if (effectiveStatus == LinkStatus.DELETED) {
                return ServiceResult.failure("Deleted link cannot be enabled");
            }
            ShortLink updated = shortLink.withStatus(LinkStatus.ACTIVE, now);
            dataStore.saveShortLink(updated);
            linkCache.put(updated);
            return ServiceResult.success("Link enabled", updated);
        }

        return ServiceResult.failure("Unknown target state");
    }

    /**
     * Помечает ссылку удалённой и немедленно запрещает редирект.
     */
    public ServiceResult<ShortLink> delete(UUID ownerUserId, UUID linkId) {
        ShortLink shortLink = dataStore.findShortLinkById(linkId).orElse(null);
        if (shortLink == null || !shortLink.ownerUserId().equals(ownerUserId)) {
            return ServiceResult.failure("Link not found or access denied");
        }
        ShortLink updated = shortLink.withStatus(LinkStatus.DELETED, Instant.now());
        dataStore.saveShortLink(updated);
        linkCache.invalidate(updated.routeKey());
        return ServiceResult.success("Link deleted", updated);
    }

    public ServiceResult<Integer> deleteHistoryRecords(UUID ownerUserId, Collection<UUID> linkIds) {
        LinkedHashSet<UUID> uniqueIds = new LinkedHashSet<>();
        if (linkIds != null) {
            for (UUID linkId : linkIds) {
                if (linkId != null) {
                    uniqueIds.add(linkId);
                }
            }
        }
        if (uniqueIds.isEmpty()) {
            return ServiceResult.failure("Select at least one history record");
        }

        List<ShortLink> ownedLinks = new ArrayList<>();
        for (UUID linkId : uniqueIds) {
            ShortLink shortLink = dataStore.findShortLinkById(linkId).orElse(null);
            if (shortLink == null || !shortLink.ownerUserId().equals(ownerUserId)) {
                return ServiceResult.failure("Link not found or access denied");
            }
            ownedLinks.add(shortLink);
        }

        int removedCount = dataStore.purgeOwnedLinksPermanently(ownerUserId, uniqueIds);
        for (ShortLink shortLink : ownedLinks) {
            linkCache.invalidate(shortLink.routeKey());
        }
        return ServiceResult.success("Selected history records were deleted", removedCount);
    }

    public ServiceResult<ShortLink> getOwnedLink(UUID ownerUserId, UUID linkId) {
        ShortLink shortLink = dataStore.findShortLinkById(linkId).orElse(null);
        if (shortLink == null || !shortLink.ownerUserId().equals(ownerUserId)) {
            return ServiceResult.failure("Link not found or access denied");
        }
        return ServiceResult.success("OK", shortLink);
    }

    /**
     * Переводит просроченные активные ссылки в статус expired.
     */
    public int expireLinks() {
        int expiredCount = dataStore.markExpiredLinks(Instant.now());
        if (expiredCount > 0) {
            linkCache.clear();
        }
        routeKeyPoolService.replenishIfNeeded();
        return expiredCount;
    }
}
