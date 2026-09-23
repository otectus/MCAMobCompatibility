package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.mca.McaMemories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Creeper awareness against a real MCA villager and MCA's real brain: the villager must visibly
 * move away, or MCA's own guard combat must take the creeper -- before anything explodes.
 */
@GameTestHolder(McaMobCompatTests.CORE)
@PrefixGameTestTemplate(false)
public final class ThreatGameTests {

    private ThreatGameTests() {
    }

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static Creeper creeper(GameTestHelper h, BlockPos at, LivingEntity quarry) {
        Creeper creeper = h.spawnWithNoFreeWill(EntityType.CREEPER, at);
        creeper.setTarget(quarry);
        return creeper;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aVillagerRunsFromACreeperHuntingIt(GameTestHelper h) {
        floor(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(6, 1, 8));
        Creeper creeper = creeper(h, new BlockPos(10, 1, 8), villager);
        double start = villager.distanceTo(creeper);
        h.succeedWhen(() -> {
            h.assertTrue(villager.distanceTo(creeper) > start + 1.5D,
                    "The villager has not moved away from the creeper yet (" + villager.distanceTo(creeper) + ")");
            creeper.discard();
            villager.discard();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void anMcaGuardTakesTheCreeperOn(GameTestHelper h) {
        floor(h);
        Villager guard = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 8));
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getOptional(GuardProxy.GUARD_PROFESSION).orElse(null);
        h.assertTrue(GuardProxy.GUARD_PROFESSION.equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession)),
                "MCA's guard profession is missing");
        guard.setVillagerData(guard.getVillagerData().setProfession(profession));
        guard.refreshBrain(h.getLevel());
        Creeper creeper = creeper(h, new BlockPos(11, 1, 8), guard);
        h.succeedWhen(() -> {
            boolean enemy = McaMemories.guardEnemy(guard).orElse(null) == creeper;
            boolean attacking = guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == creeper;
            h.assertTrue(enemy || attacking, "MCA's guard combat has not taken the creeper");
            creeper.discard();
            guard.discard();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 26)
    public static void aBurningFuseSendsEvenAGuardRunning(GameTestHelper h) {
        floor(h);
        Villager guard = ProxyGameTests.mcaVillager(h, new BlockPos(6, 1, 8));
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getOptional(GuardProxy.GUARD_PROFESSION).orElse(null);
        guard.setVillagerData(guard.getVillagerData().setProfession(profession));
        guard.refreshBrain(h.getLevel());
        Creeper creeper = creeper(h, new BlockPos(8, 1, 8), guard);
        creeper.setSwellDir(1);
        double start = guard.distanceTo(creeper);
        // Polled every tick, and finished well inside the creeper's 30-tick fuse. MCA's movement erases
        // a walk target once it is reached or unreachable, so the observable is the guard moving away.
        h.succeedWhen(() -> {
            h.assertTrue(guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) != creeper,
                    "The guard is trying to melee a creeper about to explode");
            h.assertTrue(guard.distanceTo(creeper) > start + 1.0D,
                    "The guard has not moved away from a burning fuse (" + guard.distanceTo(creeper) + ")");
            creeper.discard();
            guard.discard();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void aHiddenProxyNeverReacts(GameTestHelper h) {
        Mob guard = ProxyGameTests.guard(h);
        Villager proxy = ProxyGameTests.proxy(h, guard);
        Creeper creeper = creeper(h, new BlockPos(8, 1, 5), proxy);
        h.runAtTickTime(30, () -> {
            try {
                h.assertTrue(proxy.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(),
                        "A hidden proxy was given somewhere to run");
                h.assertTrue(proxy.position().distanceTo(guard.position()) < 0.01D, "The proxy left its guard");
            } finally {
                creeper.discard();
                guard.discard();
            }
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void aCreeperIsAThreatByTag(GameTestHelper h) {
        h.assertTrue(EntityType.CREEPER.is(dev.otectus.mcamobcompat.threat.ThreatAssessor.npcThreatsTag()),
                "minecraft:creeper is missing from mcamobcompat:npc_threats");
        h.assertFalse(EntityType.COW.is(dev.otectus.mcamobcompat.threat.ThreatAssessor.npcThreatsTag()),
                "A cow is a threat");
        h.assertTrue(McaMobCompat.location("mcamobcompat", "npc_threats")
                .equals(dev.otectus.mcamobcompat.threat.ThreatAssessor.npcThreatsTag().location()), "Tag id changed");
        h.succeed();
    }
}
