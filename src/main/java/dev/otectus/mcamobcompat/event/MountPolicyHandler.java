package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.mount.MountDecision;
import dev.otectus.mcamobcompat.mount.MountPolicy;
import dev.otectus.mcamobcompat.mount.NpcRidingPolicy;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses an MCA villager a mount it cannot ride, and tells the player who asked.
 *
 * <p>MCA seats a villager from its own "Ride Horse" order, which arrives here as an ordinary
 * {@link EntityMountEvent}. The player who gave that order is the last one to have opened the
 * villager's screen, so each click on an MCA villager is remembered briefly, and a refusal is
 * reported to that player -- once, not once per retry. See {@link MountPolicy} for which mounts are
 * refused and why.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class MountPolicyHandler {

    /** How long a click counts as the order behind a mount, in ticks. */
    private static final long ORDER_WINDOW = 20L * 60L;

    /** The shortest gap between two refusal messages to one player, in ticks. */
    private static final long MESSAGE_GAP = 40L;

    private record Order(UUID player, long time) {
    }

    private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    private MountPolicyHandler() {
    }

    /** Remembers who last spoke to each MCA villager, as the likely author of a mount order. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || !McaEntities.isMcaVillager(event.getTarget())) {
            return;
        }
        long now = event.getLevel().getGameTime();
        ORDERS.put(event.getTarget().getUUID(), new Order(event.getEntity().getUUID(), now));
        if (ORDERS.size() > 256) {
            Iterator<Order> orders = ORDERS.values().iterator();
            while (orders.hasNext()) {
                if (now - orders.next().time() > ORDER_WINDOW) {
                    orders.remove();
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || event.getLevel().isClientSide) {
            return;
        }
        Entity rider = event.getEntityMounting();
        Entity mount = event.getEntityBeingMounted();
        // A hidden proxy is dismounted by its own sync, whatever it is put on.
        if (!(rider instanceof LivingEntity villager) || !McaEntities.isMcaVillager(villager)
                || GuardProxy.isProxy(villager)) {
            return;
        }
        if (ConfigAccess.enumValue(McaMobCompatConfig.SERVER.npcRidingPolicy, NpcRidingPolicy.REFUSE)
                != NpcRidingPolicy.REFUSE) {
            return;
        }
        MountDecision decision = MountPolicy.decide(villager, mount);
        if (decision.allowed()) {
            return;
        }
        event.setCanceled(true);
        McaMobCompat.debug("Refused {} the mount {}: {}", villager.getName().getString(),
                mount.getName().getString(), decision.reasonKey());
        tellTheOrderGiver(villager, mount, decision.reasonKey());
    }

    private static void tellTheOrderGiver(LivingEntity villager, Entity mount, String reasonKey) {
        if (villager.getServer() == null) {
            return;
        }
        long now = villager.level().getGameTime();
        Order order = ORDERS.get(villager.getUUID());
        if (order == null || now - order.time() > ORDER_WINDOW) {
            return;
        }
        ServerPlayer player = villager.getServer().getPlayerList().getPlayer(order.player());
        if (player == null) {
            return;
        }
        Long last = LAST_MESSAGE.get(player.getUUID());
        if (last != null && now - last < MESSAGE_GAP) {
            return;
        }
        LAST_MESSAGE.put(player.getUUID(), now);
        player.displayClientMessage(Component.translatable(reasonKey, villager.getName(), mount.getName()), true);
    }

    /** Drops remembered orders; called when the server stops. */
    public static void clear() {
        ORDERS.clear();
        LAST_MESSAGE.clear();
    }
}
