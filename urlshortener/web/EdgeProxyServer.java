package urlshortener.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import urlshortener.config.AppConfig;
import urlshortener.util.LogUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лёгкий reverse proxy: принимает внешний трафик и маршрутизирует его в management или redirect service.
 */
public final class EdgeProxyServer {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpServer httpServer;
    private ExecutorService executorService;

    /**
     * Запускает edge proxy на внешнем порту приложения.
     */
    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(AppConfig.EDGE_PORT), 0);
            httpServer.createContext("/", this::handle);
            executorService = Executors.newFixedThreadPool(AppConfig.EDGE_THREADS);
            httpServer.setExecutor(executorService);
            httpServer.start();
            LogUtils.info("Edge proxy запущен на порту " + AppConfig.EDGE_PORT);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить edge proxy", e);
        }
    }

    /**
     * Останавливает proxy и освобождает пул потоков.
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
        if ("/favicon.ico".equals(path)) {
            respondWithoutBody(exchange, 204);
            return;
        }
        int targetPort = routeToManagement(path) ? AppConfig.MANAGEMENT_PORT : AppConfig.REDIRECT_PORT;
        String targetUrl = "http://localhost:" + targetPort + path
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());

        try {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .method(exchange.getRequestMethod(), requestBody.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(requestBody));

            copyRequestHeaders(exchange, builder);
            appendForwardedHeaders(exchange, builder);

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            copyResponseHeaders(response.headers().map(), exchange.getResponseHeaders());
            byte[] responseBody = response.body() == null ? new byte[0] : response.body();
            exchange.sendResponseHeaders(response.statusCode(), responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
            }
            exchange.close();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LogUtils.error("EDGE_INTERRUPTED", null, "Работа edge proxy прервана", ex);
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        } catch (Exception ex) {
            LogUtils.error("EDGE_PROXY_ERROR", null, "Edge proxy не смог переслать запрос к " + targetUrl, ex);
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        }
    }

    private boolean routeToManagement(String path) {
        return "/".equals(path)
                || "/health".equals(path)
                || "/app".equals(path)
                || path.startsWith("/app/")
                || "/api".equals(path)
                || path.startsWith("/api/");
    }

    private void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            String headerName = entry.getKey();
            if (headerName == null) {
                continue;
            }
            String lower = headerName.toLowerCase();
            if ("host".equals(lower)
                    || "content-length".equals(lower)
                    || "connection".equals(lower)
                    || "upgrade".equals(lower)
                    || "http2-settings".equals(lower)
                    || "expect".equals(lower)
                    || "x-forwarded-host".equals(lower)
                    || "x-forwarded-proto".equals(lower)
                    || "x-forwarded-port".equals(lower)
                    || "x-forwarded-for".equals(lower)) {
                continue;
            }
            for (String value : entry.getValue()) {
                builder.header(headerName, value);
            }
        }
    }

    private void appendForwardedHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isBlank()) {
            builder.header("X-Forwarded-Host", host.trim());
        }
        builder.header("X-Forwarded-Proto", "http");
        builder.header("X-Forwarded-Port", String.valueOf(AppConfig.EDGE_PORT));

        String remoteAddress = exchange.getRemoteAddress() == null
                ? null
                : exchange.getRemoteAddress().getAddress() == null
                ? null
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        if (remoteAddress != null && !remoteAddress.isBlank()) {
            builder.header("X-Forwarded-For", remoteAddress);
        }
    }

    private void respondWithoutBody(HttpExchange exchange, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(statusCode, -1);
        exchange.close();
    }

    private void copyResponseHeaders(Map<String, List<String>> source, Headers target) {
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String headerName = entry.getKey();
            if (headerName == null) {
                continue;
            }
            String lower = headerName.toLowerCase();
            if ("content-length".equals(lower) || "transfer-encoding".equals(lower) || "connection".equals(lower)) {
                continue;
            }
            for (String value : entry.getValue()) {
                target.add(headerName, value);
            }
        }
    }
}
