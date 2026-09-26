package org.eyman_.activation;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record CueSettings(boolean actionBarEnabled,
                           boolean soundEnabled,
                           @Nullable String openMessage,
                           @Nullable String closeMessage,
                           @Nullable String openSound,
                           float openVolume,
                           float openPitch,
                           @Nullable String closeSound,
                           float closeVolume,
                           float closePitch) {

    public CueSettings {
        if (openVolume < 0f || closeVolume < 0f) {
            throw new IllegalArgumentException("volume must not be negative");
        }
        if (openPitch <= 0f || closePitch <= 0f) {
            throw new IllegalArgumentException("pitch must be positive");
        }
    }

    // Plays the configured open-window cue for {@code player}. Must be called on the main thread.
    public void playOpenCue(@NotNull final Player player) {
        apply(player, openMessage, openSound, openVolume, openPitch);
    }

    // Plays the configured close-window cue for {@code player}. Must be called on the main thread.
    public void playCloseCue(@NotNull final Player player) {
        apply(player, closeMessage, closeSound, closeVolume, closePitch);
    }

    private void apply(@NotNull final Player player, @Nullable final String message,
                        @Nullable final String sound, final float volume, final float pitch) {
        Objects.requireNonNull(player, "player");

        if (actionBarEnabled && message != null && !message.isBlank()) {
            player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
        if (soundEnabled && sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}
