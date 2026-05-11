package urlshortener.bootstrap;

import urlshortener.config.AppConfig;
import urlshortener.service.BackgroundWorker;
import urlshortener.util.LogUtils;

import java.nio.file.Files;

/**
 * Отдельный процесс background-worker.
 */
public final class WorkerMain {

    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppConfig.LOG_DIR);
        LogUtils.init();

        DistributedComponentFactory.Components components = DistributedComponentFactory.create();
        components.routeKeyPoolService().ensurePoolTarget();
        BackgroundWorker worker = new BackgroundWorker(
                components.analyticsService(),
                components.shortLinkService(),
                components.routeKeyPoolService(),
                components.sessionService()
        );
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.stop();
            components.close();
        }));
        LogUtils.info("background-worker запущен");
        Thread.currentThread().join();
    }
}
