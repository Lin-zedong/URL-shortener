package urlshortener.store;

import urlshortener.config.AppConfig;
import urlshortener.model.DailyStat;
import urlshortener.model.LinkStatus;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.model.UserSession;
import urlshortener.store.pg.PgEscaper;
import urlshortener.store.pg.PgPool;
import urlshortener.store.pg.PgQueryResult;

import java.io.Closeable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище поверх PostgreSQL, соответствующее low-level design курсового проекта.
 */
public final class PostgresDataStore implements DataStore, Closeable {

    private final PgPool pool;

    public PostgresDataStore() {
        this(AppConfig.DB_POOL_SIZE);
    }

    public PostgresDataStore(int poolSize) {
        this.pool = new PgPool(poolSize);
    }

    @Override
    public Optional<User> findUserByUsername(String username) {
        return pool.withConnection(connection -> optionalUser(connection.execute(
                "SELECT id, username, display_name, password_hash, password_salt, extract(epoch from created_at) "
                        + "FROM users WHERE username = " + PgEscaper.text(username) + " LIMIT 1"
        )));
    }

    @Override
    public Optional<User> findUserById(UUID userId) {
        return pool.withConnection(connection -> optionalUser(connection.execute(
                "SELECT id, username, display_name, password_hash, password_salt, extract(epoch from created_at) "
                        + "FROM users WHERE id = " + PgEscaper.uuid(userId) + " LIMIT 1"
        )));
    }

    @Override
    public User saveUser(User user) {
        pool.withConnection(connection -> {
            connection.execute("INSERT INTO users(id, username, display_name, password_hash, password_salt, created_at) VALUES ("
                    + PgEscaper.uuid(user.id()) + ", "
                    + PgEscaper.text(user.username()) + ", "
                    + PgEscaper.text(user.displayName()) + ", "
                    + PgEscaper.text(user.passwordHash()) + ", "
                    + PgEscaper.text(user.passwordSalt()) + ", "
                    + PgEscaper.instant(user.createdAt()) + ") "
                    + "ON CONFLICT (id) DO UPDATE SET "
                    + "username = EXCLUDED.username, display_name = EXCLUDED.display_name, "
                    + "password_hash = EXCLUDED.password_hash, password_salt = EXCLUDED.password_salt, "
                    + "created_at = EXCLUDED.created_at");
            return null;
        });
        return user;
    }

    @Override
    public Optional<UserSession> findSessionByTokenHash(String tokenHash) {
        return pool.withConnection(connection -> optionalSession(connection.execute(
                "SELECT id, user_id, token_hash, extract(epoch from created_at), extract(epoch from expires_at), "
                        + "extract(epoch from last_access_at), user_agent "
                        + "FROM user_sessions WHERE token_hash = " + PgEscaper.text(tokenHash) + " LIMIT 1"
        )));
    }

    @Override
    public UserSession saveSession(UserSession session) {
        pool.withConnection(connection -> {
            connection.execute("INSERT INTO user_sessions(id, user_id, token_hash, created_at, expires_at, last_access_at, user_agent) VALUES ("
                    + PgEscaper.uuid(session.id()) + ", "
                    + PgEscaper.uuid(session.userId()) + ", "
                    + PgEscaper.text(session.tokenHash()) + ", "
                    + PgEscaper.instant(session.createdAt()) + ", "
                    + PgEscaper.instant(session.expiresAt()) + ", "
                    + PgEscaper.instant(session.lastAccessAt()) + ", "
                    + PgEscaper.text(session.userAgent()) + ") "
                    + "ON CONFLICT (id) DO UPDATE SET "
                    + "user_id = EXCLUDED.user_id, token_hash = EXCLUDED.token_hash, "
                    + "created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at, "
                    + "last_access_at = EXCLUDED.last_access_at, user_agent = EXCLUDED.user_agent");
            return null;
        });
        return session;
    }

    @Override
    public void deleteSessionByTokenHash(String tokenHash) {
        pool.withConnection(connection -> {
            connection.execute("DELETE FROM user_sessions WHERE token_hash = " + PgEscaper.text(tokenHash));
            return null;
        });
    }

    @Override
    public int deleteExpiredSessions(Instant now) {
        return pool.withConnection(connection -> updateCount(connection.execute(
                "DELETE FROM user_sessions WHERE expires_at <= " + PgEscaper.instant(now)
        )));
    }

    @Override
    public Optional<ShortLink> findShortLinkById(UUID linkId) {
        return pool.withConnection(connection -> optionalShortLink(connection.execute(shortLinkSelectWhere("id = " + PgEscaper.uuid(linkId)))));
    }

    @Override
    public Optional<ShortLink> findShortLinkByRouteKey(String routeKey) {
        return pool.withConnection(connection -> optionalShortLink(connection.execute(shortLinkSelectWhere("route_key = " + PgEscaper.text(routeKey)))));
    }

    @Override
    public ShortLink saveShortLink(ShortLink shortLink) {
        pool.withConnection(connection -> {
            upsertShortLink(connection, shortLink);
            return null;
        });
        return shortLink;
    }

    @Override
    public List<ShortLink> saveShortLinksBulk(Collection<ShortLink> shortLinks) {
        List<ShortLink> saved = new ArrayList<>();
        if (shortLinks == null || shortLinks.isEmpty()) {
            return saved;
        }
        pool.withConnection(connection -> {
            connection.begin();
            try {
                for (ShortLink shortLink : shortLinks) {
                    upsertShortLink(connection, shortLink);
                    saved.add(shortLink);
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
            return null;
        });
        return saved;
    }

    @Override
    public List<ShortLink> findShortLinksByOwner(UUID ownerUserId) {
        return pool.withConnection(connection -> toShortLinks(connection.execute(
                shortLinkSelectWhere("owner_user_id = " + PgEscaper.uuid(ownerUserId)) + " ORDER BY created_at DESC"
        )));
    }

    @Override
    public boolean routeKeyExistsOrReserved(String routeKey) {
        String normalized = routeKey == null ? "" : routeKey.trim();
        if (AppConfig.RESERVED_ROUTE_KEYS.contains(normalized)) {
            return true;
        }
        return pool.withConnection(connection -> booleanValue(connection.execute(
                "SELECT EXISTS ("
                        + "SELECT 1 FROM short_links WHERE route_key = " + PgEscaper.text(normalized)
                        + " UNION ALL SELECT 1 FROM route_key_pool WHERE route_key = " + PgEscaper.text(normalized)
                        + " UNION ALL SELECT 1 FROM retired_route_keys WHERE route_key = " + PgEscaper.text(normalized)
                        + ")"
        )));
    }

    @Override
    public int routeKeyPoolSize() {
        return pool.withConnection(connection -> intValue(connection.execute("SELECT COUNT(*) FROM route_key_pool")));
    }

    @Override
    public void addRouteKeys(Collection<String> routeKeys) {
        if (routeKeys == null || routeKeys.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (String routeKey : routeKeys) {
            if (routeKey == null || routeKey.isBlank()) {
                continue;
            }
            values.add("(" + PgEscaper.text(routeKey.trim()) + ", NOW())");
        }
        if (values.isEmpty()) {
            return;
        }
        pool.withConnection(connection -> {
            connection.execute("INSERT INTO route_key_pool(route_key, created_at) VALUES " + String.join(", ", values)
                    + " ON CONFLICT (route_key) DO NOTHING");
            return null;
        });
    }

    @Override
    public Optional<String> pollRouteKey() {
        return pool.withConnection(connection -> {
            PgQueryResult result = connection.execute(
                    "WITH picked AS ("
                            + "SELECT route_key FROM route_key_pool ORDER BY created_at ASC LIMIT 1"
                            + ") DELETE FROM route_key_pool WHERE route_key IN (SELECT route_key FROM picked) RETURNING route_key"
            );
            String value = result.firstValue();
            return value == null || value.isBlank() ? Optional.<String>empty() : Optional.of(value);
        });
    }

    @Override
    public List<DailyStat> findStatsByLink(UUID shortLinkId, int days) {
        int safeDays = Math.max(1, days);
        return pool.withConnection(connection -> toDailyStats(connection.execute(
                "SELECT short_link_id, stat_date::text, click_count, extract(epoch from updated_at) "
                        + "FROM link_daily_stats WHERE short_link_id = " + PgEscaper.uuid(shortLinkId)
                        + " AND stat_date >= CURRENT_DATE - INTERVAL '" + (safeDays - 1) + " days' "
                        + "ORDER BY stat_date DESC"
        )));
    }

    @Override
    public void applyAggregatedClicks(Map<UUID, Map<LocalDate, Long>> increments, Instant now) {
        if (increments == null || increments.isEmpty()) {
            return;
        }
        pool.withConnection(connection -> {
            connection.begin();
            try {
                for (Map.Entry<UUID, Map<LocalDate, Long>> entry : increments.entrySet()) {
                    UUID linkId = entry.getKey();
                    long totalDelta = 0L;
                    for (Map.Entry<LocalDate, Long> statEntry : entry.getValue().entrySet()) {
                        long delta = statEntry.getValue() == null ? 0L : statEntry.getValue();
                        if (delta <= 0L) {
                            continue;
                        }
                        totalDelta += delta;
                        connection.execute("INSERT INTO link_daily_stats(short_link_id, stat_date, click_count, updated_at) VALUES ("
                                + PgEscaper.uuid(linkId) + ", "
                                + PgEscaper.date(statEntry.getKey()) + ", "
                                + delta + ", "
                                + PgEscaper.instant(now) + ") "
                                + "ON CONFLICT (short_link_id, stat_date) DO UPDATE SET "
                                + "click_count = link_daily_stats.click_count + EXCLUDED.click_count, "
                                + "updated_at = EXCLUDED.updated_at");
                    }
                    if (totalDelta > 0L) {
                        connection.execute("UPDATE short_links SET total_clicks = total_clicks + " + totalDelta
                                + ", updated_at = " + PgEscaper.instant(now)
                                + " WHERE id = " + PgEscaper.uuid(linkId));
                    }
                }
                connection.execute("DELETE FROM link_daily_stats WHERE stat_date < CURRENT_DATE - INTERVAL '"
                        + (AppConfig.DAILY_STATS_RETENTION_DAYS - 1) + " days'");
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
            return null;
        });
    }

    @Override
    public int markExpiredLinks(Instant now) {
        return pool.withConnection(connection -> updateCount(connection.execute(
                "UPDATE short_links SET status = 'EXPIRED', updated_at = " + PgEscaper.instant(now)
                        + " WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= " + PgEscaper.instant(now)
        )));
    }

    @Override
    public int purgeOwnedLinksPermanently(UUID ownerUserId, Collection<UUID> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) {
            return 0;
        }
        LinkedHashSet<UUID> uniqueIds = new LinkedHashSet<>();
        for (UUID linkId : linkIds) {
            if (linkId != null) {
                uniqueIds.add(linkId);
            }
        }
        if (uniqueIds.isEmpty()) {
            return 0;
        }
        String idCsv = PgEscaper.csvUuid(uniqueIds);
        return pool.withConnection(connection -> {
            connection.begin();
            try {
                connection.execute("INSERT INTO retired_route_keys(route_key, retired_at) "
                        + "SELECT route_key, NOW() FROM short_links WHERE owner_user_id = " + PgEscaper.uuid(ownerUserId)
                        + " AND id IN (" + idCsv + ") ON CONFLICT (route_key) DO NOTHING");
                connection.execute("DELETE FROM link_daily_stats WHERE short_link_id IN ("
                        + "SELECT id FROM short_links WHERE owner_user_id = " + PgEscaper.uuid(ownerUserId)
                        + " AND id IN (" + idCsv + "))");
                PgQueryResult deleted = connection.execute("WITH deleted AS ("
                        + "DELETE FROM short_links WHERE owner_user_id = " + PgEscaper.uuid(ownerUserId)
                        + " AND id IN (" + idCsv + ") RETURNING id"
                        + ") SELECT COUNT(*) FROM deleted");
                connection.commit();
                return intValue(deleted);
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        });
    }

    @Override
    public void close() {
        pool.close();
    }

    private void upsertShortLink(urlshortener.store.pg.PgConnection connection, ShortLink shortLink) {
        connection.execute("INSERT INTO short_links(id, owner_user_id, route_key, original_url, status, created_at, updated_at, expires_at, total_clicks, custom_alias) VALUES ("
                + PgEscaper.uuid(shortLink.id()) + ", "
                + PgEscaper.uuid(shortLink.ownerUserId()) + ", "
                + PgEscaper.text(shortLink.routeKey()) + ", "
                + PgEscaper.text(shortLink.originalUrl()) + ", "
                + PgEscaper.text(shortLink.status().name()) + ", "
                + PgEscaper.instant(shortLink.createdAt()) + ", "
                + PgEscaper.instant(shortLink.updatedAt()) + ", "
                + PgEscaper.instant(shortLink.expiresAt()) + ", "
                + shortLink.totalClicks() + ", "
                + PgEscaper.bool(shortLink.customAlias()) + ") "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "owner_user_id = EXCLUDED.owner_user_id, route_key = EXCLUDED.route_key, original_url = EXCLUDED.original_url, "
                + "status = EXCLUDED.status, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at, "
                + "expires_at = EXCLUDED.expires_at, total_clicks = EXCLUDED.total_clicks, custom_alias = EXCLUDED.custom_alias");
    }

    private String shortLinkSelectWhere(String whereClause) {
        return "SELECT id, owner_user_id, route_key, original_url, status, "
                + "extract(epoch from created_at), extract(epoch from updated_at), "
                + "CASE WHEN expires_at IS NULL THEN NULL ELSE extract(epoch from expires_at)::text END, "
                + "total_clicks, custom_alias "
                + "FROM short_links WHERE " + whereClause;
    }

    private Optional<User> optionalUser(PgQueryResult result) {
        if (!result.hasRows()) {
            return Optional.empty();
        }
        return Optional.of(toUser(result.firstRow()));
    }

    private Optional<UserSession> optionalSession(PgQueryResult result) {
        if (!result.hasRows()) {
            return Optional.empty();
        }
        return Optional.of(toSession(result.firstRow()));
    }

    private Optional<ShortLink> optionalShortLink(PgQueryResult result) {
        if (!result.hasRows()) {
            return Optional.empty();
        }
        return Optional.of(toShortLink(result.firstRow()));
    }

    private List<ShortLink> toShortLinks(PgQueryResult result) {
        List<ShortLink> links = new ArrayList<>();
        for (List<String> row : result.rows()) {
            links.add(toShortLink(row));
        }
        return links;
    }

    private List<DailyStat> toDailyStats(PgQueryResult result) {
        List<DailyStat> stats = new ArrayList<>();
        for (List<String> row : result.rows()) {
            stats.add(new DailyStat(
                    UUID.fromString(row.get(0)),
                    LocalDate.parse(row.get(1)),
                    Long.parseLong(row.get(2)),
                    parseEpoch(row.get(3))
            ));
        }
        return stats;
    }

    private User toUser(List<String> row) {
        return new User(
                UUID.fromString(row.get(0)),
                row.get(1),
                row.get(2),
                row.get(3),
                row.get(4),
                parseEpoch(row.get(5))
        );
    }

    private UserSession toSession(List<String> row) {
        return new UserSession(
                UUID.fromString(row.get(0)),
                UUID.fromString(row.get(1)),
                row.get(2),
                parseEpoch(row.get(3)),
                parseEpoch(row.get(4)),
                parseEpoch(row.get(5)),
                row.get(6)
        );
    }

    private ShortLink toShortLink(List<String> row) {
        return new ShortLink(
                UUID.fromString(row.get(0)),
                UUID.fromString(row.get(1)),
                row.get(2),
                row.get(3),
                LinkStatus.valueOf(row.get(4).toUpperCase(Locale.ROOT)),
                parseEpoch(row.get(5)),
                parseEpoch(row.get(6)),
                parseEpochNullable(row.get(7)),
                Long.parseLong(row.get(8)),
                parseBoolean(row.get(9))
        );
    }

    private boolean booleanValue(PgQueryResult result) {
        String value = result.firstValue();
        return parseBoolean(value);
    }

    private int intValue(PgQueryResult result) {
        String value = result.firstValue();
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) Math.round(Double.parseDouble(value));
    }

    private int updateCount(PgQueryResult result) {
        if (result.commandTag() == null || result.commandTag().isBlank()) {
            return 0;
        }
        String[] parts = result.commandTag().trim().split(" ");
        String tail = parts[parts.length - 1];
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean parseBoolean(String value) {
        return "t".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private Instant parseEpoch(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        double seconds = Double.parseDouble(value);
        long millis = Math.round(seconds * 1000.0d);
        return Instant.ofEpochMilli(millis);
    }

    private Instant parseEpochNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseEpoch(value);
    }
}
