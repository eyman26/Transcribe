package org.eyman_.hook;

import org.bukkit.Server;

public interface PluginHook {

    String id();

    String pluginName();

    default boolean isAvailable(Server server) {
        return server.getPluginManager().isPluginEnabled(pluginName());
    }

    void execute(HookInvocation invocation);
}
