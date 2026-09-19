package org.eyman_.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Public API for third-party plugins to query or force-open a player's "Listen Window".
 * Every method is thread-safe and may be called directly from async STT worker threads.
 */
public final class TranscribeAPI {

    private static volatile TranscribeAPI instance;

    /** Default Listen Window duration used when callers don't specify one. */
    public static final Duration DEFAULT_WINDOW_DURATION = Duration.ofSeconds(4);

    private final ConcurrentHashMap<UUID, ListenWindow> activeWindows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("transcribe-window-expiry", 0).factory());

    private TranscribeAPI() {
    }

    /**
     * Internal bootstrap hook. Called exactly once by the Transcribe main plugin class during
     * {@code onEnable()}. Not part of the public contract for third-party plugins.
     */
    public static synchronized void internalBootstrap() {
        if (instance == null) {
            instance = new TranscribeAPI();
        }
    }

    /**
     * Internal teardown hook. Called exactly once by the Transcribe main plugin class during
     * {@code onDisable()}. Not part of the public contract for third-party plugins.
     */
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
     * @param player the player to check
     * @return {@code true} if the player currently has an open, non-expired Listen Window
     */
    public boolean isListening(@NotNull final Player player) {
        return isListening(player.getUniqueId());
    }

    /**
     * @param playerId the UUID of the player to check
     * @return {@code true} if the player currently has an open, non-expired Listen Window
     */
    public boolean isListening(@NotNull final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        final ListenWindow window = activeWindows.get(playerId);
        return window != null && window.expiresAt().isAfter(Instant.now());
    }

    /**
     * Force-opens a Listen Window for the given player for {@link #DEFAULT_WINDOW_DURATION}.
     * If a window is already open for this player, it is extended/replaced by the new one.
     *
     * @param player the player to open a Listen Window for
     * @return {@code true} if a new window was opened, {@code false} if one was already open
     *         and has simply been refreshed
     */
    public boolean forceStartListenWindow(@NotNull final Player player) {
        return forceStartListenWindow(player, DEFAULT_WINDOW_DURATION);
    }

    /**
     * Force-opens a Listen Window for the given player for a custom duration. While the window
     * is open, the audio-gating layer will stop dropping Simple Voice Chat packets for this
     * player and forward them to the active {@code SpeechRecognitionEngine} instead.
     *
     * @param player   the player to open a Listen Window for
     * @param duration how long the window should stay open; must be positive
     * @return {@code true} if a new window was opened, {@code false} if one was already open
     *         and has simply been refreshed with the new duration
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
                () -> activeWindows.remove(playerId, activeWindows.get(playerId)),
                duration.toMillis(),
                TimeUnit.MILLISECONDS
        );
        activeWindows.computeIfPresent(playerId, (id, window) -> window.withExpiryTask(expiryTask));

        return !alreadyOpen;
    }

    /**
     * Force-closes a player's Listen Window early, if one is open.
     *
     * @param player the player to close the Listen Window for
     * @return {@code true} if an open window was found and closed, {@code false} otherwise
     */
    public boolean forceStopListenWindow(@NotNull final Player player) {
        Objects.requireNonNull(player, "player");
        final ListenWindow removed = activeWindows.remove(player.getUniqueId());
        if (removed != null && removed.expiryTask() != null) {
            removed.expiryTask().cancel(false);
        }
        return removed != null;
    }

    /**
     * Internal record tracking a single player's open Listen Window and its scheduled expiry
     * task, so it can be cancelled early on manual close or replacement.
     */
    private record ListenWindow(UUID playerId, Instant expiresAt, ScheduledFuture<?> expiryTask) {

        ListenWindow(UUID playerId, Instant expiresAt) {
            this(playerId, expiresAt, null);
        }

        ListenWindow withExpiryTask(ScheduledFuture<?> task) {
            return new ListenWindow(playerId, expiresAt, task);
        }
    }
}
