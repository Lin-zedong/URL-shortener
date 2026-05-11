package urlshortener.model;

import java.io.Serializable;

/**
 * Жизненный цикл короткой ссылки: active, disabled, expired и deleted.
 */
public enum LinkStatus implements Serializable {
    ACTIVE,
    DISABLED,
    EXPIRED,
    DELETED
}
