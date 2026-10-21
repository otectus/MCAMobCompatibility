package dev.otectus.mcamobcompat.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.event.ForgeEventFactory;

/**
 * The two ways the bridge replays a click: onto the guard's own Guard Villagers gesture, and onto
 * an entity after the Forge hooks have already been offered.
 *
 * <p>It lives here rather than in {@code GuardBridgeHandler} so that the event handler stays a
 * router -- it decides which gesture happened, this decides what the gesture means.
 */
public final class GuardInteractions {

    private GuardInteractions() {
    }

    /**
     * Runs Guard Villagers' own right-click on this guard, as though the player were standing up.
     *
     * <p>This is the gesture swap. Guard Villagers opens its inventory only when the player is
     * <em>not</em> sneaking, and the plain click belongs to MCA -- so the crouch click has to be
     * replayed with the shift flag down. The flag and the pose are cleared for the length of
     * exactly one call and restored in {@code finally}, including when the guard's own handler
     * throws.
     */
    public static InteractionResult delegateToGuardVillagers(ServerPlayer player, Mob guard, InteractionHand hand) {
        return withCrouch(player, false, () -> interactAfterHook(player, guard, hand));
    }

    /**
     * Runs one interaction with the player's crouch set as given -- the flag and the pose -- and puts
     * both back in {@code finally}, including when the interaction throws. A command that opens a
     * native screen meant for a crouching click uses this, as does the Guard Villagers gesture swap.
     */
    public static InteractionResult withCrouch(ServerPlayer player, boolean crouching,
                                               java.util.function.Supplier<InteractionResult> interaction) {
        boolean shift = player.isShiftKeyDown();
        Pose pose = player.getPose();
        try {
            player.setShiftKeyDown(crouching);
            player.setPose(crouching ? Pose.CROUCHING : Pose.STANDING);
            return interaction.get();
        } finally {
            player.setShiftKeyDown(shift);
            player.setPose(pose);
        }
    }

    /**
     * Vanilla's {@code Player.interactOn}, minus its {@code EntityInteract} hook, which the caller
     * has already offered: the entity's own {@code interact}, then the held item's
     * {@code interactLivingEntity}, with vanilla's creative-mode stack restoration and Forge's
     * destroyed-item event. Replaying it by hand means an addon listening for the hook sees one
     * click, not two.
     */
    public static InteractionResult interactAfterHook(ServerPlayer player, Mob target, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack before = held.copy();
        InteractionResult result = target.interact(player, hand);
        if (result.consumesAction()) {
            if (player.getAbilities().instabuild && held == player.getItemInHand(hand)
                    && held.getCount() < before.getCount()) {
                held.setCount(before.getCount());
            }
            if (!player.getAbilities().instabuild && held.isEmpty()) {
                ForgeEventFactory.onPlayerDestroyItem(player, before, hand);
            }
            return result;
        }
        if (held.isEmpty()) {
            return InteractionResult.PASS;
        }
        ItemStack item = player.getAbilities().instabuild ? before : held;
        result = item.interactLivingEntity(player, target, hand);
        if (result.consumesAction()) {
            player.level().gameEvent(GameEvent.ENTITY_INTERACT, target.position(), GameEvent.Context.of(player));
            if (item.isEmpty() && !player.getAbilities().instabuild) {
                ForgeEventFactory.onPlayerDestroyItem(player, before, hand);
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
        return result;
    }
}
