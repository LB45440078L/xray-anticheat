package io.xrayac.core.domain;

import java.util.UUID;

/**
 * A stable reference to a player.
 *
 * <p>Identity is the UUID, never the name: names change, are not unique over time (a renamed
 * player frees the old name for someone else to claim), and are attacker-controlled at
 * registration. Every persistence key and every in-memory map in this plugin is keyed by
 * UUID; the name is carried only so that alerts are readable by a human.
 */
public record PlayerRef(UUID id, String name) {

    public PlayerRef {
        if (id == null) {
            throw new IllegalArgumentException("player id (UUID) is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player name is required for display purposes");
        }
    }

    public static PlayerRef of(UUID id, String name) {
        return new PlayerRef(id, name);
    }

    /** The display form used in messages: name plus a shortened UUID for disambiguation. */
    public String display() {
        return name + " (" + id.toString().substring(0, 8) + ")";
    }
}
