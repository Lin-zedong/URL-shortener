package urlshortener.bootstrap;

import urlshortener.config.AppConfig;
import urlshortener.util.LogUtils;
import urlshortener.web.RedirectServer;

import java.nio.file.Files;

/**
 * Отдельный процесс redirect-service.
 */
public final class RedirectMain {

    private RedirectMain() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppConfig.LOG_DIR);
        LogUtils.init();

        DistributedComponentFactory.Components components = DistributedComponentFactory.create();
        RedirectServer redirectServer = new RedirectServer(components.redirectService());
        redirectServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            redirectServer.stop();
            components.close();
        }));
        LogUtils.info("redirect-service готов к работе на порту " + AppConfig.REDIRECT_PORT);
    }
}
