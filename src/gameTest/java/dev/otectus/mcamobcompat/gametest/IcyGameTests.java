package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.integration.icysbetterhorses.IcyReflection;
import dev.otectus.mcamobcompat.integration.icysbetterhorses.IcysBetterHorsesModule;
import dev.otectus.mcamobcompat.mount.MountPolicy;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Icy's Better Horses 2.0 on one of its own breeds (1.1.6, which has none, on a vanilla horse):
 * the horse is a horse and a mount, its owner is
 * read, and a horse that belongs to a player is refused a villager by this mod's policy -- while a
 * fresh, unowned one is not. Icy 2.0 itself lets only players ride, so the villager is only seated
 * where the horse accepts non-player riders at all. Hitching exists only in release 1.1.6, so that
 * part runs only where Icy has the member.
 * Icy's state is set here by its own member names, the same way the module reads it, so the test
 * names no Icy class either.
 */
@GameTestHolder(McaMobCompatTests.ICY)
@PrefixGameTestTemplate(false)
public final class IcyGameTests {

    private IcyGameTests() {
    }

    private static Method member(Object target, String name, Class<?> parameter) {
        try {
            return target.getClass().getMethod(name, parameter);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static void call(Object target, String name, Class<?> parameter, Object argument) {
        Method method = member(target, name, parameter);
        if (method == null) {
            throw new IllegalStateException("Icy's Better Horses has no " + name);
        }
        try {
            method.invoke(target, argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** One of Icy 2.0's breeds; on Icy 1.1.6, which registers none, a vanilla horse carries its state. */
    private static AbstractHorse breed(GameTestHelper h) {
        ResourceLocation id = McaMobCompat.location(ModIds.ICYS_BETTER_HORSES_ENTITIES, "arabian_horse");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(EntityType.HORSE);
        AbstractHorse horse = (AbstractHorse) type.create(h.getLevel());
        horse.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        horse.setNoAi(true);
        h.getLevel().addFreshEntity(horse);
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        return horse;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void icysOwnerAndHitchDecideWhoRides(GameTestHelper h) {
        AbstractHorse horse = breed(h);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(7, 1, 5));
        UUID owner = UUID.randomUUID();
        h.assertTrue(MobCompatibilityAPI.getRoles(horse).containsAll(Set.of(MobRole.HORSE, MobRole.MOUNT)),
                "An Icy breed is not a horse and a mount");

        call(horse, "bh_setOwner", UUID.class, owner);
        h.assertTrue(owner.equals(IcyReflection.owner(horse).orElse(null)), "Icy's owner was not read");
        h.assertTrue(owner.equals(MobCompatibilityAPI.getOwner(horse).orElse(null)),
                "The API did not fall back to Icy's owner");
        h.assertTrue(IcysBetterHorsesModule.REASON_OWNED.equals(MountPolicy.decide(villager, horse).reasonKey()),
                "The policy did not refuse a horse that belongs to a player");
        h.assertFalse(villager.startRiding(horse), "A villager took a horse that belongs to a player");

        call(horse, "bh_setOwner", UUID.class, null);
        if (member(horse, "bh_setHitchpostPos", BlockPos.class) != null) {
            call(horse, "bh_setHitchpostPos", BlockPos.class, horse.blockPosition());
            h.assertFalse(villager.startRiding(horse), "A villager took a hitched horse");
            call(horse, "bh_setHitchpostPos", BlockPos.class, null);
        } else {
            h.assertFalse(IcyReflection.isHitched(horse), "A release without hitching reported a hitched horse");
        }

        h.assertTrue(IcyReflection.command(horse).isPresent(), "Icy's command was not read");
        h.assertTrue(MountPolicy.decide(villager, horse).allowed(),
                "The policy refused an unowned, untied horse (Icy's default command is inert while unowned)");
        // Icy 2.0 lets only players ride its horses, so it turns the villager away itself; 1.1.6 does not.
        if (MountGameTests.acceptsNonPlayerRiders(h, horse)) {
            h.assertTrue(villager.startRiding(horse), "An unowned, untied horse did not take the villager");
            villager.stopRiding();
        }
        // Switching the module off hides Icy's own entities; a vanilla horse stays a vanilla horse.
        if (ModIds.ICYS_BETTER_HORSES_ENTITIES.equals(EntityType.getKey(horse.getType()).getNamespace())) {
            try {
                ConfigAccess.override(McaMobCompatConfig.COMMON.icysBetterHorsesModule, false, () -> {
                    ClassificationCache.invalidate();
                    h.assertTrue(MobCompatibilityAPI.getRoles(horse).isEmpty(),
                            "An Icy breed kept a role with the module switched off");
                });
            } finally {
                ClassificationCache.invalidate();
            }
        }
        villager.discard();
        horse.discard();
        h.succeed();
    }
}
