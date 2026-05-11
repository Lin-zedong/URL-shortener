package urlshortener.util;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import urlshortener.config.AppConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор HTTP-утилит для парсинга форм, cookie, редиректов и определения внешнего base URL.
 */
public final class HttpUtils {

    private HttpUtils() {
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, List<String>> multi = parseQueryMulti(rawQuery);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : multi.entrySet()) {
            List<String> values = entry.getValue();
            result.put(entry.getKey(), values.isEmpty() ? "" : values.get(values.size() - 1));
        }
        return result;
    }

    /**
     * Сохраняет несколько значений одного query-параметра.
     */
    public static Map<String, List<String>> parseQueryMulti(String rawQuery) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = decode(kv[0]);
            String value = kv.length > 1 ? decode(kv[1]) : "";
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    public static Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        return parseQuery(readRequestBody(exchange));
    }

    public static Map<String, List<String>> parseFormBodyMulti(HttpExchange exchange) throws IOException {
        return parseQueryMulti(readRequestBody(exchange));
    }

    /**
     * Отправляет HTML с UTF-8 и корректной длиной тела.
     */
    public static void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    public static void sendText(HttpExchange exchange, int statusCode, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    /**
     * Возвращает HTTP 302 с безопасным Location.
     */
    public static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    public static void setSessionCookie(HttpExchange exchange, String token, Duration ttl) {
        setCookie(exchange, AppConfig.SESSION_COOKIE_NAME, token, ttl, true);
    }

    public static void clearSessionCookie(HttpExchange exchange) {
        clearCookie(exchange, AppConfig.SESSION_COOKIE_NAME, true);
    }

    public static void setLanguageCookie(HttpExchange exchange, String languageCode, Duration ttl) {
        setCookie(exchange, AppConfig.LANGUAGE_COOKIE_NAME, languageCode, ttl, false);
    }

    public static String extractSessionCookieValue(HttpExchange exchange) {
        return extractCookieValue(exchange, AppConfig.SESSION_COOKIE_NAME);
    }

    public static String extractCookieValue(HttpExchange exchange, String cookieName) {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return null;
        }
        for (String header : cookieHeaders) {
            String[] cookies = header.split(";");
            for (String cookie : cookies) {
                String[] kv = cookie.trim().split("=", 2);
                if (kv.length == 2 && cookieName.equals(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return null;
    }

    public static String method(HttpExchange exchange) {
        return exchange.getRequestMethod().toUpperCase();
    }

    public static boolean isGet(HttpExchange exchange) {
        return "GET".equals(method(exchange));
    }

    public static boolean isPost(HttpExchange exchange) {
        return "POST".equals(method(exchange));
    }

    public static String currentRelativePath(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return path == null || path.isBlank() ? "/" : path;
        }
        return (path == null || path.isBlank() ? "/" : path) + "?" + query;
    }

    /**
     * Восстанавливает публичный base URL с учётом forwarded headers.
     */
    public static String resolveExternalBaseUrl(HttpExchange exchange) {
        String forwardedProto = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        String forwardedHost = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
        if (forwardedHost != null) {
            return (forwardedProto == null ? "http" : forwardedProto) + "://" + forwardedHost;
        }

        String host = firstNonBlank(exchange.getRequestHeaders().getFirst("Host"));
        if (host != null) {
            return "http://" + host;
        }
        return AppConfig.BASE_URL;
    }

    /**
     * Защищает локальный редирект от open redirect.
     */
    public static String sanitizeRelativeRedirectPath(String candidate, String defaultPath) {
        if (candidate == null || candidate.isBlank()) {
            return defaultPath;
        }
        String trimmed = candidate.trim();
        if (!trimmed.startsWith("/")) {
            return defaultPath;
        }
        if (trimmed.startsWith("//") || trimmed.contains("://") || trimmed.contains("\r") || trimmed.contains("\n")) {
            return defaultPath;
        }
        return trimmed;
    }

    private static void setCookie(HttpExchange exchange, String name, String value, Duration ttl, boolean httpOnly) {
        long maxAge = ttl.toSeconds();
        boolean secure = isSecureRequest(exchange);
        String cookie = name + "=" + value
                + "; Path=/; SameSite=Lax; Max-Age=" + maxAge
                + (httpOnly ? "; HttpOnly" : "")
                + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static void clearCookie(HttpExchange exchange, String name, boolean httpOnly) {
        boolean secure = isSecureRequest(exchange);
        String cookie = name + "=; Path=/; SameSite=Lax; Max-Age=0"
                + (httpOnly ? "; HttpOnly" : "")
                + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static boolean isSecureRequest(HttpExchange exchange) {
        String forwardedProto = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto);
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
