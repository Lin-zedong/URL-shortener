package urlshortener.store;

import urlshortener.model.DailyStat;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.model.UserSession;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Единый абстрактный контракт хранилища для демо-режима и распределённого режима.
 */
public interface DataStore {

    Optional<User> findUserByUsername(String username);

    Optional<User> findUserById(UUID userId);

    User saveUser(User user);

    Optional<UserSession> findSessionByTokenHash(String tokenHash);

    UserSession saveSession(UserSession session);

    void deleteSessionByTokenHash(String tokenHash);

    int deleteExpiredSessions(Instant now);

    Optional<ShortLink> findShortLinkById(UUID linkId);

    Optional<ShortLink> findShortLinkByRouteKey(String routeKey);

    ShortLink saveShortLink(ShortLink shortLink);

    List<ShortLink> saveShortLinksBulk(Collection<ShortLink> shortLinks);

    List<ShortLink> findShortLinksByOwner(UUID ownerUserId);

    boolean routeKeyExistsOrReserved(String routeKey);

    int routeKeyPoolSize();

    void addRouteKeys(Collection<String> routeKeys);

    Optional<String> pollRouteKey();

    List<DailyStat> findStatsByLink(UUID shortLinkId, int days);

    void applyAggregatedClicks(Map<UUID, Map<LocalDate, Long>> increments, Instant now);

    int markExpiredLinks(Instant now);

    int purgeOwnedLinksPermanently(UUID ownerUserId, Collection<UUID> linkIds);
}
