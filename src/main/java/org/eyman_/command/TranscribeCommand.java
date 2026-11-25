package org.eyman_.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.eyman_.TranscribePlugin;
import org.eyman_.api.TranscribeAPI;
import org.eyman_.keyword.KeywordRegistry;
import org.eyman_.mute.DurationParser;
import org.eyman_.mute.MuteManager;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TranscribeCommand implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> SUBCOMMANDS = List.of("add", "mute", "unmute", "reload", "list");
    private static final List<String> DURATIONS = List.of("30s", "5m", "30m", "2h", "1d");

    private record Target(UUID id, String name) {
    }

    private final TranscribePlugin plugin;
    private final KeywordRegistry registry;
    private final MuteManager mutes;

    public TranscribeCommand(TranscribePlugin plugin, KeywordRegistry registry,
                             MuteManager mutes) {
        this.plugin = plugin;
        this.registry = registry;
        this.mutes = mutes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        List<String> t = List.of(args);
        if (t.isEmpty()) {
            usage(sender);
            return true;
        }
        switch (t.get(0).toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, t);
            case "mute" -> mute(sender, t);
            case "unmute" -> unmute(sender, t);
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void add(CommandSender sender, List<String> t) {
        if (!allowed(sender, "transcribe.admin.add")) {
            return;
        }
        if (t.size() != 3 || !t.get(1).equalsIgnoreCase("keyword")) {
            reply(sender, "<red>Usage: /transcribe add keyword <word>");
            return;
        }
        try {
            Path file = registry.createKeywordFile(t.get(2));
            reply(sender, "<green>Created <white>keywords/" + file.getFileName()
                    + "</white>. Add commands to it, then run /transcribe reload.");
            plugin.reloadKeywords(sender);
        } catch (FileAlreadyExistsException e) {
            reply(sender, "<red>That keyword already has a file.");
        } catch (IllegalArgumentException e) {
            reply(sender, "<red>" + MM.escapeTags(e.getMessage()));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create keyword file: " + e);
            reply(sender, "<red>Could not write the keyword file - see console.");
        }
    }

    private void mute(CommandSender sender, List<String> t) {
        if (!allowed(sender, "transcribe.admin.mute")) {
            return;
        }
        if (t.size() < 2 || t.size() > 3) {
            reply(sender, "<red>Usage: /transcribe mute <player> [duration, e.g. 30s, 5m, 2h]");
            return;
        }
        Target target = resolve(t.get(1));
        if (target == null) {
            reply(sender, "<red>Unknown player '" + MM.escapeTags(t.get(1)) + "' (they must be online or have joined before).");
            return;
        }
        Duration duration = null;
        if (t.size() == 3) {
            try {
                duration = DurationParser.parse(t.get(2));
            } catch (IllegalArgumentException e) {
                reply(sender, "<red>" + MM.escapeTags(e.getMessage()));
                return;
            }
        }
        mutes.mute(target.id(), target.name(), duration, sender.getName());
        Player online = plugin.getServer().getPlayer(target.id());
        if (online != null) {
            TranscribeAPI.get().forceStopListenWindow(online);
        }
        reply(sender, "<green>" + MM.escapeTags(target.name()) + " is now transcribe-muted "
                + (duration == null ? "<white>permanently</white>." : "for <white>" + DurationParser.format(duration) + "</white>."));
    }

    private void unmute(CommandSender sender, List<String> t) {
        if (!allowed(sender, "transcribe.admin.unmute")) {
            return;
        }
        if (t.size() != 2) {
            reply(sender, "<red>Usage: /transcribe unmute <player>");
            return;
        }
        Target target = resolve(t.get(1));
        if (target == null) {
            reply(sender, "<red>Unknown player '" + MM.escapeTags(t.get(1)) + "'.");
            return;
        }
        reply(sender, mutes.unmute(target.id())
                ? "<green>" + MM.escapeTags(target.name()) + " can use Transcribe again."
                : "<yellow>" + MM.escapeTags(target.name()) + " was not muted.");
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, "transcribe.admin.reload") && !sender.hasPermission("transcribe.reload")) {
            return;
        }
        plugin.reload(sender);
    }

    private void list(CommandSender sender) {
        if (!allowed(sender, "transcribe.admin.list")) {
            return;
        }
        KeywordRegistry.Snapshot s = registry.snapshot();
        reply(sender, "<gold>Transcribe keywords <gray>(" + s.byId().size() + ")");
        s.byId().values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(k -> reply(sender, (k.enabled() ? "<green> + " : "<red> - ") + "<white>"
                        + MM.escapeTags(k.word())));
    }

    private Target resolve(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName());
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayerIfCached(name);
        if (offline != null) {
            return new Target(offline.getUniqueId(), offline.getName() != null ? offline.getName() : name);
        }
        return null;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("transcribe.admin")) {
            return true;
        }
        reply(sender, "<red>You do not have permission to do that.");
        return false;
    }

    private void usage(CommandSender sender) {
        reply(sender, "<gold>Transcribe <gray>- /transcribe <white>add keyword <word> | "
                + "mute <player> [duration] | unmute <player> | list | reload");
    }

    private void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(MM.deserialize(miniMessage));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBCOMMANDS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && sub.equals("add")) {
                out.add("keyword");
            } else if (args.length == 2 && (sub.equals("mute") || sub.equals("unmute"))) {
                plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 3 && sub.equals("mute")) {
                out.addAll(DURATIONS);
            }
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(current));
        return out;
    }
}
