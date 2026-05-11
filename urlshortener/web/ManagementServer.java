package urlshortener.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import urlshortener.config.AppConfig;
import urlshortener.i18n.I18n;
import urlshortener.i18n.Language;
import urlshortener.model.Page;
import urlshortener.model.ServiceResult;
import urlshortener.model.ShortLink;
import urlshortener.model.User;
import urlshortener.service.AnalyticsService;
import urlshortener.service.SessionService;
import urlshortener.service.ShortLinkService;
import urlshortener.service.UserService;
import urlshortener.util.HtmlRenderer;
import urlshortener.util.HttpUtils;
import urlshortener.util.LogUtils;
import urlshortener.util.StaticAssets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP-сервер личного кабинета и операций управления ссылками.
 */
public final class ManagementServer {

    private final UserService userService;
    private final SessionService sessionService;
    private final ShortLinkService shortLinkService;
    private final AnalyticsService analyticsService;
    private HttpServer httpServer;
    private ExecutorService executorService;

    public ManagementServer(
            UserService userService,
            SessionService sessionService,
            ShortLinkService shortLinkService,
            AnalyticsService analyticsService
    ) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.shortLinkService = shortLinkService;
        this.analyticsService = analyticsService;
    }

    /**
     * Запускает HTTP-контуры login, dashboard, create, stats и status actions.
     */
    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(AppConfig.MANAGEMENT_PORT), 0);
            httpServer.createContext("/", this::handle);
            executorService = Executors.newFixedThreadPool(AppConfig.MANAGEMENT_THREADS);
            httpServer.setExecutor(executorService);
            httpServer.start();
            LogUtils.info("Management service запущен на порту " + AppConfig.MANAGEMENT_PORT);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить management service", e);
        }
    }

    /**
     * Останавливает management server для корректного завершения тестов.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Language language = resolveLanguage(exchange);
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);

        try {
            if ("/health".equals(path)) {
                HttpUtils.sendText(exchange, 200, "text/plain", "OK management");
                return;
            }
            if ("/".equals(path)) {
                Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
                HttpUtils.redirect(exchange, user.isPresent() ? "/app/dashboard" : "/app/login");
                return;
            }
            if ("/app/assets/styles.css".equals(path)) {
                HttpUtils.sendText(exchange, 200, "text/css", StaticAssets.readText("static/styles.css"));
                return;
            }
            if ("/app/assets/app.js".equals(path)) {
                HttpUtils.sendText(exchange, 200, "application/javascript", StaticAssets.readText("static/app.js"));
                return;
            }
            if ("/app/lang".equals(path)) {
                handleLanguageSwitch(exchange);
                return;
            }
            if ("/app/login".equals(path)) {
                handleLogin(exchange, language);
                return;
            }
            if ("/app/register".equals(path)) {
                handleRegister(exchange, language);
                return;
            }
            if ("/app/logout".equals(path)) {
                handleLogout(exchange);
                return;
            }
            if ("/app/dashboard".equals(path)) {
                handleDashboard(exchange, language);
                return;
            }
            if ("/app/links/create".equals(path)) {
                handleCreateLink(exchange, language);
                return;
            }
            if ("/app/links/toggle".equals(path)) {
                handleToggleLink(exchange);
                return;
            }
            if ("/app/links/delete".equals(path)) {
                handleDeleteLink(exchange);
                return;
            }
            if ("/app/links/history-delete".equals(path)) {
                handleDeleteHistory(exchange);
                return;
            }
            if ("/app/links/stats".equals(path)) {
                handleStats(exchange, language);
                return;
            }

            HttpUtils.sendHtml(exchange, 404, HtmlRenderer.publicErrorPage(
                    language,
                    "error.pageNotFound.title",
                    "error.pageNotFound.message",
                    null,
                    404,
                    appBaseUrl,
                    currentPath
            ));
        } catch (Exception ex) {
            LogUtils.error("MANAGEMENT_HANDLER_ERROR", null, "Непредвиденная ошибка management service", ex);
            HttpUtils.sendHtml(exchange, 500, HtmlRenderer.publicErrorPage(
                    language,
                    "error.internal.title",
                    "error.internal.managementMessage",
                    null,
                    500,
                    appBaseUrl,
                    currentPath
            ));
        }
    }

    private void handleLanguageSwitch(HttpExchange exchange) throws IOException {
        if (!HttpUtils.isGet(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        Map<String, String> query = HttpUtils.parseQuery(exchange.getRequestURI().getRawQuery());
        String value = query.getOrDefault("value", "en");
        Language language = Language.fromCode(value);
        String redirect = HttpUtils.sanitizeRelativeRedirectPath(query.get("redirect"), "/app/login");
        HttpUtils.setLanguageCookie(exchange, language.code(), AppConfig.LANGUAGE_TTL);
        HttpUtils.redirect(exchange, redirect);
    }

    private void handleLogin(HttpExchange exchange, Language language) throws IOException {
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);
        if (HttpUtils.isGet(exchange)) {
            Map<String, String> query = HttpUtils.parseQuery(exchange.getRequestURI().getRawQuery());
            HttpUtils.sendHtml(exchange, 200, HtmlRenderer.loginPage(language, query.get("message"), appBaseUrl, currentPath));
            return;
        }
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Map<String, String> form = HttpUtils.parseFormBody(exchange);
        ServiceResult<User> result = userService.authenticate(form.get("username"), form.get("password"));
        if (!result.success()) {
            HttpUtils.sendHtml(exchange, 400, HtmlRenderer.loginPage(language, result.message(), appBaseUrl, currentPath));
            return;
        }

        String sessionToken = sessionService.createSession(result.data(), exchange.getRequestHeaders().getFirst("User-Agent"));
        HttpUtils.setSessionCookie(exchange, sessionToken, AppConfig.SESSION_TTL);
        HttpUtils.redirect(exchange, dashboardMessageUrl(result.message(), "success"));
    }

    private void handleRegister(HttpExchange exchange, Language language) throws IOException {
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);
        if (HttpUtils.isGet(exchange)) {
            Map<String, String> query = HttpUtils.parseQuery(exchange.getRequestURI().getRawQuery());
            HttpUtils.sendHtml(exchange, 200, HtmlRenderer.registerPage(language, query.get("message"), appBaseUrl, currentPath));
            return;
        }
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Map<String, String> form = HttpUtils.parseFormBody(exchange);
        ServiceResult<User> result = userService.register(
                form.get("username"),
                form.get("displayName"),
                form.get("password"),
                form.get("confirmPassword")
        );
        if (!result.success()) {
            HttpUtils.sendHtml(exchange, 400, HtmlRenderer.registerPage(language, result.message(), appBaseUrl, currentPath));
            return;
        }

        String sessionToken = sessionService.createSession(result.data(), exchange.getRequestHeaders().getFirst("User-Agent"));
        HttpUtils.setSessionCookie(exchange, sessionToken, AppConfig.SESSION_TTL);
        HttpUtils.redirect(exchange, dashboardMessageUrl(result.message(), "success"));
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sessionService.logout(exchange);
        HttpUtils.clearSessionCookie(exchange);
        HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loggedOut"));
    }

    private void handleDashboard(HttpExchange exchange, Language language) throws IOException {
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);
        if (!HttpUtils.isGet(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, String> query = HttpUtils.parseQuery(exchange.getRequestURI().getRawQuery());
        String search = query.getOrDefault("q", "");
        String status = query.getOrDefault("status", "");
        int pageNumber = parsePositiveInt(query.get("page"), 1);
        int pageSize = parsePositiveInt(query.get("size"), AppConfig.DEFAULT_PAGE_SIZE);
        Page<ShortLink> page = shortLinkService.listLinks(user.get().id(), search, status, pageNumber, pageSize);

        String message = query.get("message");
        String messageType = query.get("messageType");
        String createdShortUrl = query.get("createdShortUrl");

        HttpUtils.sendHtml(exchange, 200, HtmlRenderer.dashboardPage(
                language,
                user.get(),
                page,
                search,
                status,
                message,
                messageType,
                createdShortUrl,
                appBaseUrl,
                currentPath
        ));
    }

    private void handleCreateLink(HttpExchange exchange, Language language) throws IOException {
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, String> form = HttpUtils.parseFormBody(exchange);
        ServiceResult<ShortLink> result = shortLinkService.createLink(
                user.get().id(),
                form.get("originalUrl"),
                form.get("alias"),
                form.get("expiresAtMoscow")
        );

        if (!result.success()) {
            Page<ShortLink> page = shortLinkService.listLinks(user.get().id(), "", "", 1, AppConfig.DEFAULT_PAGE_SIZE);
            HttpUtils.sendHtml(exchange, 400, HtmlRenderer.dashboardPage(
                    language, user.get(), page, "", "", result.message(), "error", null, appBaseUrl, currentPath
            ));
            return;
        }

        String shortUrl = appBaseUrl + "/" + result.data().routeKey();
        HttpUtils.redirect(exchange, "/app/dashboard?messageType=success&message="
                + urlEncode(result.message()) + "&createdShortUrl=" + urlEncode(shortUrl));
    }

    private void handleToggleLink(HttpExchange exchange) throws IOException {
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, String> form = HttpUtils.parseFormBody(exchange);
        UUID linkId = parseUuid(form.get("linkId"));
        if (linkId == null) {
            HttpUtils.redirect(exchange, "/app/dashboard?message=" + urlEncode("validation.invalidLinkId"));
            return;
        }

        ServiceResult<ShortLink> result = shortLinkService.toggle(user.get().id(), linkId, form.get("targetState"));
        String redirectUrl = "/app/dashboard?message=" + urlEncode(result.message())
                + "&messageType=" + (result.success() ? "success" : "error");
        HttpUtils.redirect(exchange, redirectUrl);
    }

    private void handleDeleteLink(HttpExchange exchange) throws IOException {
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, String> form = HttpUtils.parseFormBody(exchange);
        UUID linkId = parseUuid(form.get("linkId"));
        if (linkId == null) {
            HttpUtils.redirect(exchange, "/app/dashboard?message=" + urlEncode("validation.invalidLinkId"));
            return;
        }

        ServiceResult<ShortLink> result = shortLinkService.delete(user.get().id(), linkId);
        String redirectUrl = "/app/dashboard?message=" + urlEncode(result.message())
                + "&messageType=" + (result.success() ? "success" : "error");
        HttpUtils.redirect(exchange, redirectUrl);
    }

    private void handleDeleteHistory(HttpExchange exchange) throws IOException {
        if (!HttpUtils.isPost(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, java.util.List<String>> form = HttpUtils.parseFormBodyMulti(exchange);
        java.util.List<UUID> selectedIds = parseUuidList(form.get("selectedLinkId"));
        if (selectedIds == null) {
            HttpUtils.redirect(exchange, "/app/dashboard?message=" + urlEncode("validation.invalidLinkId"));
            return;
        }

        String search = lastValue(form.get("q"));
        String status = lastValue(form.get("status"));
        int pageNumber = parsePositiveInt(lastValue(form.get("page")), 1);
        int pageSize = parsePositiveInt(lastValue(form.get("size")), AppConfig.DEFAULT_PAGE_SIZE);

        ServiceResult<Integer> result = shortLinkService.deleteHistoryRecords(user.get().id(), selectedIds);
        HttpUtils.redirect(exchange, dashboardStateUrl(result.message(), result.success() ? "success" : "error", search, status, pageNumber, pageSize));
    }

    private void handleStats(HttpExchange exchange, Language language) throws IOException {
        String appBaseUrl = HttpUtils.resolveExternalBaseUrl(exchange);
        String currentPath = HttpUtils.currentRelativePath(exchange);
        if (!HttpUtils.isGet(exchange)) {
            HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        Optional<User> user = sessionService.resolveAuthenticatedUser(exchange);
        if (user.isEmpty()) {
            HttpUtils.redirect(exchange, "/app/login?message=" + urlEncode("auth.loginRequired"));
            return;
        }

        Map<String, String> query = HttpUtils.parseQuery(exchange.getRequestURI().getRawQuery());
        UUID linkId = parseUuid(query.get("linkId"));
        if (linkId == null) {
            HttpUtils.redirect(exchange, "/app/dashboard?message=" + urlEncode("validation.invalidLinkId"));
            return;
        }

        ServiceResult<ShortLink> result = shortLinkService.getOwnedLink(user.get().id(), linkId);
        if (!result.success()) {
            HttpUtils.redirect(exchange, "/app/dashboard?message=" + urlEncode(result.message()));
            return;
        }

        HttpUtils.sendHtml(exchange, 200, HtmlRenderer.statsPage(
                language,
                user.get(),
                result.data(),
                analyticsService.getLast90Days(result.data().id()),
                query.get("message"),
                appBaseUrl,
                currentPath
        ));
    }

    private Language resolveLanguage(HttpExchange exchange) {
        return I18n.resolveLanguage(
                HttpUtils.extractCookieValue(exchange, AppConfig.LANGUAGE_COOKIE_NAME),
                exchange.getRequestHeaders().getFirst("Accept-Language")
        );
    }

    private String dashboardMessageUrl(String message, String messageType) {
        return "/app/dashboard?messageType=" + urlEncode(messageType) + "&message=" + urlEncode(message);
    }

    private String dashboardStateUrl(String message, String messageType, String search, String status, int pageNumber, int pageSize) {
        return "/app/dashboard?messageType=" + urlEncode(messageType)
                + "&message=" + urlEncode(message)
                + "&q=" + urlEncode(search == null ? "" : search)
                + "&status=" + urlEncode(status == null ? "" : status)
                + "&page=" + pageNumber
                + "&size=" + pageSize;
    }

    private String lastValue(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(values.size() - 1);
    }

    private java.util.List<UUID> parseUuidList(java.util.List<String> rawValues) {
        java.util.List<UUID> result = new java.util.ArrayList<>();
        if (rawValues == null || rawValues.isEmpty()) {
            return result;
        }
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            UUID parsed = parseUuid(rawValue);
            if (parsed == null) {
                return null;
            }
            result.add(parsed);
        }
        return result;
    }

    private int parsePositiveInt(String raw, int defaultValue) {
        try {
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            int value = Integer.parseInt(raw);
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String urlEncode(String raw) {
        return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
