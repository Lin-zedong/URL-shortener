package urlshortener.service;

import urlshortener.model.ClickEvent;

import java.time.Duration;
import java.util.List;

/**
 * Шина событий кликов: публикация в redirect path и пакетное чтение из worker.
 */
public interface ClickEventBus {

    void publish(ClickEvent event);

    List<Envelope> readBatch(int maxItems, Duration waitTime);

    void acknowledge(List<Envelope> envelopes);

    int size();

    /**
     * Элемент очереди или стрима с транспортным идентификатором.
     */
    record Envelope(String id, ClickEvent event) {
    }
}
