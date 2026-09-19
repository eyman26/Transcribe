package org.eyman_.activation;

import org.eyman_.api.TranscribeAPI;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the configured gestures/conditions that open a player's Listen Window and forwards them
 * to {@link TranscribeAPI}, the single source of truth for window state and expiry.
 */
public final class ActivationManager implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ActivationConfig config;
    private final boolean placeholderApiAvailable;

    private boolean registered;
    private BukkitTask placeholderPollingTask;

    public ActivationManager(@NotNull final JavaPlugin plugin, @NotNull final ActivationConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.config = Objects.requireNonNull(config, "config");
        this.placeholderApiAvailable = config.papiPlaceholder() != null
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (config.papiPlaceholder() != null && !placeholderApiAvailable) {
            logger.warning("A PlaceholderAPI activation condition is configured, but PlaceholderAPI "
                    + "is not installed/enabled - that trigger will be disabled.");
        }
    }

    /**
     * Registers this class as a Bukkit listener and, if configured and available, starts the
     * PlaceholderAPI condition polling task. Safe to call at most once per instance, subsequent
     * calls are no-ops.
     */
    public void register() {
        if (registered) {
            return;
        }
        registered = true;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (placeholderApiAvailable) {
            final long intervalTicks = Math.max(1, config.papiPollIntervalTicks());
            this.placeholderPollingTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::pollPlaceholderConditions, intervalTicks, intervalTicks);
            logger.info(() -> "PlaceholderAPI activation condition '" + config.papiPlaceholder()
                    + "' == '" + config.papiExpectedValue() + "' is being polled every "
                    + intervalTicks + " ticks.");
        }
    }

    /**
     * Cancels the polling task (if running) and unregisters all Bukkit event handlers owned by
     * this instance. Safe to call multiple times, and safe to call even if {@link #register()}
     * was never called.
     */
    public void shutdown() {
        if (placeholderPollingTask != null) {
            placeholderPollingTask.cancel();
            placeholderPollingTask = null;
        }
        HandlerList.unregisterAll(this);
        registered = false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(@NotNull final PlayerDropItemEvent event) {
        if (event.getPlayer().isSneaking()) {
            triggerActivation(event.getPlayer(), "shift+q (stack drop)");
        }
    }

    @EventHandler
    public void onSwapHandItems(@NotNull final PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().isSneaking()) {
            triggerActivation(event.getPlayer(), "shift+f (swap hands)");
        }
    }

    @EventHandler
    public void onItemHeld(@NotNull final PlayerItemHeldEvent event) {
        if (config.activationHotbarSlot() >= 0 && event.getNewSlot() == config.activationHotbarSlot()) {
            triggerActivation(event.getPlayer(), "hotbar slot " + event.getNewSlot());
        }
    }

    /**
     * Polls the configured PlaceholderAPI expression for every online player. Runs on the main
     * thread (scheduled via {@code runTaskTimer}) since arbitrary placeholder expansions are not
     * guaranteed thread-safe by third-party expansion authors.
     */
    private void pollPlaceholderConditions() {
        final String placeholder = config.papiPlaceholder();
        if (placeholder == null) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final String resolved;
            try {
                resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
            } catch (final RuntimeException papiError) {
                logger.log(Level.WARNING, "Failed to resolve PlaceholderAPI condition '" + placeholder
                        + "' for " + player.getName(), papiError);
                continue;
            }
            if (resolved != null && resolved.trim().equalsIgnoreCase(config.papiExpectedValue())) {
                triggerActivation(player, "placeholderapi");
            }
        }
    }

    private void triggerActivation(@NotNull final Player player, @NotNull final String reason) {
        final boolean opened = TranscribeAPI.get().forceStartListenWindow(player, config.windowDuration());
        if (opened) {
            logger.fine(() -> "Opened Listen Window for " + player.getName() + " via " + reason);
        }
    }

    /**
     * Immutable configuration for an {@link ActivationManager} instance, typically populated
     * from {@code config.yml} by the main plugin class.
     *
     * @param windowDuration        how long a triggered Listen Window stays open; must be positive
     * @param activationHotbarSlot  0-based hotbar slot (0-8) that opens a window when selected,
     *                              or {@code -1} to disable the hotbar-slot trigger entirely
     * @param papiPlaceholder       a PlaceholderAPI expression (e.g. {@code "%someplugin_cast%"})
     *                              to poll, or {@code null} to disable the PlaceholderAPI trigger
     * @param papiExpectedValue     the value {@code papiPlaceholder} must resolve to (case-insensitive)
     *                              to count as an activation; ignored if {@code papiPlaceholder} is null
     * @param papiPollIntervalTicks how often, in server ticks, to poll {@code papiPlaceholder}
     */
    public record ActivationConfig(@NotNull Duration windowDuration,
                                    int activationHotbarSlot,
                                    @Nullable String papiPlaceholder,
                                    @NotNull String papiExpectedValue,
                                    long papiPollIntervalTicks) {

        public ActivationConfig {
            Objects.requireNonNull(windowDuration, "windowDuration");
            Objects.requireNonNull(papiExpectedValue, "papiExpectedValue");
            if (windowDuration.isZero() || windowDuration.isNegative()) {
                throw new IllegalArgumentException("windowDuration must be positive, was: " + windowDuration);
            }
            if (activationHotbarSlot < -1 || activationHotbarSlot > 8) {
                throw new IllegalArgumentException(
                        "activationHotbarSlot must be -1 (disabled) or in range 0-8, was: " + activationHotbarSlot);
            }
            if (papiPlaceholder != null && papiPlaceholder.isBlank()) {
                papiPlaceholder = null;
            }
            if (papiExpectedValue.isBlank()) {
                throw new IllegalArgumentException("papiExpectedValue must not be blank");
            }
            if (papiPollIntervalTicks <= 0) {
                throw new IllegalArgumentException(
                        "papiPollIntervalTicks must be positive, was: " + papiPollIntervalTicks);
            }
        }
    }
}
