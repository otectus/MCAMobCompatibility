package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Alex's Mobs: a tamed animal is a protected pet, a rideable one a mount, and the module switch works. */
@GameTestHolder(McaMobCompatTests.ALEX)
@PrefixGameTestTemplate(false)
public final class AlexGameTests {

    private AlexGameTests() {
    }

    private static Mob spawn(GameTestHelper h, String path, BlockPos at) {
        ResourceLocation id = McaMobCompat.location("alexsmobs", path);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        h.assertTrue(type != null && id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)), id + " is not registered");
        Mob mob = (Mob) type.create(h.getLevel());
        mob.moveTo(Vec3.atBottomCenterOf(h.absolutePos(at)));
        mob.setNoAi(true);
        h.getLevel().addFreshEntity(mob);
        return mob;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void aTamedRaccoonIsAProtectedPetAndAnElephantAMount(GameTestHelper h) {
        Mob raccoon = spawn(h, "raccoon", new BlockPos(4, 1, 4));
        Mob elephant = spawn(h, "elephant", new BlockPos(10, 1, 10));
        h.assertTrue(raccoon instanceof TamableAnimal, "alexsmobs:raccoon is not a vanilla TamableAnimal any more");
        TamableAnimal pet = (TamableAnimal) raccoon;
        pet.setTame(true, false);
        pet.setOwnerUUID(UUID.randomUUID());
        h.assertTrue(MobCompatibilityAPI.getRoles(raccoon).contains(MobRole.PET), "A tamed raccoon is not a pet");
        h.assertTrue(MobCompatibilityAPI.getRoles(elephant).contains(MobRole.MOUNT), "An elephant is not a mount");
        h.assertTrue(dev.otectus.mcamobcompat.mount.MountPolicy.playerOnly(elephant),
                "An elephant only a player can steer is not listed as player-only");
        h.assertFalse(MobCompatibilityAPI.getRoles(raccoon).contains(MobRole.MOUNT), "A raccoon became a mount");

        Villager hunter = ProxyGameTests.mcaVillager(h, new BlockPos(5, 1, 4));
        float before = raccoon.getHealth();
        raccoon.hurt(h.getLevel().damageSources().mobAttack(hunter), 3.0F);
        h.assertTrue(raccoon.getHealth() == before, "A villager hurt somebody's pet raccoon");

        try {
            ConfigAccess.override(McaMobCompatConfig.COMMON.alexsMobsModule, false, () -> {
                ClassificationCache.invalidate();
                h.assertTrue(MobCompatibilityAPI.getRoles(elephant).isEmpty(), "The elephant kept a role with the module off");
                h.assertTrue(MobCompatibilityAPI.getRoles(raccoon).isEmpty(), "The raccoon kept a role with the module off");
            });
        } finally {
            ClassificationCache.invalidate();
        }
        hunter.discard();
        raccoon.discard();
        elephant.discard();
        h.succeed();
    }
}
