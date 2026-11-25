package org.eyman_.api;

import org.bukkit.entity.Player;
import org.eyman_.mute.MuteManager;

public final class AccessPolicy {

    public static final String USE_PERMISSION = "transcribe.use";

    public enum Access { ALLOWED, NO_PERMISSION, MUTED }

    private final MuteManager mutes;

    public AccessPolicy(MuteManager mutes) {
        this.mutes = mutes;
    }

    public Access check(Player player) {
        if (mutes.isMuted(player.getUniqueId())) {
            return Access.MUTED;
        }
        return player.hasPermission(USE_PERMISSION) ? Access.ALLOWED : Access.NO_PERMISSION;
    }

    public boolean canUse(Player player) {
        return check(player) == Access.ALLOWED;
    }
}
