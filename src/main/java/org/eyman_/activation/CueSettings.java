package org.eyman_.activation;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;


public record CueSettings(boolean actionBarEnabled,
                           boolean soundEnabled,
                           String openMessage,
                           String closeMessage,
                           String openSound,
                           float openVolume,
                           float openPitch,
                           String closeSound,
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

    public void playOpenCue(Player player) {
        apply(player, openMessage, openSound, openVolume, openPitch);
    }

    public void playCloseCue(Player player) {
        apply(player, closeMessage, closeSound, closeVolume, closePitch);
    }

    private void apply(Player player, String message,
                        String sound, float volume, float pitch) {

        if (actionBarEnabled && message != null && !message.isBlank()) {
            player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
        if (soundEnabled && sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}
