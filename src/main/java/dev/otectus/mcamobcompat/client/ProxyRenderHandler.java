package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
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
 * screen the guard is drawn in the proxy's place, wearing the pose the helper just put on the proxy.
 *
 * <p><b>Which entities are proxies</b> comes from the server's explicit pairing ({@code ProxyPairs}),
 * matched by UUID and runtime id -- never from synced flags or from which guard is nearest, so a
 * real villager an operator made invisible still renders, and two guards standing together each get
 * their own portrait.
 *
 * <p><b>Whether this is a portrait</b> is read from the call stack, and only while a screen is
 * open: a {@code Screen} frame below this render means a screen is drawing the entity. A flag set
 * from screen render events would be left set by any mod that cancels the screen render or throws
 * inside it, and would then draw guards into the world.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProxyRenderHandler {

    private static final StackWalker RENDER_STACK =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Re-entrancy guard: drawing the guard fires this event again, for the guard. */
    private static boolean substituting;

    private ProxyRenderHandler() {
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (substituting || !GuardProxy.isProxy(entity)) {
            return;
        }
        event.setCanceled(true);
        if (Minecraft.getInstance().screen == null || !drawnByScreen()) {
            return;
        }
        Mob guard = GuardProxy.nearbyGuardOf(entity);
        if (guard != null) {
            drawInsteadOf(entity, guard, event);
        }
    }

    private static boolean drawnByScreen() {
        return RENDER_STACK.walk(frames -> frames.anyMatch(frame ->
                Screen.class.isAssignableFrom(frame.getDeclaringClass())));
    }

    /**
     * Draws the guard where the proxy was about to be drawn.
     *
     * <p>The pose stack is already at the proxy's origin: the dispatcher translated there before
     * handing off to the living renderer, whose event this is. The inventory helpers pose the
     * entity they were given by writing its rotation fields, so those are copied from the proxy
     * onto the guard for this one call and restored afterwards, whatever happens -- the guard's own
     * values are what the world render reads next frame.
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

        substituting = true;
        event.getPoseStack().pushPose();
        try {
            guard.yBodyRot = proxy.yBodyRot;
            guard.yBodyRotO = proxy.yBodyRotO;
            guard.yHeadRot = proxy.yHeadRot;
            guard.yHeadRotO = proxy.yHeadRotO;
            guard.setYRot(proxy.getYRot());
            guard.yRotO = proxy.yRotO;
            guard.setXRot(proxy.getXRot());
            guard.xRotO = proxy.xRotO;

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
