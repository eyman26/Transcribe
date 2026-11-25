package org.eyman_.hook;

import org.bukkit.Server;
import org.eyman_.keyword.KeywordDefinition;
import org.eyman_.keyword.KeywordRegistry;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class HookRegistry {

    private final Map<String, PluginHook> hooks = new ConcurrentHashMap<>();

    public HookRegistry(Server server, Logger logger) {
        register(new MythicMobsHook(server, logger));
        register(new CommandBridgeHook("nexo", "Nexo"));
        register(new CommandBridgeHook("itemsadder", "ItemsAdder"));
        register(new CommandBridgeHook("oraxen", "Oraxen"));
    }

    public void register(PluginHook hook) {
        hooks.put(hook.id().toLowerCase(Locale.ROOT), hook);
    }

    public Optional<PluginHook> resolve(KeywordDefinition.HookConfig cfg) {
        PluginHook known = hooks.get(cfg.id());
        if (known != null) {
            return Optional.of(known);
        }
        String plugin = cfg.string("plugin", "").strip();
        return plugin.isEmpty() ? Optional.empty() : Optional.of(new CommandBridgeHook(cfg.id(), plugin));
    }

    public void validate(KeywordRegistry.Snapshot snapshot, Logger logger) {
        for (KeywordDefinition def : snapshot.byId().values()) {
            for (KeywordDefinition.HookConfig hc : def.hooks().values()) {
                if (hc.enabled() && resolve(hc).isEmpty()) {
                    logger.warning("keywords/" + def.id() + ".yml: hook '" + hc.id()
                            + "' is unknown - add 'plugin: <PluginName>' to treat it as a command hook.");
                }
            }
        }
    }
}
