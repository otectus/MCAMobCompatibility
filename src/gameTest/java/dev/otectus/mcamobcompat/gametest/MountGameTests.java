package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.mount.MountPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The mount policy never gets in the way of the mounts MCA can actually drive. */
@GameTestHolder(McaMobCompatTests.CORE)
@PrefixGameTestTemplate(false)
public final class MountGameTests {

    private MountGameTests() {
    }

    /**
     * Whether this horse lets anything but a player ride it at all, probed with a zombie, which this
     * mod's policy never judges. Icy's Better Horses 2.0 lets only players ride any horse -- a mob
     * rides along only as cart cargo behind one -- so with it installed a villager is turned away by
     * the horse itself, before this mod's policy is even asked.
     */
    static boolean acceptsNonPlayerRiders(GameTestHelper h, AbstractHorse horse) {
        Zombie probe = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        probe.moveTo(horse.position());
        boolean seated = probe.startRiding(horse);
        probe.stopRiding();
        probe.discard();
        return seated;
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void aVanillaHorseStillTakesAnMcaVillager(GameTestHelper h) {
        Horse horse = h.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 1, 5));
        horse.setTamed(true);
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        Villager villager = ProxyGameTests.mcaVillager(h, new BlockPos(7, 1, 5));
        h.assertTrue(MountPolicy.decide(villager, horse).allowed(), "The mount policy refused a vanilla horse MCA can steer");
        if (acceptsNonPlayerRiders(h, horse)) {
            h.assertTrue(villager.startRiding(horse), "A vanilla horse MCA can steer did not take the villager");
            h.assertTrue(horse.getPassengers().contains(villager), "The villager is not on the horse");
            villager.stopRiding();
        }
        villager.discard();
        horse.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 40)
    public static void aPlayerIsNeverRefusedByTheVillagerPolicy(GameTestHelper h) {
        Horse horse = h.spawnWithNoFreeWill(EntityType.HORSE, new BlockPos(5, 1, 5));
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(h.getLevel());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.assertTrue(dev.otectus.mcamobcompat.mount.MountPolicy.decide(player, horse).allowed(),
                "The villager mount policy judged a player");
        horse.discard();
        h.assertTrue(new ItemStack(Items.SADDLE).is(Items.SADDLE), "sanity");
        h.succeed();
    }
}
