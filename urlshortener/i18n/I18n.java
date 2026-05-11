package urlshortener.i18n;

import urlshortener.model.LinkStatus;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class I18n {

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> RU = new HashMap<>();

    static {
        put("app.name", "URL Shortener", "Сократитель ссылок");
        put("app.tagline", "Course MVP for distributed systems design", "Учебный MVP по проектированию распределённых систем");
        put("lang.switch", "Interface language", "Язык интерфейса");

        put("common.back", "Back", "Назад");
        put("common.home", "Home", "На главную");
        put("common.logout", "Sign out", "Выйти");
        put("common.currentUser", "Signed in as", "Текущий пользователь");
        put("common.none", "None", "Нет");
        put("common.yes", "Yes", "Да");
        put("common.no", "No", "Нет");
        put("common.routeKey", "route_key", "route_key");
        put("common.copy", "Copy", "Копировать");
        put("common.created", "Created", "Создано");
        put("common.actions", "Actions", "Действия");
        put("common.status", "Status", "Статус");
        put("common.total", "total", "всего");

        put("login.title", "Sign in", "Вход");
        put("login.heading", "Sign in", "Вход");
        put("login.subtitle", "Open your workspace and manage short links.", "Откройте рабочее пространство и управляйте короткими ссылками.");
        put("login.username", "Username", "Имя пользователя");
        put("login.password", "Password", "Пароль");
        put("login.submit", "Sign in", "Войти");
        put("login.noAccount", "No account yet?", "Ещё нет аккаунта?");
        put("login.goRegister", "Create one", "Создать аккаунт");

        put("register.title", "Create account", "Регистрация");
        put("register.heading", "Create account", "Регистрация");
        put("register.subtitle", "Create an owner account to manage your short links.", "Создайте аккаунт владельца для управления короткими ссылками.");
        put("register.username", "Username", "Имя пользователя");
        put("register.displayName", "Display name", "Отображаемое имя");
        put("register.password", "Password", "Пароль");
        put("register.confirmPassword", "Confirm password", "Подтверждение пароля");
        put("register.submit", "Create account", "Создать аккаунт");
        put("register.haveAccount", "Already have an account?", "Уже есть аккаунт?");
        put("register.backToLogin", "Go to sign in", "Перейти ко входу");

        put("dashboard.title", "Workspace", "Кабинет");
        put("dashboard.heading", "Workspace", "Кабинет");
        put("dashboard.scope",
                "MVP scope: create short link, 302 redirect, alias, expiration, owner cabinet, basic analytics, safe error pages.",
                "MVP-покрытие: создание короткой ссылки, 302-редирект, alias, срок действия, кабинет владельца, базовая аналитика и безопасные страницы ошибок.");
        put("dashboard.createSection", "Create short link", "Создать короткую ссылку");
        put("dashboard.originalUrl", "Original URL", "Исходный URL");
        put("dashboard.alias", "Custom alias (optional)", "Пользовательский alias (необязательно)");
        put("dashboard.aliasHint", "Allowed format: [A-Za-z0-9_-], length 3–32. Leave empty to use a pre-generated route_key.", "Допустимый формат: [A-Za-z0-9_-], длина 3–32. Оставьте пустым, чтобы использовать предгенерированный route_key.");
        put("dashboard.expiresAt", "Expires at (Moscow time, optional)", "Время истечения (по московскому времени, необязательно)");
        put("dashboard.expiresHint", "Example: 2026-03-18T15:30:00 or 2026-03-18T15:30:00+03:00. Both UI and server interpret the value in Moscow time.", "Пример: 2026-03-18T15:30:00 или 2026-03-18T15:30:00+03:00. И интерфейс, и сервер трактуют значение по московскому времени.");
        put("dashboard.createSubmit", "Create short link", "Создать ссылку");
        put("dashboard.filterSection", "Filter and search", "Фильтрация и поиск");
        put("dashboard.search", "Search by route_key or URL", "Поиск по route_key или URL");
        put("dashboard.searchPlaceholder", "alias or part of original URL", "alias или часть исходного URL");
        put("dashboard.filterSubmit", "Apply filters", "Применить фильтры");
        put("dashboard.linksSection", "My links", "Мои ссылки");
        put("dashboard.noData", "No links match the current filter.", "По текущему фильтру ссылки не найдены.");
        put("dashboard.createdBanner", "Short link created", "Короткая ссылка создана");
        put("dashboard.pageSummary", "Page %d of %d • %d total", "Страница %d из %d • всего %d");
        put("dashboard.historyDelete", "Delete history", "Удалить историю");
        put("dashboard.historyCancel", "Cancel", "Отмена");
        put("dashboard.historySelect", "Select", "Выбрать");
        put("dashboard.historyDeleteNoSelection", "Select at least one history record.", "Выберите хотя бы одну запись истории.");
        put("dashboard.historyDeleteSuccess", "Selected history records were deleted.", "Выбранные записи истории удалены.");
        put("dashboard.historyConfirmTitle", "Delete link record?", "Удалить запись ссылки?");
        put("dashboard.historyConfirmActive", "Delete this link record? It is still active. After deletion the short link will stop working immediately.", "Удалить запись этой ссылки? Она всё ещё активна. После удаления короткая ссылка сразу перестанет работать.");
        put("dashboard.historyConfirmDisabled", "Delete this link record? It can still be re-enabled. After deletion the short link will stop working immediately.", "Удалить запись этой ссылки? Её ещё можно снова включить. После удаления короткая ссылка сразу перестанет работать.");

        put("table.shortLink", "Short link", "Короткая ссылка");
        put("table.originalUrl", "Original URL", "Исходный URL");
        put("table.status", "Status", "Статус");
        put("table.totalClicks", "Total clicks", "Всего переходов");
        put("table.customAlias", "Custom alias", "Пользовательский alias");
        put("table.expiresAt", "Expires at", "Время истечения");
        put("table.createdAt", "Created at", "Создано");
        put("table.actions", "Actions", "Действия");
        put("filter.all", "All", "Все");

        put("action.stats", "Stats", "Статистика");
        put("action.disable", "Disable", "Отключить");
        put("action.enable", "Enable", "Включить");
        put("action.delete", "Delete", "Удалить");

        put("stats.title", "Statistics", "Статистика");
        put("stats.heading", "Statistics", "Статистика");
        put("stats.subtitle", "Detailed link metrics", "Детальная статистика ссылки");
        put("stats.shortLink", "Short link", "Короткая ссылка");
        put("stats.totalClicks", "Total clicks", "Всего переходов");
        put("stats.currentStatus", "Current status", "Текущий статус");
        put("stats.originalUrl", "Original URL", "Исходный URL");
        put("stats.expiresAt", "Expires at", "Время истечения");
        put("stats.createdAt", "Created at", "Создано");
        put("stats.last90Days", "Daily trend for the last 90 days", "Динамика по дням за последние 90 дней");
        put("stats.noData", "No statistics for the last 90 days.", "За последние 90 дней статистики нет.");
        put("stats.date", "Date", "Дата");
        put("stats.clicks", "Clicks", "Клики");
        put("stats.chart", "Chart", "График");

        put("info.redirectService.title", "Redirect service", "Сервис редиректа");
        put("info.redirectService.message", "This port is dedicated to public /{route_key} redirects. Use the edge entry for the full system.", "Этот порт предназначен только для публичных переходов по /{route_key}. Для полного приложения используйте edge-вход.");

        put("error.pageNotFound.title", "Page not found", "Страница не найдена");
        put("error.pageNotFound.message", "The management path does not exist.", "Указанный путь управления не существует.");
        put("error.invalidShortLink.title", "Link not found", "Ссылка не найдена");
        put("error.invalidShortLink.message", "The short-link format is invalid.", "Формат короткой ссылки некорректен.");
        put("error.disabled.title", "Link disabled", "Ссылка отключена");
        put("error.expired.title", "Link expired", "Ссылка истекла");
        put("error.internal.title", "Internal error", "Внутренняя ошибка");
        put("error.internal.managementMessage", "The server failed to process the request. Internal details are intentionally hidden.", "Сервер не смог обработать запрос. Внутренние детали намеренно скрыты.");
        put("error.internal.redirectMessage", "The redirect request could not be processed.", "Не удалось обработать запрос редиректа.");
        put("error.goHome", "Go to service home", "Перейти на главную");

        put("status.ACTIVE", "Active", "Активна");
        put("status.DISABLED", "Disabled", "Отключена");
        put("status.EXPIRED", "Expired", "Истекла");
        put("status.DELETED", "Deleted", "Удалена");

        put("toast.copied", "Link copied to clipboard.", "Ссылка скопирована в буфер обмена.");
        put("toast.copyFailed", "Copy failed. Please copy the value manually.", "Не удалось скопировать. Скопируйте значение вручную.");
        put("toast.copyPrompt", "Copy the short link manually:", "Скопируйте короткую ссылку вручную:");

        put("auth.loginRequired", "Please sign in first.", "Сначала войдите.");
        put("auth.loggedOut", "You have signed out.", "Вы вышли из системы.");
        put("auth.loginSuccess", "Signed in successfully.", "Вход выполнен.");
        put("auth.registerSuccess", "Account created successfully.", "Аккаунт успешно создан.");
        put("validation.invalidLinkId", "Invalid link id.", "Некорректный идентификатор ссылки.");

        put("Исходный URL не может быть пустым", "Original URL is required.", "Исходный URL обязателен.");
        put("Разрешены только http/https URL", "Only http/https URLs are allowed.", "Разрешены только http/https URL.");
        put("URL должен содержать host", "URL must include a host.", "URL должен содержать host.");
        put("Некорректный формат URL", "Invalid URL format.", "Некорректный формат URL.");
        put("Username не может быть пустым", "Username is required.", "Имя пользователя обязательно.");
        put("Username должен состоять из 3-32 символов [A-Za-z0-9_-]", "Username must be 3–32 characters and use only letters, digits, _ or -.", "Имя пользователя должно быть длиной 3–32 символа и содержать только буквы, цифры, _ или -.");
        put("Alias должен соответствовать [A-Za-z0-9_-] и иметь длину 3-32", "Alias must match [A-Za-z0-9_-] and be 3–32 characters long.", "Alias должен соответствовать [A-Za-z0-9_-] и иметь длину 3–32 символа.");
        put("Этот alias зарезервирован системой", "This alias is reserved by the system.", "Этот alias зарезервирован системой.");
        put("Пароль должен быть не короче 8 символов", "Password must be at least 8 characters long.", "Пароль должен быть не короче 8 символов.");
        put("Пароли не совпадают", "Passwords do not match.", "Пароли не совпадают.");
        put("ExpiresAt должен быть указан по московскому времени, например 2026-03-18T15:30:00 или 2026-03-18T15:30:00+03:00", "Expiration time must be specified in Moscow time, for example 2026-03-18T15:30:00 or 2026-03-18T15:30:00+03:00.", "Время истечения должно быть указано по московскому времени, например 2026-03-18T15:30:00 или 2026-03-18T15:30:00+03:00.");
        put("Username already exists", "Username already exists.", "Пользователь с таким именем уже существует.");
        put("Registration successful", "Account created successfully.", "Аккаунт успешно создан.");
        put("Invalid username or password", "Invalid username or password.", "Неверное имя пользователя или пароль.");
        put("Login successful", "Signed in successfully.", "Вход выполнен.");
        put("Display name cannot be empty", "Display name is required.", "Отображаемое имя обязательно.");
        put("Display name must not exceed 64 chars", "Display name must not exceed 64 characters.", "Отображаемое имя не должно превышать 64 символа.");
        put("ExpiresAt must be later than current Moscow time", "Expiration time must be later than the current Moscow time.", "Время истечения должно быть позже текущего московского времени.");
        put("Alias is already taken", "Alias is already taken.", "Alias уже занят.");
        put("Short link created", "Short link created.", "Короткая ссылка создана.");
        put("Link not found or access denied", "Link not found or access denied.", "Ссылка не найдена или доступ запрещён.");
        put("Deleted link cannot be disabled", "Deleted links cannot be disabled.", "Удалённую ссылку нельзя отключить.");
        put("Link disabled", "Link disabled.", "Ссылка отключена.");
        put("Expired link cannot be re-enabled", "Expired links cannot be re-enabled.", "Истёкшую ссылку нельзя включить повторно.");
        put("Deleted link cannot be enabled", "Deleted links cannot be enabled.", "Удалённую ссылку нельзя включить.");
        put("Link enabled", "Link enabled.", "Ссылка включена.");
        put("Unknown target state", "Unknown target state.", "Неизвестное целевое состояние.");
        put("Link not found or access denied", "Link not found or access denied.", "Ссылка не найдена или доступ запрещён.");
        put("Link deleted", "Link deleted.", "Ссылка удалена.");
        put("Select at least one history record", "Select at least one history record.", "Выберите хотя бы одну запись истории.");
        put("Selected history records were deleted", "Selected history records were deleted.", "Выбранные записи истории удалены.");
        put("Link not found or access denied", "Link not found or access denied.", "Ссылка не найдена или доступ запрещён.");
        put("Short link not found", "Short link not found.", "Короткая ссылка не найдена.");
        put("This short link is disabled", "This short link is disabled.", "Эта короткая ссылка отключена.");
        put("This short link has expired", "This short link has expired.", "Срок действия этой короткой ссылки истёк.");
    }

    private I18n() {
    }

    public static Language resolveLanguage(String cookieValue, String acceptLanguageHeader) {
        if (cookieValue != null && !cookieValue.isBlank()) {
            return Language.fromCode(cookieValue);
        }
        if (acceptLanguageHeader != null) {
            String normalized = acceptLanguageHeader.toLowerCase(Locale.ROOT);
            if (normalized.contains("ru")) {
                return Language.RU;
            }
        }
        return Language.EN;
    }

    public static String t(Language language, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return dictionary(language).getOrDefault(key, key);
    }

    public static String format(Language language, String key, Object... args) {
        return t(language, key).formatted(args);
    }

    public static String status(Language language, LinkStatus status) {
        if (status == null) {
            return t(language, "common.none");
        }
        return t(language, "status." + status.name());
    }

    public static String yesNo(Language language, boolean value) {
        return t(language, value ? "common.yes" : "common.no");
    }

    private static Map<String, String> dictionary(Language language) {
        return language == Language.RU ? RU : EN;
    }

    private static void put(String key, String en, String ru) {
        EN.put(key, en);
        RU.put(key, ru);
    }
}
