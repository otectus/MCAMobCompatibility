package dev.otectus.mcamobcompat.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameRules;

import java.util.Map;
import java.util.TreeMap;

/**
 * Golems Kill Creepers alongside creeper awareness: its two gamerules exist, and nothing this mod
 * does to a threatened villager changes them.
 */
@net.neoforged.neoforge.gametest.GameTestHolder(McaMobCompatTests.GOLEMS)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class GolemGameTests {

    private GolemGameTests() {
    }

    private static Map<String, String> golemRules(GameTestHelper h) {
        Map<String, String> found = new TreeMap<>();
        GameRules rules = h.getLevel().getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                if (key.getId().startsWith("doIronGolems")) {
                    found.put(key.getId(), rules.getRule(key).serialize());
                }
            }
        });
        return found;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void golemGamerulesAreLeftAlone(GameTestHelper h) {
        Map<String, String> before = golemRules(h);
        h.assertTrue(before.containsKey("doIronGolemsAttackCreepers") && before.containsKey("doIronGolemsInstakillCreepers"),
                "Golems Kill Creepers' gamerules are missing: " + before);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(6, 1, 8));
        Creeper creeper = h.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(9, 1, 8));
        creeper.setTarget(villager);
        h.runAtTickTime(30, () -> {
            try {
                h.assertTrue(before.equals(golemRules(h)), "A gamerule changed: " + before + " -> " + golemRules(h));
            } finally {
                creeper.discard();
                villager.discard();
            }
            h.succeed();
        });
    }
}
