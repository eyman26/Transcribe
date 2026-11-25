package org.eyman_.dispatch;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.eyman_.keyword.KeywordDefinition;
import org.eyman_.placeholder.PlaceholderResolver;

import java.util.List;

public final class ActionRunner {

    private final Plugin plugin;
    private final PlaceholderResolver resolver;

    public ActionRunner(Plugin plugin, PlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    public String resolve(String template, Player player,
                          KeywordDefinition def, String spoken,
                          float confidence) {
        return resolver.resolve(template, player, def, spoken, confidence);
    }

    public void runCommands(Player player, KeywordDefinition def,
                            KeywordDefinition.Executor executor,
                            List<String> templates, String spoken,
                            float confidence) {
        for (String template : templates) {
            String command = resolver.resolve(template, player, def, spoken, confidence).strip();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.isEmpty()) {
                continue;
            }
            switch (executor) {
                case PLAYER -> player.performCommand(command);
                case CONSOLE -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
            }
        }
    }
}
