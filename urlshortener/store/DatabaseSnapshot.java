package urlshortener.store;

import urlshortener.model.DailyStat;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.model.UserSession;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Сериализуемый снимок состояния MVP-хранилища.
 */
public final class DatabaseSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public Map<UUID, User> users;
    public Map<UUID, UserSession> sessions;
    public Map<UUID, ShortLink> shortLinks;
    public Map<String, DailyStat> dailyStats;
    public ArrayDeque<String> routeKeyPool;
    public Set<String> retiredRouteKeys;

    public DatabaseSnapshot() {
        initializeMissingFields();
    }

    @Serial
    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        initializeMissingFields();
    }

    private void initializeMissingFields() {
        if (users == null) {
            users = new LinkedHashMap<>();
        }
        if (sessions == null) {
            sessions = new LinkedHashMap<>();
        }
        if (shortLinks == null) {
            shortLinks = new LinkedHashMap<>();
        }
        if (dailyStats == null) {
            dailyStats = new LinkedHashMap<>();
        }
        if (routeKeyPool == null) {
            routeKeyPool = new ArrayDeque<>();
        }
        if (retiredRouteKeys == null) {
            retiredRouteKeys = new LinkedHashSet<>();
        }
    }
}
