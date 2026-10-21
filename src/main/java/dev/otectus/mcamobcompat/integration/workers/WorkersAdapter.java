package dev.otectus.mcamobcompat.integration.workers;

import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.Capability;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.NativeRoleState;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Villager Workers' workers as MCA characters: farmer, lumberjack, miner, builder, merchant,
 * fisherman, animal farmer, courier and cook, as Workers 2.0.3 registers them. Its work-area marker
 * entities and fishing bobbers are not characters and are never claimed.
 *
 * <p>A worker is a recruit by inheritance, so ownership, hiring and orders are Recruits' and this
 * adapter extends {@link RecruitsAdapter}; it is selected before it, so a worker has one adapter and
 * one identity. What differs is work: Workers marks a worker at work with its follow state 6, so MCA's
 * Move sends a worker back to work rather than off to wander, and Follow and Stay pause the job the
 * way Workers' own orders do -- the work area, home and route assignments are kept and resume.
 *
 * <p>Workers' own animal handling stays Workers': this mod's hunting protections concern MCA
 * villagers' chores only and are not applied to workers.
 */
public final class WorkersAdapter extends RecruitsAdapter {

    public static final WorkersAdapter INSTANCE = new WorkersAdapter();

    /** Workers' working follow state: {@code AbstractWorkerEntity.isWorking()} in 2.0.3. */
    static final int WORKING = 6;

    private static final List<String> GENERIC = List.of("Farmer", "Lumberjack", "Miner", "Builder", "Merchant",
            "Fisherman", "Animal Farmer", "Courier", "Cook", "Worker");

    private WorkersAdapter() {
        super("workers", ModIntegrations.workers());
    }

    @Override
    public Optional<String> bindingProblem() {
        Optional<String> recruits = RecruitsBinding.problem();
        return recruits.isPresent() ? recruits : WorkersBinding.problem();
    }

    @Override
    public boolean bridgesActors() {
        return ModIntegrations.workers().bridgesWorkers();
    }

    @Override
    public boolean opensMcaInteraction() {
        return ModIntegrations.workers().opensMcaInteraction();
    }

    @Override
    public AppearanceMode appearanceMode() {
        return ModIntegrations.workers().appearance();
    }

    @Override
    public boolean supports(Mob actor) {
        return !McaEntities.isMcaVillager(actor) && ModIds.WORKERS.equals(EntityType.getKey(actor.getType()).getNamespace())
                && WorkersBinding.isWorker(actor);
    }

    @Override
    public Set<Capability> capabilities(Mob actor) {
        Set<Capability> set = EnumSet.copyOf(super.capabilities(actor));
        // A worker is never law enforcement, and a promotion is a recruit's, not a worker's.
        set.remove(Capability.LAW_ENFORCEMENT);
        set.remove(Capability.HOST_REPLACEMENT);
        return set;
    }

    @Override
    public NativeRoleState inspect(Mob actor) {
        NativeRoleState state = super.inspect(actor);
        boolean working = WorkersBinding.working(actor);
        String activity = working ? "activity.mcamobcompat.working" : state.activityKey();
        return new NativeRoleState(state.role(), state.owner(), activity, working);
    }

    /** Move puts a worker back to work: Workers' own working state, which keeps its assignments. */
    @Override
    protected int releasedState(Mob actor) {
        return WORKING;
    }

    @Override
    public ResourceLocation identityProfession(Mob actor, CharacterOrigin origin) {
        return origin == CharacterOrigin.ADOPTED_ACTOR ? dev.otectus.mcamobcompat.bridge.GuardProxy.NO_PROFESSION : null;
    }

    @Override
    public boolean acceptsAddonTarget(Mob actor, LivingEntity target) {
        return false;
    }

    @Override
    public boolean relaysSocialConsequences(Mob actor, Entity attacker) {
        return true;
    }

    @Override
    protected List<String> genericNames() {
        return GENERIC;
    }

    /** Frees the worker's work and home areas through Workers, then ends its service through Recruits. */
    @Override
    public void releaseNativeState(Mob actor, ServerPlayer player) {
        WorkersBinding.releaseAssignments(actor);
        super.releaseNativeState(actor, player);
        WorkersChunkTickets.release(actor);
    }
}
