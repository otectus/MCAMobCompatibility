package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Decides what is drawn when something asks to draw a hidden proxy villager: nothing in the world,
 * the guard it stands in for inside a screen.
 *
 * <p>{@code setInvisible(true)} is not enough. Vanilla's renderer honours it for the villager body,
 * but MCA draws its clothing, hair, face and skin as separate layers that do not check invisibility
 * -- so an invisible MCA villager is a suit of floating clothes with a face. Cancelling the whole
 * render is the only fix that needs no mixin. The shadow is already skipped by
 * {@code EntityRenderDispatcher} for an invisible entity.
 *
 * <p>Screens are the exception. MCAConversations' dialogue frame and MCAQuests' quest menu draw the
 * villager they are talking to through vanilla's {@code InventoryScreen.renderEntityInInventory}
 * helpers, and that villager is the proxy. Cancelling there leaves the box empty, so inside a
 * screen the guard is drawn in the proxy's place. A scoped stack inspection recognizes the
 * screen helper without leaving global state behind after a canceled or exceptional render.
 * Explicit server UUID pairs identify the proxy and its guard, including clustered guards.
 * This class is loaded only on physical clients.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT)
public final class ProxyRenderHandler {

    private static boolean substituting;
    private static final StackWalker RENDER_STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private ProxyRenderHandler() {}

    @SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        dev.otectus.mcamobcompat.network.ProxyPairs.clearClient();
    }
    @SubscribeEvent
    public static void onClientEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide && event.getEntity() instanceof net.minecraft.world.entity.npc.Villager proxy
                && GuardProxy.isProxy(proxy)) GuardProxy.hide(proxy);
    }
    private static boolean isPortrait() {
        // Inspect only renders of explicitly paired proxies. No global Pre/Post screen state can
        // leak through a canceled render or an exception. Covers the vanilla inventory helper
        // used by MCA and the companion screens, plus their direct render calls.
        return RENDER_STACK.walk(frames -> frames.anyMatch(frame ->
                net.minecraft.client.gui.screens.Screen.class.isAssignableFrom(frame.getDeclaringClass())));
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (substituting || !GuardProxy.looksLikeProxy(entity)) {
            return;
        }
        event.setCanceled(true);
        if (!isPortrait()) {
            return;
        }
        Mob guard = GuardProxy.nearbyGuardOf(entity);
        if (guard == null) {
            return;
        }
        drawInsteadOf(entity, guard, event);
    }

    /**
     * Draws the guard where the proxy was about to be drawn.
     *
     * <p>The pose stack is already at the proxy's origin: the dispatcher translated there before
     * handing off to the living renderer, whose event this is. The inventory helpers pose the
     * entity they were given by writing its rotation fields, so those are copied from the proxy
     * onto the guard for this one call and restored afterwards; the guard's own values are what
     * the world render reads next frame.
     */
    private static void drawInsteadOf(LivingEntity proxy, Mob guard, RenderLivingEvent.Pre<?, ?> event) {
        float yBodyRot = guard.yBodyRot;
        float yBodyRotO = guard.yBodyRotO;
        float yHeadRot = guard.yHeadRot;
        float yHeadRotO = guard.yHeadRotO;
        float yRot = guard.getYRot();
        float yRotO = guard.yRotO;
        float xRot = guard.getXRot();
        float xRotO = guard.xRotO;

        guard.yBodyRot = proxy.yBodyRot;
        guard.yBodyRotO = proxy.yBodyRotO;
        guard.yHeadRot = proxy.yHeadRot;
        guard.yHeadRotO = proxy.yHeadRotO;
        guard.setYRot(proxy.getYRot());
        guard.yRotO = proxy.yRotO;
        guard.setXRot(proxy.getXRot());
        guard.xRotO = proxy.xRotO;

        substituting = true;
        event.getPoseStack().pushPose();
        try {
            EntityRenderer<? super Mob> renderer =
                    Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(guard);
            renderer.render(guard, 0.0F, event.getPartialTick(), event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (RuntimeException e) {
            McaMobCompat.debug("Could not draw guard {} in place of its proxy: {}", guard.getUUID(), e.toString());
        } finally {
            event.getPoseStack().popPose();
            substituting = false;
            guard.yBodyRot = yBodyRot;
            guard.yBodyRotO = yBodyRotO;
            guard.yHeadRot = yHeadRot;
            guard.yHeadRotO = yHeadRotO;
            guard.setYRot(yRot);
            guard.yRotO = yRotO;
            guard.setXRot(xRot);
            guard.xRotO = xRotO;
        }
    }
}
