package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * MCA: Crime's effects on a bridged guard's hidden proxy reach the visible guard (0.2.2), run with the
 * real MCA: Crime jar: {@code -PwithCrime -PcrimeJar=<path>}.
 *
 * <p>MCA: Crime acts on the villager it can see, which for a bridged guard is the invisible proxy, so a
 * cuff or a sand bottle lands there. {@code bridge/ProxyEffects} copies the effects in its
 * {@code MIRRORED} list onto the guard on the slow tick and takes them off again when the proxy loses
 * them. The unit test {@code ProxyEffectsContractTest} pins which ids those are; this checks that they
 * are real, registered effects and that the copy happens, against the Crime build named in the run.
 */
@GameTestHolder(McaMobCompatTests.CRIME)
@PrefixGameTestTemplate(false)
public final class CrimeGameTests {

    /** Two slow ticks of the pair (GuardProxySync's slow interval is one second), plus slack. */
    private static final int MIRROR_WAIT_TICKS = 45;

    private CrimeGameTests() {
    }

    private static MobEffect crimeEffect(GameTestHelper h, String name) {
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(McaMobCompat.location("mcacrime", name));
        h.assertTrue(effect != null, "mcacrime:" + name + " is not registered; run with -PwithCrime -PcrimeJar=<jar>");
        return effect;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 3 * MIRROR_WAIT_TICKS)
    public static void restrainedOnTheProxyReachesTheGuardAndLeavesWithIt(GameTestHelper h) {
        MobEffect restrained = crimeEffect(h, "restrained");
        Mob guard = ProxyGameTests.guard(h);
        Villager proxy = ProxyGameTests.proxy(h, guard);
        // What MCA: Crime's RestrainedBadge puts on a subject that is wearing something.
        proxy.addEffect(new MobEffectInstance(restrained, MobEffectInstance.INFINITE_DURATION, 0, true, false, true));

        h.runAfterDelay(MIRROR_WAIT_TICKS, () -> {
            h.assertTrue(guard.hasEffect(restrained), "the proxy's restrained badge never reached the guard");
            proxy.removeEffect(restrained);
            h.runAfterDelay(MIRROR_WAIT_TICKS, () -> {
                h.assertFalse(guard.hasEffect(restrained), "the guard kept restrained after the proxy lost it");
                h.succeed();
            });
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 2 * MIRROR_WAIT_TICKS)
    public static void sandBlindnessOnTheProxyReachesTheGuard(GameTestHelper h) {
        MobEffect blinded = crimeEffect(h, "sand_blinded");
        Mob guard = ProxyGameTests.guard(h);
        Villager proxy = ProxyGameTests.proxy(h, guard);
        proxy.addEffect(new MobEffectInstance(blinded, 600));

        h.runAfterDelay(MIRROR_WAIT_TICKS, () -> {
            h.assertTrue(guard.hasEffect(blinded), "a sand bottle's blindness on the proxy never reached the guard");
            h.succeed();
        });
    }
}
