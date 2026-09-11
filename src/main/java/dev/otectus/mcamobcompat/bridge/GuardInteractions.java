package dev.otectus.mcamobcompat.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

/**
 * The one gesture the bridge hands straight back to Guard Villagers: the crouch click.
 *
 * <p>It lives here rather than in {@code GuardBridgeHandler} so that the event handler stays a
 * router -- it decides which gesture happened, this decides what the gesture means. Everything else
 * a click can mean is MCA's own, and reaches MCA through the hidden proxy villager rather than
 * through any code of ours.
 */
public final class GuardInteractions {

    private GuardInteractions() {
    }

    /**
     * Runs Guard Villagers' own right-click on this guard, as though the player were standing up.
     *
     * <p>This is the gesture swap. Guard Villagers opens its inventory only when the player is
     * <em>not</em> sneaking, and we have given the plain click to MCA -- so the crouch click has to
     * be replayed with the shift flag down. The flag and the pose are cleared for the length of
     * exactly one call and restored in {@code finally}, including when the guard's own handler
     * throws.
     *
     * <p>No recursion: Forge fires {@code PlayerInteractEvent.EntityInteract} from
     * {@code Player#interactOn}, not from {@code Mob#interact}, so calling {@code interact} here
     * does not re-enter this handler. The caller cancels the outer event with the result, which is
     * what stops vanilla calling {@code interact} a second time.
     */
    public static InteractionResult delegateToGuardVillagers(ServerPlayer player, Mob guard,
                                                             InteractionHand hand) {
        boolean shift = player.isShiftKeyDown();
        Pose pose = player.getPose();
        try {
            player.setShiftKeyDown(false);
            player.setPose(Pose.STANDING);
            return guard.interact(player, hand);
        } finally {
            player.setShiftKeyDown(shift);
            player.setPose(pose);
        }
    }
}
