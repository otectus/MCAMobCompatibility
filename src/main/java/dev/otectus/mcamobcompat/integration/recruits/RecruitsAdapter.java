package dev.otectus.mcamobcompat.integration.recruits;

import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.Capability;
import dev.otectus.mcamobcompat.character.CharacterCommand;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.EnlistContext;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.PlayerReceipts;
import dev.otectus.mcamobcompat.character.NativeRoleState;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Villager Recruits' recruits as MCA characters: every combatant, companion and noble Recruits 1.15.2
 * registers, each a real recruit with Recruits' own AI, groups, formations, upkeep and orders, and an
 * MCA person behind it.
 *
 * <p><b>Follow states.</b> Recruits keeps one integer: 0 wander, 1 follow its owner, 2 hold the
 * current spot, 3 hold its hold position, 4 hold the owner's spot, 5 protect, 6 (workers) work. MCA's
 * Follow, Stay and Move become 1, 2 and 0 through Recruits' own {@code setFollowState}, and only for a
 * player Recruits itself would take orders from ({@code isEffectedByCommand}): MCA hearts never give
 * anyone command of another player's soldier.
 *
 * <p><b>No navigation is replayed.</b> An add-on's path on the hidden identity is dropped, not handed
 * to a recruit: it would fight the formation the owner set.
 *
 * <p><b>Not law enforcement.</b> A recruit's person keeps a civilian profession unless the server
 * opts in with {@code [recruits] lawEnforcement}; a soldier is not a constable to MCA: Crime.
 */
public class RecruitsAdapter extends NativeCharacterAdapter {

    public static final RecruitsAdapter INSTANCE = new RecruitsAdapter();

    static final int WANDER = 0;
    static final int FOLLOW = 1;
    static final int HOLD = 2;

    /** Recruits' default names: labels, not names, so an adopted recruit gets an MCA name instead. */
    private static final List<String> GENERIC = List.of("Recruit", "Shieldman", "Bowman", "Crossbowman", "Horseman",
            "Nomad", "Scout", "Messenger", "Captain", "Commander", "Patrol Leader", "Noble", "Villager Noble",
            "Siege Engineer", "Assassin", "Village Guard", "Village Guard Leader");

    protected RecruitsAdapter() {
        this("recruits", ModIntegrations.recruits());
    }

    protected RecruitsAdapter(String path, IntegrationModule module) {
        super(path, module);
    }

    @Override
    public Optional<String> bindingProblem() {
        return RecruitsBinding.problem();
    }

    @Override
    public boolean bridgesActors() {
        return ModIntegrations.recruits().bridgesRecruits();
    }

    @Override
    public boolean opensMcaInteraction() {
        return ModIntegrations.recruits().opensMcaInteraction();
    }

    @Override
    public AppearanceMode appearanceMode() {
        return ModIntegrations.recruits().appearance();
    }

    @Override
    public boolean supports(Mob actor) {
        return !McaEntities.isMcaVillager(actor) && ModIds.RECRUITS.equals(EntityType.getKey(actor.getType()).getNamespace())
                && RecruitsBinding.isRecruit(actor);
    }

    @Override
    public Set<Capability> capabilities(Mob actor) {
        Set<Capability> set = EnumSet.copyOf(super.capabilities(actor));
        set.add(Capability.NATIVE_INTERFACE);
        if (!RecruitsBinding.isNoble(actor)) {
            set.add(Capability.FOLLOW);
            set.add(Capability.STAY);
            set.add(Capability.MOVE);
            set.add(Capability.HOST_REPLACEMENT);
        }
        if (transitions().enlist()) {
            set.add(Capability.ENLIST);
        }
        if (transitions().returnToCivilian()) {
            set.add(Capability.RETURN_TO_CIVILIAN);
        }
        if (ModIntegrations.recruits().lawEnforcement()) {
            set.add(Capability.LAW_ENFORCEMENT);
        }
        return set;
    }

    @Override
    public NativeRoleState inspect(Mob actor) {
        String role = EntityType.getKey(actor.getType()).getPath();
        UUID owner = RecruitsBinding.owner(actor).orElse(null);
        OptionalInt state = RecruitsBinding.followState(actor);
        String activity = actor.getTarget() != null ? "activity.mcamobcompat.fighting"
                : state.isEmpty() ? "" : switch (state.getAsInt()) {
                    case FOLLOW -> "activity.mcamobcompat.following";
                    case HOLD, 3, 4 -> "activity.mcamobcompat.holding";
                    case 5 -> "activity.mcamobcompat.protecting";
                    case 6 -> "activity.mcamobcompat.working";
                    default -> "activity.mcamobcompat.wandering";
                };
        return new NativeRoleState(role, owner, activity, state.isPresent() && state.getAsInt() == 6);
    }

    @Override
    public CharacterCommand.Result applyCommand(CharacterCommand.Context context, Mob actor, CharacterCommand command) {
        if (RecruitsBinding.isNoble(actor)) {
            return CharacterCommand.Result.unsupported("message.mcamobcompat.command.noble");
        }
        if (!RecruitsBinding.owned(actor)) {
            return CharacterCommand.Result.refused("message.mcamobcompat.command.unowned");
        }
        if (context.initiator() == null || !RecruitsBinding.acceptsCommandFrom(actor, context.initiator())) {
            return CharacterCommand.Result.refused("message.mcamobcompat.command.not_owner");
        }
        int state = switch (command) {
            case FOLLOW -> FOLLOW;
            case STAY -> HOLD;
            case MOVE -> releasedState(actor);
        };
        OptionalInt current = RecruitsBinding.followState(actor);
        if (current.isPresent() && current.getAsInt() == state) {
            return CharacterCommand.Result.APPLIED;
        }
        return RecruitsBinding.setFollowState(actor, state) ? CharacterCommand.Result.APPLIED
                : CharacterCommand.Result.unsupported("message.mcamobcompat.command.unsupported");
    }

    /** The follow state MCA's Move releases a recruit to: wandering, Recruits' own default. */
    protected int releasedState(Mob actor) {
        return WANDER;
    }

    /**
     * Recruits opens its own screen on a crouching click from the owner -- its inventory and orders --
     * and its hire screen on any click on an unhired recruit, so the click is replayed as it came.
     */
    @Override
    public InteractionResult openNativeInterface(ServerPlayer player, Mob actor, InteractionHand hand) {
        return dev.otectus.mcamobcompat.bridge.GuardInteractions.interactAfterHook(player, actor, hand);
    }

    @Override
    public boolean isHostileTo(Mob actor, Player player) {
        return actor.getTarget() == player;
    }

    /**
     * A recruit's person is a civilian to MCA unless the server makes recruits law enforcement. An
     * enlisted person keeps the profession they had; an adopted recruit's new person has none.
     */
    @Override
    public ResourceLocation identityProfession(Mob actor, CharacterOrigin origin) {
        if (ModIntegrations.recruits().lawEnforcement()) {
            return GuardProxy.GUARD_PROFESSION;
        }
        return origin == CharacterOrigin.ADOPTED_ACTOR ? GuardProxy.NO_PROFESSION : null;
    }

    @Override
    public boolean acceptsAddonTarget(Mob actor, LivingEntity target) {
        return ModIntegrations.recruits().lawEnforcement() && target != null && !(target instanceof Player);
    }

    /**
     * Battle is not assault. A hit from somebody the recruit is fighting, or who is fighting the
     * recruit's own side, stays a native combat matter; anything else -- a player striking a hired
     * soldier out of the blue -- is relayed to MCA like a hit on any villager.
     */
    @Override
    public boolean relaysSocialConsequences(Mob actor, Entity attacker) {
        Entity source = attacker instanceof Projectile projectile && projectile.getOwner() != null
                ? projectile.getOwner() : attacker;
        if (source == null) {
            return true;
        }
        if (actor.getTarget() == source) {
            return false;
        }
        if (source instanceof Mob mob && mob.getTarget() != null) {
            LivingEntity enemy = mob.getTarget();
            if (enemy == actor || RecruitsBinding.owner(actor).map(enemy.getUUID()::equals).orElse(false)) {
                return false;
            }
        }
        return !(actor.getTeam() != null && source.getTeam() != null && !actor.getTeam().isAlliedTo(source.getTeam()));
    }

    @Override
    protected List<String> genericNames() {
        return GENERIC;
    }

    @Override
    public TransitionSupport transitions() {
        boolean hire = RecruitsBinding.canHire() && Hooks.armed(Hooks.RECRUITS_HIRE_FROM_VILLAGER);
        boolean release = RecruitsBinding.canDisband();
        boolean host = RecruitsBinding.promotedEventClass() != null;
        return new TransitionSupport(hire, hire ? "" : "message.mcamobcompat.enlist.unavailable_build",
                release, release ? "" : "message.mcamobcompat.release.unavailable",
                host, host ? "" : "message.mcamobcompat.transition.unavailable");
    }

    /**
     * A recruit or a worker for an enlisting villager, hired through Recruits' own hire: Recruits'
     * spawn initialisation and variant choice, then {@code handleRecruiting}, which checks and takes the
     * player's payment, sets the owner, the group and the unit count and posts Recruits' own
     * {@code Hired} event -- exactly the steps of Recruits' hire-from-villager, without its discard of
     * the villager. What the payment took is measured into the receipt, so a failure afterwards can be
     * refunded exactly; a hire Recruits refuses has already told the player why, and takes nothing.
     */
    @Override
    public Mob createEnlistedActor(EnlistContext context) {
        ResourceLocation role = context.role();
        ServerLevel level = context.level();
        Villager person = context.person();
        ServerPlayer player = context.player();
        EntityType<?> type = role == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(role).orElse(null);
        if (type == null || !role.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))
                || !module().namespace().equals(role.getNamespace())) {
            context.refuse("message.mcamobcompat.enlist.unknown_role");
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob actor) || !supports(actor)) {
            if (created != null) {
                created.discard();
            }
            context.refuse("message.mcamobcompat.enlist.unknown_role");
            return null;
        }
        actor.moveTo(person.getX(), person.getY(), person.getZ(), person.getYRot(), person.getXRot());
        actor.setYHeadRot(person.getYHeadRot());
        actor.setYBodyRot(person.yBodyRot);
        if (!RecruitsBinding.initSpawn(actor)) {
            actor.discard();
            context.refuse("message.mcamobcompat.enlist.unavailable_build");
            return null;
        }
        RecruitsBinding.applyVariantFromVillager(actor, person);
        if (person.hasCustomName()) {
            actor.setCustomName(person.getCustomName());
        }
        Object group = context.group();
        if (group == null) {
            List<Object> groups = RecruitsBinding.playerGroups(player);
            group = groups.isEmpty() ? null : groups.get(0);
        }
        if (group == null || !RecruitsBinding.isGroup(group)) {
            actor.discard();
            context.refuse("message.mcamobcompat.enlist.no_group");
            return null;
        }
        Map<Item, Integer> before = PlayerReceipts.count(player);
        boolean hired = RecruitsBinding.handleRecruiting(player, group, actor);
        PlayerReceipts.record(before, PlayerReceipts.count(player), context.receipt());
        if (!hired) {
            // Recruits refused (cost, unit cap, a listener) and said why; anything it took goes back.
            PlayerReceipts.refund(player, context.receipt());
            actor.discard();
            context.refuse("message.mcamobcompat.enlist.hire_refused");
            return null;
        }
        RecruitsBinding.setFollowState(actor, FOLLOW);
        RecruitsBinding.groupUuid(group).ifPresent(id -> RecruitsBinding.setGroup(actor, id));
        RecruitsBinding.setUpCompanion(actor);
        float fraction = person.getMaxHealth() <= 0.0F ? 1.0F : person.getHealth() / person.getMaxHealth();
        actor.setHealth(Math.max(1.0F, actor.getMaxHealth() * Math.min(1.0F, fraction)));
        context.prepare(actor);
        if (!level.addFreshEntity(actor)) {
            rollbackEnlistedActor(context, actor);
            actor.discard();
            context.refuse("message.mcamobcompat.enlist.spawn_refused");
            return null;
        }
        return actor;
    }

    /** Undoes the hire through Recruits' own dismissal, then refunds exactly what the hire took. */
    @Override
    public void rollbackEnlistedActor(EnlistContext context, Mob actor) {
        if (RecruitsBinding.owned(actor)) {
            RecruitsBinding.disband(actor, context.player());
        }
        if (!context.receipt().getBoolean("Refunded")) {
            PlayerReceipts.refund(context.player(), context.receipt());
        }
    }

    /**
     * Ends a recruit's service through Recruits' own dismissal -- owner, team, group and unit count --
     * before it leaves the world for a civilian return. It is not killed, so no death effect runs.
     */
    @Override
    public void releaseNativeState(Mob actor, ServerPlayer player) {
        if (RecruitsBinding.owned(actor)) {
            RecruitsBinding.disband(actor, player);
        }
        actor.setTarget(null);
        actor.getNavigation().stop();
    }

    /** A recruit's possessions: its equipment and its inventory, which Recruits keeps apart. */
    @Override
    public List<ItemStack> possessions(Mob actor) {
        List<ItemStack> stacks = new ArrayList<>();
        Optional<SimpleContainer> inventory = RecruitsBinding.inventory(actor);
        if (inventory.isPresent()) {
            // Recruits mirrors the equipment into the first slots of this container, so the container
            // alone is the whole set; reading the equipment as well would count those items twice.
            SimpleContainer container = inventory.get();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            }
            return stacks;
        }
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
        RecruitsBinding.inventory(actor).ifPresent(SimpleContainer::clearContent);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            actor.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /** The owner sends a hired recruit home; an operator may send any. */
    @Override
    public boolean mayReleaseToCivilian(Mob actor, ServerPlayer player) {
        Optional<UUID> owner = RecruitsBinding.owner(actor);
        return player.hasPermissions(2) || owner.map(player.getUUID()::equals).orElse(false);
    }
}
