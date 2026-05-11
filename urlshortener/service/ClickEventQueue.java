package urlshortener.service;

import urlshortener.model.ClickEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Простейшая внутрипроцессная очередь кликов для демо-режима и самодостаточного тестового стенда.
 */
public final class ClickEventQueue implements ClickEventBus {

    private final LinkedBlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong(0L);

    @Override
    public void publish(ClickEvent event) {
        if (event == null) {
            return;
        }
        queue.offer(new Envelope(String.valueOf(sequence.incrementAndGet()), event));
    }

    @Override
    public List<Envelope> readBatch(int maxItems, Duration waitTime) {
        int safeMaxItems = Math.max(1, maxItems);
        long timeoutMillis = waitTime == null ? 0L : Math.max(0L, waitTime.toMillis());
        List<Envelope> batch = new ArrayList<>(safeMaxItems);
        try {
            Envelope first = timeoutMillis <= 0L ? queue.poll() : queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (first != null) {
                batch.add(first);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return batch;
        }
        queue.drainTo(batch, Math.max(0, safeMaxItems - batch.size()));
        return batch;
    }

    @Override
    public void acknowledge(List<Envelope> envelopes) {
        // Для внутрипроцессной очереди подтверждение не требуется: элементы удаляются при чтении.
    }

    @Override
    public int size() {
        return queue.size();
    }
}
