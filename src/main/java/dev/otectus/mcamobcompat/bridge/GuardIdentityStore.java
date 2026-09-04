package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardIdentityCreatedEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;

import java.util.Optional;
import java.util.UUID;

/**
 * The server-side door to {@link GuardIdentityData}: everything that needs a live mob or a server.
 *
 * <p>The store hangs off the <b>overworld</b>'s data storage, once per world rather than once per
 * dimension, because a guard's UUID does not change when it walks through a portal and its identity
 * must not either.
 *
 * <p>Every method is empty-or-no-op on the client and for an entity that is not a bridged guard, so
 * callers do not have to remember which side they are on.
 */
public final class GuardIdentityStore {

    private GuardIdentityStore() {
    }

    /** The world's identity store, created on first use. */
    public static GuardIdentityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                GuardIdentityData::load, GuardIdentityData::new, GuardIdentityData.STORAGE_KEY);
    }

    /**
     * This guard's existing identity, or empty.
     *
     * <p><b>Never creates.</b> Diagnostics and read-only questions go through here, so that merely
     * looking at a guard cannot write to the save file.
     */
    public static Optional<GuardIdentity> find(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null || !GuardBridge.isBridgedGuard(guard)) {
            return Optional.empty();
        }
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        return entry == null
                ? Optional.empty()
                : Optional.of(new GuardIdentityView(server, data, entry));
    }

    /**
     * This guard's identity, creating it if it has none.
     *
     * <p>Idempotent, and the only place an identity is born. Creation rolls name, gender and
     * personality from the guard's UUID (see {@link GuardNames}) and then persists them, so the
     * result is stable across restarts even if the roster changes later.
     *
     * <p>A guard that already carries a custom name keeps it -- a player's name tag outranks our
     * roll -- and a name changed since last time is adopted on the next pass, so renaming a guard
     * renames its identity too.
     */
    public static Optional<GuardIdentity> getOrCreate(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null || !GuardBridge.isBridgedGuard(guard)) {
            return Optional.empty();
        }

        GuardIdentityData data = get(server);
        UUID uuid = guard.getUUID();
        GuardIdentityData.Entry existing = data.find(uuid);
        if (existing != null) {
            if (guard.hasCustomName()) {
                String custom = guard.getCustomName().getString();
                if (!custom.isBlank() && !custom.equals(existing.name())) {
                    data.rename(existing, custom);
                }
            }
            return Optional.of(new GuardIdentityView(server, data, existing));
        }

        GuardGender gender = GuardNames.gender(uuid);
        String name = guard.hasCustomName() && !guard.getCustomName().getString().isBlank()
                ? guard.getCustomName().getString()
                : GuardNames.name(uuid, gender);
        GuardIdentityData.Entry entry = data.create(
                uuid, name, gender, GuardNames.personality(uuid), guard.level().getGameTime());

        if (!guard.hasCustomName()) {
            guard.setCustomName(Component.literal(name));
        }

        GuardIdentity identity = new GuardIdentityView(server, data, entry);
        McaMobCompat.debug("Gave guard {} the identity {} ({}, {})",
                uuid, name, gender.id(), entry.personality());
        MinecraftForge.EVENT_BUS.post(new GuardIdentityCreatedEvent(guard, identity));
        return Optional.of(identity);
    }

    /** The loaded mob with this UUID, in whichever level holds it, or null if none does. */
    static Mob resolveLoaded(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(uuid);
            if (found instanceof Mob mob) {
                return mob;
            }
        }
        return null;
    }

    /** The server this entity belongs to, or null when it is client-side or not in a level yet. */
    private static MinecraftServer serverFor(Entity entity) {
        if (entity == null || entity.level().isClientSide) {
            return null;
        }
        return entity.getServer();
    }
}
