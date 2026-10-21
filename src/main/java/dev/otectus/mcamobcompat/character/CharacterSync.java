package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half of keeping clients' MCA appearance current: the appearance revision each record
 * carries, and when it moves.
 *
 * <p>The authoritative write path is MCA's editor, whose saves the editor hook sees and bumps the
 * revision for directly. Everything else that can change how a person looks -- MCA's dyes and combs,
 * commands, add-ons -- is caught by a cheap fallback: every ten seconds a pair's heartbeat folds the
 * identity's synced appearance values (its text and number data, never NBT) into a fingerprint, and a
 * changed fingerprint bumps the revision. Clients also notice changes on their own; the revision is what
 * a client that was not watching, or an editor session, compares against.
 */
public final class CharacterSync {

    /** How often the fallback fingerprint is taken: every ten seconds of a pair's heartbeat. */
    private static final int OBSERVE_INTERVAL = 200;

    /** The data ids every entity shares; a person's appearance lives above them. */
    private static final int FIRST_SUBCLASS_ID = 16;

    private static final Map<UUID, Integer> FINGERPRINTS = new ConcurrentHashMap<>();

    private CharacterSync() {
    }

    /** One heartbeat of a pair: where it is, and whether its person's appearance changed. */
    public static void observe(ServerLevel level, Mob actor, Villager identity) {
        if (actor.getServer() == null) {
            return;
        }
        GuardIdentityData data = GuardIdentityStore.get(actor.getServer());
        GuardIdentityData.Entry entry = data.find(actor.getUUID());
        if (entry == null || data.isReadOnly()) {
            return;
        }
        data.noteLocation(entry, level.dimension().location().toString(), actor.blockPosition().asLong());
        if (identity.tickCount % OBSERVE_INTERVAL >= 20) {
            return;
        }
        int fingerprint = fingerprint(identity);
        Integer previous = FINGERPRINTS.put(actor.getUUID(), fingerprint);
        if (previous != null && previous != fingerprint) {
            data.bumpAppearance(entry);
            GuardProxySync.invalidatePairing(actor.getUUID());
        }
    }

    /**
     * The identity's appearance as one number: every synced value above the shared entity data that
     * is text or a number -- MCA's clothes, hair, skin, name, genes, colours (integers on MCA 7.7),
     * gender and age state. Compound values are left out on purpose: they carry memories and traits'
     * bookkeeping, which change with every conversation and have nothing to do with how the person
     * looks. A mood change can still move the fingerprint; that costs one small association packet.
     */
    public static int fingerprint(Villager identity) {
        List<SynchedEntityData.DataValue<?>> values = identity.getEntityData().getNonDefaultValues();
        if (values == null) {
            return 0;
        }
        int hash = 1;
        for (SynchedEntityData.DataValue<?> value : values) {
            Object raw = value.value();
            if (value.id() >= FIRST_SUBCLASS_ID
                    && (raw instanceof String || raw instanceof Float || raw instanceof Integer)) {
                hash = 31 * hash + value.id();
                hash = 31 * hash + raw.hashCode();
            }
        }
        return hash;
    }

    /** Records an appearance change made through a known write path, and re-sends the association. */
    public static void appearanceChanged(Mob actor, Villager identity) {
        if (actor == null || actor.getServer() == null) {
            return;
        }
        GuardIdentityData data = GuardIdentityStore.get(actor.getServer());
        GuardIdentityData.Entry entry = data.find(actor.getUUID());
        if (entry != null && !data.isReadOnly()) {
            data.bumpAppearance(entry);
        }
        if (identity != null) {
            FINGERPRINTS.put(actor.getUUID(), fingerprint(identity));
        }
        GuardProxySync.invalidatePairing(actor.getUUID());
    }

    public static void forget(UUID actor) {
        if (actor != null) {
            FINGERPRINTS.remove(actor);
        }
    }

    public static void clear() {
        FINGERPRINTS.clear();
    }
}
