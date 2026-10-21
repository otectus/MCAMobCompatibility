package dev.otectus.mcamobcompat.character;

import java.util.Locale;

/** The structural changes a character can go through, each run as one journaled transition. */
public enum TransitionKind {

    /** An existing MCA villager takes a native role: a new actor, the same person. */
    ENLIST,

    /** A native role ends: the actor goes, the same person becomes a visible civilian again. */
    RETURN_TO_CIVILIAN,

    /** The source mod replaces the actor with another (a Recruits promotion): new actor, same person. */
    HOST_REPLACEMENT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TransitionKind parse(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
