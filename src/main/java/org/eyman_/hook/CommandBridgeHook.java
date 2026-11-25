package org.eyman_.hook;

import org.eyman_.keyword.KeywordDefinition.Executor;

public final class CommandBridgeHook implements PluginHook {

    private final String id;
    private final String pluginName;

    public CommandBridgeHook(String id, String pluginName) {
        this.id = id;
        this.pluginName = pluginName;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String pluginName() {
        return pluginName;
    }

    @Override
    public void execute(HookInvocation inv) {
        inv.run(inv.config().executor(Executor.CONSOLE), inv.config().stringList("commands"));
    }
}
