package urlshortener.model;

/**
 * Результат разрешения route_key: успешный редирект или безопасная ошибка.
 */
public record RedirectResolution(
        boolean success,
        String errorCode,
        String humanMessage,
        ShortLink shortLink
) {

    public static RedirectResolution success(ShortLink shortLink) {
        return new RedirectResolution(true, null, null, shortLink);
    }

    public static RedirectResolution failure(String errorCode, String humanMessage) {
        return new RedirectResolution(false, errorCode, humanMessage, null);
    }
}
