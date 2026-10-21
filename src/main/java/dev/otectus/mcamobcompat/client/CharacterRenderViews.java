package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The client's render views: one detached MCA villager per character on screen, carrying that
 * person's synced appearance, drawn in place of the native actor.
 *
 * <p><b>A view is never in the world.</b> It is created through MCA's own registered entity type for
 * the client level -- exactly as MCA's editor creates its previews -- and never added to it, never
 * ticked, never saved, and never given the identity's UUID, so nothing else can mistake it for a
 * villager. Everything MCA draws comes from MCA's own synced data, copied from the hidden identity,
 * which the client tracks like any entity: genes, gender, skin, hair, clothes, dyes, age and traits,
 * whatever this MCA build syncs, and MCA's own custom-skin loading reacts to it as it would on any
 * villager. The identity's shared entity data -- invisibility above all -- is never copied.
 *
 * <p><b>Cheap to keep current.</b> A view is refreshed when the server's appearance revision for the
 * character moves, when the identity entity was replaced, and otherwise by a bounded reconciliation: a
 * fingerprint of the identity's synced values, taken every half second for views in use, never every
 * frame and never through NBT. A view that has not been drawn for thirty seconds is dropped, and the
 * cache never holds more than {@link #LIMIT}.
 */
public final class CharacterRenderViews {

    /** Upper bound on views kept, whatever a server sends. */
    static final int LIMIT = 512;

    /** Views not drawn for this long are dropped. */
    private static final long IDLE_TICKS = 600L;

    /** How often a view in use is reconciled with its identity, in client ticks. */
    private static final int RECONCILE_INTERVAL = 10;

    /** The data ids every entity shares; a person's appearance lives above them. */
    private static final int FIRST_SUBCLASS_ID = 16;

    /** Identity UUID to its view. Access-ordered, so the eldest is the least recently drawn. */
    private static final Map<UUID, View> VIEWS = new LinkedHashMap<>(64, 0.75F, true);

    /** Identities whose view could not be created on this client: drawn natively, tried again later. */
    private static final Set<UUID> FAILED = new HashSet<>();

    private static boolean warnedCreate;
    private static long clock;

    private CharacterRenderViews() {
    }

    static final class View {
        final Villager villager;
        final int identityId;
        int revision;
        int fingerprint;
        long lastUsed;
        long reconcileAt;

        View(Villager villager, int identityId) {
            this.villager = villager;
            this.identityId = identityId;
        }
    }

    /**
     * The current view of this identity, created or refreshed as needed; null when no view can be made,
     * in which case the native renderer is used.
     */
    static Villager viewOf(Villager identity, ProxyPairs.Pair pair) {
        if (identity == null || !(identity.level() instanceof ClientLevel level) || FAILED.contains(identity.getUUID())) {
            return null;
        }
        View view = VIEWS.get(identity.getUUID());
        if (view == null || view.identityId != identity.getId() || view.villager.level() != level
                || view.villager.getType() != identity.getType()) {
            view = create(identity, level);
            if (view == null) {
                return null;
            }
            VIEWS.put(identity.getUUID(), view);
            while (VIEWS.size() > LIMIT) {
                Iterator<UUID> eldest = VIEWS.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
        view.lastUsed = clock;
        int revision = pair == null ? 0 : pair.appearanceRevision();
        if (revision != view.revision || clock >= view.reconcileAt) {
            view.reconcileAt = clock + RECONCILE_INTERVAL;
            int fingerprint = fingerprint(identity);
            if (fingerprint != view.fingerprint || revision != view.revision) {
                if (!copyAppearance(identity, view)) {
                    // A value went back to its default on the identity: a fresh view is the exact copy.
                    VIEWS.remove(identity.getUUID());
                    View fresh = create(identity, level);
                    if (fresh == null) {
                        return null;
                    }
                    fresh.lastUsed = clock;
                    VIEWS.put(identity.getUUID(), fresh);
                    view = fresh;
                }
                view.fingerprint = fingerprint;
                view.revision = revision;
            }
        }
        return view.villager;
    }

    private static View create(Villager identity, ClientLevel level) {
        try {
            EntityType<?> type = identity.getType();
            Entity created = type.create(level);
            if (!(created instanceof Villager villager)) {
                throw new IllegalStateException(EntityType.getKey(type) + " did not create a villager");
            }
            View view = new View(villager, identity.getId());
            copyAppearance(identity, view);
            view.fingerprint = fingerprint(identity);
            return view;
        } catch (RuntimeException failure) {
            FAILED.add(identity.getUUID());
            if (!warnedCreate) {
                warnedCreate = true;
                McaMobCompat.LOGGER.warn("Could not create an MCA render view for {}; such characters are drawn by "
                        + "their own mod's renderer.", identity.getUUID(), failure);
            }
            return null;
        }
    }

    /**
     * Copies every synced value above the shared entity data from the identity onto the view. False
     * when the view holds a value the identity no longer does, which only a fresh view can undo.
     */
    private static boolean copyAppearance(Villager identity, View view) {
        List<SynchedEntityData.DataValue<?>> source = identity.getEntityData().getNonDefaultValues();
        List<SynchedEntityData.DataValue<?>> copied = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        if (source != null) {
            for (SynchedEntityData.DataValue<?> value : source) {
                if (value.id() >= FIRST_SUBCLASS_ID) {
                    copied.add(value);
                    ids.add(value.id());
                }
            }
        }
        List<SynchedEntityData.DataValue<?>> held = view.villager.getEntityData().getNonDefaultValues();
        if (held != null) {
            for (SynchedEntityData.DataValue<?> value : held) {
                if (value.id() >= FIRST_SUBCLASS_ID && !ids.contains(value.id())) {
                    return false;
                }
            }
        }
        if (!copied.isEmpty()) {
            view.villager.getEntityData().assignValues(copied);
        }
        view.villager.refreshDimensions();
        return true;
    }

    /** A cheap hash of the identity's synced appearance: ids and values, no allocation of NBT. */
    static int fingerprint(Villager identity) {
        List<SynchedEntityData.DataValue<?>> values = identity.getEntityData().getNonDefaultValues();
        if (values == null) {
            return 0;
        }
        int hash = 1;
        for (SynchedEntityData.DataValue<?> value : values) {
            if (value.id() >= FIRST_SUBCLASS_ID) {
                hash = 31 * hash + value.id();
                hash = 31 * hash + (value.value() == null ? 0 : value.value().hashCode());
            }
        }
        return hash;
    }

    /** Forgets one identity's view and failure, after its association changed. */
    public static void invalidate(UUID identity) {
        if (identity != null) {
            VIEWS.remove(identity);
            FAILED.remove(identity);
        }
    }

    /** Ages the cache: one client tick. */
    static void tick(ClientLevel level) {
        clock++;
        if (clock % 20 != 0) {
            return;
        }
        VIEWS.values().removeIf(view -> clock - view.lastUsed > IDLE_TICKS || view.villager.level() != level);
        if (clock % 1200 == 0) {
            FAILED.clear();
        }
    }

    /** Drops every view, on logout and level change. */
    public static void clear() {
        VIEWS.clear();
        FAILED.clear();
        NativePoseAdapter.clear();
    }

    /** How many views are cached, for diagnostics and the profiling notes. */
    public static int size() {
        return VIEWS.size();
    }
}
