package org.eyman_.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when the active engine transcribes a keyword from a player's Listen
 * Window. STT worker threads must never {@code callEvent} directly &mdash; they must schedule back
 * onto the main thread first. Cancelling prevents downstream spell-casting logic for this utterance.
 */
public final class VoiceWordSpokenEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String keyword;
    private final float confidence;
    private boolean cancelled;

    /**
     * @param player     the player whose voice audio produced this transcription; never null
     * @param keyword    the recognized keyword, normalized to lowercase and matched against
     *                   the constrained grammar array configured for the active engine; never null
     * @param confidence the engine-reported confidence score for this recognition, in the
     *                   inclusive range {@code [0.0f, 1.0f]}
     */
    public VoiceWordSpokenEvent(@NotNull final Player player, @NotNull final String keyword, final float confidence) {
        super(false); // always fired synchronously, on the main thread
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
    public void setCancelled(final boolean cancel) {
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
