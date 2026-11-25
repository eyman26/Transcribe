package org.eyman_.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Public API for checking or forcing a player's listen window. Thread-safe. */
public final class TranscribeAPI {

    private static volatile TranscribeAPI instance;

    public static final Duration DEFAULT_WINDOW_DURATION = Duration.ofSeconds(4);

    private final ConcurrentHashMap<UUID, ListenWindow> activeWindows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("transcribe-window-expiry", 0).factory());
    private final List<WindowLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private static final Logger LOGGER = Logger.getLogger(TranscribeAPI.class.getName());

    private TranscribeAPI() {
    }

    public static synchronized void internalBootstrap() {
        if (instance == null) {
            instance = new TranscribeAPI();
        }
    }

    public static synchronized void internalShutdown() {
        if (instance != null) {
            instance.expiryScheduler.shutdownNow();
            instance.activeWindows.clear();
            instance = null;
        }
    }

    /**
     * @throws IllegalStateException if Transcribe isn't enabled
     */
    @NotNull
    public static TranscribeAPI get() {
        TranscribeAPI current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "Transcribe isn't enabled");
        }
        return current;
    }

    // only the plugin itself should call this
    public void internalAddLifecycleListener(@NotNull WindowLifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    private void notifyOpened(@NotNull UUID playerId) {
        for (WindowLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onWindowOpened(playerId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "listener failed on open for " + playerId, e);
            }
        }
    }

    private void notifyClosed(@NotNull UUID playerId) {
        for (WindowLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onWindowClosed(playerId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "listener failed on close for " + playerId, e);
            }
        }
    }

    public boolean isListening(@NotNull Player player) {
        return isListening(player.getUniqueId());
    }

    public boolean isListening(@NotNull UUID playerId) {
        ListenWindow window = activeWindows.get(playerId);
        return window != null && window.expiresAt().isAfter(Instant.now());
    }

    public boolean forceStartListenWindow(@NotNull Player player) {
        return forceStartListenWindow(player, DEFAULT_WINDOW_DURATION);
    }

    /** Returns false if a window was already open (it gets refreshed). */
    public boolean forceStartListenWindow(@NotNull Player player, @NotNull Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive, was: " + duration);
        }

        UUID playerId = player.getUniqueId();
        boolean alreadyOpen = isListening(playerId);
        Instant expiresAt = Instant.now().plus(duration);

        ListenWindow previous = activeWindows.put(playerId, new ListenWindow(playerId, expiresAt));
        if (previous != null) {
            previous.expiryTask().cancel(false);
        }

        ScheduledFuture<?> expiryTask = expiryScheduler.schedule(
                () -> {
                    activeWindows.remove(playerId);
                    notifyClosed(playerId);
                },
                duration.toMillis(),
                TimeUnit.MILLISECONDS
        );
        activeWindows.computeIfPresent(playerId, (id, window) -> window.withExpiryTask(expiryTask));

        if (!alreadyOpen) {
            notifyOpened(playerId);
        }
        return !alreadyOpen;
    }

    public boolean forceStopListenWindow(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        ListenWindow removed = activeWindows.remove(playerId);
        if (removed != null && removed.expiryTask() != null) {
            removed.expiryTask().cancel(false);
        }
        if (removed != null) {
            notifyClosed(playerId);
        }
        return removed != null;
    }

    public interface WindowLifecycleListener {
        void onWindowOpened(@NotNull UUID playerId);
        void onWindowClosed(@NotNull UUID playerId);
    }

    private record ListenWindow(UUID playerId, Instant expiresAt, ScheduledFuture<?> expiryTask) {

        ListenWindow(UUID playerId, Instant expiresAt) {
            this(playerId, expiresAt, null);
        }

        ListenWindow withExpiryTask(ScheduledFuture<?> task) {
            return new ListenWindow(playerId, expiresAt, task);
        }
    }
}