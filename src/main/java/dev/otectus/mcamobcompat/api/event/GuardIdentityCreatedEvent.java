package dev.otectus.mcamobcompat.api.event;

import dev.otectus.mcamobcompat.api.GuardIdentity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.Event;

/**
 * Fired on the Forge bus, server side, the first time a guard is given an identity.
 *
 * <p>Once per guard for the life of the world, not once per load: the identity is saved data keyed
 * on the guard's UUID, so a guard that unloads and comes back does not fire this again. It is the
 * place for a sibling to seed its own per-NPC record.
 *
 * <p>Not cancellable. The identity already exists by the time listeners run.
 */
public class GuardIdentityCreatedEvent extends Event {

    private final Mob guard;
    private final GuardIdentity identity;

    public GuardIdentityCreatedEvent(Mob guard, GuardIdentity identity) {
        this.guard = guard;
        this.identity = identity;
    }

    /** The guard the identity was created for. */
    public Mob getGuard() {
        return guard;
    }

    /** The new identity. */
    public GuardIdentity getIdentity() {
        return identity;
    }
}
