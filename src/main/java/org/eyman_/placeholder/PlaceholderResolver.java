package org.eyman_.placeholder;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.eyman_.keyword.KeywordDefinition;

import java.util.Locale;

public final class PlaceholderResolver {

    private final boolean papiAvailable;

    public PlaceholderResolver(Plugin plugin) {
        this.papiAvailable = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public String resolve(String template, Player p,
                          KeywordDefinition def, String spoken,
                          float confidence) {
        if (template.indexOf('%') < 0) {
            return template;
        }
        Location loc = p.getLocation();
        String s = template
                .replace("%player%", p.getName())
                .replace("%uuid%", p.getUniqueId().toString())
                .replace("%keyword_id%", def.id())
                .replace("%keyword%", def.word())
                .replace("%spoken%", spoken)
                .replace("%confidence_percent%", Integer.toString(Math.round(confidence * 100f)))
                .replace("%confidence%", String.format(Locale.ROOT, "%.2f", confidence))
                .replace("%world%", loc.getWorld() == null ? "" : loc.getWorld().getName())
                .replace("%x%", Integer.toString(loc.getBlockX()))
                .replace("%y%", Integer.toString(loc.getBlockY()))
                .replace("%z%", Integer.toString(loc.getBlockZ()));
        return papiAvailable ? PapiBridge.apply(p, s) : s;
    }
}
