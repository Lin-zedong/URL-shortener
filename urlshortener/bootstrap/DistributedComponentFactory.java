package urlshortener.bootstrap;

import urlshortener.service.AnalyticsService;
import urlshortener.service.ClickEventBus;
import urlshortener.service.LinkCache;
import urlshortener.service.RedirectService;
import urlshortener.service.RouteKeyPoolService;
import urlshortener.service.SessionService;
import urlshortener.service.ShortLinkService;
import urlshortener.service.UserService;
import urlshortener.service.redis.RedisClickEventBus;
import urlshortener.service.redis.RedisLinkCache;
import urlshortener.store.PostgresDataStore;
import urlshortener.util.PasswordHasher;

import java.io.Closeable;

/**
 * Фабрика сервисов для распределённого режима с PostgreSQL и Redis.
 */
public final class DistributedComponentFactory {

    private DistributedComponentFactory() {
    }

    public static Components create() {
        PostgresDataStore dataStore = new PostgresDataStore();
        LinkCache linkCache = new RedisLinkCache();
        ClickEventBus clickEventBus = new RedisClickEventBus();
        PasswordHasher passwordHasher = new PasswordHasher();
        UserService userService = new UserService(dataStore, passwordHasher);
        SessionService sessionService = new SessionService(dataStore, userService);
        RouteKeyPoolService routeKeyPoolService = new RouteKeyPoolService(dataStore);
        ShortLinkService shortLinkService = new ShortLinkService(dataStore, routeKeyPoolService, linkCache);
        RedirectService redirectService = new RedirectService(dataStore, linkCache, clickEventBus);
        AnalyticsService analyticsService = new AnalyticsService(clickEventBus, dataStore);
        return new Components(dataStore, linkCache, clickEventBus, userService, sessionService, routeKeyPoolService, shortLinkService, redirectService, analyticsService);
    }

    public record Components(
            PostgresDataStore dataStore,
            LinkCache linkCache,
            ClickEventBus clickEventBus,
            UserService userService,
            SessionService sessionService,
            RouteKeyPoolService routeKeyPoolService,
            ShortLinkService shortLinkService,
            RedirectService redirectService,
            AnalyticsService analyticsService
    ) implements Closeable {
        @Override
        public void close() {
            try {
                dataStore.close();
            } catch (Exception ignored) {
                // Ошибки закрытия не должны мешать остановке приложения.
            }
        }
    }
}
