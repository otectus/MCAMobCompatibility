package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws native NPCs as their MCA persons, and decides what is drawn when something asks to draw a
 * hidden identity.
 *
 * <p><b>Three contexts, kept apart.</b>
 * <ul>
 *   <li><b>In the world</b>, an actor whose server says its character is drawn as MCA is drawn by
 *       MCA's own renderer, through its render view ({@link CharacterRenderViews}), in the actor's own
 *       pose ({@link NativePoseAdapter}): MCA's model, skin, face, hair, clothes, dyes, custom skin and
 *       genetic proportions, with the native actor's walk, swing, items, armour and mount. The native
 *       renderer is skipped for that frame; the dispatcher still draws the actor's one shadow and one
 *       fire, and the native renderer's leash, so each appears exactly once.</li>
 *   <li><b>In a portrait</b> of the hidden identity -- MCA: Conversations' dialogue frame, MCA:
 *       Quests' quest menu -- the person is drawn as the person, posed as the screen posed the identity,
 *       wearing the actor's equipment. A native screen's portrait of the actor (Recruits' inventory) is
 *       simply the actor drawn as the person, posed as that screen posed it.</li>
 *   <li><b>MCA's editor</b> draws its own detached preview villagers, with the unsaved edits. They are
 *       never paired with anything and are left entirely to MCA.</li>
 * </ul>
 *
 * <p><b>Which context</b> is known without walking the call stack: entities drawn between the level
 * stages {@code AFTER_SKY} and {@code AFTER_ENTITIES} are the world's, and the flag is reset at the
 * start and end of every frame, so a mod that aborts a frame cannot leave it set. A hidden identity
 * drawn in the world is never drawn; outside the world it is drawn only while a screen is open.
 *
 * <p><b>Which entities</b> are actors and identities comes only from the server's explicit
 * association, matched by UUID and runtime id -- never from flags or nearness -- so a real villager an
 * operator made invisible still renders, and an ordinary MCA villager is never hidden.
 *
 * <p><b>Failure is visible, never invisible.</b> Until a view exists -- the identity has not arrived
 * on this client, the server draws this mod natively, the player turned it off -- the native renderer
 * draws the actor. If MCA's renderer throws for an actor type, that is logged once and that type is
 * drawn natively from then on.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT)
public final class CharacterRenderHandler {

    /** Re-entrancy guard: drawing a view fires this event again, for the view. */
    private static boolean substituting;

    /** Whether the level renderer is drawing the world's entities right now. */
    private static boolean inWorld;

    /** Actor types MCA's renderer failed for this session: drawn natively from then on. */
    private static final Set<EntityType<?>> FAILED = ConcurrentHashMap.newKeySet();

    private CharacterRenderHandler() {
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            inWorld = true;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            inWorld = false;
        }
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        inWorld = false;
    }

    @SubscribeEvent
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        inWorld = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (substituting) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (GuardProxy.isProxy(entity)) {
            // MCA's clothing, hair, face and skin layers ignore invisibility, so an invisible identity
            // would be a floating suit of clothes: the whole render is always cancelled.
            event.setCanceled(true);
            if (!inWorld && Minecraft.getInstance().screen != null) {
                portrait((Villager) entity, event);
            }
            return;
        }
        if (!(entity instanceof Mob actor) || actor.isInvisible() || FAILED.contains(actor.getType())
                || !ConfigAccess.bool(McaMobCompatConfig.CLIENT.mcaAppearance, true)) {
            return;
        }
        ProxyPairs.Pair pair = ProxyPairs.clientPairOfActor(actor);
        if (pair == null || !pair.mcaAppearance()) {
            return;
        }
        Villager identity = ProxyPairs.clientIdentityOf(actor);
        Villager view = identity == null ? null : CharacterRenderViews.viewOf(identity, pair);
        if (view == null) {
            return;
        }
        event.setCanceled(true);
        draw(view, actor, actor, event);
    }

    /**
     * A screen is drawing the hidden identity: draw the person in its place, posed as the screen posed
     * the identity and wearing the actor's equipment. Without a view, the actor is drawn there instead,
     * as before 0.3.0.
     */
    private static void portrait(Villager identity, RenderLivingEvent.Pre<?, ?> event) {
        Mob actor = GuardProxy.nearbyGuardOf(identity);
        ProxyPairs.Pair pair = actor == null ? null : ProxyPairs.clientPairOfActor(actor);
        Villager view = pair != null && pair.mcaAppearance() && ConfigAccess.bool(McaMobCompatConfig.CLIENT.mcaAppearance, true)
                && (actor == null || !FAILED.contains(actor.getType()))
                ? CharacterRenderViews.viewOf(identity, pair) : null;
        if (view != null) {
            draw(view, actor != null ? actor : identity, identity, event);
        } else if (actor != null) {
            drawActorInsteadOf(identity, actor, event);
        }
    }

    /** Draws the view with the actor's frame, through MCA's own renderer for it. */
    private static void draw(Villager view, LivingEntity actor, LivingEntity facing, RenderLivingEvent.Pre<?, ?> event) {
        HumanoidModel<?> model = null;
        HumanoidModel.ArmPose[] arms = null;
        substituting = true;
        event.getPoseStack().pushPose();
        try {
            NativePoseAdapter.apply(actor, facing, view);
            EntityRenderer<? super Villager> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(view);
            if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                EntityModel<?> base = living.getModel();
                if (base instanceof HumanoidModel<?> humanoid) {
                    model = humanoid;
                    arms = NativePoseAdapter.poseArms(humanoid, actor);
                }
            }
            float yaw = Mth.lerp(event.getPartialTick(), facing.yRotO, facing.getYRot());
            renderer.render(view, yaw, event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(),
                    event.getPackedLight());
        } catch (RuntimeException failure) {
            if (FAILED.add(actor.getType())) {
                McaMobCompat.LOGGER.warn("MCA's renderer failed for {}; drawing it with its own renderer from now on.",
                        EntityType.getKey(actor.getType()), failure);
            }
        } finally {
            if (model != null) {
                NativePoseAdapter.restoreArms(model, arms);
            }
            event.getPoseStack().popPose();
            substituting = false;
        }
    }

    /**
     * Draws the actor where the identity was about to be drawn, with the rotation fields the screen
     * gave the identity copied onto the actor for this one call and restored afterwards, whatever
     * happens -- the actor's own values are what the world render reads next frame.
     */
    private static void drawActorInsteadOf(LivingEntity proxy, Mob guard, RenderLivingEvent.Pre<?, ?> event) {
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

            EntityRenderer<? super Mob> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(guard);
            renderer.render(guard, 0.0F, event.getPartialTick(), event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (RuntimeException e) {
            McaMobCompat.debug("Could not draw actor {} in place of its identity: {}", guard.getUUID(), e.toString());
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
