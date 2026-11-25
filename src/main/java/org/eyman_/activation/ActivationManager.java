package org.eyman_.activation;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.eyman_.api.TranscribeAPI;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ActivationManager implements Listener {

    public enum TriggerType {
        OFF_HAND,
        SNEAK_OFF_HAND,
        LEFT_CLICK,
        SNEAK_LEFT_CLICK,
        RIGHT_CLICK,
        SNEAK_RIGHT_CLICK,
        HOTBAR_SLOT,
        SNEAK_HOTBAR_SLOT;

        public static TriggerType parse(String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ActivationConfig config;
    private final boolean placeholderApiAvailable;
    private final Predicate<Player> blocked;

    private boolean registered;
    private BukkitTask placeholderPollingTask;

    public ActivationManager(JavaPlugin plugin, ActivationConfig config,
                             Predicate<Player> blocked) {
        this.blocked = blocked;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.placeholderApiAvailable = config.papiPlaceholder() != null
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (config.papiPlaceholder() != null && !placeholderApiAvailable) {
            logger.warning("PlaceholderAPI trigger is configured but PlaceholderAPI isn't installed, skipping it.");
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
            logger.info("Polling " + config.papiPlaceholder() + " every " + intervalTicks + " ticks");
        }

        if (config.debugLevel() >= 1) {
            logger.info("[Transcribe debug] triggers: "
                    + config.enabledTriggers() + " | hotbar slots: " + config.hotbarSlots()
                    + " | PlaceholderAPI trigger: " + placeholderApiAvailable);
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

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        final boolean sneaking = player.isSneaking();

        if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_OFF_HAND)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "SNEAK_OFF_HAND");
        } else if (!sneaking && config.enabledTriggers().contains(TriggerType.OFF_HAND)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "OFF_HAND");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        final Action action = event.getAction();
        final boolean sneaking = player.isSneaking();

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_LEFT_CLICK)) {
                if (config.cancelTriggerEvents()) event.setCancelled(true);
                triggerActivation(player, "SNEAK_LEFT_CLICK");
            } else if (!sneaking && config.enabledTriggers().contains(TriggerType.LEFT_CLICK)) {
                if (config.cancelTriggerEvents()) event.setCancelled(true);
                triggerActivation(player, "LEFT_CLICK");
            }
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_RIGHT_CLICK)) {
                if (config.cancelTriggerEvents()) event.setCancelled(true);
                triggerActivation(player, "SNEAK_RIGHT_CLICK");
            } else if (!sneaking && config.enabledTriggers().contains(TriggerType.RIGHT_CLICK)) {
                if (config.cancelTriggerEvents()) event.setCancelled(true);
                triggerActivation(player, "RIGHT_CLICK");
            }
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        final boolean sneaking = player.isSneaking();
        final int newSlot0Based = event.getNewSlot();

        if (!config.hotbarSlots().contains(newSlot0Based)) {
            return;
        }

        if (sneaking && config.enabledTriggers().contains(TriggerType.SNEAK_HOTBAR_SLOT)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "SNEAK_HOTBAR_SLOT (" + newSlot0Based + ")");
        } else if (!sneaking && config.enabledTriggers().contains(TriggerType.HOTBAR_SLOT)) {
            if (config.cancelTriggerEvents()) event.setCancelled(true);
            triggerActivation(player, "HOTBAR_SLOT (" + newSlot0Based + ")");
        }
    }

    private void pollPlaceholderConditions() {
        final String placeholder = config.papiPlaceholder();
        if (placeholder == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            final String resolved;
            try {
                resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
            } catch (RuntimeException papiError) {
                logger.log(Level.WARNING, "Failed to resolve PlaceholderAPI condition '" + placeholder
                        + "' for " + player.getName(), papiError);
                continue;
            }
            if (resolved != null && resolved.trim().equalsIgnoreCase(config.papiExpectedValue())) {
                triggerActivation(player, "PLACEHOLDERAPI");
            }
        }
    }

    private void triggerActivation(Player player, String reason) {
        if (blocked.test(player)) {
            if (config.debugLevel() >= 2) {
                logger.info("[Transcribe debug] " + reason + " ignored for " + player.getName() + " (muted)");
            }
            return;
        }
        if (config.debugLevel() >= 2) {
            logger.info("[Transcribe debug] Trigger fired: " + reason + " by " + player.getName());
        }

        final boolean opened = TranscribeAPI.get().forceStartListenWindow(player, config.windowDuration());
        if (opened) {
            if (config.debugLevel() >= 1) {
                logger.info("[Transcribe debug] Listen Window opened for " + player.getName() + " via " + reason);
            }
            config.cues().playOpenCue(player);
        } else if (config.debugLevel() >= 2) {
            logger.info("[Transcribe debug] " + reason + " refreshed an already-open window for " + player.getName());
        }
    }

    public record ActivationConfig(Duration windowDuration,
                                   Set<TriggerType> enabledTriggers,
                                   List<Integer> hotbarSlots,
                                   boolean cancelTriggerEvents,
                                   CueSettings cues,
                                   String papiPlaceholder,
                                   String papiExpectedValue,
                                   long papiPollIntervalTicks,
                                   int debugLevel) {

        public ActivationConfig {

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
            if (debugLevel < 0 || debugLevel > 3) {
                throw new IllegalArgumentException("debugLevel must be 0-3, was: " + debugLevel);
            }

            enabledTriggers = Collections.unmodifiableSet(EnumSet.copyOf(enabledTriggers.isEmpty() 
                    ? EnumSet.noneOf(TriggerType.class) : enabledTriggers));
            hotbarSlots = List.copyOf(hotbarSlots);
        }
    }
}