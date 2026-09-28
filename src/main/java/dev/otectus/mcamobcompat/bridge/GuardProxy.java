package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.event.ProxySpawnHandler;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVariants;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * The hidden MCA villager that stands in for a bridged guard.
 *
 * <p><b>Why a real villager.</b> Every MCA addon -- quests, conversations, reputation, crime --
 * identifies an NPC by resolving MCA's own entity class or by UUID, and MCA's interaction screen is
 * opened by MCA itself for an entity it owns. An emulated screen is invisible to all of them, and
 * MCA's {@code VillagerLike} is a closed interface a Guard Villagers guard cannot implement without
 * a mixin. So the guard gets a partner: a real {@code mca:*_villager} with profession
 * {@code mca:guard}, invisible, brainless, weightless and pinned to the guard's position. The guard
 * keeps its model, its combat AI and its own inventory GUI; the proxy carries every scrap of MCA
 * state and receives every MCA interaction.
 *
 * <p>The pair is linked in both directions through persistent data -- {@code mcamobcompat:proxy} on
 * the guard, {@code mcamobcompat:guard} on the proxy -- and in the identity store, so the link
 * survives a save, a restart and a portal and can be resolved with either entity unloaded. The
 * scoreboard tag {@code mcamobcompat_proxy} is there for operators to find proxies by selector.
 *
 * <p><b>Two identities, both durable.</b> The guard's UUID and the proxy's UUID are different, and
 * neither is ever replaced to repair the other. A recorded proxy that is not loaded is assumed to be
 * unloaded, not gone: {@link #create} refuses to manufacture a replacement while a stored link is
 * unresolved, because a new proxy is a new villager to MCA and would silently discard the old one's
 * family-tree node and every heart a player earned with it.
 *
 * <p><b>Creation is transactional.</b> The proxy is initialised through the loader's spawn event, so
 * another mod may reject it; a rejection, or a failed insertion into the level, removes the
 * family-tree node MCA created and leaves the seed and link bookkeeping uncommitted.
 *
 * <p>The proxy is never equipped. MCA's clothing, hair, face and skin layers ignore invisibility,
 * and so does armour; hiding it is done by flags here and by cancelling its render on the client.
 */
public final class GuardProxy {

    /** Persistent-data key on the guard, holding its proxy's UUID. */
    public static final String KEY_PROXY = McaMobCompat.MOD_ID + ":proxy";

    /** Persistent-data key on the proxy, holding its guard's UUID. */
    public static final String KEY_GUARD = McaMobCompat.MOD_ID + ":guard";

    /** Scoreboard tag on every proxy, so an operator can find and remove them with a selector. */
    public static final String TAG_PROXY = McaMobCompat.MOD_ID + "_proxy";

    /** MCA's guard profession, by id: the profession is what makes addons treat the proxy as one. */
    public static final ResourceLocation GUARD_PROFESSION = McaMobCompat.location(ModIds.MCA, "guard");

    /**
     * How long a guard must have existed before it may create a proxy: two seconds, to let a
     * loading chunk add the proxy that already exists before anything decides there is none.
     */
    public static final int CREATE_GRACE_TICKS = 40;

    /** How far around a guard to look for an orphaned proxy that already names it. */
    private static final double ADOPT_RANGE = 3.0D;

    /**
     * The proxy's collision width. It shares the guard's position, and a proxy box strictly inside
     * the guard's means every ray -- a projectile's, a player's pick -- meets the guard first, so
     * native hit handling lands on the entity that fights. See {@code ProxyShapeHandler}.
     */
    public static final float PROXY_WIDTH = 0.2F;

    private static volatile boolean warnedMissingType;
    private static volatile boolean warnedNotAVillager;
    private static volatile boolean warnedMissingProfession;
    private static volatile boolean warnedNoRollback;

    private GuardProxy() {
    }

    /**
     * Whether this entity is a proxy.
     *
     * <p>On the server: an MCA villager naming a guard in its persistent data, or one the identity
     * store records as a guard's proxy (the persistent key is then repaired). On the client, where
     * persistent data is never synced: an MCA villager the server has explicitly paired, by both
     * UUID and runtime id, through {@link ProxyPairs}. There is no heuristic on either side, so a
     * real villager that happens to be invisible, or standing next to a guard, is never mistaken
     * for one.
     */
    public static boolean isProxy(Entity entity) {
        if (entity == null || !McaEntities.isMcaVillager(entity)) {
            return false;
        }
        if (entity.level().isClientSide) {
            return ProxyPairs.isClientProxy(entity);
        }
        if (entity.getPersistentData().hasUUID(KEY_GUARD)) {
            return true;
        }
        MinecraftServer server = entity.getServer();
        UUID owner = server == null ? null : GuardIdentityStore.findByProxy(server, entity.getUUID()).orElse(null);
        if (owner == null) {
            return false;
        }
        entity.getPersistentData().putUUID(KEY_GUARD, owner);
        entity.addTag(TAG_PROXY);
        return true;
    }

    /**
     * The client's question, kept for the renderer: an exact server-issued pairing, never a guess
     * from synced flags. Identical to {@link #isProxy} and retained so existing callers keep their
     * meaning.
     */
    public static boolean looksLikeProxy(Entity entity) {
        return isProxy(entity);
    }

    /**
     * The guard this proxy stands in for: from the explicit pairing on the client, from the
     * persistent link on the server. Never a nearest-entity search.
     */
    public static Mob nearbyGuardOf(Entity proxy) {
        if (proxy == null) {
            return null;
        }
        return proxy.level().isClientSide ? ProxyPairs.clientGuardOf(proxy) : guardOf(proxy);
    }

    /** This guard's proxy, wherever it is loaded, or null. Never creates. Server side. */
    public static Villager proxyOf(Mob guard) {
        if (guard == null || guard.level().isClientSide) {
            return null;
        }
        MinecraftServer server = guard.getServer();
        if (server != null && GuardIdentityStore.get(server).isQuarantined(guard.getUUID())) {
            return null;
        }
        UUID proxyId = readUuid(guard, KEY_PROXY);
        if (proxyId == null) {
            proxyId = GuardIdentityStore.proxyUuid(server, guard.getUUID()).orElse(null);
        }
        Entity found = resolve(server, guard, proxyId);
        if (!(found instanceof Villager villager) || !McaEntities.isMcaVillager(villager) || villager.isRemoved()) {
            return null;
        }
        UUID owner = readUuid(villager, KEY_GUARD);
        if (owner != null && !owner.equals(guard.getUUID())) {
            return null;
        }
        if (owner == null) {
            villager.getPersistentData().putUUID(KEY_GUARD, guard.getUUID());
            villager.addTag(TAG_PROXY);
        }
        if (!villager.getUUID().equals(readUuid(guard, KEY_PROXY))) {
            guard.getPersistentData().putUUID(KEY_PROXY, villager.getUUID());
        }
        return villager;
    }

    /** The guard this proxy stands in for, wherever it is loaded, or null. Server side. */
    public static Mob guardOf(Entity proxy) {
        if (proxy == null || proxy.level().isClientSide) {
            return null;
        }
        MinecraftServer server = proxy.getServer();
        UUID guardId = readUuid(proxy, KEY_GUARD);
        if (guardId == null) {
            guardId = GuardIdentityStore.findByProxy(server, proxy.getUUID()).orElse(null);
        }
        if (guardId == null || (server != null && GuardIdentityStore.get(server).isQuarantined(guardId))) {
            return null;
        }
        Entity found = resolve(server, proxy, guardId);
        if (!(found instanceof Mob mob) || McaEntities.isMcaVillager(mob) || mob.isRemoved()) {
            return null;
        }
        UUID link = readUuid(mob, KEY_PROXY);
        return link == null || proxy.getUUID().equals(link) ? mob : null;
    }

    /**
     * The one path by which a proxy comes to exist: resolve the recorded one, adopt an unambiguous
     * orphan, or create a new one -- in that order, and only when it is safe.
     *
     * <p>Returns null, and creates nothing, while the guard is inside its loading grace period, while
     * a recorded proxy UUID is unresolved (it may simply be unloaded), when two orphans claim the
     * guard, when the identity is quarantined or the store is read-only, and when MCA or another mod
     * rejects the new villager. Every one of those is survivable: the guard keeps its stock
     * behaviour and the next attempt tries again.
     */
    public static Villager create(ServerLevel level, Mob guard) {
        if (level == null || guard == null || guard.level() != level || !guard.isAlive()) {
            return null;
        }
        GuardIdentityData data = GuardIdentityStore.get(level.getServer());
        if (data.isReadOnly() || data.isQuarantined(guard.getUUID())) {
            return null;
        }
        Villager existing = proxyOf(guard);
        if (existing != null) {
            return existing;
        }
        if (guard.tickCount < CREATE_GRACE_TICKS) {
            return null;
        }

        List<Villager> orphans = level.getEntitiesOfClass(Villager.class, guard.getBoundingBox().inflate(ADOPT_RANGE),
                candidate -> McaEntities.isMcaVillager(candidate) && guard.getUUID().equals(linkedGuardUuid(candidate)));
        if (orphans.size() > 1) {
            McaMobCompat.debug("Guard {} has {} candidate proxies nearby; leaving them for an operator",
                    guard.getUUID(), orphans.size());
            return null;
        }
        if (orphans.size() == 1) {
            return link(guard, orphans.get(0)) ? orphans.get(0) : null;
        }

        // A link on record whose proxy is not loaded is not a missing proxy: never replace it. The
        // store is authoritative, though: when it has an identity for this guard and records no
        // proxy, the link was released (the proxy died or was discarded while this guard was
        // unloaded), and a key still on the guard is stale.
        boolean storeLinked = GuardIdentityStore.proxyUuid(level.getServer(), guard.getUUID()).isPresent();
        if (!storeLinked && readUuid(guard, KEY_PROXY) != null && data.find(guard.getUUID()) != null) {
            guard.getPersistentData().remove(KEY_PROXY);
        }
        if (storeLinked || readUuid(guard, KEY_PROXY) != null) {
            return null;
        }

        GuardIdentityStore.getOrCreate(guard);
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        return entry == null ? null : spawn(level, guard, data, entry);
    }

    private static Villager spawn(ServerLevel level, Mob guard, GuardIdentityData data,
                                  GuardIdentityData.Entry entry) {
        if (!McaBinding.canRollbackSpawn()) {
            if (!warnedNoRollback) {
                warnedNoRollback = true;
                McaMobCompat.LOGGER.warn("MCA's family tree could not be bound, so a rejected proxy spawn "
                        + "could not be undone; bridged guards get no MCA proxy. See /mcamobcompat mca.");
            }
            return null;
        }
        ResourceLocation typeId = entry.gender() == GuardGender.FEMALE
                ? McaEntities.FEMALE_VILLAGER
                : McaEntities.MALE_VILLAGER;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null || !typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))) {
            if (!warnedMissingType) {
                warnedMissingType = true;
                McaMobCompat.LOGGER.warn("No entity type '{}' is registered, so bridged guards get no "
                        + "MCA proxy. Is MCA installed?", typeId);
            }
            return null;
        }

        Entity spawned = type.create(level);
        if (!(spawned instanceof Villager proxy)) {
            if (!warnedNotAVillager) {
                warnedNotAVillager = true;
                McaMobCompat.LOGGER.warn("'{}' did not create a Villager, so bridged guards get no MCA "
                        + "proxy. Something has replaced MCA's villager type.", typeId);
            }
            if (spawned != null) {
                spawned.discard();
            }
            return null;
        }

        proxy.moveTo(guard.getX(), guard.getY(), guard.getZ(), guard.getYRot(), guard.getXRot());
        try {
            if (!ProxySpawnHandler.initialize(proxy, level)) {
                McaBinding.rollbackSpawn(proxy);
                proxy.discard();
                McaMobCompat.debug("Proxy initialisation for guard {} was rejected", guard.getUUID());
                return null;
            }
            applyVillagerData(proxy, guard);
            hide(proxy);
            proxy.setPersistenceRequired();
            proxy.getPersistentData().putUUID(KEY_GUARD, guard.getUUID());
            proxy.addTag(TAG_PROXY);

            // The stored roll only ever seeds a proxy once. After that MCA owns the name and the
            // personality, and re-seeding would undo a rename or a personality an addon changed.
            if (!entry.seeded()) {
                if (entry.name() != null && !entry.name().isBlank()) {
                    proxy.setCustomName(Component.literal(entry.name()));
                }
                McaBinding.setPersonality(proxy, entry.personality());
            }

            if (!level.addFreshEntity(proxy)) {
                McaBinding.rollbackSpawn(proxy);
                proxy.discard();
                McaMobCompat.debug("Proxy for guard {} was refused by the level", guard.getUUID());
                return null;
            }
        } catch (RuntimeException failure) {
            McaBinding.rollbackSpawn(proxy);
            proxy.discard();
            throw failure;
        }

        // Committed only now that the villager really exists in the world.
        data.markSeeded(entry);
        // A home village, the way MCA gives any villager one, so a deed against this guard has a
        // community to be charged to. Retried from the slow tick while the guard is outside every
        // village; nothing is invented when none is near.
        McaBinding.seekHome(proxy);
        guard.getPersistentData().putUUID(KEY_PROXY, proxy.getUUID());
        GuardIdentityStore.link(level.getServer(), guard.getUUID(), proxy.getUUID());
        copyNameToGuard(guard, proxy);
        ProxyPairs.broadcast(guard, proxy);

        McaMobCompat.debug("Created MCA proxy {} for bridged guard {}", proxy.getUUID(), guard.getUUID());
        return proxy;
    }

    /**
     * The two things that make the proxy an MCA guard rather than an MCA villager: the profession
     * and a biome type that matches the guard's own variant.
     */
    static void applyVillagerData(Villager proxy, Mob guard) {
        VillagerProfession profession =
                BuiltInRegistries.VILLAGER_PROFESSION.getOptional(GUARD_PROFESSION).orElse(null);
        if (profession == null || !GUARD_PROFESSION.equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession))) {
            if (!warnedMissingProfession) {
                warnedMissingProfession = true;
                McaMobCompat.LOGGER.warn("No villager profession '{}' is registered; the proxy keeps "
                        + "whatever profession MCA rolled for it.", GUARD_PROFESSION);
            }
            return;
        }
        VillagerData current = proxy.getVillagerData();
        VillagerData updated = current.setProfession(profession);
        VillagerType villagerType = BuiltInRegistries.VILLAGER_TYPE.get(
                GuardVariants.villagerType(GuardReflection.variant(guard).orElse(null)));
        if (villagerType != null) {
            updated = updated.setType(villagerType);
        }
        proxy.setVillagerData(updated);
    }

    /**
     * Keeps the proxy out of the world: brainless, invisible, silent, weightless, without physics
     * or momentum, and narrow. Idempotent and cheap; re-asserted every tick on both sides.
     */
    public static void hide(Villager proxy) {
        proxy.setNoAi(true);
        proxy.setInvisible(true);
        proxy.setSilent(true);
        proxy.setNoGravity(true);
        proxy.noPhysics = true;
        if (proxy.isCurrentlyGlowing() && !proxy.level().isClientSide) {
            proxy.setGlowingTag(false);
        }
        if (!proxy.getDeltaMovement().equals(Vec3.ZERO)) {
            proxy.setDeltaMovement(Vec3.ZERO);
        }
        if (proxy.getBbWidth() > PROXY_WIDTH + 1.0E-3F) {
            proxy.refreshDimensions();
        }
    }

    /**
     * Puts the proxy's name on the guard, which is the name a player actually reads.
     *
     * <p>Name-tag visibility follows the same option new identities use, and is only ever turned
     * on: a nameplate already showing is a player-visible fact we do not quietly take away.
     */
    static void copyNameToGuard(Mob guard, Villager proxy) {
        if (!proxy.hasCustomName()) {
            return;
        }
        guard.setCustomName(proxy.getCustomName());
        if (!guard.isCustomNameVisible() && ModIntegrations.guardVillagers().showsNameTag()) {
            guard.setCustomNameVisible(true);
        }
    }

    /** The guard UUID written on a proxy, or null when it carries none. */
    public static UUID linkedGuardUuid(Entity proxy) {
        return proxy == null ? null : readUuid(proxy, KEY_GUARD);
    }

    /**
     * Re-establishes the link between an existing pair, in persistent data and in the store.
     *
     * <p>Refuses when either side already names a different partner, or the store gives the proxy
     * to another guard: an ambiguous link is left for an operator rather than guessed at.
     */
    public static boolean link(Mob guard, Villager proxy) {
        if (guard == null || proxy == null || guard.level().isClientSide) {
            return false;
        }
        MinecraftServer server = guard.getServer();
        UUID recorded = GuardIdentityStore.proxyUuid(server, guard.getUUID()).orElse(null);
        UUID owner = linkedGuardUuid(proxy);
        if ((recorded != null && !recorded.equals(proxy.getUUID()))
                || (owner != null && !owner.equals(guard.getUUID()))) {
            return false;
        }
        GuardIdentityStore.getOrCreate(guard);
        if (!GuardIdentityStore.link(server, guard.getUUID(), proxy.getUUID())) {
            return false;
        }
        guard.getPersistentData().putUUID(KEY_PROXY, proxy.getUUID());
        proxy.getPersistentData().putUUID(KEY_GUARD, guard.getUUID());
        proxy.addTag(TAG_PROXY);
        ProxyPairs.broadcast(guard, proxy);
        return true;
    }

    /**
     * Removes a proxy from the world and forgets it, on both sides of the link.
     *
     * <p>Destructive to MCA state -- the villager's memories and family-tree node go with it -- so
     * it is reached only from an explicit operator action or from the guard itself being discarded.
     */
    public static void discard(Entity proxy) {
        if (proxy == null) {
            return;
        }
        Mob guard = guardOf(proxy);
        if (guard != null) {
            unlink(guard);
        } else if (proxy.getServer() != null) {
            GuardIdentityStore.findByProxy(proxy.getServer(), proxy.getUUID()).ifPresent(uuid ->
                    GuardIdentityStore.unlink(proxy.getServer(), uuid));
        }
        if (proxy instanceof Villager villager && guard != null) {
            ProxyPairs.broadcastRemoval(guard, villager);
        }
        proxy.discard();
    }

    /** Forgets a guard's proxy, in its persistent data and in the store. Does not discard it. */
    public static void unlink(Mob guard) {
        if (guard == null) {
            return;
        }
        guard.getPersistentData().remove(KEY_PROXY);
        GuardIdentityStore.unlink(guard.getServer(), guard.getUUID());
    }

    private static UUID readUuid(Entity holder, String key) {
        CompoundTag persistent = holder.getPersistentData();
        return persistent.hasUUID(key) ? persistent.getUUID(key) : null;
    }

    /**
     * Resolves a UUID to a loaded entity, checking the partner's own level first.
     *
     * <p>The pair is normally in the same level and the same chunk, so the first lookup answers;
     * the walk over every level is the portal case, where one half has moved and the other has not
     * caught up yet.
     */
    private static Entity resolve(MinecraftServer server, Entity near, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (near.level() instanceof ServerLevel level) {
            Entity found = level.getEntity(uuid);
            if (found != null) {
                return found;
            }
        }
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(uuid);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
