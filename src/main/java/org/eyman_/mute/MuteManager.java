package org.eyman_.mute;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MuteManager {

    public record Mute(UUID playerId, String playerName, long expiresAtMillis,
                       String actor, long createdAtMillis) {
        public static final long PERMANENT = -1L;

        public boolean isPermanent() {
            return expiresAtMillis == PERMANENT;
        }

        public boolean isExpired(long nowMillis) {
            return !isPermanent() && nowMillis >= expiresAtMillis;
        }
    }

    private final Path file;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();

    public MuteManager(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("mutes");
        if (root == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                Mute m = new Mute(id, s.getString("name", key), s.getLong("expires", Mute.PERMANENT),
                        s.getString("actor", "unknown"), s.getLong("created", now));
                if (!m.isExpired(now)) {
                    mutes.put(id, m);
                }
            } catch (IllegalArgumentException bad) {
                logger.warning("Ignoring malformed mute entry '" + key + "' in " + file.getFileName());
            }
        }
        logger.info("Loaded " + mutes.size() + " active transcribe-mute(s).");
    }

    public Mute mute(UUID id, String name, Duration duration,
                     String actor) {
        long now = System.currentTimeMillis();
        Mute m = new Mute(id, name, duration == null ? Mute.PERMANENT : now + duration.toMillis(), actor, now);
        mutes.put(id, m);
        save();
        return m;
    }

    public boolean unmute(UUID id) {
        if (mutes.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    public boolean isMuted(UUID id) {
        Mute m = mutes.get(id);
        if (m == null) {
            return false;
        }
        if (m.isExpired(System.currentTimeMillis())) {
            if (mutes.remove(id, m)) {
                save();
            }
            return false;
        }
        return true;
    }

    public void shutdown() {
        save();
    }

    private void save() {
        synchronized (saveLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            long now = System.currentTimeMillis();
            for (Mute m : mutes.values()) {
                if (m.isExpired(now)) {
                    continue;
                }
                String base = "mutes." + m.playerId();
                yaml.set(base + ".name", m.playerName());
                yaml.set(base + ".expires", m.expiresAtMillis());
                yaml.set(base + ".actor", m.actor());
                yaml.set(base + ".created", m.createdAtMillis());
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                yaml.save(tmp.toFile());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to save " + file.getFileName(), e);
            }
        }
    }
}
