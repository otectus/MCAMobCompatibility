package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
 * screen the guard is drawn in the proxy's place instead, wearing the pose the helper just put on
 * the proxy. World rendering and screen rendering never overlap within a frame, which is what makes
 * the {@link ScreenEvent.Render} flag a reliable way to tell the two apart.
 *
 * <p>The client cannot see the persistent data that makes a proxy a proxy, so the test is
 * {@link GuardProxy#looksLikeProxy}, a heuristic over synced entity flags, and the guard is found by
 * position with {@link GuardProxy#nearbyGuardOf}. Both are kept free of client-only types precisely
 * so this class can be the only one in the jar that needs them.
 *
 * <p>The single client-side class in this mod, and the only {@code Dist.CLIENT} subscriber.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProxyRenderHandler {

    /** True while a screen is rendering; set and cleared on the render thread only. */
    private static boolean renderingScreen;

    /** Re-entrancy guard: drawing the guard fires this event again, for the guard. */
    private static boolean substituting;

    private ProxyRenderHandler() {
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        renderingScreen = true;
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        renderingScreen = false;
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (substituting || !GuardProxy.looksLikeProxy(entity)) {
            return;
        }
        event.setCanceled(true);
        if (!renderingScreen) {
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
        try {
            EntityRenderer<? super Mob> renderer =
                    Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(guard);
            renderer.render(guard, 0.0F, event.getPartialTick(), event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (RuntimeException e) {
            McaMobCompat.debug("Could not draw guard {} in place of its proxy: {}", guard.getUUID(), e.toString());
        } finally {
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
