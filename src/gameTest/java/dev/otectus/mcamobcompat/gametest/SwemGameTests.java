package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.mount.MountPolicy;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/** A real SWEM horse: recognised, owned through vanilla, suppressible -- and refused to villagers. */
@GameTestHolder(McaMobCompatTests.SWEM)
@PrefixGameTestTemplate(false)
public final class SwemGameTests {

    private SwemGameTests() {
    }

    private static AbstractHorse swemHorse(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("swem", "swem_horse")).orElse(null);
        h.assertTrue(type != null && McaMobCompat.location("swem", "swem_horse").equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)),
                "SWEM is not installed");
        AbstractHorse horse = (AbstractHorse) type.create(h.getLevel());
        horse.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        h.getLevel().addFreshEntity(horse);
        return horse;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void anOwnedSwemHorseIsRecognisedAndRespectsSuppression(GameTestHelper h) {
        AbstractHorse horse = swemHorse(h);
        UUID owner = UUID.randomUUID();
        horse.setOwnerUUID(owner);
        horse.setTamed(true);
        h.assertTrue(owner.equals(MobCompatibilityAPI.getOwner(horse).orElse(null)), "SWEM ownership was not read");
        h.assertTrue(MobCompatibilityAPI.getRoles(horse).containsAll(Set.of(MobRole.HORSE, MobRole.MOUNT)),
                "A SWEM horse is not a horse and a mount");
        try {
            ConfigAccess.override(McaMobCompatConfig.COMMON.swemModule, false, () -> {
                ClassificationCache.invalidate();
                h.assertTrue(MobCompatibilityAPI.getRoles(horse).isEmpty(),
                        "A tamed SWEM horse kept a role with its module switched off");
            });
        } finally {
            ClassificationCache.invalidate();
        }
        h.assertTrue(owner.equals(horse.getOwnerUUID()) && horse.isTamed(), "Classification changed ownership");
        horse.discard();
        h.succeed();
    }

    /**
     * The root cause, observed on real entities rather than read from bytecode. A mount is steered by
     * its controlling passenger, and every route MCA has for steering a horse goes through it. A
     * vanilla horse names the villager on it its controlling passenger and, with it aboard, walks
     * where its navigation is sent; a SWEM horse names only a player, and, counting only a player as
     * a rider at all, goes on under its own AI as if unridden -- which is why how far it moves proves
     * nothing. Seated under {@code ALLOW_INERT} so the policy does not refuse it first. Its own
     * batch, because it measures how far a horse moves.
     */
    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80, batch = "mcamobcompat_swem_motion")
    public static void aVillagerCannotSteerASwemHorseButCanAVanillaOne(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        AbstractHorse swem = swemHorse(h);
        swem.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 3))));
        AbstractHorse vanilla = h.spawn(EntityType.HORSE, new BlockPos(2, 1, 11));
        vanilla.setTamed(true);
        vanilla.equipSaddle(null);
        Villager onSwem = h.spawnWithNoFreeWill(villagerType(h), new BlockPos(3, 1, 3));
        Villager onVanilla = h.spawnWithNoFreeWill(villagerType(h), new BlockPos(3, 1, 11));
        // A mod that lets only players ride vanilla horses (Icy's Better Horses 2.0) leaves nothing
        // to compare against; the SWEM half still stands on its own.
        boolean compare = MountGameTests.acceptsNonPlayerRiders(h, vanilla);
        ConfigAccess.override(McaMobCompatConfig.SERVER.npcRidingPolicy,
                dev.otectus.mcamobcompat.mount.NpcRidingPolicy.ALLOW_INERT, () -> {
                    h.assertTrue(onSwem.startRiding(swem), "The villager could not even sit on the SWEM horse");
                    h.assertTrue(!compare || onVanilla.startRiding(vanilla), "The villager could not sit on the vanilla horse");
                });
        h.assertTrue(swem.getControllingPassenger() == null, "A villager controls the SWEM horse it rides");
        h.assertTrue(!compare || vanilla.getControllingPassenger() == onVanilla,
                "The villager does not control the vanilla horse it rides");
        Vec3 vanillaStart = vanilla.position();
        h.onEachTick(() -> {
            if (compare && vanilla.isAlive()) {
                vanilla.getNavigation().moveTo(vanillaStart.x + 10.0D, vanillaStart.y, vanillaStart.z, 1.2D);
            }
        });
        h.runAtTickTime(60, () -> {
            double vanillaMoved = vanilla.position().distanceTo(vanillaStart);
            try {
                h.assertTrue(!compare || vanillaMoved > 2.0D,
                        "The vanilla horse did not move with a villager aboard: " + vanillaMoved);
                h.assertTrue(onSwem.getVehicle() == swem && swem.getControllingPassenger() == null,
                        "The SWEM horse lost its villager, or let it steer");
            } finally {
                onSwem.discard();
                onVanilla.discard();
                swem.discard();
                vanilla.discard();
            }
            h.succeed();
        });
    }

    @SuppressWarnings("unchecked")
    private static EntityType<Villager> villagerType(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("mca", "male_villager")).orElse(null);
        return (EntityType<Villager>) type;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void aSwemHorseRefusesAnMcaVillager(GameTestHelper h) {
        AbstractHorse horse = swemHorse(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(7, 1, 5));
        h.assertTrue(MountPolicy.playerOnly(horse), "swem:swem_horse is missing from player_only_mounts");
        h.assertFalse(villager.startRiding(horse), "A villager was seated on a horse only a player can steer");
        h.assertTrue(horse.getPassengers().isEmpty(), "The SWEM horse has a passenger");
        villager.discard();
        horse.discard();
        h.succeed();
    }
}
