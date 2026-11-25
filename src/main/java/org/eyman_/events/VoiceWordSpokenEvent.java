package org.eyman_.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the main thread when a keyword is heard. Cancel it to stop the actions. */
public final class VoiceWordSpokenEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String keyword;
    private final float confidence;
    private boolean cancelled;

    public VoiceWordSpokenEvent(@NotNull Player player, @NotNull String keyword, float confidence) {
        super(false);
        this.player = player;
        this.keyword = keyword;
        this.confidence = confidence;
        this.cancelled = false;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public String getKeyword() {
        return keyword;
    }

    public float getConfidence() {
        return confidence;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}