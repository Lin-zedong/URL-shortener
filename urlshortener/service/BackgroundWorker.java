package urlshortener.service;

import urlshortener.util.LogUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Фоновый процесс для агрегации статистики, истечения ссылок, пополнения пула ключей и очистки сессий.
 */
public final class BackgroundWorker {

    private final AnalyticsService analyticsService;
    private final ShortLinkService shortLinkService;
    private final RouteKeyPoolService routeKeyPoolService;
    private final SessionService sessionService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public BackgroundWorker(
            AnalyticsService analyticsService,
            ShortLinkService shortLinkService,
            RouteKeyPoolService routeKeyPoolService,
            SessionService sessionService
    ) {
        this.analyticsService = analyticsService;
        this.shortLinkService = shortLinkService;
        this.routeKeyPoolService = routeKeyPoolService;
        this.sessionService = sessionService;
    }

    /**
     * Запускает периодический цикл обслуживания фоновых задач.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                analyticsService.flushClickEvents();
            } catch (Exception ex) {
                LogUtils.error("WORKER_STATS_FLUSH", null, "Не удалось выгрузить события кликов", ex);
            }
        }, 5, 10, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                int expired = shortLinkService.expireLinks();
                if (expired > 0) {
                    LogUtils.info("Просроченные ссылки переведены в статус EXPIRED: " + expired);
                }
            } catch (Exception ex) {
                LogUtils.error("WORKER_EXPIRE_SWEEP", null, "Не удалось обработать просроченные ссылки", ex);
            }
        }, 10, 30, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                routeKeyPoolService.replenishIfNeeded();
            } catch (Exception ex) {
                LogUtils.error("WORKER_POOL_FILL", null, "Не удалось пополнить пул route_key", ex);
            }
        }, 3, 30, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                int cleaned = sessionService.cleanupExpiredSessions();
                if (cleaned > 0) {
                    LogUtils.info("Просроченные сессии удалены: " + cleaned);
                }
            } catch (Exception ex) {
                LogUtils.error("WORKER_SESSION_CLEANUP", null, "Не удалось очистить просроченные сессии", ex);
            }
        }, 60, 300, TimeUnit.SECONDS);
    }

    /**
     * Останавливает фоновый поток при завершении приложения или теста.
     */
    public void stop() {
        scheduler.shutdownNow();
    }
}
