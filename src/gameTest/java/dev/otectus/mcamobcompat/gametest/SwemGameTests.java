package dev.otectus.mcamobcompat.gametest;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.*;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("mcamobcompat_swem")
@PrefixGameTestTemplate(false)
public class SwemGameTests {
    @GameTest(template = "empty", templateNamespace = "mcamobcompat")
    public static void realOwnedHorseRespectsSuppression(GameTestHelper h) {
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("swem:swem_horse")).orElseThrow();
        var horse = (AbstractHorse) type.create(h.getLevel());
        horse.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        h.getLevel().addFreshEntity(horse);
        UUID owner = UUID.randomUUID(); horse.setOwnerUUID(owner); horse.setTamed(true);
        h.assertTrue(MobCompatibilityAPI.getOwner(horse).orElseThrow().equals(owner), "SWEM vanilla ownership accessor failed");
        h.assertTrue(MobCompatibilityAPI.getRoles(horse).containsAll(java.util.Set.of(MobRole.HORSE, MobRole.MOUNT)), "Real SWEM horse is unclassified");
        boolean previous = McaMobCompatConfig.COMMON.swemModule.get();
        try {
            McaMobCompatConfig.COMMON.swemModule.set(false); ClassificationCache.invalidate();
            h.assertTrue(MobCompatibilityAPI.getRoles(horse).isEmpty(), "Dynamic tamed-mount provider bypassed module suppression");
            h.assertTrue(owner.equals(horse.getOwnerUUID()) && horse.isTamed(), "Classification modified ownership");
        } finally { McaMobCompatConfig.COMMON.swemModule.set(previous); ClassificationCache.invalidate(); horse.discard(); }
        h.succeed();
    }
}
