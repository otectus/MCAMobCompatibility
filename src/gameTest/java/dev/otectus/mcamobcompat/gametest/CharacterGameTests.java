package dev.otectus.mcamobcompat.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.CharacterCommandRouter;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterState;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.McaEditorBridge;
import dev.otectus.mcamobcompat.character.TransitionKind;
import dev.otectus.mcamobcompat.character.TransitionStage;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsAdapter;
import dev.otectus.mcamobcompat.integration.workers.WorkersAdapter;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The 0.3.0 character transitions against the real MCA and Guard Villagers: an MCA villager who
 * becomes a real guard and comes home again, every refusal that must change nothing, the editor's
 * saves through MCA's own request, and recovery of a pair whose record was lost.
 */
@GameTestHolder(McaMobCompatTests.CORE)
@PrefixGameTestTemplate(false)
public final class CharacterGameTests {

    private CharacterGameTests() {
    }

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static ServerPlayer player(GameTestHelper h, String name) {
        UUID id = UUID.nameUUIDFromBytes(("mcamobcompat-test-" + name).getBytes());
        ServerPlayer player = FakePlayerFactory.get(h.getLevel(), new GameProfile(id, name));
        player.moveTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(5.5D, 1.0D, 7.5D)));
        player.getAbilities().instabuild = false;
        return player;
    }

    /** Enlists a fresh MCA villager as a Guard Villagers guard through the real recruit gesture. */
    private static Mob enlist(GameTestHelper h, Villager villager, ServerPlayer player, String weaponName) {
        ItemStack weapon = new ItemStack(Items.IRON_SWORD);
        weapon.set(DataComponents.CUSTOM_NAME, Component.literal(weaponName));
        player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        player.setShiftKeyDown(true);
        try {
            var click = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
            NeoForge.EVENT_BUS.post(click);
            h.assertTrue(click.isCanceled(), "Guard Villagers' own conversion was not pre-empted");
        } finally {
            player.setShiftKeyDown(false);
        }
        Mob actor = GuardProxy.guardOf(villager);
        h.assertTrue(actor != null, "The villager was not enlisted as a guard");
        return actor;
    }

    /** The player's reputation with a guard, read from the guard's own gossip store. */
    private static int reputation(Mob guard, ServerPlayer player) {
        try {
            Object gossips = guard.getClass().getMethod("getGossips").invoke(guard);
            return ((net.minecraft.world.entity.ai.gossip.GossipContainer) gossips).getReputation(player.getUUID(), type -> true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void nativeRecruitmentKeepsThePerson(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        villager.setCustomName(Component.literal("Edda"));
        UUID person = villager.getUUID();
        String name = villager.getName().getString();
        ServerPlayer player = player(h, "Enlister");
        Mob actor = enlist(h, villager, player, "Heirloom");
        try {
            h.assertTrue(!villager.isRemoved() && person.equals(villager.getUUID()), "The MCA villager was replaced");
            h.assertTrue(villager.isInvisible() && villager.isNoAi() && GuardProxy.isProxy(villager),
                    "The person is not hidden behind the guard");
            h.assertTrue(McaMobCompat.location("guardvillagers", "guard").equals(EntityType.getKey(actor.getType())),
                    "The actor is not a Guard Villagers guard");
            h.assertTrue(actor.getMainHandItem().getHoverName().getString().equals("Heirloom")
                    && player.getMainHandItem().isEmpty(), "The weapon did not move to the guard exactly once");
            h.assertTrue(name.equals(actor.getName().getString()), "The guard does not carry the person's name");
            h.assertTrue(reputation(actor, player) >= 15,
                    "The enlisting player was not given the reputation Guard Villagers' own conversion grants");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(actor.getUUID());
            h.assertTrue(entry != null && entry.origin() == CharacterOrigin.ENLISTED_PERSON
                    && entry.state() == CharacterState.ACTIVE && person.equals(entry.proxyUuid())
                    && person.equals(entry.characterUuid()), "The record does not name the same person");
            h.assertTrue("minecraft:none".equals(entry.civilianProfession()), "The civilian profession was not kept");
            h.assertTrue(GuardProxy.GUARD_PROFESSION.equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                    villager.getVillagerData().getProfession())), "The person is not an MCA guard while serving");
            GuardIdentityData.Transition enlistment = entry.journal().get(entry.journal().size() - 1);
            h.assertTrue(enlistment.kind() == TransitionKind.ENLIST && enlistment.stage() == TransitionStage.CLEANED,
                    "The enlistment is not journaled as settled");
        } finally {
            actor.discard();
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void civilianReturnKeepsThePersonAndTheirPossessions(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        ServerPlayer player = player(h, "Returner");
        Mob actor = enlist(h, villager, player, "Keepsake");
        actor.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        CharacterTransitions.Outcome outcome = CharacterTransitions.returnToCivilian(player, actor);
        try {
            h.assertTrue(outcome.done(), "The return was refused: " + outcome.messageKey());
            h.assertTrue(actor.isRemoved() && villager.isAlive(), "The guard did not leave, or the person died");
            h.assertTrue(!villager.isInvisible() && !villager.isNoAi() && !GuardProxy.isProxy(villager),
                    "The person was not brought back");
            h.assertTrue("minecraft:none".equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                    villager.getVillagerData().getProfession()).toString()), "The civilian profession was not restored");
            boolean sword = false;
            boolean shield = false;
            for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
                ItemStack stack = villager.getInventory().getItem(i);
                sword |= stack.is(Items.IRON_SWORD) && stack.getHoverName().getString().equals("Keepsake");
                shield |= stack.is(Items.SHIELD);
            }
            h.assertTrue(sword && shield, "The guard's possessions did not reach the person exactly once");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(actor.getUUID());
            h.assertTrue(entry != null && entry.state() == CharacterState.SUSPENDED && entry.proxyUuid() == null,
                    "The record still links the person");
        } finally {
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aFullInventoryRefusesTheReturnAndChangesNothing(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        ServerPlayer player = player(h, "Hoarder");
        Mob actor = enlist(h, villager, player, "Spare");
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            villager.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        try {
            CharacterTransitions.Outcome outcome = CharacterTransitions.returnToCivilian(player, actor);
            h.assertTrue(!outcome.done() && "message.mcamobcompat.release.no_room".equals(outcome.messageKey()),
                    "A return that cannot hold the guard's things went ahead");
            h.assertTrue(!actor.isRemoved() && actor.getMainHandItem().is(Items.IRON_SWORD) && GuardProxy.isProxy(villager),
                    "A refused return changed something");
        } finally {
            actor.discard();
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aRefusedGuardRollsTheEnlistmentBack(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        ServerPlayer player = player(h, "Refused");
        Consumer<EntityJoinLevelEvent> refuse = event -> {
            if (event.getEntity().getType() != villager.getType()
                    && McaMobCompat.location("guardvillagers", "guard").equals(EntityType.getKey(event.getEntity().getType()))
                    && event.getEntity().position().distanceTo(villager.position()) < 0.01D) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityJoinLevelEvent.class, refuse);
        ItemStack weapon = new ItemStack(Items.IRON_SWORD);
        player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        try {
            CharacterTransitions.Outcome outcome = CharacterTransitions.enlistGuard(player, villager);
            h.assertTrue(!outcome.done(), "A guard the world refused was committed");
            h.assertTrue(!villager.isInvisible() && !villager.isNoAi() && !GuardProxy.isProxy(villager),
                    "The person was left hidden by a rolled-back enlistment");
            h.assertTrue(player.getMainHandItem().is(Items.IRON_SWORD), "A rolled-back enlistment took the weapon");
            h.assertTrue(GuardIdentityStore.get(h.getLevel().getServer()).findByProxy(villager.getUUID()) == null,
                    "A rolled-back enlistment left a record");
        } finally {
            NeoForge.EVENT_BUS.unregister(refuse);
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aDiscardedGuardSendsThePersonHome(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        Mob actor = enlist(h, villager, player(h, "Despawn"), "Plain");
        actor.discard();
        try {
            h.assertTrue(villager.isAlive() && !villager.isInvisible() && !GuardProxy.isProxy(villager),
                    "An enlisted person was lost with their discarded body");
        } finally {
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aLostRecordIsRolledForwardFromTheStamps(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        Mob actor = enlist(h, villager, player(h, "Recovery"), "Saved");
        GuardIdentityData data = GuardIdentityStore.get(h.getLevel().getServer());
        data.remove(data.find(actor.getUUID()));
        try {
            h.assertTrue(data.find(actor.getUUID()) == null, "The record was not removed for the test");
            CharacterTransitions.repairLink(actor, villager);
            GuardIdentityData.Entry entry = data.find(actor.getUUID());
            h.assertTrue(entry != null && villager.getUUID().equals(entry.proxyUuid()), "The pair was not recorded again");
            h.assertTrue(entry.origin() == CharacterOrigin.ENLISTED_PERSON
                    && "minecraft:none".equals(entry.civilianProfession()), "The person's civilian life was not recovered");
            h.assertTrue(GuardProxy.isProxy(villager) && villager.isInvisible(), "Recovery exposed the person");
        } finally {
            actor.discard();
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void anInvisibleVillagerIsNeverTakenForAnIdentity(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        villager.setInvisible(true);
        h.runAtTickTime(40, () -> {
            h.assertTrue(!GuardProxy.isProxy(villager) && !villager.isNoAi(),
                    "An ordinary invisible MCA villager was treated as a hidden identity");
            villager.discard();
            h.succeed();
        });
    }

    /** MCA's own editor save payload, built and handled the way MCA's network hands it over. */
    private static void save(Villager identity, ServerPlayer player, CompoundTag data) {
        try {
            Class<?> type = Class.forName(McaBinding.packageRoot().orElseThrow() + ".network.c2s.VillagerEditorSyncRequest");
            Object request = type.getConstructor(String.class, UUID.class, CompoundTag.class)
                    .newInstance("sync", identity.getUUID(), data);
            type.getMethod("handleServer", ServerPlayer.class).invoke(request, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static float gene(Villager identity) {
        CompoundTag saved = new CompoundTag();
        identity.addAdditionalSaveData(saved);
        return saved.contains("gene_size") ? saved.getFloat("gene_size") : saved.getFloat("GeneSize");
    }

    private static CompoundTag edit(Villager identity, float gene) {
        CompoundTag data = new CompoundTag();
        identity.addAdditionalSaveData(data);
        data.putFloat(data.contains("gene_size") ? "gene_size" : "GeneSize", gene);
        data.putBoolean("NoAI", false);
        ListTag inventory = new ListTag();
        CompoundTag diamond = (CompoundTag) new ItemStack(Items.DIAMOND, 64).save(identity.registryAccess());
        diamond.putByte("Slot", (byte) 0);
        inventory.add(diamond);
        data.put("Inventory", inventory);
        return data;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void editorSavesAreSessionBoundAndRebuilt(GameTestHelper h) {
        h.assertTrue(Hooks.armed(Hooks.MCA_EDITOR_SAVE), "The editor-save hook did not land on this MCA");
        Mob guard = ProxyGameTests.guard(h);
        Villager identity = ProxyGameTests.proxy(h, guard);
        ServerPlayer first = player(h, "EditorOne");
        ServerPlayer second = player(h, "EditorTwo");
        ServerPlayer stranger = player(h, "NoSession");
        ItemStack editor = new ItemStack(BuiltInRegistries.ITEM.get(McaEditorBridge.EDITOR_ITEM));
        h.assertTrue(McaEditorBridge.isEditor(editor), "MCA's villager editor item is not registered");
        try {
            for (ServerPlayer player : new ServerPlayer[]{first, second}) {
                player.setItemInHand(InteractionHand.MAIN_HAND, editor.copy());
                McaEditorBridge.open(player, guard, identity, InteractionHand.MAIN_HAND);
            }
            save(identity, stranger, edit(identity, 0.11F));
            h.assertTrue(gene(identity) != 0.11F, "A save without an editor session was applied");

            save(identity, first, edit(identity, 0.83F));
            h.assertTrue(Math.abs(gene(identity) - 0.83F) < 1.0E-4F, "A permitted cosmetic edit was not applied");
            h.assertTrue(identity.isNoAi() && identity.isInvisible(), "The editor exposed the hidden person");
            h.assertTrue(identity.getInventory().countItem(Items.DIAMOND) == 0, "The editor wrote the person's inventory");

            save(identity, second, edit(identity, 0.27F));
            h.assertTrue(Math.abs(gene(identity) - 0.83F) < 1.0E-4F,
                    "A second editor silently overwrote the first player's save");
        } finally {
            guard.discard();
        }
        h.succeed();
    }

    /**
     * NeoForge 1.21.1 has no build of Villager Recruits or Villager Workers: both adapters say they are
     * unavailable here, and stay inactive; Guard Villagers is available.
     */
    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 20)
    public static void theNativeAdaptersReportThisPlatformsAvailability(GameTestHelper h) {
        h.assertTrue(GuardVillagersAdapter.INSTANCE.platformProblem().isEmpty(), "Guard Villagers is reported unavailable");
        h.assertTrue(RecruitsAdapter.INSTANCE.platformProblem().orElse("").startsWith("unavailable on NeoForge")
                && !RecruitsAdapter.INSTANCE.isActive(), "Villager Recruits is not reported unavailable on NeoForge");
        h.assertTrue(WorkersAdapter.INSTANCE.platformProblem().orElse("").startsWith("unavailable on NeoForge")
                && !WorkersAdapter.INSTANCE.isActive(), "Villager Workers is not reported unavailable on NeoForge");
        h.succeed();
    }

    /**
     * An MCA order is carried out once per change. One that merely persisted on the person is not applied
     * again after a restart, over an order the guard was given since through Guard Villagers itself.
     */
    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 220)
    public static void aPersistedMcaOrderIsNotAppliedAgainAfterARestart(GameTestHelper h) {
        Mob guard = ProxyGameTests.guard(h);
        Villager identity = ProxyGameTests.proxy(h, guard);
        // The pair's first heartbeat takes MCA's state as it stands; the order below is a change.
        h.runAtTickTime(45, () -> h.assertTrue(McaBinding.setMoveState(identity, "STAY"), "MCA's Stay could not be set"));
        h.runAtTickTime(100, () -> {
            h.assertTrue(GuardReflection.patrolling(guard).orElse(false), "MCA's Stay did not make the guard patrol");
            // What a restart forgets for this guard (only this one: other tests run beside it), then the
            // guard's own order since: no more patrolling.
            CharacterCommandRouter.forget(guard);
            GuardReflection.setPatrolling(guard, false);
        });
        h.runAtTickTime(190, () -> {
            h.assertTrue(!GuardReflection.patrolling(guard).orElse(true),
                    "An MCA order that only persisted was applied again over the guard's own order");
            h.assertTrue("STAY".equals(McaBinding.moveState(identity).orElse("")), "MCA's own state was changed");
            guard.discard();
            identity.discard();
            h.succeed();
        });
    }
}
