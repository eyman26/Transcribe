package org.eyman_.cooldown;


import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CooldownTracker {

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> until = new ConcurrentHashMap<>();

    public long remainingMillis(UUID playerId, String keywordId) {
        ConcurrentHashMap<String, Long> m = until.get(playerId);
        if (m == null) {
            return 0L;
        }
        Long end = m.get(keywordId);
        if (end == null) {
            return 0L;
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            m.remove(keywordId, end);
            return 0L;
        }
        return left;
    }

    public void start(UUID playerId, String keywordId, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        until.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(keywordId, System.currentTimeMillis() + cooldown.toMillis());
    }
}
