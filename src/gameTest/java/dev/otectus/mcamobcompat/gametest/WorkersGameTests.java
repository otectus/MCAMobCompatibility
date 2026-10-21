package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.CharacterCommandRouter;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import dev.otectus.mcamobcompat.integration.workers.WorkersAdapter;
import dev.otectus.mcamobcompat.integration.workers.WorkersBinding;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Villager Workers 2.0.3: every worker is claimed once, by the Workers adapter rather than the
 * Recruits one it inherits from, and none of its work-area markers or bobbers is; MCA's Move sends a
 * worker back to work; and a noble's hire of an MCA villager as a worker keeps the person. Runs with
 * {@code -PwithWorkers}, which brings Villager Recruits along, on Forge 1.20.1.
 *
 * <p>Kept in step on NeoForge 1.21.1, where Villager Workers has no build: the namespace is never
 * enabled here, and the adapter reports itself unavailable (see {@code CharacterGameTests}).
 */
@GameTestHolder(McaMobCompatTests.WORKERS)
@PrefixGameTestTemplate(false)
public final class WorkersGameTests {

    private static final List<String> WORKERS = List.of("farmer", "lumberjack", "miner", "builder", "merchant",
            "fisherman", "animal_farmer", "courier", "cook");

    private static final List<String> NOT_CHARACTERS = List.of("croparea", "lumberarea", "buildarea", "miningarea",
            "storagearea", "marketarea", "kitchenarea", "fishingarea", "animalpenarea", "homearea", "fishing_bobber");

    /** Workers' working follow state, {@code AbstractWorkerEntity.isWorking()}. */
    private static final int WORKING = 6;

    private WorkersGameTests() {
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void everyWorkerIsClaimedOnceAndNothingElseIs(GameTestHelper h) {
        for (String id : WORKERS) {
            Entity worker = RecruitsGameTests.type("workers:" + id).create(h.getLevel());
            h.assertTrue(worker instanceof Mob mob && NativeCharacterAdapters.claimant(mob) == WorkersAdapter.INSTANCE,
                    "workers:" + id + " is not claimed by the Workers adapter");
            h.assertTrue(RecruitsBinding.isRecruit(worker), "workers:" + id + " is no longer a recruit by inheritance");
        }
        for (String id : NOT_CHARACTERS) {
            Entity marker;
            try {
                marker = RecruitsGameTests.type("workers:" + id).create(h.getLevel());
            } catch (RuntimeException needsAnOwner) {
                // A bobber cannot exist without the worker holding it; it is not a character either way.
                continue;
            }
            h.assertTrue(marker == null || NativeCharacterAdapters.claimant(marker) == null,
                    "workers:" + id + " was claimed as a character");
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 260)
    public static void mcasMoveSendsAWorkerBackToWork(GameTestHelper h) {
        RecruitsGameTests.floor(h);
        ServerPlayer owner = RecruitsGameTests.player(h, "Employer");
        Mob farmer = (Mob) RecruitsGameTests.type("workers:farmer").create(h.getLevel());
        farmer.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        RecruitsBinding.initSpawn(farmer);
        List<Object> groups = RecruitsBinding.playerGroups(owner);
        h.assertTrue(!groups.isEmpty() && RecruitsBinding.handleRecruiting(owner, groups.get(0), farmer),
                "Recruits refused the hire of a farmer");
        farmer.setNoAi(true);
        farmer.tickCount = GuardProxy.CREATE_GRACE_TICKS + 20;
        h.getLevel().addFreshEntity(farmer);
        UUID id = farmer.getUUID();

        // MCA's buttons arrive as MCA's own movement state on the person; the owner opened the screen.
        Villager[] identity = new Villager[1];
        h.runAtTickTime(60, () -> {
            identity[0] = GuardProxy.proxyOf(farmer);
            h.assertTrue(identity[0] != null, "The farmer has no MCA person");
            CharacterCommandRouter.noteInteraction(farmer, owner.getUUID());
            h.assertTrue(McaBinding.setMoveState(identity[0], "STAY"), "MCA's Stay could not be set");
        });
        h.runAtTickTime(110, () -> {
            h.assertTrue(RecruitsBinding.followState(farmer).orElse(-1) == 2, "MCA's Stay did not hold the farmer");
            h.assertTrue(McaBinding.setMoveState(identity[0], "MOVE"), "MCA's Move could not be set");
        });
        h.runAtTickTime(160, () -> {
            h.assertTrue(RecruitsBinding.followState(farmer).orElse(-1) == WORKING && WorkersBinding.working(farmer),
                    "MCA's Move did not send the farmer back to work");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(id);
            h.assertTrue(entry != null && "mcamobcompat:workers".equals(entry.adapter())
                    && entry.origin() == CharacterOrigin.ADOPTED_ACTOR, "The farmer's record is not a Workers adoption");
            farmer.discard();
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void aNobleHireMakesTheSamePersonAWorker(GameTestHelper h) throws Exception {
        RecruitsGameTests.floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 5));
        UUID person = villager.getUUID();
        ServerPlayer player = RecruitsGameTests.player(h, "Steward");
        RecruitsGameTests.nobleHire(h, villager, "workers:lumberjack", player);

        h.assertTrue(!villager.isRemoved() && person.equals(villager.getUUID()), "The hire discarded the MCA villager");
        Mob worker = GuardProxy.guardOf(villager);
        h.assertTrue(worker != null && "workers:lumberjack".equals(EntityType.getKey(worker.getType()).toString()),
                "No lumberjack stands behind the person");
        GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(worker.getUUID());
        h.assertTrue(entry != null && "mcamobcompat:workers".equals(entry.adapter())
                && entry.origin() == CharacterOrigin.ENLISTED_PERSON && person.equals(entry.characterUuid()),
                "The record does not name the same person as a worker");
        h.assertTrue(player.getUUID().equals(RecruitsBinding.owner(worker).orElse(null)), "Recruits' own hire did not set the owner");

        CharacterTransitions.Outcome home = CharacterTransitions.returnToCivilian(player, worker);
        h.assertTrue(home.done(), "The way home was refused: " + home.messageKey());
        h.assertTrue(worker.isRemoved() && villager.isAlive() && !villager.isInvisible() && !GuardProxy.isProxy(villager),
                "The person did not come home from work");
        villager.discard();
        h.succeed();
    }
}
