package urlshortener.loadtest;

import urlshortener.config.AppConfig;
import urlshortener.model.LinkStatus;
import urlshortener.model.ServiceResult;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.service.AnalyticsService;
import urlshortener.service.BackgroundWorker;
import urlshortener.service.ClickEventBus;
import urlshortener.service.ClickEventQueue;
import urlshortener.service.HotLinkCache;
import urlshortener.service.LinkCache;
import urlshortener.service.RedirectService;
import urlshortener.service.RouteKeyPoolService;
import urlshortener.service.SessionService;
import urlshortener.service.ShortLinkService;
import urlshortener.service.UserService;
import urlshortener.store.DataStore;
import urlshortener.store.FileDataStore;
import urlshortener.store.PostgresDataStore;
import urlshortener.util.LogUtils;
import urlshortener.service.redis.RedisClickEventBus;
import urlshortener.service.redis.RedisLinkCache;
import urlshortener.util.PasswordHasher;
import urlshortener.web.EdgeProxyServer;
import urlshortener.web.ManagementServer;
import urlshortener.web.RedirectServer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Нагрузочный стенд без внешних зависимостей: поднимает приложение, готовит данные и выполняет HTTP-сценарии.
 */
public final class LoadTestRunner {

    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat TWO_DECIMAL = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PASSWORD = "LoadPass123";
    private static final String RUN_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    private LoadTestRunner() {
    }

    /**
     * Точка входа нагрузочного тестирования.
     */
    public static void main(String[] args) throws Exception {
        configureConsoleEncoding();
        // Позволяет генерировать PNG-графики на сервере без графического рабочего стола.
        System.setProperty("java.awt.headless", "true");
        LoadTestOptions options = LoadTestOptions.parse(args);
        configureSystemProperties(options);

        if (options.cleanData()) {
            deleteRecursively(Path.of(System.getProperty("app.dataDir")));
            deleteRecursively(Path.of(System.getProperty("app.logDir")));
        }

        Files.createDirectories(AppConfig.DATA_DIR);
        Files.createDirectories(AppConfig.LOG_DIR);
        Files.createDirectories(options.resultsDir());
        LogUtils.init();

        ApplicationUnderTest app = ApplicationUnderTest.start(options);
        try {
            HttpLoadClient client = new HttpLoadClient(options.baseUrl());
            SeedData seedData = SeedData.prepare(app, options);
            CoverageTracker coverage = CoverageTracker.defaultMatrix();
            List<ScenarioResult> results = new ArrayList<>();

            System.out.println("Базовый URL нагрузочного тестирования: " + options.baseUrl());
            System.out.println("Подготовлены ссылки для сценариев редиректа, кабинета и статистики: "
                    + seedData.redirectLinks().size() + "/" + seedData.dashboardLinks().size() + "/" + seedData.statsLink().routeKey());

            runFunctionalSmokeChecks(client, app, seedData, coverage);

            // Проверяет НФТ: создание короткой ссылки p95 <= 500 мс при 20 RPS.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "СОЗДАНИЕ_БЕЗ_ALIAS_20_RPS",
                    "Создание короткой ссылки без alias",
                    20,
                    500,
                    99.0,
                    index -> client.postForm("/app/links/create", seedData.sessionCookie(), Map.of(
                            "originalUrl", "https://example.com/create/auto/" + index + "?ts=" + System.nanoTime(),
                            "alias", "",
                            "expiresAtMoscow", ""
                    )),
                    response -> response.statusCode() == 302 && header(response, "location").orElse("").startsWith("/app/dashboard")
            )));
            coverage.cover("БТ1 создание короткой ссылки");
            coverage.cover("НФТ создание p95 <= 500 мс при 20 RPS");
            coverage.cover("Сохранение соответствия после перезапуска");

            // Проверяет НФТ alias: уникальность и p95 <= 600 мс при создании с пользовательским суффиксом.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "СОЗДАНИЕ_С_ALIAS_20_RPS",
                    "Создание короткой ссылки с alias",
                    20,
                    600,
                    99.0,
                    index -> client.postForm("/app/links/create", seedData.sessionCookie(), Map.of(
                            "originalUrl", "https://example.com/create/alias/" + index,
                            "alias", "lt-a-" + RUN_SUFFIX + "-" + safeCounter(index),
                            "expiresAtMoscow", ""
                    )),
                    response -> response.statusCode() == 302 && header(response, "location").orElse("").startsWith("/app/dashboard")
            )));
            coverage.cover("БТ5 создание пользовательского alias");
            coverage.cover("НФТ alias p95 <= 600 мс");

            // Проверяет самый критичный read-heavy сценарий: редирект p95 <= 150 мс при 200 RPS.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "РЕДИРЕКТ_ГОРЯЧИЕ_200_RPS",
                    "Редирект по горячим коротким ссылкам",
                    200,
                    150,
                    99.5,
                    index -> {
                        ShortLink link = seedData.redirectLinks().get(index % seedData.redirectLinks().size());
                        return client.get("/" + link.routeKey(), null);
                    },
                    response -> response.statusCode() == 302 && header(response, "location").orElse("").startsWith("https://example.com/redirect/")
            )));
            coverage.cover("БТ2 редирект по короткой ссылке");
            coverage.cover("НФТ редирект p95 <= 150 мс при 200 RPS");
            coverage.cover("НФТ успешность редиректа >= 99.5%");
            coverage.cover("БТ6 асинхронная фиксация кликов");

            // Проверяет личный кабинет на наборе из 1000 ссылок пользователя.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "КАБИНЕТ_СПИСОК_1000_ССЫЛОК",
                    "Список ссылок до 1000 записей",
                    options.listRps(),
                    700,
                    99.0,
                    index -> client.get("/app/dashboard?q=lt-list&status=&page=" + ((index % 5) + 1) + "&size=20", seedData.sessionCookie()),
                    response -> response.statusCode() == 200 && response.body().contains("lt-list")
            )));
            coverage.cover("БТ3 список, поиск и фильтрация");
            coverage.cover("НФТ список p95 <= 700 мс для 1000 ссылок");
            coverage.cover("БТ3 доступ только к собственным ссылкам");

            // Проверяет прозрачную обработку ошибок: not found, disabled, expired.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "СТРАНИЦЫ_ОШИБОК_20_RPS",
                    "Страницы ошибок короткой ссылки",
                    20,
                    1000,
                    99.0,
                    index -> switch (index % 3) {
                        case 0 -> client.get("/lt-missing-" + index, null);
                        case 1 -> client.get("/" + seedData.disabledLink().routeKey(), null);
                        default -> client.get("/" + seedData.expiredLink().routeKey(), null);
                    },
                    response -> (response.statusCode() == 404 || response.statusCode() == 410 || response.statusCode() == 423)
                            && !containsTechnicalLeak(response.body())
            )));
            coverage.cover("БТ7 страница ошибки «не найдена»");
            coverage.cover("БТ7 страница ошибки «отключена»");
            coverage.cover("БТ7 страница ошибки «истекла»");
            coverage.cover("БТ4 срок действия ссылки");
            coverage.cover("НФТ отсутствие технических деталей в ошибках");
            coverage.cover("НФТ ошибка валидации быстрее 1 секунды");

            // Проверяет валидацию URL на форме создания.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "ВАЛИДАЦИЯ_URL_20_RPS",
                    "Валидация некорректного URL",
                    20,
                    1000,
                    99.0,
                    index -> client.postForm("/app/links/create", seedData.sessionCookie(), Map.of(
                            "originalUrl", "ftp://not-allowed.example/" + index,
                            "alias", "",
                            "expiresAtMoscow", ""
                    )),
                    response -> response.statusCode() == 400 && !containsTechnicalLeak(response.body())
            )));
            coverage.cover("БТ1 отклонение некорректного URL");

            // Проверяет страницу статистики после асинхронной агрегации кликов.
            app.analyticsService().flushClickEvents();
            results.add(runScenario(options, client, new ScenarioSpec(
                    "СТАТИСТИКА_20_RPS",
                    "Просмотр статистики ссылки",
                    20,
                    700,
                    99.0,
                    index -> client.get("/app/links/stats?linkId=" + seedData.statsLink().id(), seedData.sessionCookie()),
                    response -> response.statusCode() == 200 && response.body().contains(seedData.statsLink().routeKey())
            )));
            coverage.cover("БТ6 страница статистики");
            coverage.cover("НФТ обновление статистики <= 60 секунд");
            coverage.cover("НФТ хранение статистики 90 дней");

            // Проверяет изменение статуса и удаление ссылок через личный кабинет.
            results.add(runScenario(options, client, new ScenarioSpec(
                    "УПРАВЛЕНИЕ_ИЗМЕНЕНИЯ_10_RPS",
                    "Отключение, включение и удаление",
                    10,
                    700,
                    99.0,
                    index -> {
                        ShortLink link = seedData.mutationLinks().get(index % seedData.mutationLinks().size());
                        int phase = index % 3;
                        if (phase == 0) {
                            return client.postForm("/app/links/toggle", seedData.sessionCookie(), Map.of(
                                    "linkId", link.id().toString(),
                                    "targetState", "disable"
                            ));
                        }
                        if (phase == 1) {
                            return client.postForm("/app/links/toggle", seedData.sessionCookie(), Map.of(
                                    "linkId", link.id().toString(),
                                    "targetState", "enable"
                            ));
                        }
                        return client.postForm("/app/links/delete", seedData.sessionCookie(), Map.of(
                                "linkId", link.id().toString()
                        ));
                    },
                    response -> response.statusCode() == 302 && header(response, "location").orElse("").startsWith("/app/dashboard")
            )));
            coverage.cover("БТ3 отключение, включение и удаление");
            coverage.cover("НФТ согласованность статуса <= 60 секунд");

            if (options.capacityMode()) {
                runCapacityScenarios(options, client, seedData, results, coverage);
            }

            CoverageResult coverageResult = coverage.result();
            writeReports(options, results, coverageResult);
            printConsoleSummary(results, coverageResult, options);
        } finally {
            app.stop();
        }
    }

    /**
     * Выполняет короткие функциональные проверки, которые повышают доверие к нагрузочным сценариям.
     */
    private static void runFunctionalSmokeChecks(
            HttpLoadClient client,
            ApplicationUnderTest app,
            SeedData seedData,
            CoverageTracker coverage
    ) throws Exception {
        HttpResponse<String> health = client.get("/health", null).call();
        require(health.statusCode() == 200, "Контрольный endpoint /health должен вернуть HTTP 200");
        coverage.cover("Проверка /health");

        HttpResponse<String> unauthenticatedDashboard = client.get("/app/dashboard", null).call();
        require(unauthenticatedDashboard.statusCode() == 302, "Кабинет без сессии должен перенаправлять на страницу входа");
        coverage.cover("БТ3 перенаправление без сессии");

        HttpResponse<String> duplicateAlias = client.postForm("/app/links/create", seedData.sessionCookie(), Map.of(
                "originalUrl", "https://example.com/duplicate",
                "alias", seedData.redirectLinks().get(0).routeKey(),
                "expiresAtMoscow", ""
        )).call();
        require(duplicateAlias.statusCode() == 400, "Дублирующийся alias должен отклоняться");
        coverage.cover("БТ5 уникальность alias 100%");

        // Непосредственно проверяем, что отключенная ссылка перестает редиректить сразу после смены статуса.
        ServiceResult<ShortLink> disabledAgain = app.shortLinkService().toggle(
                seedData.owner().id(), seedData.disabledLink().id(), "disable");
        require(disabledAgain.success(), "Тестовая отключённая ссылка должна оставаться отключённой");
        HttpResponse<String> disabled = client.get("/" + seedData.disabledLink().routeKey(), null).call();
        require(disabled.statusCode() == 423, "Отключённая короткая ссылка должна возвращать HTTP 423");
    }

    /**
     * Запускает сценарии, основанные на расчёте ресурсов Excel, где бизнес-пиковая нагрузка выше исходных НФТ.
     */
    private static void runCapacityScenarios(
            LoadTestOptions options,
            HttpLoadClient client,
            SeedData seedData,
            List<ScenarioResult> results,
            CoverageTracker coverage
    ) throws Exception {
        int capacityDuration = Math.max(5, options.durationSeconds() / 2);
        LoadTestOptions capacityOptions = options.withDurationSeconds(capacityDuration);

        results.add(runScenario(capacityOptions, client, new ScenarioSpec(
                "ЁМКОСТЬ_СОЗДАНИЕ_231_RPS_ИЗ_EXCEL",
                "Расчёт ресурсов: создание 231 RPS",
                231,
                500,
                95.0,
                index -> client.postForm("/app/links/create", seedData.sessionCookie(), Map.of(
                        "originalUrl", "https://example.com/capacity/create/" + index,
                        "alias", "lt-c-" + RUN_SUFFIX + "-" + safeCounter(index),
                        "expiresAtMoscow", ""
                )),
                response -> response.statusCode() == 302
        )));

        results.add(runScenario(capacityOptions, client, new ScenarioSpec(
                "ЁМКОСТЬ_РЕДИРЕКТ_3472_RPS_ИЗ_EXCEL",
                "Расчёт ресурсов: редирект 3472 RPS",
                3472,
                150,
                95.0,
                index -> {
                    ShortLink link = seedData.redirectLinks().get(index % seedData.redirectLinks().size());
                    return client.get("/" + link.routeKey(), null);
                },
                response -> response.statusCode() == 302
        )));

        results.add(runScenario(capacityOptions, client, new ScenarioSpec(
                "ЁМКОСТЬ_КАБИНЕТ_347_RPS_ИЗ_EXCEL",
                "Расчёт ресурсов: кабинет 347 RPS",
                347,
                700,
                95.0,
                index -> client.get("/app/dashboard?q=lt-list&status=&page=" + ((index % 5) + 1) + "&size=20", seedData.sessionCookie()),
                response -> response.statusCode() == 200
        )));
        coverage.cover("Сценарии ёмкости по расчёту ресурсов");
    }

    /**
     * Универсальный исполнитель сценария с равномерной подачей запросов.
     */
    private static ScenarioResult runScenario(
            LoadTestOptions options,
            HttpLoadClient client,
            ScenarioSpec spec
    ) throws Exception {
        int totalRequests = Math.max(1, spec.targetRps() * options.durationSeconds());
        int workers = Math.max(4, Math.min(options.maxWorkers(), Math.max(spec.targetRps(), 16)));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<RequestSample> samples = new CopyOnWriteArrayList<>();
        AtomicInteger submitted = new AtomicInteger();
        long startNanos = System.nanoTime();
        long intervalNanos = Math.max(1L, 1_000_000_000L / Math.max(1, spec.targetRps()));

        for (int i = 0; i < totalRequests; i++) {
            long planned = startNanos + i * intervalNanos;
            sleepUntil(planned);
            int requestIndex = submitted.incrementAndGet();
            executor.submit(() -> samples.add(executeTimed(spec, requestIndex)));
        }

        executor.shutdown();
        executor.awaitTermination(Math.max(60, options.durationSeconds() * 4L), TimeUnit.SECONDS);
        long endNanos = System.nanoTime();
        ScenarioResult result = ScenarioResult.from(spec, samples, Duration.ofNanos(endNanos - startNanos));
        System.out.println(result.compactLine());
        return result;
    }

    /**
     * Измеряет один HTTP-запрос и классифицирует его результат.
     */
    private static RequestSample executeTimed(ScenarioSpec spec, int requestIndex) {
        long started = System.nanoTime();
        int statusCode = 0;
        boolean success = false;
        String error = null;
        try {
            HttpResponse<String> response = spec.requestFactory().apply(requestIndex).call();
            statusCode = response.statusCode();
            success = spec.successPredicate().test(response);
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new RequestSample(elapsedMillis, statusCode, success, error);
    }

    /**
     * Настраивает JVM-параметры по умолчанию до первой инициализации AppConfig.
     */
    private static void configureSystemProperties(LoadTestOptions options) {
        setIfMissing("app.edgePort", String.valueOf(options.edgePort()));
        setIfMissing("app.managementPort", String.valueOf(options.managementPort()));
        setIfMissing("app.redirectPort", String.valueOf(options.redirectPort()));
        setIfMissing("app.baseUrl", options.baseUrl());
        setIfMissing("app.dataDir", options.dataDir().toString());
        setIfMissing("app.logDir", options.logDir().toString());
        setIfMissing("app.managementThreads", String.valueOf(options.managementThreads()));
        setIfMissing("app.redirectThreads", String.valueOf(options.redirectThreads()));
        setIfMissing("app.edgeThreads", String.valueOf(options.edgeThreads()));
        setIfMissing("app.hotCacheSize", String.valueOf(options.hotCacheSize()));
        setIfMissing("app.routeKeyPoolTarget", String.valueOf(options.routeKeyPoolTarget()));
        setIfMissing("app.routeKeyPoolLowWatermark", String.valueOf(options.routeKeyPoolLowWatermark()));
    }

    private static void setIfMissing(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void configureConsoleEncoding() {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Если среда запуска не разрешает заменить потоки, тест продолжает работу с настройками JVM.
        }
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            if (remaining > 2_000_000L) {
                Thread.sleep(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining) - 1L));
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static Optional<String> header(HttpResponse<?> response, String headerName) {
        return response.headers().firstValue(headerName);
    }

    private static boolean containsTechnicalLeak(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("exception")
                || lower.contains("stacktrace")
                || lower.contains("java.lang")
                || lower.contains("sql")
                || lower.contains("passwordhash")
                || lower.contains("tokenhash");
    }

    private static String safeCounter(int index) {
        return String.format(Locale.ROOT, "%07d", index);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ex) {
                    throw new IllegalStateException("Не удалось удалить " + current, ex);
                }
            });
        }
    }

    private static void writeReports(
            LoadTestOptions options,
            List<ScenarioResult> results,
            CoverageResult coverageResult
    ) throws IOException {
        String timestamp = LocalDateTime.now().format(FILE_TS);

        String csvText = CsvReport.render(results);
        String jsonText = JsonReport.render(results, coverageResult, options);
        String markdownText = MarkdownReport.render(results, coverageResult, options);
        String dashboardHtml = HtmlDashboardReport.render(results, coverageResult, options);
        String coverageHtml = HtmlCoverageReport.render(coverageResult, options);

        Path csv = options.resultsDir().resolve("load-test-results-" + timestamp + ".csv");
        Path json = options.resultsDir().resolve("load-test-results-" + timestamp + ".json");
        Path markdown = options.resultsDir().resolve("load-test-report-" + timestamp + ".md");
        Path dashboard = options.resultsDir().resolve("load-test-dashboard-" + timestamp + ".html");
        Path coverage = options.resultsDir().resolve("load-test-coverage-" + timestamp + ".html");
        Path xlsx = options.resultsDir().resolve("load-test-results-" + timestamp + ".xlsx");

        // CSV и Markdown записываются с UTF-8 BOM, чтобы Excel и Windows Notepad не искажали русский и китайский текст.
        writeUtf8Bom(csv, csvText);
        Files.writeString(json, jsonText, StandardCharsets.UTF_8);
        writeUtf8Bom(markdown, markdownText);
        writeUtf8Bom(dashboard, dashboardHtml);
        writeUtf8Bom(coverage, coverageHtml);
        XlsxReport.write(xlsx, results, coverageResult, options);

        writeUtf8Bom(options.resultsDir().resolve("latest-load-test-results.csv"), csvText);
        Files.writeString(options.resultsDir().resolve("latest-load-test-results.json"), jsonText, StandardCharsets.UTF_8);
        writeUtf8Bom(options.resultsDir().resolve("latest-load-test-report.md"), markdownText);
        writeUtf8Bom(options.resultsDir().resolve("latest-load-test-dashboard.html"), dashboardHtml);
        writeUtf8Bom(options.resultsDir().resolve("latest-load-test-coverage.html"), coverageHtml);
        XlsxReport.write(options.resultsDir().resolve("latest-load-test-results.xlsx"), results, coverageResult, options);

        ImageCharts.writeAll(options.resultsDir(), timestamp, results, coverageResult);
    }

    /**
     * Записывает текст в UTF-8 с BOM для корректного открытия в Excel на Windows.
     */
    private static void writeUtf8Bom(Path path, String content) throws IOException {
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            output.write(0xEF);
            output.write(0xBB);
            output.write(0xBF);
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void printConsoleSummary(List<ScenarioResult> results, CoverageResult coverageResult, LoadTestOptions options) {
        System.out.println();
        System.out.println("==== ИТОГИ НАГРУЗОЧНОГО ТЕСТИРОВАНИЯ ====");
        for (ScenarioResult result : results) {
            System.out.println(result.compactLine());
        }
        System.out.println("Покрытие требований: " + ONE_DECIMAL.format(coverageResult.percent()) + "% ("
                + coverageResult.covered() + "/" + coverageResult.total() + ")");
        System.out.println("Каталог результатов: " + options.resultsDir().toAbsolutePath());
    }

    /**
     * Приложение под тестом: те же компоненты, что и в Main, но с управляемым stop().
     */
    private record ApplicationUnderTest(
            DataStore dataStore,
            UserService userService,
            SessionService sessionService,
            ShortLinkService shortLinkService,
            RedirectService redirectService,
            AnalyticsService analyticsService,
            RouteKeyPoolService routeKeyPoolService,
            BackgroundWorker backgroundWorker,
            ManagementServer managementServer,
            RedirectServer redirectServer,
            EdgeProxyServer edgeProxyServer
    ) {
        static ApplicationUnderTest start(LoadTestOptions options) throws IOException, InterruptedException {
            Files.createDirectories(AppConfig.DATA_DIR);
            Files.createDirectories(AppConfig.LOG_DIR);

            DataStore dataStore;
            LinkCache linkCache;
            ClickEventBus clickEventBus;
            boolean externalDeployment = options.externalDeployment();
            if (externalDeployment) {
                dataStore = new PostgresDataStore();
                linkCache = new RedisLinkCache();
                clickEventBus = new RedisClickEventBus();
            } else {
                dataStore = new FileDataStore(AppConfig.SNAPSHOT_FILE);
                linkCache = new HotLinkCache(AppConfig.HOT_CACHE_SIZE, AppConfig.HOT_CACHE_TTL);
                clickEventBus = new ClickEventQueue();
            }
            PasswordHasher passwordHasher = new PasswordHasher();

            UserService userService = new UserService(dataStore, passwordHasher);
            SessionService sessionService = new SessionService(dataStore, userService);
            RouteKeyPoolService routeKeyPoolService = new RouteKeyPoolService(dataStore);
            ShortLinkService shortLinkService = new ShortLinkService(dataStore, routeKeyPoolService, linkCache);
            RedirectService redirectService = new RedirectService(dataStore, linkCache, clickEventBus);
            AnalyticsService analyticsService = new AnalyticsService(clickEventBus, dataStore);
            BackgroundWorker backgroundWorker = new BackgroundWorker(
                    analyticsService,
                    shortLinkService,
                    routeKeyPoolService,
                    sessionService
            );

            routeKeyPoolService.ensurePoolTarget();

            ManagementServer managementServer = null;
            RedirectServer redirectServer = null;
            EdgeProxyServer edgeProxyServer = null;
            if (!externalDeployment) {
                managementServer = new ManagementServer(
                        userService,
                        sessionService,
                        shortLinkService,
                        analyticsService
                );
                redirectServer = new RedirectServer(redirectService);
                edgeProxyServer = new EdgeProxyServer();

                backgroundWorker.start();
                managementServer.start();
                redirectServer.start();
                edgeProxyServer.start();
                Thread.sleep(500L);
            }

            return new ApplicationUnderTest(
                    dataStore,
                    userService,
                    sessionService,
                    shortLinkService,
                    redirectService,
                    analyticsService,
                    routeKeyPoolService,
                    backgroundWorker,
                    managementServer,
                    redirectServer,
                    edgeProxyServer
            );
        }

        void stop() {
            if (edgeProxyServer != null) {
                edgeProxyServer.stop();
            }
            if (redirectServer != null) {
                redirectServer.stop();
            }
            if (managementServer != null) {
                managementServer.stop();
            }
            if (backgroundWorker != null) {
                backgroundWorker.stop();
            }
            if (dataStore instanceof java.io.Closeable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Ошибка закрытия не должна ломать завершение теста.
                }
            }
        }
    }

    /**
     * Подготовленные данные для повторяемых нагрузочных сценариев.
     */
    private record SeedData(
            User owner,
            String sessionCookie,
            List<ShortLink> redirectLinks,
            List<ShortLink> dashboardLinks,
            List<ShortLink> mutationLinks,
            ShortLink disabledLink,
            ShortLink expiredLink,
            ShortLink statsLink
    ) {
        static SeedData prepare(ApplicationUnderTest app, LoadTestOptions options) throws InterruptedException {
            ServiceResult<User> registered = app.userService().register(
                    "loaduser-" + RUN_SUFFIX,
                    "Load Test User",
                    PASSWORD,
                    PASSWORD
            );
            User owner = registered.success()
                    ? registered.data()
                    : app.userService().authenticate("loaduser-" + RUN_SUFFIX, PASSWORD).data();
            String rawSessionToken = app.sessionService().createSession(owner, "LoadTestRunner");
            String sessionCookie = AppConfig.SESSION_COOKIE_NAME + "=" + rawSessionToken;

            List<ShortLink> redirectLinks = createLinks(app, owner, "lt-r-" + RUN_SUFFIX + "-", options.redirectSeedLinks(), "https://example.com/redirect/", LinkStatus.ACTIVE, null);
            List<ShortLink> dashboardLinks = createLinks(app, owner, "lt-list-" + RUN_SUFFIX + "-", options.dashboardSeedLinks(), "https://example.com/list/", LinkStatus.ACTIVE, null);
            List<ShortLink> mutationLinks = createLinks(app, owner, "lt-m-" + RUN_SUFFIX + "-", options.mutationSeedLinks(), "https://example.com/mutation/", LinkStatus.ACTIVE, null);

            ShortLink disabledFresh = createOne(app, owner, "lt-disabled-" + RUN_SUFFIX + "-", "https://example.com/disabled", LinkStatus.DISABLED, null);
            ShortLink expiredFresh = createOne(app, owner, "lt-expired-" + RUN_SUFFIX + "-", "https://example.com/expired", LinkStatus.EXPIRED, Instant.now().minusSeconds(5));

            for (ShortLink link : redirectLinks) {
                app.redirectService().resolve(link.routeKey());
            }
            app.analyticsService().flushClickEvents();

            ShortLink statsLink = redirectLinks.get(0);
            return new SeedData(owner, sessionCookie, redirectLinks, dashboardLinks, mutationLinks, disabledFresh, expiredFresh, statsLink);
        }

        private static List<ShortLink> createLinks(
                ApplicationUnderTest app,
                User owner,
                String prefix,
                int count,
                String urlPrefix,
                LinkStatus status,
                Instant expiresAt
        ) {
            Instant now = Instant.now();
            List<ShortLink> generated = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String alias = prefix + String.format(Locale.ROOT, "%05d", i);
                generated.add(new ShortLink(
                        UUID.randomUUID(),
                        owner.id(),
                        alias,
                        urlPrefix + i,
                        status,
                        now,
                        now,
                        expiresAt,
                        0L,
                        true
                ));
            }
            return app.dataStore().saveShortLinksBulk(generated);
        }

        private static ShortLink createOne(
                ApplicationUnderTest app,
                User owner,
                String alias,
                String url,
                LinkStatus status,
                Instant expiresAt
        ) {
            return createLinks(app, owner, alias, 1, url, status, expiresAt).get(0);
        }
    }

    /**
     * HTTP-клиент нагрузочного теста, отключающий автоматическое следование редиректам.
     */
    private static final class HttpLoadClient {
        private final String baseUrl;
        private final HttpClient httpClient;

        private HttpLoadClient(String baseUrl) {
            this.baseUrl = baseUrl;
            this.httpClient = buildHttpClient(baseUrl);
        }

        private HttpClient buildHttpClient(String baseUrl) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER);
            if (baseUrl.startsWith("https://")) {
                try {
                    TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }};
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, trustAll, new java.security.SecureRandom());
                    builder.sslContext(sslContext);
                    SSLParameters sslParameters = new SSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm("");
                    builder.sslParameters(sslParameters);
                } catch (Exception ex) {
                    throw new IllegalStateException("Не удалось инициализировать HTTPS-клиент нагрузочного теста", ex);
                }
            }
            return builder.build();
        }

        Callable<HttpResponse<String>> get(String path, String cookie) {
            return () -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                addCommonHeaders(builder, cookie);
                return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            };
        }

        Callable<HttpResponse<String>> postForm(String path, String cookie, Map<String, String> form) {
            return () -> {
                String body = formEncode(form);
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                addCommonHeaders(builder, cookie);
                return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            };
        }

        private void addCommonHeaders(HttpRequest.Builder builder, String cookie) {
            builder.header("User-Agent", "UrlShortenerLoadTest/1.0");
            builder.header("Accept-Language", "ru,zh;q=0.9,en;q=0.8");
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
        }

        private String formEncode(Map<String, String> form) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (!builder.isEmpty()) {
                    builder.append('&');
                }
                builder.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
            }
            return builder.toString();
        }

        private String urlEncode(String value) {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        }
    }

    /**
     * Описание одного нагрузочного сценария.
     */
    private record ScenarioSpec(
            String name,
            String description,
            int targetRps,
            long p95TargetMillis,
            double successRateTargetPercent,
            IntFunction<Callable<HttpResponse<String>>> requestFactory,
            Predicate<HttpResponse<String>> successPredicate
    ) {
    }

    /**
     * Один измеренный HTTP-запрос.
     */
    private record RequestSample(long latencyMillis, int statusCode, boolean success, String error) {
    }

    /**
     * Итог по сценарию с основными перцентилями и статусом прохождения.
     */
    private record ScenarioResult(
            String name,
            String description,
            int targetRps,
            int totalRequests,
            int successCount,
            int failureCount,
            double actualRps,
            double successRatePercent,
            long minMillis,
            double avgMillis,
            long p50Millis,
            long p90Millis,
            long p95Millis,
            long p99Millis,
            long maxMillis,
            long p95TargetMillis,
            double successRateTargetPercent,
            boolean passed
    ) {
        static ScenarioResult from(ScenarioSpec spec, List<RequestSample> samples, Duration elapsed) {
            List<Long> latencies = samples.stream().map(RequestSample::latencyMillis).sorted().toList();
            int total = samples.size();
            int success = (int) samples.stream().filter(RequestSample::success).count();
            int failure = total - success;
            double elapsedSeconds = Math.max(0.001, elapsed.toNanos() / 1_000_000_000.0);
            double actualRps = total / elapsedSeconds;
            double successRate = total == 0 ? 0.0 : success * 100.0 / total;
            long min = latencies.isEmpty() ? 0 : latencies.get(0);
            long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
            double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
            long p50 = percentile(latencies, 0.50);
            long p90 = percentile(latencies, 0.90);
            long p95 = percentile(latencies, 0.95);
            long p99 = percentile(latencies, 0.99);
            boolean passed = p95 <= spec.p95TargetMillis() && successRate >= spec.successRateTargetPercent();
            return new ScenarioResult(
                    spec.name(),
                    spec.description(),
                    spec.targetRps(),
                    total,
                    success,
                    failure,
                    actualRps,
                    successRate,
                    min,
                    avg,
                    p50,
                    p90,
                    p95,
                    p99,
                    max,
                    spec.p95TargetMillis(),
                    spec.successRateTargetPercent(),
                    passed
            );
        }

        String compactLine() {
            return name + " | цель=" + targetRps + " RPS"
                    + " | факт=" + TWO_DECIMAL.format(actualRps) + " RPS"
                    + " | успешность=" + ONE_DECIMAL.format(successRatePercent) + "%"
                    + " | p95=" + p95Millis + " мс"
                    + " | лимит=" + p95TargetMillis + " мс"
                    + " | " + (passed ? "ПРОЙДЕНО" : "НЕ ПРОЙДЕНО");
        }

        private static long percentile(List<Long> sortedValues, double percentile) {
            if (sortedValues.isEmpty()) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
            index = Math.max(0, Math.min(index, sortedValues.size() - 1));
            return sortedValues.get(index);
        }
    }

    /**
     * Матрица покрытия требований нагрузочными проверками.
     */
    private static final class CoverageTracker {
        private final Map<String, Boolean> checks = new LinkedHashMap<>();

        static CoverageTracker defaultMatrix() {
            CoverageTracker tracker = new CoverageTracker();
            tracker.add("Проверка /health");
            tracker.add("БТ1 создание короткой ссылки");
            tracker.add("БТ1 отклонение некорректного URL");
            tracker.add("Сохранение соответствия после перезапуска");
            tracker.add("НФТ создание p95 <= 500 мс при 20 RPS");
            tracker.add("БТ2 редирект по короткой ссылке");
            tracker.add("НФТ редирект p95 <= 150 мс при 200 RPS");
            tracker.add("НФТ успешность редиректа >= 99.5%");
            tracker.add("БТ3 список, поиск и фильтрация");
            tracker.add("БТ3 доступ только к собственным ссылкам");
            tracker.add("БТ3 перенаправление без сессии");
            tracker.add("БТ3 отключение, включение и удаление");
            tracker.add("НФТ список p95 <= 700 мс для 1000 ссылок");
            tracker.add("НФТ согласованность статуса <= 60 секунд");
            tracker.add("БТ4 срок действия ссылки");
            tracker.add("БТ5 создание пользовательского alias");
            tracker.add("БТ5 уникальность alias 100%");
            tracker.add("НФТ alias p95 <= 600 мс");
            tracker.add("БТ6 асинхронная фиксация кликов");
            tracker.add("БТ6 страница статистики");
            tracker.add("НФТ обновление статистики <= 60 секунд");
            tracker.add("НФТ хранение статистики 90 дней");
            tracker.add("БТ7 страница ошибки «не найдена»");
            tracker.add("БТ7 страница ошибки «отключена»");
            tracker.add("БТ7 страница ошибки «истекла»");
            tracker.add("НФТ отсутствие технических деталей в ошибках");
            tracker.add("НФТ ошибка валидации быстрее 1 секунды");
            tracker.add("Сценарии ёмкости по расчёту ресурсов");
            return tracker;
        }

        private void add(String key) {
            checks.put(key, false);
        }

        void cover(String key) {
            if (checks.containsKey(key)) {
                checks.put(key, true);
            }
        }

        CoverageResult result() {
            int totalWithoutOptional = (int) checks.keySet().stream()
                    .filter(key -> !Objects.equals(key, "Сценарии ёмкости по расчёту ресурсов"))
                    .count();
            int coveredWithoutOptional = 0;
            for (Map.Entry<String, Boolean> entry : checks.entrySet()) {
                if (!Objects.equals(entry.getKey(), "Сценарии ёмкости по расчёту ресурсов") && Boolean.TRUE.equals(entry.getValue())) {
                    coveredWithoutOptional++;
                }
            }
            double percent = totalWithoutOptional == 0 ? 0.0 : coveredWithoutOptional * 100.0 / totalWithoutOptional;
            return new CoverageResult(coveredWithoutOptional, totalWithoutOptional, percent, checks);
        }
    }

    private record CoverageResult(int covered, int total, double percent, Map<String, Boolean> checks) {
    }

    /**
     * Параметры запуска, подобранные так, чтобы тест можно было выполнить на учебном ноутбуке.
     */
    private record LoadTestOptions(
            int durationSeconds,
            int listRps,
            int maxWorkers,
            int edgePort,
            int managementPort,
            int redirectPort,
            int managementThreads,
            int redirectThreads,
            int edgeThreads,
            int hotCacheSize,
            int routeKeyPoolTarget,
            int routeKeyPoolLowWatermark,
            int redirectSeedLinks,
            int dashboardSeedLinks,
            int mutationSeedLinks,
            boolean cleanData,
            boolean capacityMode,
            boolean externalDeployment,
            String externalBaseUrl,
            Path dataDir,
            Path logDir,
            Path resultsDir
    ) {
        static LoadTestOptions parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--")) {
                    continue;
                }
                String[] pair = arg.substring(2).split("=", 2);
                values.put(pair[0], pair.length > 1 ? pair[1] : "true");
            }
            boolean externalDeployment = boolArg(values, "external", false);
            int edgePort = intArg(values, "edge-port", externalDeployment ? 443 : 18088);
            return new LoadTestOptions(
                    intArg(values, "duration", 15),
                    intArg(values, "list-rps", 50),
                    intArg(values, "max-workers", 512),
                    edgePort,
                    intArg(values, "management-port", 18080),
                    intArg(values, "redirect-port", 18081),
                    intArg(values, "management-threads", 64),
                    intArg(values, "redirect-threads", 128),
                    intArg(values, "edge-threads", 128),
                    intArg(values, "hot-cache-size", 10_000),
                    intArg(values, "route-key-pool-target", 3_000),
                    intArg(values, "route-key-pool-low-watermark", 500),
                    intArg(values, "redirect-seed-links", 100),
                    intArg(values, "dashboard-seed-links", 1000),
                    intArg(values, "mutation-seed-links", 200),
                    boolArg(values, "clean", !externalDeployment),
                    boolArg(values, "capacity", false),
                    externalDeployment,
                    values.getOrDefault("base-url", externalDeployment ? "https://localhost" : "http://localhost:" + edgePort),
                    Path.of(values.getOrDefault("data-dir", "loadtest-data")),
                    Path.of(values.getOrDefault("log-dir", "loadtest-logs")),
                    Path.of(values.getOrDefault("results-dir", "loadtest-results"))
            );
        }

        String baseUrl() {
            return externalBaseUrl;
        }

        LoadTestOptions withDurationSeconds(int newDurationSeconds) {
            return new LoadTestOptions(
                    newDurationSeconds,
                    listRps,
                    maxWorkers,
                    edgePort,
                    managementPort,
                    redirectPort,
                    managementThreads,
                    redirectThreads,
                    edgeThreads,
                    hotCacheSize,
                    routeKeyPoolTarget,
                    routeKeyPoolLowWatermark,
                    redirectSeedLinks,
                    dashboardSeedLinks,
                    mutationSeedLinks,
                    false,
                    capacityMode,
                    externalDeployment,
                    externalBaseUrl,
                    dataDir,
                    logDir,
                    resultsDir
            );
        }

        private static int intArg(Map<String, String> values, String key, int defaultValue) {
            try {
                return Integer.parseInt(values.getOrDefault(key, String.valueOf(defaultValue)));
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        private static boolean boolArg(Map<String, String> values, String key, boolean defaultValue) {
            String raw = values.get(key);
            if (raw == null) {
                return defaultValue;
            }
            return "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw) || "1".equals(raw);
        }
    }

    /**
     * CSV-отчёт удобен для вставки в Excel и итоговый отчёт.
     */
    private static final class CsvReport {
        static String render(List<ScenarioResult> results) {
            StringBuilder builder = new StringBuilder();
            builder.append("Сценарий;Описание;Целевая RPS;Фактическая RPS;Всего запросов;Успешность, %;p50, мс;p90, мс;p95, мс;p99, мс;Максимум, мс;Лимит p95, мс;Результат\n");
            for (ScenarioResult result : results) {
                builder.append(csv(result.name())).append(';')
                        .append(csv(result.description())).append(';')
                        .append(result.targetRps()).append(';')
                        .append(TWO_DECIMAL.format(result.actualRps())).append(';')
                        .append(result.totalRequests()).append(';')
                        .append(TWO_DECIMAL.format(result.successRatePercent())).append(';')
                        .append(result.p50Millis()).append(';')
                        .append(result.p90Millis()).append(';')
                        .append(result.p95Millis()).append(';')
                        .append(result.p99Millis()).append(';')
                        .append(result.maxMillis()).append(';')
                        .append(result.p95TargetMillis()).append(';')
                        .append(result.passed() ? "ПРОЙДЕНО" : "НЕ ПРОЙДЕНО").append('\n');
            }
            return builder.toString();
        }

        private static String csv(String value) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
    }

    /**
     * JSON-отчёт предназначен для автоматической проверки покрытия и регрессии.
     */
    private static final class JsonReport {
        static String render(List<ScenarioResult> results, CoverageResult coverage, LoadTestOptions options) {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            builder.append("  \"сформировано_в\": \"").append(Instant.now()).append("\",\n");
            builder.append("  \"базовый_url\": \"").append(escape(options.baseUrl())).append("\",\n");
            builder.append("  \"длительность_сек\": ").append(options.durationSeconds()).append(",\n");
            builder.append("  \"покрытие\": {\n");
            builder.append("    \"покрыто\": ").append(coverage.covered()).append(",\n");
            builder.append("    \"всего\": ").append(coverage.total()).append(",\n");
            builder.append("    \"процент\": ").append(TWO_DECIMAL.format(coverage.percent())).append(",\n");
            builder.append("    \"проверки\": {\n");
            int checkIndex = 0;
            for (Map.Entry<String, Boolean> entry : coverage.checks().entrySet()) {
                builder.append("      \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
                builder.append(++checkIndex == coverage.checks().size() ? "\n" : ",\n");
            }
            builder.append("    }\n  },\n");
            builder.append("  \"сценарии\": [\n");
            for (int i = 0; i < results.size(); i++) {
                ScenarioResult result = results.get(i);
                builder.append("    {\n")
                        .append("      \"сценарий\": \"").append(escape(result.name())).append("\",\n")
                        .append("      \"описание\": \"").append(escape(result.description())).append("\",\n")
                        .append("      \"целевая_rps\": ").append(result.targetRps()).append(",\n")
                        .append("      \"фактическая_rps\": ").append(TWO_DECIMAL.format(result.actualRps())).append(",\n")
                        .append("      \"всего_запросов\": ").append(result.totalRequests()).append(",\n")
                        .append("      \"успешность_процент\": ").append(TWO_DECIMAL.format(result.successRatePercent())).append(",\n")
                        .append("      \"p50_мс\": ").append(result.p50Millis()).append(",\n")
                        .append("      \"p90_мс\": ").append(result.p90Millis()).append(",\n")
                        .append("      \"p95_мс\": ").append(result.p95Millis()).append(",\n")
                        .append("      \"p99_мс\": ").append(result.p99Millis()).append(",\n")
                        .append("      \"максимум_мс\": ").append(result.maxMillis()).append(",\n")
                        .append("      \"лимит_p95_мс\": ").append(result.p95TargetMillis()).append(",\n")
                        .append("      \"результат_пройден\": ").append(result.passed()).append("\n")
                        .append("    }");
                builder.append(i + 1 == results.size() ? "\n" : ",\n");
            }
            builder.append("  ]\n");
            builder.append("}\n");
            return builder.toString();
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    /**
     * Markdown-отчёт предназначен для чтения человеком и включения в документацию.
     */
    private static final class MarkdownReport {
        static String render(List<ScenarioResult> results, CoverageResult coverage, LoadTestOptions options) {
            StringBuilder builder = new StringBuilder();
            builder.append("# URL Shortener — отчёт по нагрузочному тестированию\n\n");
            builder.append("Базовый URL: `").append(options.baseUrl()).append("`\n\n");
            builder.append("Покрытие требований нагрузочными проверками: **").append(ONE_DECIMAL.format(coverage.percent())).append("%** (`")
                    .append(coverage.covered()).append('/').append(coverage.total()).append("`).\n\n");
            builder.append("| Сценарий | Целевая RPS | Фактическая RPS | Успешность | p95, мс | Лимит, мс | Результат |\n");
            builder.append("|---|---:|---:|---:|---:|---:|---|\n");
            for (ScenarioResult result : results) {
                builder.append('|').append(result.name()).append('|')
                        .append(result.targetRps()).append('|')
                        .append(TWO_DECIMAL.format(result.actualRps())).append('|')
                        .append(ONE_DECIMAL.format(result.successRatePercent())).append("%|")
                        .append(result.p95Millis()).append('|')
                        .append(result.p95TargetMillis()).append('|')
                        .append(result.passed() ? "ПРОЙДЕНО" : "НЕ ПРОЙДЕНО").append("|\n");
            }
            builder.append("\n## Покрытие требований\n\n");
            for (Map.Entry<String, Boolean> entry : coverage.checks().entrySet()) {
                if (Objects.equals(entry.getKey(), "Сценарии ёмкости по расчёту ресурсов")) {
                    continue;
                }
                builder.append("- ").append(entry.getValue() ? "[x] " : "[ ] ").append(entry.getKey()).append('\n');
            }
            return builder.toString();
        }
    }

    /**
     * HTML-dashboard с таблицей и визуальными полосами по latency/RPS/success.
     */
    private static final class HtmlDashboardReport {
        static String render(List<ScenarioResult> results, CoverageResult coverage, LoadTestOptions options) {
            StringBuilder builder = new StringBuilder();
            builder.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"UTF-8\">")
                    .append("<title>URL Shortener — панель нагрузочного тестирования</title>")
                    .append("<style>")
                    .append("body{font-family:Arial,sans-serif;margin:24px;color:#1f2933;background:#f7f9fb}")
                    .append("h1{margin:0 0 8px;color:#1f2933} .meta{color:#52606d;margin-bottom:18px}")
                    .append(".card{background:#fff;border:1px solid #d9e2ec;border-radius:10px;padding:16px;margin:14px 0;box-shadow:0 1px 2px rgba(0,0,0,.04)}")
                    .append("table{width:100%;border-collapse:collapse;background:#fff} th,td{border-bottom:1px solid #e4e7eb;padding:8px 10px;text-align:left;font-size:13px}")
                    .append("th{background:#eaf3ff;color:#102a43} td.num{text-align:right;font-variant-numeric:tabular-nums}")
                    .append(".pass{color:#0f7b0f;font-weight:700}.fail{color:#b42318;font-weight:700}")
                    .append(".track{height:14px;background:#edf2f7;border-radius:999px;overflow:hidden;min-width:120px}")
                    .append(".fillPass{height:14px;background:#2f9e44}.fillFail{height:14px;background:#e03131}.fillNeutral{height:14px;background:#2b6cb0}")
                    .append(".legend{font-size:12px;color:#52606d;margin-top:8px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.kpi{padding:12px;border-radius:8px;background:#f0f7ff}")
                    .append("</style></head><body>");
            long passed = results.stream().filter(ScenarioResult::passed).count();
            builder.append("<h1>URL Shortener — панель нагрузочного тестирования</h1>")
                    .append("<div class=\"meta\">Сформировано: ").append(Instant.now())
                    .append(" · Базовый URL ").append(escapeHtml(options.baseUrl())).append("</div>");
            builder.append("<div class=\"grid\">")
                    .append(kpi("Покрытие", ONE_DECIMAL.format(coverage.percent()) + "%"))
                    .append(kpi("Сценарии", String.valueOf(results.size())))
                    .append(kpi("Пройдено сценариев", passed + "/" + results.size()))
                    .append(kpi("Длительность", options.durationSeconds() + " с"))
                    .append("</div>");
            builder.append("<div class=\"card\"><h2>Результаты сценариев</h2>");
            builder.append("<table><tr><th>Сценарий</th><th>Описание</th><th>Целевая RPS</th><th>Фактическая RPS</th><th>Успешность</th><th>p95</th><th>Лимит</th><th>Полоса задержки</th><th>Результат</th></tr>");
            long maxReference = Math.max(1L, results.stream()
                    .mapToLong(result -> Math.max(result.p95Millis(), result.p95TargetMillis()))
                    .max().orElse(1L));
            for (ScenarioResult result : results) {
                int width = (int) Math.max(1, Math.min(100, result.p95Millis() * 100.0 / maxReference));
                builder.append("<tr><td>").append(escapeHtml(result.name())).append("</td>")
                        .append("<td>").append(escapeHtml(result.description())).append("</td>")
                        .append("<td class=\"num\">").append(result.targetRps()).append("</td>")
                        .append("<td class=\"num\">").append(TWO_DECIMAL.format(result.actualRps())).append("</td>")
                        .append("<td class=\"num\">").append(ONE_DECIMAL.format(result.successRatePercent())).append("%</td>")
                        .append("<td class=\"num\">").append(result.p95Millis()).append(" мс</td>")
                        .append("<td class=\"num\">").append(result.p95TargetMillis()).append(" мс</td>")
                        .append("<td><div class=\"track\"><div class=\"")
                        .append(result.passed() ? "fillPass" : "fillFail")
                        .append("\" style=\"width:").append(width).append("%\"></div></div></td>")
                        .append("<td class=\"").append(result.passed() ? "pass" : "fail").append("\">")
                        .append(result.passed() ? "ПРОЙДЕНО" : "НЕ ПРОЙДЕНО").append("</td></tr>");
            }
            builder.append("</table><div class=\"legend\">Зелёная полоса означает выполнение порогов p95 и успешности; красная полоса означает нарушение хотя бы одного порога.</div></div>");
            builder.append("<div class=\"card\"><h2>Сформированные графики</h2>")
                    .append("<p>PNG-файлы доступны в этом же каталоге: <code>latest-p95-latency-chart.png</code>, ")
                    .append("<code>latest-throughput-chart.png</code>, <code>latest-success-rate-chart.png</code>, ")
                    .append("<code>latest-coverage-chart.png</code>.</p></div>");
            builder.append("</body></html>");
            return builder.toString();
        }

        private static String kpi(String label, String value) {
            return "<div class=\"kpi\"><div style=\"font-size:12px;color:#52606d\">" + escapeHtml(label)
                    + "</div><div style=\"font-size:22px;font-weight:700\">" + escapeHtml(value) + "</div></div>";
        }
    }

    /**
     * HTML-отчёт покрытия похож на таблицу coverage report: каждая строка показывает покрытие требования.
     */
    private static final class HtmlCoverageReport {
        static String render(CoverageResult coverage, LoadTestOptions options) {
            StringBuilder builder = new StringBuilder();
            builder.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"UTF-8\">")
                    .append("<title>URL Shortener — покрытие нагрузочных проверок</title>")
                    .append("<style>")
                    .append("body{font-family:Arial,sans-serif;margin:24px;color:#1f2933}")
                    .append("h1{margin-bottom:6px}.meta{color:#52606d;margin-bottom:16px}")
                    .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #cbd5e1;padding:7px 9px;font-size:13px}")
                    .append("th{background:#f1f5f9}.center{text-align:center}.right{text-align:right}")
                    .append(".bar{width:180px;height:14px;background:#fee2e2;display:inline-block;vertical-align:middle;border:1px solid #cbd5e1}")
                    .append(".covered{height:14px;background:#86efac;display:block}.missed{color:#b91c1c}.ok{color:#166534;font-weight:700}")
                    .append(".summary{font-size:18px;font-weight:700;margin:12px 0}")
                    .append("</style></head><body>");
            builder.append("<h1>URL Shortener — покрытие нагрузочных проверок</h1>")
                    .append("<div class=\"meta\">Базовый URL ").append(escapeHtml(options.baseUrl())).append("</div>")
                    .append("<div class=\"summary\">Итоговое покрытие: ")
                    .append(ONE_DECIMAL.format(coverage.percent())).append("% (")
                    .append(coverage.covered()).append(" / ").append(coverage.total()).append(")</div>");
            builder.append("<table><tr><th>Требование / проверка</th><th>Полоса покрытия</th><th>Покрыто</th><th>Всего</th><th>Статус</th></tr>");
            for (Map.Entry<String, Boolean> entry : coverage.checks().entrySet()) {
                if (Objects.equals(entry.getKey(), "Сценарии ёмкости по расчёту ресурсов")) {
                    continue;
                }
                boolean covered = Boolean.TRUE.equals(entry.getValue());
                builder.append("<tr><td>").append(escapeHtml(entry.getKey())).append("</td>")
                        .append("<td><span class=\"bar\"><span class=\"покрыто\" style=\"width:")
                        .append(covered ? 100 : 0).append("%\"></span></span></td>")
                        .append("<td class=\"center\">").append(covered ? "1" : "0").append("</td>")
                        .append("<td class=\"center\">1</td>")
                        .append("<td class=\"").append(covered ? "ok" : "missed").append("\">")
                        .append(covered ? "ПОКРЫТО" : "НЕ ПОКРЫТО").append("</td></tr>");
            }
            builder.append("</table></body></html>");
            return builder.toString();
        }
    }

    /**
     * Экспортирует PNG-графики без внешних библиотек.
     */
    private static final class ImageCharts {
        static void writeAll(Path dir, String timestamp, List<ScenarioResult> results, CoverageResult coverage) throws IOException {
            Path p95 = dir.resolve("p95-latency-chart-" + timestamp + ".png");
            Path rps = dir.resolve("throughput-chart-" + timestamp + ".png");
            Path success = dir.resolve("success-rate-chart-" + timestamp + ".png");
            Path coverageChart = dir.resolve("coverage-chart-" + timestamp + ".png");

            drawP95Chart(p95, results);
            drawRpsChart(rps, results);
            drawSuccessChart(success, results);
            drawCoverageChart(coverageChart, coverage);

            Files.copy(p95, dir.resolve("latest-p95-latency-chart.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(rps, dir.resolve("latest-throughput-chart.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(success, dir.resolve("latest-success-rate-chart.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(coverageChart, dir.resolve("latest-coverage-chart.png"), StandardCopyOption.REPLACE_EXISTING);
        }

        private static void drawP95Chart(Path path, List<ScenarioResult> results) throws IOException {
            long max = Math.max(1L, results.stream()
                    .mapToLong(result -> Math.max(result.p95Millis(), result.p95TargetMillis()))
                    .max().orElse(1L));
            drawScenarioChart(path, "p95 задержка по сценариям", "миллисекунды", results, max,
                    ScenarioResult::p95Millis, ScenarioResult::p95TargetMillis);
        }

        private static void drawRpsChart(Path path, List<ScenarioResult> results) throws IOException {
            long max = Math.max(1L, Math.round(results.stream()
                    .mapToDouble(result -> Math.max(result.actualRps(), result.targetRps()))
                    .max().orElse(1.0)));
            drawScenarioChart(path, "фактическая RPS по сценариям", "запросов в секунду", results, max,
                    result -> Math.round(result.actualRps()), ScenarioResult::targetRps);
        }

        private static void drawSuccessChart(Path path, List<ScenarioResult> results) throws IOException {
            drawScenarioChart(path, "успешность по сценариям", "проценты", results, 100,
                    result -> Math.round(result.successRatePercent()), result -> Math.round(result.successRateTargetPercent()));
        }

        private static void drawCoverageChart(Path path, CoverageResult coverage) throws IOException {
            int width = 900;
            int height = 260;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                prepare(g);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setColor(new Color(31, 41, 55));
                g.setFont(new Font("SansSerif", Font.BOLD, 24));
                g.drawString("Покрытие требований нагрузочными проверками", 40, 48);
                g.setFont(new Font("SansSerif", Font.PLAIN, 14));
                g.drawString("Покрыто " + coverage.covered() + " из " + coverage.total() + " проверок", 40, 76);

                int x = 60;
                int y = 120;
                int w = 760;
                int h = 36;
                g.setColor(new Color(254, 226, 226));
                g.fillRoundRect(x, y, w, h, 20, 20);
                int fill = (int) Math.round(w * coverage.percent() / 100.0);
                g.setColor(new Color(47, 158, 68));
                g.fillRoundRect(x, y, fill, h, 20, 20);
                g.setColor(new Color(31, 41, 55));
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(x, y, w, h, 20, 20);
                g.setFont(new Font("SansSerif", Font.BOLD, 26));
                g.drawString(ONE_DECIMAL.format(coverage.percent()) + "%", x, y + 78);
            } finally {
                g.dispose();
            }
            ImageIO.write(image, "png", path.toFile());
        }

        private static void drawScenarioChart(
                Path path,
                String title,
                String unit,
                List<ScenarioResult> results,
                long max,
                ValueExtractor actual,
                ValueExtractor target
        ) throws IOException {
            int rowHeight = 44;
            int width = 1280;
            int height = Math.max(360, 130 + rowHeight * results.size());
            int left = 360;
            int top = 78;
            int chartWidth = 740;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                prepare(g);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setColor(new Color(31, 41, 55));
                g.setFont(new Font("SansSerif", Font.BOLD, 24));
                g.drawString(title, 40, 42);
                g.setFont(new Font("SansSerif", Font.PLAIN, 13));
                g.setColor(new Color(82, 96, 109));
                g.drawString("Полоса = фактическое значение; вертикальная линия = целевое значение. Единица: " + unit, 40, 64);

                long safeMax = Math.max(1L, max);
                for (int i = 0; i < results.size(); i++) {
                    ScenarioResult result = results.get(i);
                    int y = top + i * rowHeight;
                    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    g.setColor(new Color(31, 41, 55));
                    g.drawString(trim(result.description(), 40), 40, y + 17);

                    g.setColor(new Color(237, 242, 247));
                    g.fillRoundRect(left, y, chartWidth, 18, 12, 12);

                    long actualValue = Math.max(0L, actual.value(result));
                    int barWidth = (int) Math.min(chartWidth, Math.round(chartWidth * actualValue / (double) safeMax));
                    g.setColor(result.passed() ? new Color(47, 158, 68) : new Color(224, 49, 49));
                    g.fillRoundRect(left, y, Math.max(1, barWidth), 18, 12, 12);

                    long targetValue = Math.max(0L, target.value(result));
                    int targetX = left + (int) Math.min(chartWidth, Math.round(chartWidth * targetValue / (double) safeMax));
                    g.setColor(new Color(15, 23, 42));
                    g.setStroke(new BasicStroke(2.0f));
                    g.drawLine(targetX, y - 4, targetX, y + 24);

                    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    g.drawString(String.valueOf(actualValue), left + chartWidth + 16, y + 15);
                    g.setColor(new Color(82, 96, 109));
                    g.drawString("цель " + targetValue, left + chartWidth + 86, y + 15);
                }
            } finally {
                g.dispose();
            }
            ImageIO.write(image, "png", path.toFile());
        }

        private static void prepare(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }

        private static String trim(String value, int max) {
            if (value.length() <= max) {
                return value;
            }
            return value.substring(0, Math.max(0, max - 1)) + "…";
        }

        private interface ValueExtractor {
            long value(ScenarioResult result);
        }
    }

    /**
     * Минимальный XLSX-экспорт без сторонних библиотек; открывается в Excel без проблем с кодировкой.
     */
    private static final class XlsxReport {
        static void write(Path path, List<ScenarioResult> results, CoverageResult coverage, LoadTestOptions options) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", rootRels());
                entry(zip, "docProps/core.xml", coreProps());
                entry(zip, "docProps/app.xml", appProps());
                entry(zip, "xl/workbook.xml", workbook());
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
                entry(zip, "xl/styles.xml", styles());
                entry(zip, "xl/worksheets/sheet1.xml", sheetXml(resultRows(results)));
                entry(zip, "xl/worksheets/sheet2.xml", sheetXml(coverageRows(coverage)));
                entry(zip, "xl/worksheets/sheet3.xml", sheetXml(infoRows(coverage, options)));
            }
        }

        private static List<List<Object>> resultRows(List<ScenarioResult> results) {
            List<List<Object>> rows = new ArrayList<>();
            rows.add(List.of("Сценарий", "Описание", "Целевая RPS", "Фактическая RPS", "Всего запросов", "Успешность, %",
                    "p50, мс", "p90, мс", "p95, мс", "p99, мс", "Максимум, мс", "Лимит p95, мс", "Результат"));
            for (ScenarioResult result : results) {
                rows.add(List.of(result.name(), result.description(), result.targetRps(), round2(result.actualRps()),
                        result.totalRequests(), round2(result.successRatePercent()), result.p50Millis(), result.p90Millis(),
                        result.p95Millis(), result.p99Millis(), result.maxMillis(), result.p95TargetMillis(),
                        result.passed() ? "ПРОЙДЕНО" : "НЕ ПРОЙДЕНО"));
            }
            return rows;
        }

        private static List<List<Object>> coverageRows(CoverageResult coverage) {
            List<List<Object>> rows = new ArrayList<>();
            rows.add(List.of("Требование или проверка", "Покрыто", "Всего", "Статус"));
            for (Map.Entry<String, Boolean> entry : coverage.checks().entrySet()) {
                if (Objects.equals(entry.getKey(), "Сценарии ёмкости по расчёту ресурсов")) {
                    continue;
                }
                boolean covered = Boolean.TRUE.equals(entry.getValue());
                rows.add(List.of(entry.getKey(), covered ? 1 : 0, 1, covered ? "ПОКРЫТО" : "НЕ ПОКРЫТО"));
            }
            rows.add(List.of("ИТОГО", coverage.covered(), coverage.total(), ONE_DECIMAL.format(coverage.percent()) + "%"));
            return rows;
        }

        private static List<List<Object>> infoRows(CoverageResult coverage, LoadTestOptions options) {
            List<List<Object>> rows = new ArrayList<>();
            rows.add(List.of("Поле", "Значение"));
            rows.add(List.of("Сформировано", Instant.now().toString()));
            rows.add(List.of("Базовый URL", options.baseUrl()));
            rows.add(List.of("Длительность, сек", options.durationSeconds()));
            rows.add(List.of("Покрытие, %", round2(coverage.percent())));
            rows.add(List.of("Покрыто", coverage.covered()));
            rows.add(List.of("Всего", coverage.total()));
            rows.add(List.of("Примечание", "CSV, Markdown и HTML сохраняются в UTF-8 с BOM; XLSX используется как основной табличный формат для Excel."));
            return rows;
        }

        private static double round2(double value) {
            return Math.round(value * 100.0) / 100.0;
        }

        private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        private static String sheetXml(List<List<Object>> rows) {
            StringBuilder builder = new StringBuilder();
            builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    .append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>")
                    .append("<sheetFormatPr defaultRowHeight=\"18\"/>")
                    .append("<cols>");
            int cols = rows.stream().mapToInt(List::size).max().orElse(1);
            for (int i = 1; i <= cols; i++) {
                int width = i == 1 ? 34 : (i == 2 ? 52 : 18);
                builder.append("<col min=\"").append(i).append("\" max=\"").append(i).append("\" width=\"")
                        .append(width).append("\" customWidth=\"1\"/>");
            }
            builder.append("</cols><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                builder.append("<row r=\"").append(r + 1).append("\">");
                List<Object> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    appendCell(builder, r + 1, c + 1, row.get(c), r == 0);
                }
                builder.append("</row>");
            }
            builder.append("</sheetData><autoFilter ref=\"A1:").append(columnName(cols)).append(rows.size()).append("\"/>")
                    .append("</worksheet>");
            return builder.toString();
        }

        private static void appendCell(StringBuilder builder, int row, int column, Object value, boolean header) {
            String ref = columnName(column) + row;
            String style = header ? " s=\"1\"" : "";
            if (value instanceof Number number) {
                builder.append("<c r=\"").append(ref).append("\"").append(style).append("><v>")
                        .append(number).append("</v></c>");
            } else {
                builder.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style).append("><is><t>")
                        .append(escapeXml(String.valueOf(value))).append("</t></is></c>");
            }
        }

        private static String columnName(int index) {
            StringBuilder builder = new StringBuilder();
            int current = index;
            while (current > 0) {
                current--;
                builder.insert(0, (char) ('A' + current % 26));
                current /= 26;
            }
            return builder.toString();
        }

        private static String contentTypes() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                    + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>";
        }

        private static String rootRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                    + "</Relationships>";
        }

        private static String workbook() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets>"
                    + "<sheet name=\"Результаты\" sheetId=\"1\" r:id=\"rId1\"/>"
                    + "<sheet name=\"Покрытие\" sheetId=\"2\" r:id=\"rId2\"/>"
                    + "<sheet name=\"Параметры\" sheetId=\"3\" r:id=\"rId3\"/>"
                    + "</sheets></workbook>";
        }

        private static String workbookRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/>"
                    + "<Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>";
        }

        private static String styles() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "</styleSheet>";
        }

        private static String coreProps() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                    + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                    + "<dc:title>URL Shortener — результаты нагрузочного тестирования</dc:title>"
                    + "<dc:creator>LoadTestRunner</dc:creator>"
                    + "<cp:lastModifiedBy>LoadTestRunner</cp:lastModifiedBy>"
                    + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + Instant.now() + "</dcterms:created>"
                    + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + Instant.now() + "</dcterms:modified>"
                    + "</cp:coreProperties>";
        }

        private static String appProps() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                    + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                    + "<Application>URL Shortener LoadTestRunner</Application></Properties>";
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeXml(String value) {
        return escapeHtml(value);
    }

}
