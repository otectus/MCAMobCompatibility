package dev.otectus.mcamobcompat.api.event;

import dev.otectus.mcamobcompat.api.GuardIdentity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Fired on the Forge bus, server side, after a player's hearts with a guard changed.
 *
 * <p>Both this mod's own hit response and any sibling calling {@link GuardIdentity#modHearts} raise
 * it, so a reputation addon can mirror the change without caring who caused it. Never fired for a
 * zero delta.
 *
 * <p>{@link #getGuard()} is <b>nullable</b>: hearts are keyed on a UUID and can be changed while the
 * guard is not loaded in any level, in which case there is no entity to hand over.
 *
 * <p>Not cancellable; the change is already stored. Changing it back from a listener re-enters this
 * event, so a listener that writes hearts must guard against its own recursion.
 */
public class GuardHeartsChangedEvent extends Event {

    private final GuardIdentity identity;
    private final Mob guard;
    private final UUID player;
    private final int oldHearts;
    private final int newHearts;

    public GuardHeartsChangedEvent(GuardIdentity identity, Mob guard, UUID player,
                                   int oldHearts, int newHearts) {
        this.identity = identity;
        this.guard = guard;
        this.player = player;
        this.oldHearts = oldHearts;
        this.newHearts = newHearts;
    }

    /** The guard's identity. */
    public GuardIdentity getIdentity() {
        return identity;
    }

    /** The guard entity, or null when it is not loaded in any level. */
    public Mob getGuard() {
        return guard;
    }

    /** The player whose hearts changed. */
    public UUID getPlayer() {
        return player;
    }

    /** Hearts before the change. */
    public int getOldHearts() {
        return oldHearts;
    }

    /** Hearts after the change. */
    public int getNewHearts() {
        return newHearts;
    }

    /** The signed change that was applied. */
    public int getDelta() {
        return newHearts - oldHearts;
    }
}
