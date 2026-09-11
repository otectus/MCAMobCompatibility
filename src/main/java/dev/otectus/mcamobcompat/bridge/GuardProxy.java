package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVariants;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

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
 * the guard, {@code mcamobcompat:guard} on the proxy -- so the link survives a save, a restart and
 * a portal, and the store keeps the same link so it can be resolved without either entity loaded.
 * The scoreboard tag {@code mcamobcompat_proxy} is there for operators: {@code /kill @e[tag=...]}
 * is the manual broom.
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
    public static final ResourceLocation GUARD_PROFESSION = new ResourceLocation(ModIds.MCA, "guard");

    private static volatile boolean warnedMissingType;
    private static volatile boolean warnedNotAVillager;
    private static volatile boolean warnedMissingProfession;

    private GuardProxy() {
    }

    /**
     * Whether this entity is a proxy: an MCA villager that names a guard in its persistent data.
     *
     * <p>Server-side truth. Persistent data is not synced, so this is always false on the client --
     * {@link #looksLikeProxy} is the client's question.
     */
    public static boolean isProxy(Entity entity) {
        return McaEntities.isMcaVillager(entity) && entity.getPersistentData().hasUUID(KEY_GUARD);
    }

    /**
     * The client-side heuristic: an MCA villager wearing all four hiding flags.
     *
     * <p>Every flag read here is synced entity data, which is the whole point -- persistent data is
     * not, so the client cannot ask the real question. The accepted false positive is a genuine
     * villager somebody has deliberately made invisible, silent, weightless <em>and</em> brainless
     * by command; that villager stops rendering. Nothing else in vanilla or MCA produces all four.
     *
     * <p>Uses only common types, so the class stays loadable on a dedicated server.
     */
    public static boolean looksLikeProxy(Entity entity) {
        return entity instanceof Mob mob
                && McaEntities.isMcaVillager(mob)
                && mob.isInvisible()
                && mob.isNoAi()
                && mob.isSilent()
                && mob.isNoGravity();
    }

    /**
     * The bridged guard standing where this proxy stands, or null: the client's answer to
     * {@link #guardOf}.
     *
     * <p>The link between the two lives in server-side persistent data, which the client never
     * sees. What the client does see is that a proxy is pinned inside its guard's hitbox every
     * tick, so the bridged guard whose bounding box overlaps the proxy's is the one it stands in
     * for. Works on either side and on any level; uses only common types.
     */
    public static Mob nearbyGuardOf(Entity proxy) {
        if (proxy == null) {
            return null;
        }
        AABB box = proxy.getBoundingBox().inflate(0.5D);
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob candidate : proxy.level().getEntitiesOfClass(Mob.class, box, GuardBridge::isBridgedGuard)) {
            double distance = candidate.distanceToSqr(proxy);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** This guard's proxy, wherever it is loaded, or null. Never creates. */
    public static Villager proxyOf(Mob guard) {
        if (guard == null) {
            return null;
        }
        MinecraftServer server = guard.level().getServer();
        UUID proxy = readUuid(guard, KEY_PROXY);
        if (proxy == null) {
            proxy = GuardIdentityStore.proxyUuid(server, guard.getUUID()).orElse(null);
        }
        Entity found = resolve(server, guard, proxy);
        return found instanceof Villager villager ? villager : null;
    }

    /** The guard this proxy stands in for, wherever it is loaded, or null. */
    public static Mob guardOf(Entity proxy) {
        if (proxy == null) {
            return null;
        }
        MinecraftServer server = proxy.level().getServer();
        UUID guard = readUuid(proxy, KEY_GUARD);
        if (guard == null) {
            guard = GuardIdentityStore.findByProxy(server, proxy.getUUID()).orElse(null);
        }
        Entity found = resolve(server, proxy, guard);
        return found instanceof Mob mob ? mob : null;
    }

    /**
     * Creates this guard's proxy and puts it in the world, or returns null if it could not be made.
     *
     * <p>The proxy is spawned through MCA's own {@code finalizeSpawn}, which is what gives it a
     * name, genetics, an age state and a node in the family tree -- the things a later-added
     * villager is missing and which no amount of field setting can fake. Only then is it made
     * invisible and inert.
     *
     * <p>Returning null is survivable everywhere: the guard simply keeps its stock behaviour, and
     * the reason is logged once rather than every tick.
     */
    public static Villager create(ServerLevel level, Mob guard) {
        if (level == null || guard == null) {
            return null;
        }
        GuardIdentityStore.getOrCreate(guard);
        GuardIdentityData data = GuardIdentityStore.get(level.getServer());
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        return entry == null ? null : create(level, guard, data, entry);
    }

    private static Villager create(ServerLevel level, Mob guard, GuardIdentityData data,
                                   GuardIdentityData.Entry entry) {
        ResourceLocation typeId = entry.gender() == GuardGender.FEMALE
                ? McaEntities.FEMALE_VILLAGER
                : McaEntities.MALE_VILLAGER;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null) {
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
        proxy.finalizeSpawn(level, level.getCurrentDifficultyAt(guard.blockPosition()),
                MobSpawnType.MOB_SUMMONED, null, null);
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
            data.markSeeded(entry);
        }

        level.addFreshEntity(proxy);
        guard.getPersistentData().putUUID(KEY_PROXY, proxy.getUUID());
        data.linkProxy(entry, proxy.getUUID());
        copyNameToGuard(guard, proxy);

        McaMobCompat.debug("Created MCA proxy {} for bridged guard {}", proxy.getUUID(), guard.getUUID());
        return proxy;
    }

    /**
     * The two things that make the proxy an MCA guard rather than an MCA villager: the profession
     * and a biome type that matches the guard's own variant.
     */
    static void applyVillagerData(Villager proxy, Mob guard) {
        VillagerProfession profession = ForgeRegistries.VILLAGER_PROFESSIONS.getValue(GUARD_PROFESSION);
        if (profession == null) {
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

    /** The five flags that keep the proxy out of the world. Re-asserted every tick by the sync. */
    static void hide(Villager proxy) {
        proxy.setNoAi(true);
        proxy.setInvisible(true);
        proxy.setSilent(true);
        proxy.setNoGravity(true);
        proxy.noPhysics = true;
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

    /**
     * The guard UUID written on a proxy, or null when it carries none.
     *
     * <p>Public because the tick handler uses it to re-adopt a proxy it has lost the link to --
     * the chunk-load case where the proxy is already standing next to its guard.
     */
    public static UUID linkedGuardUuid(Entity proxy) {
        return proxy == null ? null : readUuid(proxy, KEY_GUARD);
    }

    /** Re-establishes the link between an existing pair, in persistent data and in the store. */
    public static void link(Mob guard, Villager proxy) {
        if (guard == null || proxy == null) {
            return;
        }
        guard.getPersistentData().putUUID(KEY_PROXY, proxy.getUUID());
        proxy.getPersistentData().putUUID(KEY_GUARD, guard.getUUID());
        GuardIdentityStore.link(guard.level().getServer(), guard.getUUID(), proxy.getUUID());
    }

    /** Removes a proxy from the world and forgets it, on both sides of the link. */
    public static void discard(Entity proxy) {
        if (proxy == null) {
            return;
        }
        Mob guard = guardOf(proxy);
        if (guard != null) {
            unlink(guard);
        } else {
            GuardIdentityStore.findByProxy(proxy.getServer(), proxy.getUUID()).ifPresent(uuid ->
                    GuardIdentityStore.unlink(proxy.getServer(), uuid));
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
