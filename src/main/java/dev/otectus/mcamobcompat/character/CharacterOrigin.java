package dev.otectus.mcamobcompat.character;

import java.util.Locale;

/**
 * How a character came to have both halves. It decides what "returning to civilian life" means and
 * what is lost when the actor goes.
 */
public enum CharacterOrigin {

    /**
     * A guard bridged by 0.2.x and migrated into schema 4. Its MCA villager is the proxy that release
     * created for it, so its UUID is the character's UUID and is never replaced.
     */
    MIGRATED_GUARD_BRIDGE,

    /**
     * A native NPC that already existed and was given an MCA identity in place. The identity was born
     * for it, so it has no civilian life to return to.
     */
    ADOPTED_ACTOR,

    /**
     * An existing MCA villager who took a native role. The identity is that villager, with its own
     * UUID, family, genes and possessions, and it can go back to civilian life.
     */
    ENLISTED_PERSON;

    /** The persisted form: the lower-case constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the identity is a person who lived before the role and may return to that life. */
    public boolean hasCivilianLife() {
        return this == ENLISTED_PERSON;
    }

    public static CharacterOrigin parse(String id, CharacterOrigin fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
