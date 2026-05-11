package urlshortener.model;

import java.util.List;

/**
 * Универсальная модель страницы результата для списков в личном кабинете.
 */
public record Page<T>(
        List<T> items,
        int pageNumber,
        int pageSize,
        int totalItems,
        int totalPages
) {
}
