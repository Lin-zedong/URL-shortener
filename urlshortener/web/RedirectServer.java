package urlshortener.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import urlshortener.config.AppConfig;
import urlshortener.i18n.I18n;
import urlshortener.i18n.Language;
import urlshortener.model.RedirectResolution;
import urlshortener.service.RedirectService;
import urlshortener.util.HtmlRenderer;
import urlshortener.util.HttpUtils;
import urlshortener.util.LogUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Публичный HTTP-сервер, обслуживающий route_key и возвращающий 302 либо безопасную ошибку.
 */
public final class RedirectServer {

    private final RedirectService redirectService;
    private HttpServer httpServer;
    private ExecutorService executorService;

    public RedirectServer(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    /**
     * Запускает публичный redirect endpoint для route_key.
     */
    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(AppConfig.REDIRECT_PORT), 0);
            httpServer.createContext("/", this::handle);
            executorService = Executors.newFixedThreadPool(AppConfig.REDIRECT_THREADS);
            httpServer.setExecutor(executorService);
            httpServer.start();
            LogUtils.info("Redirect service запущен на порту " + AppConfig.REDIRECT_PORT);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить redirect service", e);
        }
    }

    /**
     * Останавливает redirect server и его executor.
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
        Language language = resolveLanguage(exchange);
        String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
        String appBaseUrl = (forwardedHost != null && !forwardedHost.isBlank())
                ? HttpUtils.resolveExternalBaseUrl(exchange)
                : AppConfig.BASE_URL;
        String currentPath = HttpUtils.currentRelativePath(exchange);
        try {
            if (!HttpUtils.isGet(exchange)) {
                HttpUtils.sendText(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path)) {
                HttpUtils.sendText(exchange, 200, "text/plain", "OK redirect");
                return;
            }
            if ("/".equals(path)) {
                HttpUtils.sendHtml(exchange, 200, HtmlRenderer.infoPage(
                        language,
                        "info.redirectService.title",
                        "info.redirectService.message",
                        appBaseUrl,
                        currentPath
                ));
                return;
            }

            String routeKey = extractRouteKey(path);
            if (routeKey == null || routeKey.isBlank()) {
                HttpUtils.sendHtml(exchange, 404, HtmlRenderer.publicErrorPage(
                        language,
                        "error.invalidShortLink.title",
                        "error.invalidShortLink.message",
                        null,
                        404,
                        appBaseUrl,
                        currentPath
                ));
                return;
            }

            RedirectResolution resolution = redirectService.resolve(routeKey);
            if (resolution.success()) {
                exchange.getResponseHeaders().set("Location", resolution.shortLink().originalUrl());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            int statusCode = switch (resolution.errorCode()) {
                case "DISABLED" -> 423;
                case "EXPIRED" -> 410;
                default -> 404;
            };
            LogUtils.error(resolution.errorCode(), routeKey, resolution.humanMessage(), null);
            HttpUtils.sendHtml(exchange, statusCode, HtmlRenderer.publicErrorPage(
                    language,
                    switch (resolution.errorCode()) {
                        case "DISABLED" -> "error.disabled.title";
                        case "EXPIRED" -> "error.expired.title";
                        default -> "error.invalidShortLink.title";
                    },
                    resolution.humanMessage(),
                    routeKey,
                    statusCode,
                    appBaseUrl,
                    currentPath
            ));
        } catch (Exception ex) {
            LogUtils.error("REDIRECT_HANDLER_ERROR", null, "Непредвиденная ошибка redirect service", ex);
            HttpUtils.sendHtml(exchange, 500, HtmlRenderer.publicErrorPage(
                    language,
                    "error.internal.title",
                    "error.internal.redirectMessage",
                    null,
                    500,
                    appBaseUrl,
                    currentPath
            ));
        }
    }

    private Language resolveLanguage(HttpExchange exchange) {
        return I18n.resolveLanguage(
                HttpUtils.extractCookieValue(exchange, AppConfig.LANGUAGE_COOKIE_NAME),
                exchange.getRequestHeaders().getFirst("Accept-Language")
        );
    }

    private String extractRouteKey(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.contains("/")) {
            return null;
        }
        return normalized;
    }
}
