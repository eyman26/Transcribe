package org.eyman_.hook;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.eyman_.keyword.KeywordDefinition.Executor;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

// casts skills through reflection so we don't need MythicMobs at compile time
public final class MythicMobsHook implements PluginHook {

    private final Server server;
    private final Logger logger;
    private Object helper;
    private volatile Method castSkill;

    public MythicMobsHook(Server server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Override
    public String id() {
        return "mythicmobs";
    }

    @Override
    public String pluginName() {
        return "MythicMobs";
    }

    @Override
    public void execute(HookInvocation inv) {
        String skill = inv.resolve(inv.config().string("skill", "")).strip();
        if (!skill.isEmpty()) {
            try {
                bind();
                Object ok = castSkill.invoke(helper, inv.player(), skill);
                if (Boolean.FALSE.equals(ok)) {
                    logger.fine("MythicMobs didn't cast '" + skill + "'");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                logger.log(Level.WARNING, "MythicMobs hook failed for skill '" + skill + "'", e);
            }
        }
        var extra = inv.config().stringList("commands");
        if (!extra.isEmpty()) {
            inv.run(inv.config().executor(Executor.CONSOLE), extra);
        }
    }

    private synchronized void bind() throws ReflectiveOperationException {
        if (castSkill != null) {
            return;
        }
        Plugin mythic = server.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null) {
            throw new ClassNotFoundException("MythicMobs is not loaded");
        }
        Class<?> main = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true,
                mythic.getClass().getClassLoader());
        Object instance = main.getMethod("inst").invoke(null);
        Object api = main.getMethod("getAPIHelper").invoke(instance);
        Method method = api.getClass().getMethod("castSkill", Entity.class, String.class);
        this.helper = api;
        this.castSkill = method;
    }
}
