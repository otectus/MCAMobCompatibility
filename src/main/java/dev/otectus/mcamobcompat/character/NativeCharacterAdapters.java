package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsAdapter;
import dev.otectus.mcamobcompat.integration.workers.WorkersAdapter;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapter registry: exactly one adapter per actor, the most specific first.
 *
 * <p>The order is the whole rule. Villager Workers' workers are Villager Recruits recruits by
 * inheritance, so Workers is asked before Recruits; Guard Villagers is last, because its claim is a
 * datapack tag that a pack could, in principle, put on a recruit. An actor is never given to two.
 *
 * <p>Which adapter structurally claims an entity type is cached per type -- it depends only on the
 * type's class, its namespace and its tags -- and dropped whenever tags or the config reload. Whether
 * that adapter is switched on is read live, so a config change applies at once.
 */
public final class NativeCharacterAdapters {

    private static final List<NativeCharacterAdapter> ORDER =
            List.of(WorkersAdapter.INSTANCE, RecruitsAdapter.INSTANCE, GuardVillagersAdapter.INSTANCE);

    /** A sentinel for "no adapter claims this type", since the map cannot hold null. */
    private static final Object NONE = new Object();

    private static final Map<EntityType<?>, Object> CLAIMS = new ConcurrentHashMap<>();

    private NativeCharacterAdapters() {
    }

    /** Every adapter, in selection order. */
    public static List<NativeCharacterAdapter> all() {
        return ORDER;
    }

    /** The adapter with this id, from a record; empty for one this version does not have. */
    public static Optional<NativeCharacterAdapter> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (NativeCharacterAdapter adapter : ORDER) {
            if (adapter.id().toString().equals(id)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }

    /**
     * The adapter whose mod this actor belongs to, whether or not it is switched on: the one that
     * would bridge it. Null for anything no adapter claims, MCA's own villagers included.
     */
    public static NativeCharacterAdapter claimant(Entity entity) {
        if (!(entity instanceof Mob mob) || McaEntities.isMcaVillager(entity)) {
            return null;
        }
        Object cached = CLAIMS.get(mob.getType());
        if (cached == null) {
            cached = NONE;
            for (NativeCharacterAdapter adapter : ORDER) {
                if (adapter.isActive() && adapter.bindingProblem().isEmpty() && adapter.supports(mob)) {
                    cached = adapter;
                    break;
                }
            }
            CLAIMS.put(mob.getType(), cached);
        }
        return cached instanceof NativeCharacterAdapter adapter ? adapter : null;
    }

    /** The adapter bridging this actor right now: it claims it and its bridge is switched on. */
    public static NativeCharacterAdapter bridging(Entity entity) {
        NativeCharacterAdapter adapter = claimant(entity);
        return adapter != null && adapter.isActive() && adapter.bridgesActors() ? adapter : null;
    }

    /** Forgets which adapter claims which type. Called when tags or the config reload. */
    public static void invalidate() {
        CLAIMS.clear();
    }
}
