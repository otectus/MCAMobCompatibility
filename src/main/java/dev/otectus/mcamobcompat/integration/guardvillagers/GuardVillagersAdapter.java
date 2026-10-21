package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.Capability;
import dev.otectus.mcamobcompat.character.CharacterCommand;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.EnlistContext;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeRoleState;
import dev.otectus.mcamobcompat.classification.TagClassifier;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guard Villagers' guards as MCA characters: the bridge 0.2 built, now one adapter among three.
 *
 * <p>Claims every entity the {@code mcamobcompat:guard} tag names while Guard Villagers is installed,
 * as 0.2 did, so a pack that tags another mod's defender keeps its MCA identity and screen. Only Guard
 * Villagers' own guard gets the native half -- follow, patrol, its inventory, enlistment -- because
 * those are reached through Guard Villagers' own member names, which another mod's class does not have.
 *
 * <p>A guard's MCA person carries MCA's {@code mca:guard} profession: Guard Villagers' guards are a
 * village's guards, and MCA: Crime and MCA: Reputation reading them as law enforcement is the
 * contract 0.2 established.
 */
public final class GuardVillagersAdapter extends NativeCharacterAdapter {

    public static final GuardVillagersAdapter INSTANCE = new GuardVillagersAdapter();

    /** Guard Villagers' guard entity, the one an enlisting villager becomes. */
    public static final ResourceLocation GUARD_TYPE = McaMobCompat.location(ModIds.GUARD_VILLAGERS, "guard");

    /** Villager types whose path is also a Guard Villagers variant name. */
    private static final Set<String> VARIANTS = Set.of("desert", "savanna", "swamp", "jungle", "taiga", "snow", "plains");

    private GuardVillagersAdapter() {
        super("guardvillagers", ModIntegrations.guardVillagers());
    }

    @Override
    public boolean bridgesActors() {
        return ModIntegrations.guardVillagers().bridgesGuards();
    }

    @Override
    public boolean opensMcaInteraction() {
        return ModIntegrations.guardVillagers().opensMcaInteraction();
    }

    @Override
    public AppearanceMode appearanceMode() {
        return ConfigAccess.enumValue(McaMobCompatConfig.SERVER.guardAppearance, AppearanceMode.MCA);
    }

    @Override
    public boolean supports(Mob actor) {
        return !McaEntities.isMcaVillager(actor) && TagClassifier.hasRole(actor, MobRole.GUARD);
    }

    /** Whether this is Guard Villagers' own guard rather than another mod's tagged defender. */
    private static boolean nativeGuard(Mob actor) {
        return ModIds.GUARD_VILLAGERS.equals(EntityType.getKey(actor.getType()).getNamespace())
                && GuardReflection.isGuardVillagersGuard(actor);
    }

    @Override
    public Set<Capability> capabilities(Mob actor) {
        Set<Capability> set = EnumSet.copyOf(super.capabilities(actor));
        set.add(Capability.LAW_ENFORCEMENT);
        set.add(Capability.ADDON_TARGETS);
        set.add(Capability.NATIVE_INTERFACE);
        if (nativeGuard(actor)) {
            set.add(Capability.FOLLOW);
            set.add(Capability.STAY);
            set.add(Capability.MOVE);
            if (transitions().enlist()) {
                set.add(Capability.ENLIST);
            }
            set.add(Capability.RETURN_TO_CIVILIAN);
        }
        return set;
    }

    @Override
    public NativeRoleState inspect(Mob actor) {
        String role = EntityType.getKey(actor.getType()).getPath();
        if (!nativeGuard(actor)) {
            return NativeRoleState.of(role);
        }
        UUID owner = GuardReflection.ownerId(actor).orElse(null);
        String activity;
        if (GuardReflection.following(actor).orElse(false)) {
            activity = "activity.mcamobcompat.following";
        } else if (GuardReflection.patrolling(actor).orElse(false)) {
            activity = "activity.mcamobcompat.patrolling";
        } else if (actor.getTarget() != null) {
            activity = "activity.mcamobcompat.fighting";
        } else {
            activity = "activity.mcamobcompat.guarding";
        }
        return new NativeRoleState(role, owner, activity, false);
    }

    /**
     * MCA's Follow, Stay and Move on Guard Villagers' own flags. Guard Villagers' follow goal still
     * decides whether it actually follows -- its Hero of the Village rule included -- so nothing here
     * lets hearts buy what Guard Villagers would refuse.
     */
    @Override
    public CharacterCommand.Result applyCommand(CharacterCommand.Context context, Mob actor, CharacterCommand command) {
        if (!nativeGuard(actor)) {
            return CharacterCommand.Result.unsupported("message.mcamobcompat.command.unsupported");
        }
        switch (command) {
            case FOLLOW -> {
                if (context.initiator() == null) {
                    GuardReflection.setFollowing(actor, false);
                    return CharacterCommand.Result.refused("message.mcamobcompat.command.no_player");
                }
                GuardReflection.setOwnerId(actor, context.initiator());
                GuardReflection.setPatrolling(actor, false);
                GuardReflection.setFollowing(actor, true);
            }
            case STAY -> {
                GuardReflection.setFollowing(actor, false);
                GuardReflection.setPatrolPos(actor, actor.blockPosition());
                GuardReflection.setPatrolling(actor, true);
            }
            case MOVE -> {
                GuardReflection.setFollowing(actor, false);
                GuardReflection.setPatrolling(actor, false);
            }
        }
        return CharacterCommand.Result.APPLIED;
    }

    /** Guard Villagers opens its inventory on a standing click, so the crouch is cleared for one call. */
    @Override
    public InteractionResult openNativeInterface(ServerPlayer player, Mob actor, InteractionHand hand) {
        return GuardInteractions.delegateToGuardVillagers(player, actor, hand);
    }

    @Override
    public ResourceLocation identityProfession(Mob actor, CharacterOrigin origin) {
        return GuardProxy.GUARD_PROFESSION;
    }

    @Override
    public boolean relaysNavigation() {
        return true;
    }

    @Override
    public boolean acceptsAddonTarget(Mob actor, LivingEntity target) {
        return GuardProxySync.acceptsAddonTarget(target);
    }

    @Override
    public TransitionSupport transitions() {
        boolean guardType = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_TYPE)
                .map(type -> GUARD_TYPE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))).orElse(false);
        boolean enlist = isActive() && guardType;
        return new TransitionSupport(enlist, enlist ? "" : "message.mcamobcompat.enlist.unavailable",
                true, "", false, "message.mcamobcompat.transition.unavailable");
    }

    /**
     * A real Guard Villagers guard for an enlisting villager, initialised the way Guard Villagers'
     * own conversion initialises one -- the weapon in hand, a variant, persistence, every slot
     * dropping its item, and the enlisting player's reputation with it, which is what lets them open
     * its inventory -- and added to the level, honouring any mod that refuses it. Its name and its
     * link to the person are the transition's to set.
     */
    @Override
    public Mob createEnlistedActor(EnlistContext context) {
        ServerLevel level = context.level();
        Villager person = context.person();
        ItemStack payment = context.payment();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_TYPE).orElse(null);
        if (type == null || !GUARD_TYPE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))) {
            context.refuse("message.mcamobcompat.enlist.unavailable");
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob guard)) {
            if (created != null) {
                created.discard();
            }
            context.refuse("message.mcamobcompat.enlist.unavailable");
            return null;
        }
        guard.moveTo(person.getX(), person.getY(), person.getZ(), person.getYRot(), person.getXRot());
        guard.setYHeadRot(person.getYHeadRot());
        guard.setYBodyRot(person.yBodyRot);
        if (!payment.isEmpty()) {
            guard.setItemSlot(EquipmentSlot.MAINHAND, payment.copyWithCount(1));
        }
        String villagerType = BuiltInRegistries.VILLAGER_TYPE.getKey(person.getVillagerData().getType()).getPath();
        String variant = VARIANTS.contains(villagerType) ? villagerType
                : GuardReflection.variantForBiome(guard).orElse(null);
        GuardReflection.setVariant(guard, variant);
        guard.setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            guard.setDropChance(slot, 100.0F);
        }
        float fraction = person.getMaxHealth() <= 0.0F ? 1.0F : person.getHealth() / person.getMaxHealth();
        guard.setHealth(Math.max(1.0F, guard.getMaxHealth() * Math.min(1.0F, fraction)));
        if (person.hasCustomName()) {
            guard.setCustomName(person.getCustomName());
        }
        if (context.player() != null) {
            GuardReflection.addReputation(guard, context.player().getUUID(), GuardVillagersConfig.reputationRequirement());
        }
        context.prepare(guard);
        if (!level.addFreshEntity(guard)) {
            guard.discard();
            context.refuse("message.mcamobcompat.enlist.spawn_refused");
            return null;
        }
        return guard;
    }

    /**
     * What Guard Villagers' own conversion credits the player who made a guard with, once the enlistment
     * has committed: vanilla's summoned-entity trigger, and its own {@code guards_made} statistic by id.
     */
    public static void creditEnlistment(ServerPlayer player, Mob guard) {
        if (player == null || guard == null) {
            return;
        }
        net.minecraft.advancements.CriteriaTriggers.SUMMONED_ENTITY.trigger(player, guard);
        // The registered instance itself: a statistic is found by identity, so an equal copy of the id
        // would have no name.
        ResourceLocation stat = BuiltInRegistries.CUSTOM_STAT.get(McaMobCompat.location(ModIds.GUARD_VILLAGERS, "guards_made"));
        if (stat != null) {
            player.awardStat(stat);
        }
    }

    /** A Guard Villagers guard's possessions are its six equipment slots, which are its inventory. */
    @Override
    public List<ItemStack> possessions(Mob actor) {
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = actor.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    @Override
    public void clearPossessions(Mob actor) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            actor.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /** A guard belongs to its village: the player it follows is not its owner for the editor. */
    @Override
    public UUID editorOwner(Mob actor) {
        return null;
    }

    @Override
    public boolean mayReleaseToCivilian(Mob actor, ServerPlayer player) {
        // A guard has no hiring contract; whoever enlisted it, or an operator, may send it home. The
        // transition service checks the enlisting player against the journal before asking here.
        return player.hasPermissions(2);
    }
}
