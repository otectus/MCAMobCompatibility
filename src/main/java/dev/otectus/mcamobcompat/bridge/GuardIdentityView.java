package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardHeartsChangedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;

import java.util.UUID;

/**
 * A live view of one stored {@link GuardIdentityData.Entry}, handed out through the public API.
 *
 * <p>A view rather than a snapshot: two siblings holding the same guard's identity must see each
 * other's heart changes, and a copy would let one of them write over the other. All state stays in
 * the store; this only knows how to reach it and how to announce a change.
 */
final class GuardIdentityView implements GuardIdentity {

    private final MinecraftServer server;
    private final GuardIdentityData data;
    private final GuardIdentityData.Entry entry;

    GuardIdentityView(MinecraftServer server, GuardIdentityData data, GuardIdentityData.Entry entry) {
        this.server = server;
        this.data = data;
        this.entry = entry;
    }

    @Override
    public UUID uuid() {
        return entry.uuid();
    }

    @Override
    public String name() {
        return entry.name();
    }

    @Override
    public GuardGender gender() {
        return entry.gender();
    }

    @Override
    public String personality() {
        return entry.personality();
    }

    @Override
    public long created() {
        return entry.created();
    }

    @Override
    public int hearts(UUID player) {
        return entry.hearts(player);
    }

    @Override
    public void modHearts(UUID player, int delta) {
        if (player == null || delta == 0) {
            return;
        }
        int before = entry.hearts(player);
        int after = data.addHearts(entry, player, delta);

        // Nullable on purpose: hearts are keyed on a UUID and can move while the guard is in an
        // unloaded chunk or a level nobody is in. Resolving the mob is a convenience for listeners,
        // never a precondition for the change.
        Mob guard = GuardIdentityStore.resolveLoaded(server, entry.uuid());
        MinecraftForge.EVENT_BUS.post(
                new GuardHeartsChangedEvent(this, guard, player, before, after));
    }

    @Override
    public boolean deceased() {
        return entry.deceased();
    }
}
