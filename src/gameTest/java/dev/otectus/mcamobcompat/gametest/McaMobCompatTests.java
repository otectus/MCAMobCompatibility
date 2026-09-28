package dev.otectus.mcamobcompat.gametest;

import net.minecraftforge.fml.common.Mod;

/**
 * The development-only GameTest mod. Its tests are found by Forge through their
 * {@code @GameTestHolder} annotations; this class exists because every mod id needs one.
 */
@Mod(McaMobCompatTests.MOD_ID)
public final class McaMobCompatTests {

    public static final String MOD_ID = "mcamobcompat_tests";

    /** The core suite's namespace, which is also the template namespace every suite uses. */
    public static final String CORE = "mcamobcompat";

    /** Suites that need an optional mod, enabled by the matching -Pwith* flag. */
    public static final String SWEM = "mcamobcompat_swem";
    public static final String ICY = "mcamobcompat_icy";
    public static final String ALEX = "mcamobcompat_alex";
    public static final String GOLEMS = "mcamobcompat_golems";
    public static final String CRIME = "mcamobcompat_crime";

    /** The one template: a 16x8x16 air box; each test lays its own floor. */
    public static final String EMPTY = "empty";

    public McaMobCompatTests() {
    }
}
