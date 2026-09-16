package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardIdentityCreatedEvent;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;

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
                new net.minecraft.world.level.saveddata.SavedData.Factory<>(GuardIdentityData::new,
                        (tag, registries) -> GuardIdentityData.load(tag)), GuardIdentityData.STORAGE_KEY);
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
     * result is stable across restarts even if the roster changes later. Those rolls are the seed
     * the hidden MCA proxy is created from; once a proxy exists, MCA owns the live values and
     * {@link GuardIdentityView} reads them from it instead.
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
        if (data.isReadOnly() || data.isQuarantined(guard.getUUID())) return Optional.empty();
        UUID uuid = guard.getUUID();
        GuardIdentityData.Entry existing = data.find(uuid);
        if (existing != null) {
            if (guard.hasCustomName()) {
                String custom = guard.getCustomName().getString();
                if (!custom.isBlank() && !custom.equals(existing.name())) {
                    data.rename(existing, custom);
                }
            }
            showNameTag(guard);
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
        showNameTag(guard);

        GuardIdentity identity = new GuardIdentityView(server, data, entry);
        McaMobCompat.debug("Gave guard {} the identity {} ({}, {})",
                uuid, name, gender.id(), entry.personality());
        NeoForge.EVENT_BUS.post(new GuardIdentityCreatedEvent(guard, identity));
        return Optional.of(identity);
    }

    /**
     * Makes the guard's name visible without a name tag, the way MCA shows a villager's.
     *
     * <p>Only ever turns visibility on. Clearing it would take away a nameplate a player set with a
     * name tag, and the option is documented as affecting new guards only for that reason.
     */
    private static void showNameTag(Mob guard) {
        if (!guard.isCustomNameVisible() && ModIntegrations.guardVillagers().showsNameTag()) {
            guard.setCustomNameVisible(true);
        }
    }

    /**
     * Points a guard's entry at its hidden proxy villager, or clears the link when {@code proxy} is
     * null. No-op for a guard with no entry: a proxy for an unknown guard is an orphan, and
     * {@code GuardProxyHandler} discards it rather than inventing an identity for it.
     */
    public static void link(MinecraftServer server, UUID guard, UUID proxy) {
        if (server == null || guard == null) {
            return;
        }
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard);
        if (entry != null) {
            data.linkProxy(entry, proxy);
        }
    }

    /** Forgets a guard's proxy. Called when the proxy is discarded or the guard dies. */
    public static void unlink(MinecraftServer server, UUID guard) {
        link(server, guard, null);
    }

    /** The proxy villager's UUID for this guard, or empty while it has none. */
    public static Optional<UUID> proxyUuid(MinecraftServer server, UUID guard) {
        if (server == null || guard == null) {
            return Optional.empty();
        }
        GuardIdentityData.Entry entry = get(server).find(guard);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.proxyUuid());
    }

    /** The guard this proxy belongs to, or empty when no entry claims it. */
    public static Optional<UUID> findByProxy(MinecraftServer server, UUID proxy) {
        if (server == null || proxy == null) {
            return Optional.empty();
        }
        GuardIdentityData.Entry entry = get(server).findByProxy(proxy);
        return entry == null ? Optional.empty() : Optional.of(entry.uuid());
    }

    /** The loaded mob with this UUID, in whichever level holds it, or null if none does. */
    public static Mob resolveLoaded(MinecraftServer server, UUID uuid) {
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

    public static void synchronize(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null) return;
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        if (entry != null) new GuardIdentityView(server, data, entry).synchronize();
    }

    /** The server this entity belongs to, or null when it is client-side or not in a level yet. */
    private static MinecraftServer serverFor(Entity entity) {
        if (entity == null || entity.level().isClientSide) {
            return null;
        }
        return entity.getServer();
    }
}
