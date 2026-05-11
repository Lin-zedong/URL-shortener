
package urlshortener.util;

import urlshortener.config.AppConfig;
import urlshortener.i18n.I18n;
import urlshortener.i18n.Language;
import urlshortener.model.DailyStat;
import urlshortener.model.LinkStatus;
import urlshortener.model.Page;
import urlshortener.model.ShortLink;
import urlshortener.model.User;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import urlshortener.store.ZoneHelper;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HtmlRenderer {

    private static final DateTimeFormatter MOSCOW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'MSK'")
                    .withLocale(Locale.ROOT)
                    .withZone(ZoneHelper.moscow());

    private HtmlRenderer() {
    }

    public static String loginPage(Language language, String message, String appBaseUrl, String currentPath) {
        String body = """
                <section class="page-auth">
                    <div class="card auth-card">
                        <div class="auth-hero">
                            <span class="hero-badge">%s</span>
                            <h1>%s</h1>
                            <p class="muted">%s</p>
                        </div>
                        %s
                        <form method="post" action="/app/login" class="form-grid">
                            <div class="field-group">
                                <label for="username">%s</label>
                                <input id="username" name="username" required minlength="3" maxlength="32" autocomplete="username"/>
                            </div>
                            <div class="field-group">
                                <label for="password">%s</label>
                                <input id="password" type="password" name="password" required minlength="8" autocomplete="current-password"/>
                            </div>
                            <button type="submit" class="button">%s</button>
                        </form>
                        <p class="muted auth-foot">%s <a href="/app/register">%s</a></p>
                    </div>
                </section>
                """.formatted(
                escapeHtml(I18n.t(language, "app.tagline")),
                escapeHtml(I18n.t(language, "login.heading")),
                escapeHtml(I18n.t(language, "login.subtitle")),
                alertBlock(language, message, "error"),
                escapeHtml(I18n.t(language, "login.username")),
                escapeHtml(I18n.t(language, "login.password")),
                escapeHtml(I18n.t(language, "login.submit")),
                escapeHtml(I18n.t(language, "login.noAccount")),
                escapeHtml(I18n.t(language, "login.goRegister"))
        );
        return layout(language, I18n.t(language, "login.title"), body, null, appBaseUrl, currentPath);
    }

    public static String registerPage(Language language, String message, String appBaseUrl, String currentPath) {
        String body = """
                <section class="page-auth">
                    <div class="card auth-card">
                        <div class="auth-hero">
                            <span class="hero-badge">%s</span>
                            <h1>%s</h1>
                            <p class="muted">%s</p>
                        </div>
                        %s
                        <form method="post" action="/app/register" class="form-grid">
                            <div class="field-group">
                                <label for="username">%s</label>
                                <input id="username" name="username" required minlength="3" maxlength="32" autocomplete="username"/>
                            </div>
                            <div class="field-group">
                                <label for="displayName">%s</label>
                                <input id="displayName" name="displayName" required maxlength="64" autocomplete="name"/>
                            </div>
                            <div class="field-group">
                                <label for="password">%s</label>
                                <input id="password" type="password" name="password" required minlength="8" autocomplete="new-password"/>
                            </div>
                            <div class="field-group">
                                <label for="confirmPassword">%s</label>
                                <input id="confirmPassword" type="password" name="confirmPassword" required minlength="8" autocomplete="new-password"/>
                            </div>
                            <button type="submit" class="button">%s</button>
                        </form>
                        <p class="muted auth-foot">%s <a href="/app/login">%s</a></p>
                    </div>
                </section>
                """.formatted(
                escapeHtml(I18n.t(language, "app.tagline")),
                escapeHtml(I18n.t(language, "register.heading")),
                escapeHtml(I18n.t(language, "register.subtitle")),
                alertBlock(language, message, "error"),
                escapeHtml(I18n.t(language, "register.username")),
                escapeHtml(I18n.t(language, "register.displayName")),
                escapeHtml(I18n.t(language, "register.password")),
                escapeHtml(I18n.t(language, "register.confirmPassword")),
                escapeHtml(I18n.t(language, "register.submit")),
                escapeHtml(I18n.t(language, "register.haveAccount")),
                escapeHtml(I18n.t(language, "register.backToLogin"))
        );
        return layout(language, I18n.t(language, "register.title"), body, null, appBaseUrl, currentPath);
    }

    
    public static String dashboardPage(
            Language language,
            User user,
            Page<ShortLink> page,
            String search,
            String status,
            String message,
            String messageType,
            String createdShortUrl,
            String appBaseUrl,
            String currentPath
    ) {
        Instant now = Instant.now();
        StringBuilder rows = new StringBuilder();
        for (ShortLink link : page.items()) {
            rows.append(renderLinkRow(language, link, link.effectiveStatus(now), appBaseUrl));
        }
        if (rows.isEmpty()) {
            rows.append("""
                    <tr>
                        <td colspan="9" class="empty-cell">%s</td>
                    </tr>
                    """.formatted(escapeHtml(I18n.t(language, "dashboard.noData"))));
        }

        String createdBanner = "";
        if (createdShortUrl != null && !createdShortUrl.isBlank()) {
            createdBanner = """
                    <div class="created-banner">
                        <div>
                            <div class="created-banner-title">%s</div>
                            <code id="created-short-url" data-copy-value="%s">%s</code>
                        </div>
                        <button type="button" class="button button-secondary" onclick="copyCreatedShortUrl()">%s</button>
                    </div>
                    """.formatted(
                    escapeHtml(I18n.t(language, "dashboard.createdBanner")),
                    escapeHtml(createdShortUrl),
                    escapeHtml(createdShortUrl),
                    escapeHtml(I18n.t(language, "common.copy"))
            );
        }

        String body = """
                <section class="page-shell dashboard-shell" id="dashboard-page">
                    %s
                    %s

                    <div class="page-header">
                        <div>
                            <h1>%s</h1>
                            <p class="muted">%s</p>
                        </div>
                        <form method="post" action="/app/logout">
                            <button type="submit" class="button button-secondary">%s</button>
                        </form>
                    </div>

                    %s
                    %s

                    <div class="dashboard-grid">
                        <section class="card">
                            <div class="card-head">
                                <h2>%s</h2>
                            </div>
                            <form method="post" action="/app/links/create" class="form-grid">
                                <div class="field-group">
                                    <label for="originalUrl">%s</label>
                                    <input id="originalUrl" name="originalUrl" placeholder="https://example.com/very/long/link" required/>
                                </div>
                                <div class="field-group">
                                    <label for="alias">%s</label>
                                    <input id="alias" name="alias" placeholder="my-course-link"/>
                                    <div class="hint">%s</div>
                                </div>
                                <div class="field-group">
                                    <label for="expiresAtMoscow">%s</label>
                                    <input id="expiresAtMoscow" name="expiresAtMoscow" placeholder="2026-03-18T15:30:00"/>
                                    <div class="hint">%s</div>
                                </div>
                                <button type="submit" class="button">%s</button>
                            </form>
                        </section>

                        <section class="card">
                            <div class="card-head">
                                <h2>%s</h2>
                            </div>
                            <form method="get" action="/app/dashboard" class="form-grid">
                                <div class="field-group">
                                    <label for="q">%s</label>
                                    <input id="q" name="q" value="%s" placeholder="%s"/>
                                </div>
                                <div class="field-group">
                                    <label for="status">%s</label>
                                    <select id="status" name="status">
                                        %s
                                    </select>
                                </div>
                                <button type="submit" class="button">%s</button>
                            </form>
                        </section>
                    </div>

                    <section class="card">
                        <div class="card-head card-head-space links-card-head">
                            <div>
                                <h2>%s</h2>
                                <p class="muted">%s</p>
                            </div>
                            <div class="history-toolbar">
                                <button type="button" id="history-cancel-button" class="button button-secondary button-small history-cancel-button" hidden>%s</button>
                                <button type="button" id="history-delete-button" class="button button-small history-toggle-button">%s</button>
                            </div>
                        </div>
                        <form method="post" action="/app/links/history-delete" id="history-delete-form" class="history-delete-form">
                            <input type="hidden" name="q" value="%s"/>
                            <input type="hidden" name="status" value="%s"/>
                            <input type="hidden" name="page" value="%d"/>
                            <input type="hidden" name="size" value="%d"/>
                        </form>
                        <div class="table-wrap">
                            <table class="responsive-table">
                                <thead>
                                    <tr>
                                        <th class="history-select-col">%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    %s
                                </tbody>
                            </table>
                        </div>
                        %s
                    </section>
                </section>
                """.formatted(
                renderDashboardClientStrings(language),
                renderDeleteHistoryModal(language),
                escapeHtml(user.displayName()),
                escapeHtml(I18n.t(language, "dashboard.scope")),
                escapeHtml(I18n.t(language, "common.logout")),
                alertBlock(language, message, Objects.equals(messageType, "success") ? "success" : "error"),
                createdBanner,
                escapeHtml(I18n.t(language, "dashboard.createSection")),
                escapeHtml(I18n.t(language, "dashboard.originalUrl")),
                escapeHtml(I18n.t(language, "dashboard.alias")),
                escapeHtml(I18n.t(language, "dashboard.aliasHint")),
                escapeHtml(I18n.t(language, "dashboard.expiresAt")),
                escapeHtml(I18n.t(language, "dashboard.expiresHint")),
                escapeHtml(I18n.t(language, "dashboard.createSubmit")),
                escapeHtml(I18n.t(language, "dashboard.filterSection")),
                escapeHtml(I18n.t(language, "dashboard.search")),
                escapeHtml(search == null ? "" : search),
                escapeHtml(I18n.t(language, "dashboard.searchPlaceholder")),
                escapeHtml(I18n.t(language, "common.status")),
                buildStatusOptions(language, status),
                escapeHtml(I18n.t(language, "dashboard.filterSubmit")),
                escapeHtml(I18n.t(language, "dashboard.linksSection")),
                escapeHtml(I18n.format(language, "dashboard.pageSummary",
                        Math.max(1, page.pageNumber()),
                        Math.max(1, page.totalPages()),
                        page.totalItems())),
                escapeHtml(I18n.t(language, "dashboard.historyCancel")),
                escapeHtml(I18n.t(language, "dashboard.historyDelete")),
                escapeHtml(search == null ? "" : search),
                escapeHtml(status == null ? "" : status),
                Math.max(1, page.pageNumber()),
                Math.max(1, page.pageSize()),
                escapeHtml(I18n.t(language, "dashboard.historySelect")),
                escapeHtml(I18n.t(language, "table.shortLink")),
                escapeHtml(I18n.t(language, "table.originalUrl")),
                escapeHtml(I18n.t(language, "table.status")),
                escapeHtml(I18n.t(language, "table.totalClicks")),
                escapeHtml(I18n.t(language, "table.customAlias")),
                escapeHtml(I18n.t(language, "table.expiresAt")),
                escapeHtml(I18n.t(language, "table.createdAt")),
                escapeHtml(I18n.t(language, "table.actions")),
                rows,
                renderPagination(language, page, search, status)
        );
        return layout(language, I18n.t(language, "dashboard.title"), body, user, appBaseUrl, currentPath);
    }


    public static String statsPage(Language language, User user, ShortLink link, List<DailyStat> stats, String message, String appBaseUrl, String currentPath) {
        Instant now = Instant.now();
        long maxClicks = 1L;
        for (DailyStat stat : stats) {
            maxClicks = Math.max(maxClicks, stat.clickCount());
        }

        StringBuilder rows = new StringBuilder();
        for (DailyStat stat : stats) {
            long percentage = Math.max(5, Math.round(stat.clickCount() * 100.0 / maxClicks));
            rows.append("""
                    <tr>
                        <td data-label="%s">%s</td>
                        <td data-label="%s">%d</td>
                        <td data-label="%s">
                            <div class="chart-bar"><span style="width:%d%%"></span></div>
                        </td>
                    </tr>
                    """.formatted(
                    escapeHtml(I18n.t(language, "stats.date")),
                    escapeHtml(stat.statDate().toString()),
                    escapeHtml(I18n.t(language, "stats.clicks")),
                    stat.clickCount(),
                    escapeHtml(I18n.t(language, "stats.chart")),
                    percentage
            ));
        }
        if (rows.isEmpty()) {
            rows.append("""
                    <tr>
                        <td colspan="3" class="empty-cell">%s</td>
                    </tr>
                    """.formatted(escapeHtml(I18n.t(language, "stats.noData"))));
        }

        String body = """
                <section class="page-shell">
                    <div class="page-header">
                        <div>
                            <h1>%s</h1>
                            <p class="muted">%s: <code>%s</code></p>
                        </div>
                        <a href="/app/dashboard" class="button button-secondary">%s</a>
                    </div>

                    %s

                    <section class="stats-grid">
                        <article class="card stat-card">
                            <span class="stat-label">%s</span>
                            <code>%s/%s</code>
                        </article>
                        <article class="card stat-card">
                            <span class="stat-label">%s</span>
                            <span class="stat-value">%d</span>
                        </article>
                        <article class="card stat-card">
                            <span class="stat-label">%s</span>
                            <span class="stat-value">%s</span>
                        </article>
                    </section>

                    <section class="card">
                        <div class="meta-list">
                            <div><strong>%s</strong> <span class="break-all">%s</span></div>
                            <div><strong>%s</strong> <span>%s</span></div>
                            <div><strong>%s</strong> <span>%s</span></div>
                        </div>
                    </section>

                    <section class="card">
                        <div class="card-head">
                            <h2>%s</h2>
                        </div>
                        <div class="table-wrap">
                            <table class="responsive-table">
                                <thead>
                                    <tr>
                                        <th>%s</th>
                                        <th>%s</th>
                                        <th>%s</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    %s
                                </tbody>
                            </table>
                        </div>
                    </section>
                </section>
                """.formatted(
                escapeHtml(I18n.t(language, "stats.heading")),
                escapeHtml(I18n.t(language, "common.routeKey")),
                escapeHtml(link.routeKey()),
                escapeHtml(I18n.t(language, "common.back")),
                alertBlock(language, message, "error"),
                escapeHtml(I18n.t(language, "stats.shortLink")),
                escapeHtml(appBaseUrl),
                escapeHtml(link.routeKey()),
                escapeHtml(I18n.t(language, "stats.totalClicks")),
                link.totalClicks(),
                escapeHtml(I18n.t(language, "stats.currentStatus")),
                escapeHtml(I18n.status(language, link.effectiveStatus(now))),
                escapeHtml(I18n.t(language, "stats.originalUrl") + ":"),
                escapeHtml(link.originalUrl()),
                escapeHtml(I18n.t(language, "stats.expiresAt") + ":"),
                escapeHtml(renderDateTime(link.expiresAt())),
                escapeHtml(I18n.t(language, "stats.createdAt") + ":"),
                escapeHtml(renderDateTime(link.createdAt())),
                escapeHtml(I18n.t(language, "stats.last90Days")),
                escapeHtml(I18n.t(language, "stats.date")),
                escapeHtml(I18n.t(language, "stats.clicks")),
                escapeHtml(I18n.t(language, "stats.chart")),
                rows
        );
        return layout(language, I18n.t(language, "stats.title"), body, user, appBaseUrl, currentPath);
    }

    public static String publicErrorPage(Language language, String title, String humanMessage, String routeKey, int httpCode, String appBaseUrl, String currentPath) {
        String body = """
                <section class="page-auth">
                    <div class="card auth-card">
                        <div class="error-code">%d</div>
                        <h1>%s</h1>
                        <p class="muted">%s</p>
                        <div class="meta-pill">%s: <code>%s</code></div>
                        <a href="%s" class="button">%s</a>
                    </div>
                </section>
                """.formatted(
                httpCode,
                escapeHtml(localizeMessage(language, title)),
                escapeHtml(localizeMessage(language, humanMessage)),
                escapeHtml(I18n.t(language, "common.routeKey")),
                escapeHtml(routeKey == null ? "-" : routeKey),
                escapeHtml(appBaseUrl),
                escapeHtml(I18n.t(language, "error.goHome"))
        );
        return layout(language, localizeMessage(language, title), body, null, appBaseUrl, currentPath);
    }

    public static String infoPage(Language language, String title, String message, String appBaseUrl, String currentPath) {
        String body = """
                <section class="page-auth">
                    <div class="card auth-card">
                        <span class="hero-badge">%s</span>
                        <h1>%s</h1>
                        <p class="muted">%s</p>
                    </div>
                </section>
                """.formatted(
                escapeHtml(I18n.t(language, "app.name")),
                escapeHtml(localizeMessage(language, title)),
                escapeHtml(localizeMessage(language, message))
        );
        return layout(language, localizeMessage(language, title), body, null, appBaseUrl, currentPath);
    }

    private static String layout(Language language, String title, String body, User currentUser, String appBaseUrl, String currentPath) {
        String baseUrl = normalizeBaseUrl(appBaseUrl);
        String currentUserBlock = currentUser == null ? "" : """
                <div class="user-chip">
                    <span class="user-chip-label">%s</span>
                    <strong>%s</strong>
                </div>
                """.formatted(
                escapeHtml(I18n.t(language, "common.currentUser")),
                escapeHtml(currentUser.username())
        );

        return """
                <!doctype html>
                <html lang="%s">
                <head>
                    <meta charset="UTF-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1"/>
                    <title>%s</title>
                    <link rel="stylesheet" href="%s/app/assets/styles.css?v=%s"/>
                    <script defer src="%s/app/assets/app.js?v=%s"></script>
                </head>
                <body>
                    <div class="site-shell">
                        <header class="site-header">
                            <a class="brand" href="%s">
                                <span class="brand-mark">↗</span>
                                <span class="brand-text">
                                    <strong>%s</strong>
                                    <small>%s</small>
                                </span>
                            </a>
                            <div class="site-header-actions">
                                %s
                                %s
                            </div>
                        </header>
                        <main class="site-main">
                            %s
                        </main>
                    </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(language.code()),
                escapeHtml(title),
                escapeHtml(baseUrl),
                escapeHtml(AppConfig.ASSET_VERSION),
                escapeHtml(baseUrl),
                escapeHtml(AppConfig.ASSET_VERSION),
                escapeHtml(baseUrl),
                escapeHtml(I18n.t(language, "app.name")),
                escapeHtml(I18n.t(language, "app.tagline")),
                renderLanguageSwitcher(language, baseUrl, currentPath),
                currentUserBlock,
                body
        );
    }

    private static String renderLanguageSwitcher(Language currentLanguage, String appBaseUrl, String currentPath) {
        String enClass = currentLanguage == Language.EN ? " active" : "";
        String ruClass = currentLanguage == Language.RU ? " active" : "";
        String redirect = urlEncode(currentPath == null || currentPath.isBlank() ? "/" : currentPath);
        String enHref = normalizeBaseUrl(appBaseUrl) + "/app/lang?value=en&redirect=" + redirect;
        String ruHref = normalizeBaseUrl(appBaseUrl) + "/app/lang?value=ru&redirect=" + redirect;
        return """
                <div class="lang-switcher" aria-label="%s">
                    <a class="lang-button%s" href="%s">En</a>
                    <a class="lang-button%s" href="%s">Ру</a>
                </div>
                """.formatted(
                escapeHtml(I18n.t(currentLanguage, "lang.switch")),
                enClass,
                escapeHtml(enHref),
                ruClass,
                escapeHtml(ruHref)
        );
    }

    
    private static String renderLinkRow(Language language, ShortLink link, LinkStatus effectiveStatus, String appBaseUrl) {
        String shortUrl = normalizeBaseUrl(appBaseUrl) + "/" + link.routeKey();
        StringBuilder actions = new StringBuilder();

        actions.append("""
                <a class="button button-secondary button-small" href="/app/links/stats?linkId=%s">%s</a>
                """.formatted(
                escapeHtml(link.id().toString()),
                escapeHtml(I18n.t(language, "action.stats"))
        ));

        if (effectiveStatus == LinkStatus.ACTIVE) {
            actions.append("""
                    <form method="post" action="/app/links/toggle">
                        <input type="hidden" name="linkId" value="%s"/>
                        <input type="hidden" name="targetState" value="disable"/>
                        <button type="submit" class="button button-warning button-small">%s</button>
                    </form>
                    """.formatted(
                    escapeHtml(link.id().toString()),
                    escapeHtml(I18n.t(language, "action.disable"))
            ));
        } else if (effectiveStatus == LinkStatus.DISABLED) {
            actions.append("""
                    <form method="post" action="/app/links/toggle">
                        <input type="hidden" name="linkId" value="%s"/>
                        <input type="hidden" name="targetState" value="enable"/>
                        <button type="submit" class="button button-secondary button-small">%s</button>
                    </form>
                    """.formatted(
                    escapeHtml(link.id().toString()),
                    escapeHtml(I18n.t(language, "action.enable"))
            ));
        }

        if (effectiveStatus != LinkStatus.DELETED) {
            actions.append("""
                    <form method="post" action="/app/links/delete" data-confirm="1">
                        <input type="hidden" name="linkId" value="%s"/>
                        <button type="submit" class="button button-danger button-small">%s</button>
                    </form>
                    """.formatted(
                    escapeHtml(link.id().toString()),
                    escapeHtml(I18n.t(language, "action.delete"))
            ));
        }

        String confirmKind = switch (effectiveStatus) {
            case ACTIVE -> "active";
            case DISABLED -> "disabled";
            default -> "none";
        };
        boolean requiresConfirm = effectiveStatus == LinkStatus.ACTIVE || effectiveStatus == LinkStatus.DISABLED;

        return """
                <tr>
                    <td class="history-select-cell" data-label="%s">
                        <label class="history-select-control">
                            <input class="history-select-checkbox" form="history-delete-form" type="checkbox" name="selectedLinkId" value="%s" data-confirm-kind="%s" data-requires-confirm="%s"/>
                            <span class="sr-only">%s</span>
                        </label>
                    </td>
                    <td data-label="%s">
                        <a href="%s" target="_blank" rel="noreferrer">%s</a>
                    </td>
                    <td data-label="%s">
                        <span class="break-all">%s</span>
                    </td>
                    <td data-label="%s">%s</td>
                    <td data-label="%s">%d</td>
                    <td data-label="%s">%s</td>
                    <td data-label="%s">%s</td>
                    <td data-label="%s">%s</td>
                    <td data-label="%s">
                        <div class="actions">%s</div>
                    </td>
                </tr>
                """.formatted(
                escapeHtml(I18n.t(language, "dashboard.historySelect")),
                escapeHtml(link.id().toString()),
                escapeHtml(confirmKind),
                requiresConfirm ? "true" : "false",
                escapeHtml(I18n.t(language, "dashboard.historySelect")),
                escapeHtml(I18n.t(language, "table.shortLink")),
                escapeHtml(shortUrl),
                escapeHtml(shortUrl),
                escapeHtml(I18n.t(language, "table.originalUrl")),
                escapeHtml(link.originalUrl()),
                escapeHtml(I18n.t(language, "table.status")),
                statusBadge(language, effectiveStatus),
                escapeHtml(I18n.t(language, "table.totalClicks")),
                link.totalClicks(),
                escapeHtml(I18n.t(language, "table.customAlias")),
                escapeHtml(I18n.yesNo(language, link.customAlias())),
                escapeHtml(I18n.t(language, "table.expiresAt")),
                escapeHtml(renderDateTime(link.expiresAt())),
                escapeHtml(I18n.t(language, "table.createdAt")),
                escapeHtml(renderDateTime(link.createdAt())),
                escapeHtml(I18n.t(language, "table.actions")),
                actions
        );
    }

    private static String renderDashboardClientStrings(Language language) {
        return """
                <div id="dashboard-i18n" hidden
                     data-copy-success="%s"
                     data-copy-failure="%s"
                     data-copy-prompt="%s"
                     data-history-no-selection="%s"
                     data-history-confirm-title="%s"
                     data-history-confirm-active="%s"
                     data-history-confirm-disabled="%s"
                     data-common-no="%s"
                     data-common-yes="%s"></div>
                """.formatted(
                escapeHtml(I18n.t(language, "toast.copied")),
                escapeHtml(I18n.t(language, "toast.copyFailed")),
                escapeHtml(I18n.t(language, "toast.copyPrompt")),
                escapeHtml(I18n.t(language, "dashboard.historyDeleteNoSelection")),
                escapeHtml(I18n.t(language, "dashboard.historyConfirmTitle")),
                escapeHtml(I18n.t(language, "dashboard.historyConfirmActive")),
                escapeHtml(I18n.t(language, "dashboard.historyConfirmDisabled")),
                escapeHtml(I18n.t(language, "common.no")),
                escapeHtml(I18n.t(language, "common.yes"))
        );
    }

    private static String renderDeleteHistoryModal(Language language) {
        return """
                <div id="history-confirm-backdrop" class="modal-backdrop" hidden>
                    <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="history-confirm-title">
                        <h3 id="history-confirm-title">%s</h3>
                        <p id="history-confirm-message" class="muted"></p>
                        <div class="modal-actions">
                            <button type="button" class="button button-danger" id="history-confirm-yes">%s</button>
                            <button type="button" class="button button-secondary" id="history-confirm-no">%s</button>
                        </div>
                    </div>
                </div>
                """.formatted(
                escapeHtml(I18n.t(language, "dashboard.historyConfirmTitle")),
                escapeHtml(I18n.t(language, "common.yes")),
                escapeHtml(I18n.t(language, "common.no"))
        );
    }

    private static String renderPagination(Language language, Page<ShortLink> page, String search, String status) {
        if (page.totalPages() <= 1) {
            return "";
        }

        String previous = "";
        String next = "";
        if (page.pageNumber() > 1) {
            previous = """
                    <a class="button button-secondary" href="%s">%s</a>
                    """.formatted(
                    escapeHtml(buildPageHref(page.pageNumber() - 1, page.pageSize(), search, status)),
                    "←"
            );
        }
        if (page.pageNumber() < page.totalPages()) {
            next = """
                    <a class="button button-secondary" href="%s">%s</a>
                    """.formatted(
                    escapeHtml(buildPageHref(page.pageNumber() + 1, page.pageSize(), search, status)),
                    "→"
            );
        }

        return """
                <nav class="pagination">
                    %s
                    <span class="pagination-text">%s</span>
                    %s
                </nav>
                """.formatted(
                previous,
                escapeHtml(I18n.format(language, "dashboard.pageSummary",
                        page.pageNumber(),
                        page.totalPages(),
                        page.totalItems())),
                next
        );
    }

    private static String buildStatusOptions(Language language, String selectedStatus) {
        String normalized = selectedStatus == null ? "" : selectedStatus.trim().toUpperCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        builder.append(option("", I18n.t(language, "filter.all"), normalized.isBlank()));
        for (LinkStatus status : LinkStatus.values()) {
            builder.append(option(status.name(), I18n.status(language, status), status.name().equals(normalized)));
        }
        return builder.toString();
    }

    private static String option(String value, String label, boolean selected) {
        return """
                <option value="%s"%s>%s</option>
                """.formatted(
                escapeHtml(value),
                selected ? " selected" : "",
                escapeHtml(label)
        );
    }

    private static String buildPageHref(int pageNumber, int pageSize, String search, String status) {
        return "/app/dashboard?page=" + pageNumber
                + "&size=" + pageSize
                + "&q=" + urlEncode(search == null ? "" : search)
                + "&status=" + urlEncode(status == null ? "" : status);
    }

    private static String alertBlock(Language language, String message, String type) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String normalizedType = "success".equalsIgnoreCase(type) ? "success" : "error";
        return """
                <div class="alert %s">%s</div>
                """.formatted(
                escapeHtml(normalizedType),
                escapeHtml(localizeMessage(language, message))
        );
    }

    private static String statusBadge(Language language, LinkStatus status) {
        return """
                <span class="status-badge status-%s">%s</span>
                """.formatted(
                escapeHtml(status.name().toLowerCase(Locale.ROOT)),
                escapeHtml(I18n.status(language, status))
        );
    }

    private static String localizeMessage(Language language, String messageOrKey) {
        if (messageOrKey == null || messageOrKey.isBlank()) {
            return "";
        }
        return I18n.t(language, messageOrKey);
    }

    private static String renderDateTime(Instant instant) {
        return instant == null ? "—" : MOSCOW_FORMATTER.format(instant);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }


    private static String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return AppConfig.BASE_URL;
        }
        String trimmed = rawBaseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
