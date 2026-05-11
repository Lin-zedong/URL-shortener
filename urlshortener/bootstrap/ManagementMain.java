package urlshortener.bootstrap;

import urlshortener.config.AppConfig;
import urlshortener.util.LogUtils;
import urlshortener.web.ManagementServer;

import java.nio.file.Files;

/**
 * Отдельный процесс management-service.
 */
public final class ManagementMain {

    private ManagementMain() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppConfig.LOG_DIR);
        LogUtils.init();

        DistributedComponentFactory.Components components = DistributedComponentFactory.create();
        components.routeKeyPoolService().replenishIfNeeded();
        ManagementServer managementServer = new ManagementServer(
                components.userService(),
                components.sessionService(),
                components.shortLinkService(),
                components.analyticsService()
        );
        managementServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            managementServer.stop();
            components.close();
        }));
        LogUtils.info("management-service готов к работе на порту " + AppConfig.MANAGEMENT_PORT);
    }
}
