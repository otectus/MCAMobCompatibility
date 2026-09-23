package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Gives every proxy a narrow collision box nested inside its guard's.
 *
 * <p>A guard and its proxy stand on the same spot, and a projectile, a player's pick and a melee
 * swing each select the <em>nearest</em> entity along their ray. With two boxes of the same size
 * that choice is arbitrary, and a projectile that selects the proxy is skipped past both: the
 * arrow flies through the guard as though nothing were there. A proxy box strictly inside the
 * guard's makes the guard the nearest hit on every ray that crosses it, so vanilla's own hit code
 * -- piercing, potion effects, trident loyalty, knockback, attribution -- lands on the entity that
 * actually fights, with no rerouting at all.
 *
 * <p>{@link EntityEvent.Size} is the only mixin-free way to change an entity's box that survives
 * every {@code setPos}. It fires on both sides; the client knows a proxy from the explicit pairing
 * packet, which triggers a refresh on arrival. Eye height is left alone, so MCA's look-at and
 * reach logic are unaffected, and entity searches that inflate an area around a player still find
 * the proxy.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxyShapeHandler {

    private ProxyShapeHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSize(EntityEvent.Size event) {
        // The registry-id check first: this fires for every entity whose pose changes.
        if (!McaEntities.isMcaVillager(event.getEntity()) || !GuardProxy.isProxy(event.getEntity())) {
            return;
        }
        event.setNewSize(proxyDimensions(event.getNewSize()));
    }

    /**
     * The collision shape a proxy gets instead of the one MCA gives it: no wider than
     * {@link GuardProxy#PROXY_WIDTH} and a little shorter, so it sits wholly inside the guard's box.
     * {@code setNewSize} without its second argument leaves the eye height as it was.
     */
    public static EntityDimensions proxyDimensions(EntityDimensions original) {
        return EntityDimensions.scalable(Math.min(GuardProxy.PROXY_WIDTH, original.width), original.height * 0.9F);
    }
}
