package dev.otectus.mcamobcompat.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.CharacterCommand;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.character.TransitionKind;
import dev.otectus.mcamobcompat.character.TransitionStage;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Villager Recruits 1.15.2 with real recruits: adoption in place, every Recruits conversion that would
 * discard an MCA villager, the noble hire of an MCA villager as the same person, a promotion that keeps
 * the character, orders that keep Recruits' ownership, and the way home. A vanilla villager's
 * conversion is the control: it must still happen. Runs with {@code -PwithRecruits}.
 */
@GameTestHolder(McaMobCompatTests.RECRUITS)
@PrefixGameTestTemplate(false)
public final class RecruitsGameTests {

    private static final String ROOT = "com.talhanation.recruits.";

    private RecruitsGameTests() {
    }

    static void floor(GameTestHelper h) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    static EntityType<?> type(String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(McaMobCompat.location(id.substring(0, id.indexOf(':')),
                id.substring(id.indexOf(':') + 1)));
        if (!id.equals(EntityType.getKey(type).toString())) {
            throw new IllegalStateException(id + " is not registered");
        }
        return type;
    }

    static ServerPlayer player(GameTestHelper h, String name) {
        ServerPlayer player = FakePlayerFactory.get(h.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(("mcamobcompat-recruits-" + name).getBytes()), name));
        player.moveTo(h.absoluteVec(new Vec3(5.5D, 1.0D, 7.5D)));
        player.getAbilities().instabuild = false;
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(Items.EMERALD, 64));
        player.getInventory().add(new ItemStack(Items.EMERALD, 64));
        return player;
    }

    static int emeralds(ServerPlayer player) {
        return player.getInventory().countItem(Items.EMERALD);
    }

    /** Recruits' own noble hire of a villager, invoked as its hire screen's message does. */
    static void nobleHire(GameTestHelper h, Villager villager, String role, ServerPlayer player) throws Exception {
        List<Object> groups = RecruitsBinding.playerGroups(player);
        h.assertTrue(!groups.isEmpty(), "Recruits gave the player no group");
        Class<?> events = Class.forName(ROOT + "VillagerEvents");
        Method hire = events.getMethod("createHiredRecruitFromVillager", ServerLevel.class, Villager.class,
                EntityType.class, Player.class, Class.forName(ROOT + "world.RecruitsGroup"));
        hire.invoke(null, h.getLevel(), villager, type(role), player, groups.get(0));
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 200)
    public static void aRecruitIsAdoptedInPlace(GameTestHelper h) {
        floor(h);
        Mob bowman = (Mob) type("recruits:bowman").create(h.getLevel());
        bowman.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        RecruitsBinding.initSpawn(bowman);
        bowman.setNoAi(true);
        bowman.tickCount = GuardProxy.CREATE_GRACE_TICKS + 20;
        h.getLevel().addFreshEntity(bowman);
        UUID id = bowman.getUUID();
        OptionalInt state = RecruitsBinding.followState(bowman);
        h.assertTrue(NativeCharacterAdapters.claimant(bowman) == RecruitsAdapter.INSTANCE, "The bowman has no Recruits adapter");
        h.succeedWhen(() -> {
            Villager identity = GuardProxy.proxyOf(bowman);
            h.assertTrue(identity != null, "The bowman has no MCA person yet");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(id);
            h.assertTrue(entry != null && "mcamobcompat:recruits".equals(entry.adapter())
                    && entry.origin() == CharacterOrigin.ADOPTED_ACTOR, "The bowman's record is not an adoption");
            h.assertTrue(id.equals(bowman.getUUID()) && !bowman.isRemoved(), "Adoption replaced the bowman");
            h.assertTrue(state.equals(RecruitsBinding.followState(bowman)), "Adoption changed the bowman's orders");
            h.assertTrue(identity.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "A recruit's person became law enforcement by default");
            bowman.discard();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void aRecruitProfessionNeverConvertsAnMcaVillager(GameTestHelper h) {
        floor(h);
        h.assertTrue(Hooks.armed(Hooks.RECRUITS_CREATE_RECRUIT), "The profession-conversion hook did not land");
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        UUID id = villager.getUUID();
        VillagerProfession recruit = BuiltInRegistries.VILLAGER_PROFESSION.get(McaMobCompat.location("recruits", "recruit"));
        villager.setVillagerData(villager.getVillagerData().setProfession(recruit));
        h.runAtTickTime(20, () -> {
            h.assertTrue(!villager.isRemoved() && id.equals(villager.getUUID()), "Recruits discarded an MCA villager");
            h.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "The recruit profession was left to ask again");
            h.assertTrue(h.getLevel().getEntitiesOfClass(Mob.class, new AABB(villager.blockPosition()).inflate(2.0D),
                    RecruitsBinding::isRecruit).isEmpty(), "A recruit was spawned for the MCA villager");
            villager.discard();
            h.succeed();
        });
    }

    /**
     * The control: Recruits' own conversion still runs for a vanilla villager. Called directly, on a
     * villager never added to the level, because MCA replaces a vanilla villager as it joins one.
     */
    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void aVanillaVillagerStillConverts(GameTestHelper h) throws Exception {
        floor(h);
        Villager villager = EntityType.VILLAGER.create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(5, 1, 5));
        villager.moveTo(Vec3.atBottomCenterOf(at));
        Method convert = Class.forName(ROOT + "VillagerEvents").getDeclaredMethod("createRecruit", Villager.class,
                EntityType.class);
        convert.setAccessible(true);
        convert.invoke(null, villager, type("recruits:recruit"));
        List<Mob> recruits = h.getLevel().getEntitiesOfClass(Mob.class, new AABB(at).inflate(2.0D),
                RecruitsBinding::isRecruit);
        h.assertTrue(villager.isRemoved() && !recruits.isEmpty(), "A vanilla villager's conversion was blocked");
        recruits.forEach(Mob::discard);
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void aJoblessMcaVillagerNeverBecomesANoble(GameTestHelper h) throws Exception {
        floor(h);
        h.assertTrue(Hooks.armed(Hooks.RECRUITS_CREATE_NOBLE), "The noble hook did not land");
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        Class.forName(ROOT + "VillagerEvents").getMethod("createNobleVillager", Villager.class).invoke(null, villager);
        h.assertTrue(!villager.isRemoved(), "Recruits turned an MCA villager into a noble");
        villager.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 120)
    public static void aNobleHireEnlistsTheSamePersonAndPromotionKeepsThem(GameTestHelper h) throws Exception {
        floor(h);
        h.assertTrue(Hooks.armed(Hooks.RECRUITS_HIRE_FROM_VILLAGER), "The hire hook did not land");
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        villager.setCustomName(Component.literal("Bran"));
        UUID person = villager.getUUID();
        ServerPlayer player = player(h, "Hirer");
        int before = emeralds(player);
        nobleHire(h, villager, "recruits:recruit", player);

        h.assertTrue(!villager.isRemoved() && person.equals(villager.getUUID()), "The hire discarded the MCA villager");
        Mob recruit = GuardProxy.guardOf(villager);
        h.assertTrue(recruit != null && "recruits:recruit".equals(EntityType.getKey(recruit.getType()).toString()),
                "No recruit stands behind the person");
        h.assertTrue(villager.isInvisible() && GuardProxy.isProxy(villager), "The person is not hidden behind the recruit");
        h.assertTrue(player.getUUID().equals(RecruitsBinding.owner(recruit).orElse(null)), "Recruits' own hire did not set the owner");
        h.assertTrue(emeralds(player) < before, "Recruits' own payment was not taken");
        GuardIdentityData data = GuardIdentityStore.get(h.getLevel().getServer());
        GuardIdentityData.Entry entry = data.find(recruit.getUUID());
        h.assertTrue(entry != null && entry.origin() == CharacterOrigin.ENLISTED_PERSON && person.equals(entry.characterUuid()),
                "The record does not name the same person");

        // Commands keep Recruits' ownership: a stranger is refused, the owner is obeyed.
        ServerPlayer stranger = player(h, "Stranger");
        h.assertTrue(RecruitsAdapter.INSTANCE.applyCommand(new CharacterCommand.Context(CharacterCommand.Source.COMMAND,
                stranger.getUUID(), 1L), recruit, CharacterCommand.FOLLOW).outcome() == CharacterCommand.Outcome.REFUSED,
                "A stranger commanded another player's recruit");
        h.assertTrue(RecruitsAdapter.INSTANCE.applyCommand(new CharacterCommand.Context(CharacterCommand.Source.COMMAND,
                player.getUUID(), 2L), recruit, CharacterCommand.STAY).applied()
                && RecruitsBinding.followState(recruit).orElse(-1) == 2, "The owner's Stay did not hold the recruit");

        // A promotion replaces the entity; the character moves with it.
        UUID old = recruit.getUUID();
        int generation = entry.generation();
        Class<?> recruitClass = Class.forName(ROOT + "entities.AbstractRecruitEntity");
        Class.forName(ROOT + "RecruitEvents").getMethod("promoteRecruit", recruitClass, int.class, String.class,
                ServerPlayer.class).invoke(null, recruit, 3, "Captain Bran", player);
        h.assertTrue(recruit.isRemoved(), "Recruits did not replace the promoted recruit");
        Mob captain = GuardProxy.guardOf(villager);
        h.assertTrue(captain != null && !captain.getUUID().equals(old)
                && "recruits:captain".equals(EntityType.getKey(captain.getType()).toString()), "The person was not moved to the captain");
        GuardIdentityData.Entry moved = data.find(captain.getUUID());
        h.assertTrue(moved != null && data.find(old) == null && person.equals(moved.characterUuid())
                && moved.generation() == generation + 1, "The record did not move to the captain, or the generation did not move on");
        GuardIdentityData.Transition replacement = moved.journal().get(moved.journal().size() - 1);
        h.assertTrue(replacement.kind() == TransitionKind.HOST_REPLACEMENT && replacement.stage() == TransitionStage.CLEANED,
                "The promotion is not journaled as a settled replacement");
        h.assertTrue(villager.isAlive() && villager.isInvisible(), "The promotion exposed or killed the person");

        // And home again: the same person, visible, with the captain's possessions.
        CharacterTransitions.Outcome home = CharacterTransitions.returnToCivilian(player, captain);
        h.assertTrue(home.done(), "The way home was refused: " + home.messageKey());
        h.assertTrue(captain.isRemoved() && villager.isAlive() && !villager.isInvisible() && !GuardProxy.isProxy(villager),
                "The person did not come home");
        villager.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void aBlockedHireTakesNothing(GameTestHelper h) throws Exception {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        ServerPlayer player = player(h, "Blocked");
        int before = emeralds(player);
        dev.otectus.mcamobcompat.util.ConfigAccess.override(
                dev.otectus.mcamobcompat.McaMobCompatConfig.SERVER.recruitsVillagerConversion,
                dev.otectus.mcamobcompat.character.RecruitsConversionPolicy.BLOCK, () -> {
                    try {
                        nobleHire(h, villager, "recruits:recruit", player);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        h.assertTrue(!villager.isRemoved() && !GuardProxy.isProxy(villager) && !villager.isInvisible(),
                "A blocked hire touched the villager");
        h.assertTrue(emeralds(player) == before, "A blocked hire took the payment");
        villager.discard();
        h.succeed();
    }
}
