package dev.otectus.mcamobcompat.api.event;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * Fired on the Forge bus when a player right-clicks a bridged guard, before the guard's own mod
 * sees the click.
 *
 * <p>This is how a sibling addon puts its own screen in front of a guard -- a conversation, a quest
 * offer, a bounty board -- without touching Guard Villagers. Cancelling it cancels the underlying
 * {@code PlayerInteractEvent.EntityInteract} with {@link #getCancellationResult()}, so the guard's
 * own GUI does not open.
 *
 * <p>Fired on <b>both</b> logical sides, once per hand. A listener that only wants the server must
 * check {@code getGuard().level().isClientSide} itself; one that only wants a single fire per click
 * should check {@link #getHand()}. Cancelling on the server alone leaves the client swinging.
 */
@Cancelable
public class GuardInteractEvent extends Event {

    private final Player player;
    private final Mob guard;
    private final InteractionHand hand;

    private InteractionResult cancellationResult = InteractionResult.SUCCESS;

    public GuardInteractEvent(Player player, Mob guard, InteractionHand hand) {
        this.player = player;
        this.guard = guard;
        this.hand = hand;
    }

    /** The player who clicked. */
    public Player getPlayer() {
        return player;
    }

    /** The guard that was clicked. It has an identity; {@code MobCompatibilityAPI} will hand it over. */
    public Mob getGuard() {
        return guard;
    }

    /** Which hand raised the click. */
    public InteractionHand getHand() {
        return hand;
    }

    /** The result the underlying interact event is cancelled with. Defaults to SUCCESS. */
    public InteractionResult getCancellationResult() {
        return cancellationResult;
    }

    /** Sets that result. Only meaningful together with {@code setCanceled(true)}. */
    public void setCancellationResult(InteractionResult cancellationResult) {
        this.cancellationResult = cancellationResult;
    }
}
