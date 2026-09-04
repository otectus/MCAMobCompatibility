package dev.otectus.mcamobcompat.api;

import java.util.UUID;

/**
 * The MCA-style identity this mod keeps for a guard: a name, a gender, a personality and hearts.
 *
 * <p>Guard Villagers' guard is a plain {@code Mob} with none of that, and MCA will never give it
 * any, because neither mod knows the other exists. This interface is the seam: the addon suite
 * (quests, crime, reputation, conversations) asks a guard the same questions it asks an MCA
 * villager, and gets answers from a store this mod owns.
 *
 * <p><b>Server-side only.</b> Identities live in the overworld's saved data and are never synced,
 * so {@link MobCompatibilityAPI#getGuardIdentity} is always empty on the client.
 *
 * <p>Instances are views onto the store, not snapshots: {@link #hearts} re-reads live data.
 */
public interface GuardIdentity {

    /** The guard entity's UUID. The identity is keyed on it and survives the entity unloading. */
    UUID uuid();

    /** The guard's display name, which is also set as its custom name when it had none. */
    String name();

    /** Male or female, rolled from the UUID. */
    GuardGender gender();

    /**
     * The personality id, lowercase and unnamespaced -- {@code "confident"}, {@code "gloomy"} and
     * so on, from MCA's own roster. Rolled from the UUID and then persisted, so that a later change
     * to the roster cannot silently repersonalise an existing guard.
     */
    String personality();

    /**
     * The profession id a sibling should treat this NPC as holding: always {@code "mca:guard"}.
     *
     * <p>A string, not an MCA type. It is here so dialogue and quest tables can key off profession
     * without special-casing bridged guards.
     */
    default String professionId() {
        return "mca:guard";
    }

    /** Game time, in ticks, at which the identity was first created. */
    long created();

    /** This player's hearts with the guard. Zero for a player who has never met it. */
    int hearts(UUID player);

    /**
     * Adds to a player's hearts, negative to subtract, and fires
     * {@link dev.otectus.mcamobcompat.api.event.GuardHeartsChangedEvent} on the Forge bus.
     *
     * <p>Call on the server thread. A delta of zero does nothing and fires nothing. Values are not
     * clamped: the caller owns its own scale.
     */
    void modHearts(UUID player, int delta);

    /** Whether the guard has been killed. Deceased entries persist until purged. */
    boolean deceased();
}
