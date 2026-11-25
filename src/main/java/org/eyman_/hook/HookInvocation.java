package org.eyman_.hook;

import org.bukkit.entity.Player;
import org.eyman_.dispatch.ActionRunner;
import org.eyman_.keyword.KeywordDefinition;

import java.util.List;

public record HookInvocation(Player player, KeywordDefinition definition,
                             String spoken, float confidence,
                             KeywordDefinition.HookConfig config, ActionRunner runner) {

    public String resolve(String template) {
        return runner.resolve(template, player, definition, spoken, confidence);
    }

    public void run(KeywordDefinition.Executor executor, List<String> templates) {
        runner.runCommands(player, definition, executor, templates, spoken, confidence);
    }
}
