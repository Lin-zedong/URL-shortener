package urlshortener;

import urlshortener.bootstrap.DistributedAllInOneMain;
import urlshortener.config.AppConfig;
import urlshortener.service.AnalyticsService;
import urlshortener.service.BackgroundWorker;
import urlshortener.service.ClickEventQueue;
import urlshortener.service.HotLinkCache;
import urlshortener.service.RedirectService;
import urlshortener.service.RouteKeyPoolService;
import urlshortener.service.SessionService;
import urlshortener.service.ShortLinkService;
import urlshortener.service.UserService;
import urlshortener.store.FileDataStore;
import urlshortener.util.LogUtils;
import urlshortener.util.PasswordHasher;
import urlshortener.web.EdgeProxyServer;
import urlshortener.web.ManagementServer;
import urlshortener.web.RedirectServer;

import java.nio.file.Files;

/**
 * Основная точка входа.
 * По умолчанию запускает демо-режим с файловым snapshot.
 * Если APP_MODE=distributed-all-in-one, используется внешний PostgreSQL/Redis.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        configureConsoleEncoding();
        if ("distributed-all-in-one".equalsIgnoreCase(AppConfig.DEPLOYMENT_MODE)) {
            DistributedAllInOneMain.main(args);
            return;
        }

        Files.createDirectories(AppConfig.DATA_DIR);
        Files.createDirectories(AppConfig.LOG_DIR);
        LogUtils.init();

        FileDataStore fileDataStore = new FileDataStore(AppConfig.SNAPSHOT_FILE);
        PasswordHasher passwordHasher = new PasswordHasher();
        HotLinkCache hotLinkCache = new HotLinkCache(AppConfig.HOT_CACHE_SIZE, AppConfig.HOT_CACHE_TTL);
        ClickEventQueue clickEventQueue = new ClickEventQueue();

        UserService userService = new UserService(fileDataStore, passwordHasher);
        SessionService sessionService = new SessionService(fileDataStore, userService);
        RouteKeyPoolService routeKeyPoolService = new RouteKeyPoolService(fileDataStore);
        ShortLinkService shortLinkService = new ShortLinkService(fileDataStore, routeKeyPoolService, hotLinkCache);
        RedirectService redirectService = new RedirectService(fileDataStore, hotLinkCache, clickEventQueue);
        AnalyticsService analyticsService = new AnalyticsService(clickEventQueue, fileDataStore);
        BackgroundWorker backgroundWorker = new BackgroundWorker(
                analyticsService,
                shortLinkService,
                routeKeyPoolService,
                sessionService
        );

        routeKeyPoolService.ensurePoolTarget();

        ManagementServer managementServer = new ManagementServer(
                userService,
                sessionService,
                shortLinkService,
                analyticsService
        );
        RedirectServer redirectServer = new RedirectServer(redirectService);
        EdgeProxyServer edgeProxyServer = new EdgeProxyServer();

        backgroundWorker.start();
        managementServer.start();
        redirectServer.start();
        edgeProxyServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LogUtils.info("Получен сигнал завершения работы.");
            edgeProxyServer.stop();
            redirectServer.stop();
            managementServer.stop();
            backgroundWorker.stop();
        }));

        LogUtils.info("Система готова к работе.");
        LogUtils.info("Откройте " + AppConfig.BASE_URL + " в браузере.");
    }

    private static void configureConsoleEncoding() {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Приложение продолжит работу с кодировкой, заданной JVM или IDE.
        }
    }
}
