package org.eyman_.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe public API for querying or force-opening a player's "Listen Window".
 */
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

    // Lifecycle hooks for the main plugin class only; not part of the public contract.
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
     * @return the running singleton instance
     * @throws IllegalStateException if called before Transcribe has finished enabling, or after
     *                                it has been disabled
     */
    @NotNull
    public static TranscribeAPI get() {
        final TranscribeAPI current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "TranscribeAPI is not available - Transcribe is not currently enabled on this server.");
        }
        return current;
    }

    /**
     * Internal hook for the main plugin to be told when a Listen Window opens or closes
     * (naturally expiring or force-stopped) - used to start/stop audio capture and play cues.
     * Not part of the public contract for third-party plugins; they should use
     * {@link #isListening} instead of trying to observe window lifecycle directly.
     *
     * <p>Callbacks may fire from any thread: window opens are called from whatever thread called
     * {@link #forceStartListenWindow}, but natural expiry fires from this class's internal
     * virtual-thread scheduler. Listener implementations are responsible for hopping to the main
     * thread themselves before touching any Bukkit API.</p>
     */
    public void internalAddLifecycleListener(@NotNull final WindowLifecycleListener listener) {
        lifecycleListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyOpened(@NotNull final UUID playerId) {
        for (final WindowLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onWindowOpened(playerId);
            } catch (final RuntimeException e) {
                LOGGER.log(Level.WARNING, "A WindowLifecycleListener threw on window open for " + playerId, e);
            }
        }
    }

    private void notifyClosed(@NotNull final UUID playerId) {
        for (final WindowLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onWindowClosed(playerId);
            } catch (final RuntimeException e) {
                LOGGER.log(Level.WARNING, "A WindowLifecycleListener threw on window close for " + playerId, e);
            }
        }
    }

    public boolean isListening(@NotNull final Player player) {
        return isListening(player.getUniqueId());
    }

    public boolean isListening(@NotNull final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        final ListenWindow window = activeWindows.get(playerId);
        return window != null && window.expiresAt().isAfter(Instant.now());
    }

    /**
     * Opens a window using {@link #DEFAULT_WINDOW_DURATION}. Returns {@code false} if one was
     * already open (and was refreshed).
     */
    public boolean forceStartListenWindow(@NotNull final Player player) {
        return forceStartListenWindow(player, DEFAULT_WINDOW_DURATION);
    }

    /**
     * Opens a Listen Window for {@code duration}; while open, the player's audio is forwarded
     * to the active engine instead of dropped. Returns {@code false} if a window was already
     * open (and was refreshed with the new duration).
     *
     * @throws IllegalArgumentException if {@code duration} is zero or negative
     */
    public boolean forceStartListenWindow(@NotNull final Player player, @NotNull final Duration duration) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive, was: " + duration);
        }

        final UUID playerId = player.getUniqueId();
        final boolean alreadyOpen = isListening(playerId);
        final Instant expiresAt = Instant.now().plus(duration);

        final ListenWindow previous = activeWindows.put(playerId, new ListenWindow(playerId, expiresAt));
        if (previous != null) {
            previous.expiryTask().cancel(false);
        }

        final ScheduledFuture<?> expiryTask = expiryScheduler.schedule(
                () -> {
                    activeWindows.remove(playerId, activeWindows.get(playerId));
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

    /**
     * Force-closes a player's Listen Window early, if one is open.
     */
    public boolean forceStopListenWindow(@NotNull final Player player) {
        Objects.requireNonNull(player, "player");
        final UUID playerId = player.getUniqueId();
        final ListenWindow removed = activeWindows.remove(playerId);
        if (removed != null && removed.expiryTask() != null) {
            removed.expiryTask().cancel(false);
        }
        if (removed != null) {
            notifyClosed(playerId);
        }
        return removed != null;
    }

    /** Observes Listen Window lifecycle events. See {@link #internalAddLifecycleListener}. */
    public interface WindowLifecycleListener {
        void onWindowOpened(@NotNull UUID playerId);
        void onWindowClosed(@NotNull UUID playerId);
    }

    // Stores the scheduled expiry task so it can be cancelled on early close or replacement.
    private record ListenWindow(UUID playerId, Instant expiresAt, ScheduledFuture<?> expiryTask) {

        ListenWindow(UUID playerId, Instant expiresAt) {
            this(playerId, expiresAt, null);
        }

        ListenWindow withExpiryTask(ScheduledFuture<?> task) {
            return new ListenWindow(playerId, expiresAt, task);
        }
    }
}