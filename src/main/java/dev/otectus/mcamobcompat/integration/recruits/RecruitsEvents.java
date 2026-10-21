package dev.otectus.mcamobcompat.integration.recruits;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Villager Recruits' own events, listened to on the loader's bus without naming them: the event class
 * comes from {@link RecruitsBinding}, which knows it by name, and its contents are read through the
 * same binding.
 *
 * <p>Only {@code Promoted} is needed. Recruits posts it, cancellable, before it discards the recruit
 * and adds the promoted one; at the last priority, and only when nothing cancelled it, it opens the
 * bracket the character transition reads that discard and that join against. Hiring and dismissal
 * need nothing: a hired recruit is the same entity and keeps its character, and Recruits' own
 * dismissal keeps the entity too -- it is not a return to civilian life.
 *
 * <p>On NeoForge 1.21.1 Villager Recruits has no build, its module never activates, and this is never
 * registered; it is kept in step with the Forge build for the day one is verified.
 */
public final class RecruitsEvents {

    private static boolean registered;

    private RecruitsEvents() {
    }

    /** Registers the listener once, when the binding found the event. Called from common setup. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Class<?> promoted = RecruitsBinding.promotedEventClass();
        if (promoted == null || !Event.class.isAssignableFrom(promoted)) {
            return;
        }
        listen(promoted.asSubclass(Event.class));
        registered = true;
        McaMobCompat.LOGGER.info("Listening for Villager Recruits promotions, to keep each promoted recruit's MCA person.");
    }

    private static <T extends Event> void listen(Class<T> type) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, type, RecruitsEvents::onPromoted);
    }

    private static void onPromoted(Event event) {
        if (event instanceof ICancellableEvent cancellable && cancellable.isCanceled()) {
            return;
        }
        Entity recruit = RecruitsBinding.eventRecruit(event);
        if (recruit instanceof Mob mob && !mob.level().isClientSide) {
            CharacterTransitions.onPromotion(mob, RecruitsBinding.promotingPlayer(event));
        }
    }
}
