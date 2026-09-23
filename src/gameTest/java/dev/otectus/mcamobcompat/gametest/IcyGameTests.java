package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.integration.icysbetterhorses.IcyReflection;
import dev.otectus.mcamobcompat.integration.icysbetterhorses.IcysBetterHorsesModule;
import dev.otectus.mcamobcompat.mount.MountPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Icy's Better Horses on a real vanilla horse: its owner is read, and a horse that belongs to a
 * player, or is hitched, is not a villager's to take -- while a fresh, unowned one is. Icy's state is set here by its own member
 * names, the same way the module reads it, so the test names no Icy class either.
 */
@GameTestHolder(McaMobCompatTests.ICY)
@PrefixGameTestTemplate(false)
public final class IcyGameTests {

    private IcyGameTests() {
    }

    private static void call(Object target, String name, Class<?> parameter, Object argument) {
        try {
            Method method = target.getClass().getMethod(name, parameter);
            method.invoke(target, argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void icysOwnerAndHitchDecideWhoRides(GameTestHelper h) {
        Horse horse = h.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 1, 5));
        horse.equipSaddle(null);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(7, 1, 5));
        UUID owner = UUID.randomUUID();

        call(horse, "bh_setOwner", UUID.class, owner);
        h.assertTrue(owner.equals(IcyReflection.owner(horse).orElse(null)), "Icy's owner was not read");
        h.assertTrue(owner.equals(MobCompatibilityAPI.getOwner(horse).orElse(null)),
                "The API did not fall back to Icy's owner");
        h.assertTrue(IcysBetterHorsesModule.REASON_OWNED.equals(MountPolicy.decide(villager, horse).reasonKey()),
                "The policy did not refuse a horse that belongs to a player");
        h.assertFalse(villager.startRiding(horse), "A villager took a horse that belongs to a player");

        call(horse, "bh_setOwner", UUID.class, null);
        call(horse, "bh_setHitchpostPos", BlockPos.class, horse.blockPosition());
        h.assertFalse(villager.startRiding(horse), "A villager took a hitched horse");

        call(horse, "bh_setHitchpostPos", BlockPos.class, null);
        h.assertTrue(IcyReflection.command(horse).isPresent(), "Icy's command was not read");
        h.assertTrue(MountPolicy.decide(villager, horse).allowed(),
                "The policy refused an unowned, untied horse (Icy's default command is inert while unowned)");
        if (MountGameTests.acceptsNonPlayerRiders(h, horse)) {
            h.assertTrue(villager.startRiding(horse), "An unowned, untied horse did not take the villager");
            villager.stopRiding();
        }
        villager.discard();
        horse.discard();
        h.succeed();
    }
}
