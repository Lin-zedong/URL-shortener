package urlshortener.store;

import urlshortener.config.AppConfig;
import urlshortener.model.DailyStat;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.model.UserSession;
import urlshortener.util.LogUtils;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Потокобезопасное файловое хранилище прототипа; в целевой архитектуре заменяется PostgreSQL.
 */
public final class FileDataStore implements DataStore {

    private final Path snapshotFile;
    private DatabaseSnapshot snapshot;

    // Эти индексы не сериализуются в snapshot и пересобираются при запуске и после изменений, чтобы ускорять запросы.
    private final Map<String, UUID> usernameIndex = new HashMap<>();
    private final Map<String, UUID> sessionTokenIndex = new HashMap<>();
    private final Map<String, UUID> routeKeyIndex = new HashMap<>();
    private final Set<String> poolIndex = new LinkedHashSet<>();
    private final Set<String> retiredRouteKeyIndex = new LinkedHashSet<>();

    public FileDataStore(Path snapshotFile) {
        this.snapshotFile = snapshotFile;
        this.snapshot = loadOrCreate();
        rebuildIndexes();
    }

    private DatabaseSnapshot loadOrCreate() {
        try {
            Files.createDirectories(AppConfig.DATA_DIR);
            if (!Files.exists(snapshotFile)) {
                LogUtils.info("Файл снимка данных не найден. Хранилище запущено пустым.");
                return new DatabaseSnapshot();
            }
            try (ObjectInputStream objectInputStream = new ObjectInputStream(Files.newInputStream(snapshotFile))) {
                Object object = objectInputStream.readObject();
                if (object instanceof DatabaseSnapshot loadedSnapshot) {
                    LogUtils.info("Снимок данных загружен из " + snapshotFile.toAbsolutePath());
                    return loadedSnapshot;
                }
                throw new IllegalStateException("Неожиданный тип снимка данных");
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Не удалось загрузить снимок данных", e);
        }
    }

    private void rebuildIndexes() {
        usernameIndex.clear();
        sessionTokenIndex.clear();
        routeKeyIndex.clear();
        poolIndex.clear();
        retiredRouteKeyIndex.clear();

        for (User user : snapshot.users.values()) {
            usernameIndex.put(user.username(), user.id());
        }
        for (UserSession session : snapshot.sessions.values()) {
            sessionTokenIndex.put(session.tokenHash(), session.id());
        }
        for (ShortLink shortLink : snapshot.shortLinks.values()) {
            routeKeyIndex.put(shortLink.routeKey(), shortLink.id());
        }
        poolIndex.addAll(snapshot.routeKeyPool);
        retiredRouteKeyIndex.addAll(snapshot.retiredRouteKeys);
    }

    private void save() {
        try {
            Files.createDirectories(AppConfig.DATA_DIR);
            Path tempFile = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(Files.newOutputStream(tempFile))) {
                objectOutputStream.writeObject(snapshot);
            }
            Files.move(tempFile, snapshotFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить снимок данных", e);
        }
    }

    public synchronized Optional<User> findUserByUsername(String username) {
        UUID userId = usernameIndex.get(username);
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.users.get(userId));
    }

    public synchronized Optional<User> findUserById(UUID userId) {
        return Optional.ofNullable(snapshot.users.get(userId));
    }

    /**
     * Сохраняет пользователя и переписывает snapshot прототипа.
     */
    public synchronized User saveUser(User user) {
        snapshot.users.put(user.id(), user);
        usernameIndex.put(user.username(), user.id());
        save();
        return user;
    }

    public synchronized Optional<UserSession> findSessionByTokenHash(String tokenHash) {
        UUID sessionId = sessionTokenIndex.get(tokenHash);
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.sessions.get(sessionId));
    }

    public synchronized UserSession saveSession(UserSession session) {
        snapshot.sessions.put(session.id(), session);
        sessionTokenIndex.put(session.tokenHash(), session.id());
        save();
        return session;
    }

    public synchronized void deleteSessionByTokenHash(String tokenHash) {
        UUID sessionId = sessionTokenIndex.remove(tokenHash);
        if (sessionId != null) {
            snapshot.sessions.remove(sessionId);
            save();
        }
    }

    public synchronized int deleteExpiredSessions(Instant now) {
        List<String> expiredTokenHashes = new ArrayList<>();
        for (UserSession session : snapshot.sessions.values()) {
            if (session.isExpired(now)) {
                expiredTokenHashes.add(session.tokenHash());
            }
        }
        for (String tokenHash : expiredTokenHashes) {
            UUID sessionId = sessionTokenIndex.remove(tokenHash);
            if (sessionId != null) {
                snapshot.sessions.remove(sessionId);
            }
        }
        if (!expiredTokenHashes.isEmpty()) {
            save();
        }
        return expiredTokenHashes.size();
    }

    public synchronized Optional<ShortLink> findShortLinkById(UUID linkId) {
        return Optional.ofNullable(snapshot.shortLinks.get(linkId));
    }

    public synchronized Optional<ShortLink> findShortLinkByRouteKey(String routeKey) {
        UUID linkId = routeKeyIndex.get(routeKey);
        if (linkId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.shortLinks.get(linkId));
    }

    /**
     * Сохраняет одну ссылку; эта операция является bottleneck при высокой write-нагрузке.
     */
    public synchronized ShortLink saveShortLink(ShortLink shortLink) {
        snapshot.shortLinks.put(shortLink.id(), shortLink);
        routeKeyIndex.put(shortLink.routeKey(), shortLink.id());
        save();
        return shortLink;
    }

    /**
     * Сохраняет набор ссылок одним атомарным снимком; используется для быстрой подготовки нагрузочного стенда.
     */
    public synchronized List<ShortLink> saveShortLinksBulk(Collection<ShortLink> shortLinks) {
        List<ShortLink> saved = new ArrayList<>();
        if (shortLinks == null || shortLinks.isEmpty()) {
            return saved;
        }
        for (ShortLink shortLink : shortLinks) {
            snapshot.shortLinks.put(shortLink.id(), shortLink);
            routeKeyIndex.put(shortLink.routeKey(), shortLink.id());
            saved.add(shortLink);
        }
        save();
        return saved;
    }

    public synchronized List<ShortLink> findShortLinksByOwner(UUID ownerUserId) {
        List<ShortLink> result = new ArrayList<>();
        for (ShortLink shortLink : snapshot.shortLinks.values()) {
            if (shortLink.ownerUserId().equals(ownerUserId)) {
                result.add(shortLink);
            }
        }
        return result;
    }

    public synchronized boolean routeKeyExistsOrReserved(String routeKey) {
        return routeKeyIndex.containsKey(routeKey)
                || poolIndex.contains(routeKey)
                || retiredRouteKeyIndex.contains(routeKey);
    }

    public synchronized int routeKeyPoolSize() {
        return snapshot.routeKeyPool.size();
    }

    public synchronized void addRouteKeys(Collection<String> routeKeys) {
        boolean changed = false;
        for (String routeKey : routeKeys) {
            if (!routeKeyIndex.containsKey(routeKey)
                    && !retiredRouteKeyIndex.contains(routeKey)
                    && poolIndex.add(routeKey)) {
                snapshot.routeKeyPool.addLast(routeKey);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public synchronized Optional<String> pollRouteKey() {
        String routeKey = snapshot.routeKeyPool.pollFirst();
        if (routeKey != null) {
            poolIndex.remove(routeKey);
            save();
            return Optional.of(routeKey);
        }
        return Optional.empty();
    }

    public synchronized List<DailyStat> findStatsByLink(UUID shortLinkId, int days) {
        LocalDate minDate = LocalDate.now(ZoneHelper.moscow()).minusDays(Math.max(0, days - 1L));
        List<DailyStat> result = new ArrayList<>();
        for (DailyStat dailyStat : snapshot.dailyStats.values()) {
            if (dailyStat.shortLinkId().equals(shortLinkId) && !dailyStat.statDate().isBefore(minDate)) {
                result.add(dailyStat);
            }
        }
        result.sort((left, right) -> right.statDate().compareTo(left.statDate()));
        return result;
    }

    /**
     * Применяет агрегированные клики и обновляет total_clicks ссылок.
     */
    public synchronized void applyAggregatedClicks(Map<UUID, Map<LocalDate, Long>> increments, Instant now) {
        if (increments == null || increments.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, Map<LocalDate, Long>> linkEntry : increments.entrySet()) {
            UUID linkId = linkEntry.getKey();
            ShortLink existingLink = snapshot.shortLinks.get(linkId);
            if (existingLink == null) {
                continue;
            }
            long totalDelta = 0L;

            for (Map.Entry<LocalDate, Long> statEntry : linkEntry.getValue().entrySet()) {
                LocalDate statDate = statEntry.getKey();
                long delta = statEntry.getValue();
                if (delta <= 0) {
                    continue;
                }
                totalDelta += delta;
                String compositeKey = linkId + "|" + statDate;
                DailyStat current = snapshot.dailyStats.get(compositeKey);
                DailyStat updated = current == null
                        ? new DailyStat(linkId, statDate, delta, now)
                        : current.increment(delta, now);
                snapshot.dailyStats.put(compositeKey, updated);
            }

            if (totalDelta > 0) {
                snapshot.shortLinks.put(linkId, existingLink.withTotalClicks(existingLink.totalClicks() + totalDelta, now));
            }
        }

        pruneOldDailyStats(now);
        save();
    }

    /**
     * Находит просроченные активные ссылки и меняет их статус.
     */
    public synchronized int markExpiredLinks(Instant now) {
        int updatedCount = 0;
        for (Map.Entry<UUID, ShortLink> entry : snapshot.shortLinks.entrySet()) {
            ShortLink link = entry.getValue();
            if (link.status() == urlshortener.model.LinkStatus.ACTIVE && link.isExpiredByTime(now)) {
                entry.setValue(link.markExpired(now));
                updatedCount++;
            }
        }
        if (updatedCount > 0) {
            save();
        }
        return updatedCount;
    }

    public synchronized int purgeOwnedLinksPermanently(UUID ownerUserId, Collection<UUID> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) {
            return 0;
        }

        int removedCount = 0;
        boolean changed = false;
        for (UUID linkId : new LinkedHashSet<>(linkIds)) {
            if (linkId == null) {
                continue;
            }
            ShortLink existing = snapshot.shortLinks.get(linkId);
            if (existing == null || !existing.ownerUserId().equals(ownerUserId)) {
                continue;
            }

            snapshot.shortLinks.remove(linkId);
            routeKeyIndex.remove(existing.routeKey());
            if (retiredRouteKeyIndex.add(existing.routeKey())) {
                snapshot.retiredRouteKeys.add(existing.routeKey());
            }
            removeDailyStatsForLink(linkId);
            removedCount++;
            changed = true;
        }

        if (changed) {
            save();
        }
        return removedCount;
    }

    private void removeDailyStatsForLink(UUID linkId) {
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, DailyStat> entry : snapshot.dailyStats.entrySet()) {
            if (entry.getValue().shortLinkId().equals(linkId)) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            snapshot.dailyStats.remove(key);
        }
    }

    private void pruneOldDailyStats(Instant now) {
        LocalDate minDate = LocalDate.now(ZoneHelper.moscow()).minusDays(AppConfig.DAILY_STATS_RETENTION_DAYS - 1L);
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, DailyStat> entry : snapshot.dailyStats.entrySet()) {
            if (entry.getValue().statDate().isBefore(minDate)) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            snapshot.dailyStats.remove(key);
        }
    }
}
