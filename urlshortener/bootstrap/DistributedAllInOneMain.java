package urlshortener.bootstrap;

import urlshortener.config.AppConfig;
import urlshortener.service.BackgroundWorker;
import urlshortener.util.LogUtils;
import urlshortener.web.EdgeProxyServer;
import urlshortener.web.ManagementServer;
import urlshortener.web.RedirectServer;

import java.nio.file.Files;

/**
 * Удобный локальный режим: edge, management, redirect и worker в одном JVM, но с внешними PostgreSQL и Redis.
 */
public final class DistributedAllInOneMain {

    private DistributedAllInOneMain() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppConfig.LOG_DIR);
        LogUtils.init();

        DistributedComponentFactory.Components components = DistributedComponentFactory.create();
        components.routeKeyPoolService().ensurePoolTarget();

        ManagementServer managementServer = new ManagementServer(
                components.userService(),
                components.sessionService(),
                components.shortLinkService(),
                components.analyticsService()
        );
        RedirectServer redirectServer = new RedirectServer(components.redirectService());
        EdgeProxyServer edgeProxyServer = new EdgeProxyServer();
        BackgroundWorker worker = new BackgroundWorker(
                components.analyticsService(),
                components.shortLinkService(),
                components.routeKeyPoolService(),
                components.sessionService()
        );

        worker.start();
        managementServer.start();
        redirectServer.start();
        edgeProxyServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            edgeProxyServer.stop();
            redirectServer.stop();
            managementServer.stop();
            worker.stop();
            components.close();
        }));

        LogUtils.info("Распределённый all-in-one режим запущен. Откройте " + AppConfig.BASE_URL);
    }
}
