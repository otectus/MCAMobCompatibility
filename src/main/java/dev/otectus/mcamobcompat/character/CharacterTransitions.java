package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every structural change a character goes through, each as one journaled transition with an explicit
 * rollback: an MCA villager taking a native role, a native role ending, and the native mod replacing
 * the actor.
 *
 * <p><b>Enlistment keeps the person.</b> The MCA villager is never discarded and never re-rolled: a new
 * native actor is created and registered first, through the native mod's own initialisation and, for
 * Recruits and Workers, its own hire and payment; only once the world accepted it is the villager
 * linked, hidden and given its role's MCA profession, and only then is the cost settled. Every step
 * before the link is undone on failure -- the actor goes, the payment is refunded from what the
 * receipt measured, and the villager is exactly as it was.
 *
 * <p><b>A return keeps everything acquired.</b> The actor's equipment and cargo move into the person's
 * own inventory exactly once, and the return is refused before anything changes when they do not fit;
 * the native mod's own dismissal releases its groups, contracts and chunk tickets; the person reappears
 * with the flags proxy mode changed restored and their civilian profession back, keeping every edit,
 * heart and relationship gained meanwhile. The actor leaves the world as a role change -- no death, no
 * loot, no experience.
 *
 * <p><b>A replacement keeps the character.</b> Recruits promotes a recruit by discarding it and adding
 * a new entity. Its {@code Promoted} event opens a bracket; the discard inside it is not an abandonment,
 * the next recruit to join in the same call takes over the record, and the association generation
 * moves on, so anything still naming the old actor is stale. A replacement that never joins is undone
 * at the end of the tick by restoring the old actor from the snapshot taken as it left.
 *
 * <p>The transaction id and the generation are stamped on both entities, so a world reloaded after an
 * unclean stop can tell a committed pair from a stale one (see {@link TransitionRecovery}).
 */
public final class CharacterTransitions {

    /** Persistent-data key on both halves: the last transition that committed. */
    public static final String KEY_TX = McaMobCompat.MOD_ID + ":tx";

    /** Persistent-data key on both halves: the association generation of that commit. */
    public static final String KEY_GENERATION = McaMobCompat.MOD_ID + ":generation";

    /**
     * Persistent-data key on an enlisted person: their civilian profession and the flags proxy mode
     * changed, so the return is possible even if the store's record was lost to an unclean stop.
     */
    public static final String KEY_CIVILIAN = McaMobCompat.MOD_ID + ":civilian";

    /** Actors leaving the world because a transition of this mod is removing them. */
    private static final Set<UUID> LEAVING = ConcurrentHashMap.newKeySet();

    /** Open host replacements, by the actor being replaced. */
    private static final Map<UUID, Replacement> REPLACEMENTS = new LinkedHashMap<>();

    private CharacterTransitions() {
    }

    /** The result of a transition a player asked for, with the message to show them. */
    public record Outcome(boolean done, String messageKey, Mob actor) {

        static Outcome refused(String key) {
            return new Outcome(false, key, null);
        }
    }

    /** Whether this actor is leaving the world because one of this mod's transitions removes it. */
    public static boolean isLeavingForTransition(Entity actor) {
        return actor != null && LEAVING.contains(actor.getUUID());
    }

    // ------------------------------------------------------------------------------------ enlistment

    /** Guard Villagers' recruit gesture, in native mode: the person becomes a real guard. */
    public static Outcome enlistGuard(ServerPlayer player, Villager person) {
        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty()) {
            return Outcome.refused("message.mcamobcompat.enlist.no_weapon");
        }
        EnlistContext context = new EnlistContext(player.serverLevel(), person, player, weapon.copyWithCount(1),
                GuardVillagersAdapter.GUARD_TYPE, null);
        return enlist(context, GuardVillagersAdapter.INSTANCE, guard -> {
            if (!player.getAbilities().instabuild) {
                player.getMainHandItem().shrink(1);
            }
            GuardVillagersAdapter.creditEnlistment(player, guard);
        });
    }

    /** Recruits' noble hire of a villager, for an MCA villager: the same person is hired. */
    public static Outcome enlistHired(ServerLevel level, Villager person, EntityType<?> type, ServerPlayer player,
                                      Object group) {
        ResourceLocation role = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        NativeCharacterAdapter adapter = adapterForRole(role);
        if (adapter == null) {
            return Outcome.refused("message.mcamobcompat.enlist.unknown_role");
        }
        return enlist(new EnlistContext(level, person, player, ItemStack.EMPTY, role, group), adapter, actor -> {
        });
    }

    /** The role action: give an MCA adult a native role, with the native mod's own costs. */
    public static Outcome enlistByCommand(ServerPlayer player, Villager person, ResourceLocation role) {
        if (!ConfigAccess.bool(McaMobCompatConfig.SERVER.playerEnlistment, true) && !player.hasPermissions(2)) {
            return Outcome.refused("message.mcamobcompat.enlist.disabled");
        }
        if (person.level() != player.level() || player.distanceToSqr(person) > 8.0D * 8.0D) {
            return Outcome.refused("message.mcamobcompat.character.too_far");
        }
        if (GuardVillagersAdapter.GUARD_TYPE.equals(role)) {
            return enlistGuard(player, person);
        }
        NativeCharacterAdapter adapter = adapterForRole(role);
        if (adapter == null) {
            return Outcome.refused("message.mcamobcompat.enlist.unknown_role");
        }
        return enlist(new EnlistContext(player.serverLevel(), person, player, ItemStack.EMPTY, role, null), adapter,
                actor -> {
                });
    }

    /** The adapter whose mod owns an entity type id, by namespace. */
    public static NativeCharacterAdapter adapterForRole(ResourceLocation role) {
        if (role == null) {
            return null;
        }
        for (NativeCharacterAdapter adapter : NativeCharacterAdapters.all()) {
            if (adapter.module().namespace().equals(role.getNamespace())) {
                return adapter;
            }
        }
        return null;
    }

    /** Why this person cannot enlist into this adapter now, or null when they can. */
    public static String eligibility(Villager person, NativeCharacterAdapter adapter) {
        MinecraftServer server = person == null ? null : person.getServer();
        if (server == null || !McaEntities.isMcaVillager(person)) {
            return "message.mcamobcompat.enlist.not_mca";
        }
        if (!person.isAlive()) {
            return "message.mcamobcompat.enlist.unavailable";
        }
        if (person.isBaby()) {
            return "message.mcamobcompat.recruit.too_young";
        }
        GuardIdentityData data = GuardIdentityStore.get(server);
        if (GuardProxy.isProxy(person) || data.findByProxy(person.getUUID()) != null) {
            return "message.mcamobcompat.enlist.already";
        }
        if (data.isReadOnly()) {
            return "message.mcamobcompat.enlist.read_only";
        }
        if (adapter == null || !adapter.isActive() || adapter.bindingProblem().isPresent()) {
            return "message.mcamobcompat.enlist.unavailable";
        }
        if (!adapter.transitions().enlist()) {
            return adapter.transitions().enlistReason();
        }
        if (!adapter.bridgesActors() || !adapter.opensMcaInteraction()) {
            return "message.mcamobcompat.enlist.bridge_off";
        }
        return null;
    }

    private static Outcome enlist(EnlistContext context, NativeCharacterAdapter adapter,
                                  java.util.function.Consumer<Mob> settleCost) {
        Villager person = context.person();
        String refusal = eligibility(person, adapter);
        if (refusal != null) {
            return Outcome.refused(refusal);
        }
        ServerLevel level = context.level();
        MinecraftServer server = level.getServer();
        GuardIdentityData data = GuardIdentityStore.get(server);
        String civilianProfession = String.valueOf(
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(person.getVillagerData().getProfession()));
        CompoundTag civilianFlags = GuardProxy.civilianFlags(person);

        Mob actor = adapter.createEnlistedActor(context);
        if (actor == null) {
            return Outcome.refused(context.refusal());
        }
        long now = level.getGameTime();
        GuardIdentityData.Entry entry;
        UUID tx;
        try {
            entry = data.createEnlisted(actor.getUUID(), person.getUUID(),
                    person.hasCustomName() ? person.getCustomName().getString() : "", genderOf(person),
                    McaBinding.personality(person).orElse(""), now, adapter.id().toString(),
                    EntityType.getKey(actor.getType()).toString(), civilianProfession, civilianFlags);
            tx = data.beginTransition(entry, TransitionKind.ENLIST, person.getUUID(), context.player().getUUID(), now,
                    context.receipt());
            data.advanceTransition(entry, tx, TransitionStage.ACTOR_READY, actor.getUUID(), now, "", null);
        } catch (RuntimeException failure) {
            McaMobCompat.LOGGER.warn("Could not record the enlistment of {}: {}", person.getUUID(), failure.toString());
            undoActor(context, adapter, actor);
            return Outcome.refused("message.mcamobcompat.enlist.failed");
        }

        try {
            actor.getPersistentData().remove(EnlistContext.KEY_ENLISTING);
            commitLink(actor, person, entry.generation(), tx);
            person.getPersistentData().put(KEY_CIVILIAN, civilian(civilianProfession, civilianFlags));
            if (person.isSleeping()) {
                person.stopSleeping();
            }
            person.stopRiding();
            person.setTarget(null);
            person.getNavigation().stop();
            // The job the person held is free for somebody else; home and meeting point stay theirs.
            person.releasePoi(MemoryModuleType.JOB_SITE);
            person.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
            person.setPersistenceRequired();
            GuardProxySync.pin(actor, person);
            person.refreshDimensions();
            reprofession(person, actor);
            if (person.hasCustomName()) {
                actor.setCustomName(person.getCustomName());
            }
            data.advanceTransition(entry, tx, TransitionStage.ASSOCIATION_COMMITTED, null, now, "", null);
        } catch (RuntimeException failure) {
            McaMobCompat.LOGGER.warn("Enlistment of {} failed before its commit and was rolled back: {}",
                    person.getUUID(), failure.toString());
            unlinkPerson(person);
            GuardProxy.reveal(person, civilianFlags);
            data.advanceTransition(entry, tx, TransitionStage.ROLLED_BACK, null, now, failure.toString(), null);
            data.remove(entry);
            undoActor(context, adapter, actor);
            return Outcome.refused("message.mcamobcompat.enlist.failed");
        }

        // Committed: from here the transition only rolls forward.
        settleCost.accept(actor);
        data.advanceTransition(entry, tx, TransitionStage.CLEANED, null, now, "", CharacterState.ACTIVE);
        GuardProxySync.invalidatePairing(actor.getUUID());
        ProxyPairs.broadcast(actor, person);
        McaMobCompat.LOGGER.info("{} enlisted MCA villager {} ({}) as {} {}", context.player().getName().getString(),
                person.getName().getString(), person.getUUID(), EntityType.getKey(actor.getType()), actor.getUUID());
        return new Outcome(true, "message.mcamobcompat.enlist.done", actor);
    }

    private static void undoActor(EnlistContext context, NativeCharacterAdapter adapter, Mob actor) {
        adapter.rollbackEnlistedActor(context, actor);
        remove(actor);
    }

    private static GuardGender genderOf(Villager person) {
        return "FEMALE".equals(McaBinding.gender(person).orElse(null))
                || McaEntities.FEMALE_VILLAGER.equals(EntityType.getKey(person.getType()))
                ? GuardGender.FEMALE : GuardGender.MALE;
    }

    private static CompoundTag civilian(String profession, CompoundTag flags) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Profession", profession);
        tag.put("Flags", flags.copy());
        return tag;
    }

    /** Links and hides a person as an actor's identity, and stamps both with the commit. */
    private static void commitLink(Mob actor, Villager person, int generation, UUID tx) {
        actor.getPersistentData().putUUID(GuardProxy.KEY_PROXY, person.getUUID());
        person.getPersistentData().putUUID(GuardProxy.KEY_GUARD, actor.getUUID());
        person.addTag(GuardProxy.TAG_PROXY);
        stamp(actor, generation, tx);
        stamp(person, generation, tx);
        CharacterCommandRouter.resetOrders(person);
        GuardProxy.hide(person);
    }

    private static void stamp(Entity entity, int generation, UUID tx) {
        entity.getPersistentData().putInt(KEY_GENERATION, generation);
        if (tx != null) {
            entity.getPersistentData().putUUID(KEY_TX, tx);
        }
    }

    private static void unlinkPerson(Villager person) {
        person.getPersistentData().remove(GuardProxy.KEY_GUARD);
        person.getPersistentData().remove(KEY_TX);
        person.getPersistentData().remove(KEY_GENERATION);
        person.getPersistentData().remove(KEY_CIVILIAN);
        person.removeTag(GuardProxy.TAG_PROXY);
    }

    /** The profession the adapter gives the person while employed, if it names one. */
    private static void reprofession(Villager person, Mob actor) {
        NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
        ResourceLocation wanted = adapter == null ? null : adapter.identityProfession(actor, CharacterOrigin.ENLISTED_PERSON);
        if (wanted != null) {
            setProfession(person, wanted);
        }
    }

    private static void setProfession(Villager person, ResourceLocation id) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id).orElse(null);
        if (profession == null || person.getVillagerData().getProfession() == profession) {
            return;
        }
        person.setVillagerData(person.getVillagerData().setProfession(profession));
        if (person.level() instanceof ServerLevel level) {
            // MCA builds behaviours from the profession when the brain is made; a bare data change does
            // not rebuild it, and MCA's override of this keeps every memory.
            person.refreshBrain(level);
        }
    }

    /** Takes an actor out of the world as a role change: no death, no loot, no experience. */
    private static void remove(Mob actor) {
        LEAVING.add(actor.getUUID());
        try {
            actor.discard();
        } finally {
            LEAVING.remove(actor.getUUID());
        }
    }

    // ------------------------------------------------------------------------------ civilian return

    /** The role action: send an enlisted person home, through the native mod's own dismissal. */
    public static Outcome returnToCivilian(ServerPlayer player, Mob actor) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Outcome.refused("message.mcamobcompat.release.unavailable");
        }
        if (!ConfigAccess.bool(McaMobCompatConfig.SERVER.playerCivilianReturn, true) && !player.hasPermissions(2)) {
            return Outcome.refused("message.mcamobcompat.release.disabled");
        }
        GuardIdentityData data = GuardIdentityStore.get(server);
        GuardIdentityData.Entry entry = data.find(actor.getUUID());
        NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
        if (entry == null || adapter == null) {
            return Outcome.refused("message.mcamobcompat.character.not_bridged");
        }
        if (entry.origin() != CharacterOrigin.ENLISTED_PERSON) {
            return Outcome.refused("message.mcamobcompat.release.not_enlisted");
        }
        if (entry.openTransition() != null || data.isReadOnly()) {
            return Outcome.refused("message.mcamobcompat.release.busy");
        }
        if (!adapter.transitions().returnToCivilian()) {
            return Outcome.refused(adapter.transitions().returnReason());
        }
        if (!player.hasPermissions(2) && !adapter.mayReleaseToCivilian(actor, player)
                && !player.getUUID().equals(enlister(entry))) {
            return Outcome.refused("message.mcamobcompat.release.not_owner");
        }
        if (actor.level() != player.level() || player.distanceToSqr(actor) > 8.0D * 8.0D) {
            return Outcome.refused("message.mcamobcompat.character.too_far");
        }
        Villager person = GuardProxy.proxyOf(actor);
        if (person == null || person.level() != actor.level()) {
            return Outcome.refused("message.mcamobcompat.release.identity_unloaded");
        }
        List<ItemStack> possessions = adapter.possessions(actor);
        int leftOver = overflow(person.getInventory(), possessions);
        if (leftOver > 0) {
            return new Outcome(false, "message.mcamobcompat.release.no_room", actor);
        }

        long now = actor.level().getGameTime();
        CompoundTag receipt = new CompoundTag();
        receipt.putInt("Possessions", possessions.size());
        UUID tx = data.beginTransition(entry, TransitionKind.RETURN_TO_CIVILIAN, actor.getUUID(), player.getUUID(), now,
                receipt);
        // Release the native side first, then move the possessions exactly once: added, then cleared.
        adapter.releaseNativeState(actor, player);
        SimpleContainer inventory = person.getInventory();
        for (ItemStack stack : possessions) {
            ItemStack rest = inventory.addItem(stack.copy());
            if (!rest.isEmpty()) {
                // Cannot happen after the simulation above; never delete an item if it does.
                actor.spawnAtLocation(rest);
            }
        }
        adapter.clearPossessions(actor);
        data.advanceTransition(entry, tx, TransitionStage.ACTOR_READY, null, now, "possessions moved", null);
        float fraction = actor.getMaxHealth() <= 0.0F ? 1.0F : actor.getHealth() / actor.getMaxHealth();
        dissolve(server, data, entry, actor, person, tx, now, fraction);
        McaMobCompat.LOGGER.info("{} returned {} ({}) to civilian life from {} {}", player.getName().getString(),
                person.getName().getString(), person.getUUID(), EntityType.getKey(actor.getType()), actor.getUUID());
        return new Outcome(true, "message.mcamobcompat.release.done", null);
    }

    /** Who enlisted this person, from the record's journal, or null. */
    static UUID enlister(GuardIdentityData.Entry entry) {
        UUID found = null;
        for (GuardIdentityData.Transition transition : entry.journal()) {
            if (transition.kind() == TransitionKind.ENLIST && transition.stage().isCommitted()) {
                found = transition.player();
            }
        }
        return found;
    }

    /** How many of these stacks would not fit into a copy of this container. */
    static int overflow(SimpleContainer inventory, List<ItemStack> stacks) {
        SimpleContainer copy = new SimpleContainer(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            copy.setItem(i, inventory.getItem(i).copy());
        }
        int left = 0;
        for (ItemStack stack : stacks) {
            left += copy.addItem(stack.copy()).getCount();
        }
        return left;
    }

    /**
     * Ends the association and brings the person back: the store forgets the link first -- otherwise
     * the person would be recognised as hidden again on its next tick -- then the person is revealed
     * with its own flags and civilian profession, and the actor leaves the world as a role change.
     */
    private static void dissolve(MinecraftServer server, GuardIdentityData data, GuardIdentityData.Entry entry,
                                 Mob actor, Villager person, UUID tx, long now, float healthFraction) {
        CompoundTag civilian = person.getPersistentData().getCompound(KEY_CIVILIAN);
        CompoundTag flags = entry.civilianState().isEmpty() ? civilian.getCompound("Flags") : entry.civilianState();
        String profession = !entry.civilianProfession().isEmpty() ? entry.civilianProfession()
                : civilian.getString("Profession");
        // Flush what MCA can take now; the record stays for the history and anything left over.
        GuardIdentityStore.synchronize(actor);
        GuardIdentityStore.unlink(server, actor.getUUID());
        actor.getPersistentData().remove(GuardProxy.KEY_PROXY);
        unlinkPerson(person);
        ProxyPairs.broadcastRemoval(actor, person);
        GuardProxy.reveal(person, flags);
        person.moveTo(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot(), actor.getXRot());
        person.setHealth(Math.max(1.0F, person.getMaxHealth() * Math.min(1.0F, healthFraction)));
        ResourceLocation id = ResourceLocation.tryParse(profession);
        if (id != null) {
            setProfession(person, id);
        }
        if (!McaBinding.hasHomeVillage(person)) {
            McaBinding.seekHome(person);
        }
        data.advanceTransition(entry, tx, TransitionStage.ASSOCIATION_COMMITTED, null, now, "person revealed", null);
        GuardProxySync.forget(actor);
        if (!actor.isRemoved()) {
            // A despawn is already removing the actor; a second discard would remove it twice.
            remove(actor);
        }
        data.advanceTransition(entry, tx, TransitionStage.CLEANED, null, now, "", CharacterState.SUSPENDED);
        warnUnapplied(entry);
    }

    private static void warnUnapplied(GuardIdentityData.Entry entry) {
        for (UUID player : entry.pendingHeartPlayers()) {
            McaMobCompat.LOGGER.warn("Character {} went back to civilian life with {} heart(s) for player {} not yet "
                    + "applied to MCA (the player was offline); they stay in its record.", entry.characterUuid(),
                    entry.pendingDelta(player), player);
        }
    }

    /**
     * An actor left the world as discarded, outside any transition of this mod. An enlisted person is
     * never lost with their body: they go home, keeping their own possessions, and the record keeps the
     * history. Answers whether it handled the removal; other characters keep 0.2's rule, where the
     * identity made for the actor goes with it.
     */
    public static boolean onActorDiscarded(Mob actor) {
        if (actor == null || isLeavingForTransition(actor) || actor.getServer() == null) {
            return actor != null && isLeavingForTransition(actor);
        }
        if (onReplacementSourceDiscarded(actor)) {
            return true;
        }
        GuardIdentityData data = GuardIdentityStore.get(actor.getServer());
        GuardIdentityData.Entry entry = data.find(actor.getUUID());
        if (entry == null || entry.origin() != CharacterOrigin.ENLISTED_PERSON || data.isReadOnly()) {
            return false;
        }
        Villager person = GuardProxy.proxyOf(actor);
        if (person == null || entry.openTransition() != null) {
            // The person is not loaded: they stay hidden and linked, waiting for an operator's repair,
            // rather than be guessed at.
            data.setState(entry, CharacterState.AWAITING_ACTOR);
            McaMobCompat.LOGGER.warn("The actor of enlisted character {} was discarded while its person was not "
                    + "loaded; see /mcamobcompat characters repair.", entry.characterUuid());
            return true;
        }
        long now = actor.level().getGameTime();
        UUID tx = data.beginTransition(entry, TransitionKind.RETURN_TO_CIVILIAN, actor.getUUID(), null, now,
                new CompoundTag());
        dissolve(actor.getServer(), data, entry, actor, person, tx, now, 1.0F);
        McaMobCompat.LOGGER.info("The actor of enlisted character {} was discarded; {} went back to civilian life.",
                entry.characterUuid(), person.getName().getString());
        return true;
    }

    /**
     * An operator's repair: brings back a hidden person whose actor is gone for good, without an actor
     * to take possessions from. The flags and profession come from the record, or from the person's
     * own stamp when the record was lost; with neither, the person simply becomes visible and active.
     */
    public static boolean restoreCivilian(GuardIdentityData data, GuardIdentityData.Entry entry, Villager person) {
        MinecraftServer server = person.getServer();
        if (server == null || data.isReadOnly()) {
            return false;
        }
        CompoundTag civilian = person.getPersistentData().getCompound(KEY_CIVILIAN);
        CompoundTag flags = entry != null && !entry.civilianState().isEmpty() ? entry.civilianState()
                : civilian.getCompound("Flags");
        String profession = entry != null && !entry.civilianProfession().isEmpty() ? entry.civilianProfession()
                : civilian.getString("Profession");
        long now = person.level().getGameTime();
        if (entry != null) {
            UUID tx = entry.openTransition() == null
                    ? data.beginTransition(entry, TransitionKind.RETURN_TO_CIVILIAN, entry.uuid(), null, now, new CompoundTag())
                    : entry.openTransition().id();
            GuardIdentityStore.unlink(server, entry.uuid());
            data.advanceTransition(entry, tx, TransitionStage.CLEANED, null, now, "restored by an operator",
                    CharacterState.SUSPENDED);
        }
        unlinkPerson(person);
        GuardProxy.reveal(person, flags);
        ResourceLocation id = profession.isEmpty() ? null : ResourceLocation.tryParse(profession);
        if (id != null) {
            setProfession(person, id);
        }
        if (!McaBinding.hasHomeVillage(person)) {
            McaBinding.seekHome(person);
        }
        McaMobCompat.LOGGER.info("An operator restored {} ({}) to civilian life.", person.getName().getString(), person.getUUID());
        return true;
    }

    // ---------------------------------------------------------------------------- host replacement

    private static final class Replacement {
        private final UUID source;
        private final UUID tx;
        private final ServerLevel level;
        private final Vec3 position;
        private final UUID owner;
        private final UUID group;
        private CompoundTag snapshot;
        private boolean discarded;
        private UUID joined;
        private boolean committed;

        private Replacement(UUID source, UUID tx, ServerLevel level, Vec3 position, UUID owner, UUID group) {
            this.source = source;
            this.tx = tx;
            this.level = level;
            this.position = position;
            this.owner = owner;
            this.group = group;
        }
    }

    /**
     * Recruits is about to promote a recruit (its {@code Promoted} event, at its last priority): opens
     * the bracket the discard and the join inside the same call are read against.
     */
    public static void onPromotion(Mob source, Player player) {
        if (source == null || !(source.level() instanceof ServerLevel level) || source.getServer() == null) {
            return;
        }
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        GuardIdentityData.Entry entry = data.find(source.getUUID());
        if (entry == null || entry.proxyUuid() == null || entry.openTransition() != null || data.isReadOnly()) {
            return;
        }
        UUID tx = data.beginTransition(entry, TransitionKind.HOST_REPLACEMENT, source.getUUID(),
                player == null ? null : player.getUUID(), level.getGameTime(), new CompoundTag());
        REPLACEMENTS.put(source.getUUID(), new Replacement(source.getUUID(), tx, level, source.position(),
                RecruitsBinding.owner(source).orElse(null), RecruitsBinding.group(source).orElse(null)));
    }

    private static boolean onReplacementSourceDiscarded(Mob source) {
        Replacement replacement = REPLACEMENTS.get(source.getUUID());
        if (replacement == null || replacement.discarded) {
            return false;
        }
        replacement.discarded = true;
        // Entity.save refuses an entity that is being discarded, so the snapshot is its full save plus
        // its type id, taken directly: everything needed to put the very same actor back.
        try {
            CompoundTag snapshot = source.saveWithoutId(new CompoundTag());
            snapshot.putString("id", EntityType.getKey(source.getType()).toString());
            replacement.snapshot = snapshot;
        } catch (RuntimeException unreadable) {
            McaMobCompat.LOGGER.warn("Could not snapshot actor {} as it was replaced: {}", source.getUUID(),
                    unreadable.toString());
        }
        return true;
    }

    /**
     * An entity joined a level. Inside an open bracket whose actor was just discarded, the first Recruits
     * recruit to join at the same spot, owned by the same player, is the replacement Recruits made: the
     * record moves to it. {@code cancelled} joins are remembered only to repair Recruits' groups.
     */
    public static void onActorJoined(Entity joined, boolean cancelled) {
        if (REPLACEMENTS.isEmpty() || !(joined instanceof Mob mob) || !RecruitsBinding.isRecruit(mob)) {
            return;
        }
        for (Replacement replacement : REPLACEMENTS.values()) {
            if (!replacement.discarded || replacement.joined != null || replacement.level != mob.level()
                    || mob.getUUID().equals(replacement.source) || !mob.position().equals(replacement.position)
                    || !java.util.Objects.equals(replacement.owner, RecruitsBinding.owner(mob).orElse(null))) {
                continue;
            }
            replacement.joined = mob.getUUID();
            if (!cancelled) {
                commitReplacement(replacement, mob);
            }
            return;
        }
    }

    private static void commitReplacement(Replacement replacement, Mob actor) {
        MinecraftServer server = actor.getServer();
        GuardIdentityData data = GuardIdentityStore.get(server);
        GuardIdentityData.Entry entry = data.find(replacement.source);
        if (entry == null) {
            return;
        }
        long now = actor.level().getGameTime();
        try {
            GuardIdentityData.Entry stray = data.find(actor.getUUID());
            if (stray != null && stray != entry && stray.proxyUuid() == null && stray.journal().isEmpty()) {
                // A record something made for the newcomer before the bracket saw it: empty, and the
                // character moving in is the real one.
                data.remove(stray);
            }
            data.advanceTransition(entry, replacement.tx, TransitionStage.ACTOR_READY, actor.getUUID(), now, "", null);
            data.rekeyActor(entry, actor.getUUID());
            NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
            data.assignActor(entry, adapter == null ? entry.adapter() : adapter.id().toString(),
                    EntityType.getKey(actor.getType()).toString());
            UUID identity = entry.proxyUuid();
            actor.getPersistentData().putUUID(GuardProxy.KEY_PROXY, identity);
            stamp(actor, entry.generation(), replacement.tx);
            Entity person = replacement.level.getEntity(identity);
            if (person instanceof Villager villager) {
                villager.getPersistentData().putUUID(GuardProxy.KEY_GUARD, actor.getUUID());
                stamp(villager, entry.generation(), replacement.tx);
                GuardProxySync.pin(actor, villager);
                ProxyPairs.broadcast(actor, villager);
            }
            data.advanceTransition(entry, replacement.tx, TransitionStage.ASSOCIATION_COMMITTED, null, now, "", null);
            data.advanceTransition(entry, replacement.tx, TransitionStage.CLEANED, null, now, "", CharacterState.ACTIVE);
            replacement.committed = true;
            McaMobCompat.debug("Character {} moved from actor {} to {} (generation {})", entry.characterUuid(),
                    replacement.source, actor.getUUID(), entry.generation());
        } catch (RuntimeException failure) {
            McaMobCompat.LOGGER.warn("Could not move character {} to its replacement actor {}: {}",
                    entry.characterUuid(), actor.getUUID(), failure.toString());
        }
    }

    /**
     * The end of the tick: every bracket settles. A replacement whose new actor never joined is undone
     * by restoring the old actor, with its own UUID and state, from the snapshot taken as it left, and
     * Recruits' group is pointed back at it.
     */
    public static void onServerTickEnd(MinecraftServer server) {
        if (REPLACEMENTS.isEmpty()) {
            return;
        }
        GuardIdentityData data = GuardIdentityStore.get(server);
        for (Replacement replacement : new ArrayList<>(REPLACEMENTS.values())) {
            if (replacement.committed) {
                continue;
            }
            GuardIdentityData.Entry entry = data.find(replacement.source);
            if (entry == null) {
                continue;
            }
            long now = replacement.level.getGameTime();
            if (!replacement.discarded) {
                data.advanceTransition(entry, replacement.tx, TransitionStage.ROLLED_BACK, null, now,
                        "promotion did not happen", null);
                continue;
            }
            String note = "replacement never joined";
            if (replacement.snapshot != null) {
                Optional<Entity> restored = EntityType.create(replacement.snapshot, replacement.level);
                if (restored.isPresent() && replacement.level.addFreshEntity(restored.get())) {
                    note = "replacement never joined; previous actor restored";
                    if (replacement.group != null) {
                        RecruitsBinding.moveGroupMember(replacement.group, replacement.joined, replacement.source,
                                replacement.level);
                    }
                } else {
                    note = "replacement never joined; previous actor could not be restored";
                    data.setState(entry, CharacterState.AWAITING_ACTOR);
                }
            }
            data.advanceTransition(entry, replacement.tx, TransitionStage.ROLLED_BACK, null, now, note,
                    note.endsWith("restored") ? CharacterState.ACTIVE : CharacterState.AWAITING_ACTOR);
            McaMobCompat.LOGGER.warn("Host replacement of character {}: {}", entry.characterUuid(), note);
        }
        REPLACEMENTS.clear();
    }

    // ------------------------------------------------------------------------------------- recovery

    /**
     * Server start: every transition the store still has open settles -- forward if its association
     * was committed, back otherwise. Nothing in the world is touched here; entities reconcile as they
     * load, through {@link #repairLink}.
     */
    public static void recover(MinecraftServer server) {
        GuardIdentityData data = GuardIdentityStore.get(server);
        if (data.isReadOnly()) {
            return;
        }
        for (GuardIdentityData.Entry entry : data.withOpenTransitions()) {
            GuardIdentityData.Transition open = entry.openTransition();
            TransitionStage settled = TransitionRecovery.settle(open.stage());
            data.advanceTransition(entry, open.id(), settled, null, open.updated(),
                    "settled at server start from " + open.stage().id(), null);
            McaMobCompat.LOGGER.warn("Character {}: {} transition {} was still {} at start; settled as {}.",
                    entry.characterUuid(), open.kind().id(), open.id(), open.stage().id(), settled.id());
        }
    }

    /**
     * A pair's heartbeat found an actor whose identity names it while the store does not record the
     * pair: an unclean stop saved the entities after a commit the store never saved. The pair is
     * recorded again -- rolled forward, never re-rolled -- or, if another record holds the person at a
     * newer generation, this actor is stale and left alone.
     */
    public static void repairLink(Mob actor, Villager identity) {
        MinecraftServer server = actor.getServer();
        if (server == null) {
            return;
        }
        GuardIdentityData data = GuardIdentityStore.get(server);
        if (data.isReadOnly() || data.isQuarantined(actor.getUUID())) {
            return;
        }
        GuardIdentityData.Entry own = data.find(actor.getUUID());
        boolean linked = own != null && identity.getUUID().equals(own.proxyUuid());
        GuardIdentityData.Entry other = data.findByProxy(identity.getUUID());
        boolean otherRecord = other != null && !other.uuid().equals(actor.getUUID());
        int stamp = actor.getPersistentData().getInt(KEY_GENERATION);
        boolean namesActor = actor.getUUID().equals(GuardProxy.linkedGuardUuid(identity));
        TransitionRecovery.Action action = TransitionRecovery.forLinkedActor(linked, namesActor, otherRecord,
                TransitionRecovery.compare(otherRecord ? other.generation() : 0, stamp));
        switch (action) {
            case NONE, IGNORE_STALE -> {
            }
            case QUARANTINE -> McaMobCompat.LOGGER.warn("Actor {} and the record of actor {} both claim identity {}; "
                    + "left for an operator.", actor.getUUID(), other == null ? "?" : other.uuid(), identity.getUUID());
            case ROLL_FORWARD -> {
                GuardIdentityData.Entry entry = own;
                if (otherRecord && own == null) {
                    data.rekeyActor(other, actor.getUUID());
                    entry = other;
                } else if (entry == null) {
                    entry = GuardIdentityStore.ensureRecord(actor);
                }
                if (entry == null || !GuardIdentityStore.link(server, actor.getUUID(), identity.getUUID())) {
                    return;
                }
                CompoundTag civilian = identity.getPersistentData().getCompound(KEY_CIVILIAN);
                if (!civilian.isEmpty()) {
                    data.restoreEnlistment(entry, civilian.getString("Profession"), civilian.getCompound("Flags"), stamp);
                }
                McaMobCompat.LOGGER.warn("Recorded the pair of actor {} and identity {} again: the entities were saved "
                        + "after a commit the store was not.", actor.getUUID(), identity.getUUID());
            }
        }
    }

    /** A message for the player who asked for a transition. */
    public static void tell(ServerPlayer player, Outcome outcome, Entity subject) {
        if (player != null && outcome != null && !outcome.messageKey().isEmpty()) {
            player.displayClientMessage(Component.translatable(outcome.messageKey(),
                    subject == null ? Component.literal("?") : subject.getName()), true);
        }
    }

    /** Drops every bracket; called when the server stops. */
    public static void clear() {
        REPLACEMENTS.clear();
        LEAVING.clear();
    }
}
