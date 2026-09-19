package org.eyman_.activation;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.eyman_.api.TranscribeAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the configured client actions/conditions that open a player's Listen Window.
 */
public final class ActivationManager implements Listener {

    public enum TriggerType {
        SWAP_HAND,
        SNEAK_SWAP_HAND,
        DROP_ITEM,
        SNEAK_DROP_ITEM,
        LEFT_CLICK,
        SNEAK_LEFT_CLICK,
        RIGHT_CLICK,
        SNEAK_RIGHT_CLICK,
        HOTBAR_SLOT;

        @Nullable
        public static TriggerType parse(@NotNull final String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
    }

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
        final Player player = event.getPlayer();
        final boolean sneaking = player.isSneaking();

        if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_DROP_ITEM)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "SNEAK_DROP_ITEM");
        } else if (!sneaking && config.enabledTriggers().contains(TriggerType.DROP_ITEM)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "DROP_ITEM");
        }
    }

    @EventHandler
    public void onSwapHandItems(@NotNull final PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        final boolean sneaking = player.isSneaking();

        if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_SWAP_HAND)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "SNEAK_SWAP_HAND");
        } else if (!sneaking && config.enabledTriggers().contains(TriggerType.SWAP_HAND)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "SWAP_HAND");
        }
    }

    @EventHandler
    public void onPlayerInteract(@NotNull final PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        final Action action = event.getAction();
        final boolean sneaking = player.isSneaking();

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_LEFT_CLICK)) {
                triggerActivation(player, "SNEAK_LEFT_CLICK");
            } else if (!sneaking && config.enabledTriggers().contains(TriggerType.LEFT_CLICK)) {
                triggerActivation(player, "LEFT_CLICK");
            }
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_RIGHT_CLICK)) {
                triggerActivation(player, "SNEAK_RIGHT_CLICK");
            } else if (!sneaking && config.enabledTriggers().contains(TriggerType.RIGHT_CLICK)) {
                triggerActivation(player, "RIGHT_CLICK");
            }
        }
    }

    @EventHandler
    public void onItemHeld(@NotNull final PlayerItemHeldEvent event) {
        if (!config.enabledTriggers().contains(TriggerType.HOTBAR_SLOT)) {
            return;
        }

        final int newSlot0Based = event.getNewSlot();
        if (config.hotbarSlots().contains(newSlot0Based)) {
            triggerActivation(event.getPlayer(), "HOTBAR_SLOT (" + newSlot0Based + ")");
        }
    }

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
                triggerActivation(player, "PLACEHOLDERAPI");
            }
        }
    }

    private void triggerActivation(@NotNull final Player player, @NotNull final String reason) {
        final boolean opened = TranscribeAPI.get().forceStartListenWindow(player, config.windowDuration());
        if (opened) {
            logger.fine(() -> "Opened Listen Window for " + player.getName() + " via " + reason);
            
            if (config.enableActionBar()) {
                player.sendActionBar(Component.text("🎤 Listening...", NamedTextColor.GREEN));
            }
            if (config.enableSoundCues()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.8f);
            }
        }
    }

    public record ActivationConfig(@NotNull Duration windowDuration,
                                    @NotNull Set<TriggerType> enabledTriggers,
                                    @NotNull List<Integer> hotbarSlots,
                                    boolean cancelTriggerEvents,
                                    boolean enableActionBar,
                                    boolean enableSoundCues,
                                    @Nullable String papiPlaceholder,
                                    @NotNull String papiExpectedValue,
                                    long papiPollIntervalTicks) {

        public ActivationConfig {
            Objects.requireNonNull(windowDuration, "windowDuration");
            Objects.requireNonNull(enabledTriggers, "enabledTriggers");
            Objects.requireNonNull(hotbarSlots, "hotbarSlots");
            Objects.requireNonNull(papiExpectedValue, "papiExpectedValue");

            if (windowDuration.isZero() || windowDuration.isNegative()) {
                throw new IllegalArgumentException("windowDuration must be positive, was: " + windowDuration);
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

            enabledTriggers = Collections.unmodifiableSet(EnumSet.copyOf(enabledTriggers.isEmpty() 
                    ? EnumSet.noneOf(TriggerType.class) : enabledTriggers));
            hotbarSlots = List.copyOf(hotbarSlots);
        }
    }
}