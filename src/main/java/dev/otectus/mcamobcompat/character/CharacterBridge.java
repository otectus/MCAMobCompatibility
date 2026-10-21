package dev.otectus.mcamobcompat.character;

import net.minecraft.world.entity.Entity;

/**
 * The questions the whole bridge turns on, for any native NPC: is this actor bridged into an MCA
 * character, by which adapter, and does a plain click on it belong to MCA?
 *
 * <p>Side-agnostic and cheap, because it runs on every entity join, every living tick and every
 * click. The guard-only facade the 0.2 API promises, {@code bridge.GuardBridge}, answers through this.
 */
public final class CharacterBridge {

    private CharacterBridge() {
    }

    /** The adapter bridging this actor right now, or null. */
    public static NativeCharacterAdapter adapterOf(Entity entity) {
        return NativeCharacterAdapters.bridging(entity);
    }

    /** Whether this entity is a native NPC that gets an MCA character. */
    public static boolean isBridgedActor(Entity entity) {
        return adapterOf(entity) != null;
    }

    /** Whether a plain click on this actor opens MCA's screen for its person. */
    public static boolean routesToMca(Entity entity) {
        NativeCharacterAdapter adapter = adapterOf(entity);
        return adapter != null && adapter.opensMcaInteraction();
    }
}
