package org.eyman_.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

final class PapiBridge {

    private PapiBridge() {
    }

    static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
