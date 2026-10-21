package dev.otectus.mcamobcompat.character;

import java.util.Locale;

/**
 * Where a character's two halves stand: the native actor that moves and fights, and the real MCA
 * villager that carries the person.
 *
 * <p>A gameplay state, kept apart from the store's read-only and quarantine handling: a record
 * from a newer schema is never given a state at all, and a quarantined record keeps whatever state
 * it had when it was quarantined.
 *
 * <p>Plain Java on purpose, so the store and its migration can be unit-tested without Minecraft.
 */
public enum CharacterState {

    /** Both halves exist and are linked. The normal state. */
    ACTIVE,

    /** The identity exists but its actor is not resolved: unloaded, or gone without a confirmed death. */
    AWAITING_ACTOR,

    /** The actor exists but has no MCA identity yet; one is created only from the actor's own tick. */
    AWAITING_IDENTITY,

    /** A journaled transition is in progress; no other transition may start until it settles. */
    TRANSITIONING,

    /** The integration is switched off for this character. Both halves are kept and hidden as they were. */
    SUSPENDED,

    /** The actor's death was confirmed and relayed. Kept so siblings can still resolve the name. */
    DECEASED,

    /** The record conflicts with another one. Reported only; the store never assigns it. */
    QUARANTINED;

    /** The persisted form: the lower-case constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a persisted id, or returns {@code fallback} for anything this version does not know. */
    public static CharacterState parse(String id, CharacterState fallback) {
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
