package dev.otectus.mcamobcompat.probe;

import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The production client driver. After the 0.2 checks (pairing, MCA's own screen, the portrait), 0.3.0
 * adds: native actors drawn as their MCA persons in the world; MCA's real editor opened on the player's
 * guard and saved through MCA's own request, with every client watching each character's synced
 * appearance; Guard Villagers' own inventory on a crouching click; an MCA villager enlisted as a real
 * guard through the recruit gesture; and that person sent home again with the role action.
 */
@Mod.EventBusSubscriber(modid="mcamobcompat_probe", value=Dist.CLIENT)
public final class ClientProbe {
    private static int ticks, stage;
    private static Villager proxy;
    private static Mob guard;
    private static int hiddenWorld, hiddenPortrait, guardPortrait, viewPortrait;
    /** Set only while the probe's portrait draws its entity, so world renders behind the screen are not counted. */
    private static boolean drawingPortrait;
    private static Villager recruit;
    private static Mob enlisted;
    private static boolean sneaking, clicked, edited;
    private static int editorPresses;
    private static String lastScreen="";
    private static java.util.UUID observed;
    private static java.util.UUID recruitId;
    private static final java.util.Map<java.util.UUID,Integer> renders=new java.util.HashMap<>();
    /** Renders of a detached MCA villager drawn where an actor stands: that actor drawn as its person. */
    private static final java.util.Map<java.util.UUID,Integer> views=new java.util.HashMap<>();
    /** Native renders of an actor that the bridge cancelled. */
    private static final java.util.Map<java.util.UUID,Integer> substituted=new java.util.HashMap<>();
    /** The last appearance seen for each character: its revision and its SIZE gene. */
    private static final java.util.Map<java.util.UUID,String> appearance=new java.util.HashMap<>();
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END) return;
        var mc=Minecraft.getInstance();
        // Before the world: report each screen once, so a launch that stalls says where.
        String screen=mc.screen==null?"none":mc.screen.getClass().getName();
        if(!screen.equals(lastScreen)) { lastScreen=screen; RuntimeProbe.log("SCREEN "+screen+" inWorld="+(mc.level!=null)); }
        if(mc.level==null || mc.player==null) return;
        ticks++;
        if (!net.minecraftforge.fml.ModList.get().isLoaded("guardvillagers") && ticks == 100) {
            RuntimeProbe.log("MINIMAL_CLIENT_READY " + mc.getUser().getName());capture(mc,"minimal");mc.stop();return;
        }
        // MCA's first-join Destiny screen: accept the destiny, then finish its character page ("Done"),
        // so the probe's screenshots show the world rather than the editor. Never during the editor stage,
        // whose editor is this probe's own.
        if (stage != 9 && mc.screen != null && (mc.screen.getClass().getName().endsWith("DestinyScreen")
                || mc.screen.getClass().getName().endsWith("VillagerEditorScreen"))) {
            String pressed = null;
            // A few presses finish the first-join flow; a screen MCA opens again later is just closed,
            // since in single player it would pause the game under the probe.
            for (String label : editorPresses >= 4 ? new String[0] : new String[]{"Accept", "Done"}) {
                for (var child : mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button
                        && button.active && button.getMessage().getString().equals(label)) { button.onPress(); pressed = label; break; }
                if (pressed != null) break;
            }
            if (pressed != null && editorPresses++ < 4) RuntimeProbe.log("DESTINY_SCREEN pressed=" + pressed);
            // Its last page picks a starting village and would move the player; leave it unanswered and
            // close the flow the way MCA's own story pages do, or MCA reopens it every tick and, in single
            // player, keeps the integrated server paused under the probe.
            if (pressed == null) { allowDestinyClosing(mc.screen); mc.setScreen(null); }
        }
        if(stage>0 && ticks%20==0) observeAppearance(mc);
        if(stage==0) {
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && ProxyPairs.isClientProxy(v)) {
                Mob candidate=ProxyPairs.clientGuardOf(v);
                if(candidate!=null && candidate.distanceTo(mc.player)<4 && isGuardActor(candidate)) { proxy=v;guard=candidate;break; }
            }
            if(proxy==null) return;
            var pair=ProxyPairs.clientPairOfActor(guard);
            RuntimeProbe.log("CLIENT_PAIR user="+mc.getUser().getName()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()
                    +" routed="+ProxyPairs.clientRoutesGuard(guard)+" mcaAppearance="+(pair!=null && pair.mcaAppearance())
                    +" generation="+(pair==null?-1:pair.generation()));
            mc.player.connection.sendCommand("mcamobcompatprobe ready");
            stage=1;ticks=0;
        } else if(stage==1 && ticks==60) {
            mc.setScreen(null);
            mc.player.setYRot(180); mc.player.setXRot(0);
        } else if(stage==1 && ticks==100) {
            capture(mc,"world");
            // Every paired actor this client can see, and how it was drawn: as its MCA person, or natively.
            for(var entry:ProxyPairs.clientPairs(mc.level).values()) {
                Entity actor=mc.level.getEntity(entry.guardId());
                if(actor==null) continue;
                RuntimeProbe.log("WORLD_DRAW user="+mc.getUser().getName()+" actor="+EntityType.getKey(actor.getType())
                        +" uuid="+actor.getUUID()+" mcaAppearance="+entry.mcaAppearance()+" asPerson="+views.getOrDefault(actor.getUUID(),0)
                        +" nativeCancelled="+substituted.getOrDefault(actor.getUUID(),0)+" native="+renders.getOrDefault(actor.getUUID(),0));
            }
            mc.gameMode.interactAt(mc.player,guard,new EntityHitResult(guard,guard.position().add(0,1,0)),InteractionHand.MAIN_HAND);
        } else if(stage==1 && ticks==160) {
            RuntimeProbe.log("INTERACTION_SCREEN " + (mc.screen==null ? "none" : mc.screen.getClass().getName()));
            capture(mc,"interaction");
            if (press(mc,"Quests")) {stage=4;ticks=0;}
            else {mc.setScreen(new PortraitScreen());stage=2;ticks=0;}
        } else if(stage==4 && ticks==80) {
            RuntimeProbe.log("QUEST_SCREEN "+(mc.screen==null?"none":mc.screen.getClass().getName()));capture(mc,"quests");
            mc.setScreen(null);mc.gameMode.interactAt(mc.player,guard,new EntityHitResult(guard,guard.position().add(0,1,0)),InteractionHand.MAIN_HAND);
            stage=5;ticks=0;
        } else if(stage==5 && ticks==60) {
            press(mc,"Talk");stage=6;ticks=0;
        } else if(stage==6 && ticks==60) {
            if (!press(mc,"Conversations") && mc.screen!=null) mc.screen.keyPressed(56,0,0);
            stage=7;ticks=0;
        } else if(stage==7 && ticks==80) {
            RuntimeProbe.log("CONVERSATION_SCREEN "+(mc.screen==null?"none":mc.screen.getClass().getName()));capture(mc,"conversations");
            mc.setScreen(new PortraitScreen());stage=2;ticks=0;
        } else if(stage==2 && ticks==60) {
            capture(mc,"portrait");
            // 0.3.0: a portrait of the hidden identity draws the person; before, it drew the guard.
            RuntimeProbe.log("RENDER_COUNTS hiddenWorld="+hiddenWorld+" hiddenPortrait="+hiddenPortrait
                    +" personPortrait="+viewPortrait+" guardPortrait="+guardPortrait);
            mc.setScreen(null);stage=9;ticks=0;
        } else if(stage==9) {
            editorStage(mc);
        } else if(stage==10) {
            nativeControlsStage(mc);
        } else if(stage==3 && ticks==60) {
            capture(mc,"world-final");
        } else if(stage==3 && ticks==80) {
            // Recruit: the real client click path, crouched, holding a sword, on a plain MCA villager.
            mc.player.connection.sendCommand("mcamobcompatprobe recruit");stage=8;ticks=0;
        } else if(stage==8) {
            enlistStage(mc);
        }
    }

    /** MCA's real editor on the player's own guard: opened by the item, edited, saved by MCA's request. */
    private static void editorStage(Minecraft mc) {
        if(ticks==1) {
            mc.player.connection.sendCommand("mcamobcompatprobe editor");
        } else if(ticks==20) {
            var hit=new EntityHitResult(guard,guard.position().add(0,1,0));
            InteractionResult result=mc.gameMode.interactAt(mc.player,guard,hit,InteractionHand.MAIN_HAND);
            if(!result.consumesAction()) mc.gameMode.interact(mc.player,guard,InteractionHand.MAIN_HAND);
        } else if(ticks>20 && ticks<=200 && !edited && mc.screen!=null && mc.screen.getClass().getName().endsWith("VillagerEditorScreen")) {
            Object page=field(mc.screen,"page");
            if("loading".equals(page)) return;
            Object target=field(mc.screen,"villagerUUID");
            Object preview=field(mc.screen,"villager");
            float size=0.05F+0.1F*Math.floorMod(mc.getUser().getName().hashCode(),8);
            boolean set=setSize(preview,size);
            try { mc.screen.getClass().getMethod("syncVillagerData").invoke(mc.screen); }
            catch (ReflectiveOperationException e) { RuntimeProbe.log("EDITOR_SAVED FAIL "+e); }
            RuntimeProbe.log("EDITOR_SAVED user="+mc.getUser().getName()+" character="+target+" isGuardsPerson="
                    +proxy.getUUID().equals(target)+" size="+size+" set="+set);
            capture(mc,"editor");
            mc.setScreen(null);edited=true;ticks=200;
        } else if(ticks==201 && !edited) {
            RuntimeProbe.log("EDITOR_SAVED FAIL user="+mc.getUser().getName()+" no editor opened; screen="
                    +(mc.screen==null?"none":mc.screen.getClass().getName()));
            mc.setScreen(null);
        } else if(ticks==230) {
            mc.player.connection.sendCommand("mcamobcompatprobe editorcheck");
        } else if(ticks==260) {
            stage=10;ticks=0;
        }
    }

    /**
     * A crouching click on a native NPC opens its own mod's screen, not MCA's: Guard Villagers' inventory
     * on the guard, and Recruits' own screen on the adopted bowman.
     */
    private static void nativeControlsStage(Minecraft mc) {
        if(ticks==1) {
            mc.player.connection.sendCommand("mcamobcompatprobe emptyhand");
            mc.player.connection.sendCommand("mcamobcompatprobe ai true");
        } else if(ticks==20) {
            holdSneak(mc,true);clicked=false;
        } else if(ticks>=30 && ticks<=60 && !clicked) {
            if(mc.player.isCrouching() || ticks==60) {click(mc,guard);clicked=true;}
        } else if(ticks==80) {
            RuntimeProbe.log("NATIVE_SCREEN user="+mc.getUser().getName()+" actor=guardvillagers:guard screen="
                    +(mc.screen==null?"none":mc.screen.getClass().getName()));
            capture(mc,"native");
        } else if(ticks==90) {
            if(mc.player.containerMenu!=mc.player.inventoryMenu) mc.player.closeContainer(); else mc.setScreen(null);
            clicked=false;
        } else if(ticks==100) {
            Mob bowman=null;
            for(var entry:ProxyPairs.clientPairs(mc.level).values())
                if(mc.level.getEntity(entry.guardId()) instanceof Mob m && "recruits".equals(EntityType.getKey(m.getType()).getNamespace())
                        && m.distanceTo(mc.player)<5) { bowman=m; break; }
            if(bowman!=null) click(mc,bowman);
        } else if(ticks==140) {
            RuntimeProbe.log("NATIVE_SCREEN user="+mc.getUser().getName()+" actor=recruits screen="
                    +(mc.screen==null?"none":mc.screen.getClass().getName()));
            capture(mc,"native-recruit");
        } else if(ticks==150) {
            holdSneak(mc,false);
            if(mc.player.containerMenu!=mc.player.inventoryMenu) mc.player.closeContainer(); else mc.setScreen(null);
            mc.player.connection.sendCommand("mcamobcompatprobe ai false");
            clicked=false;stage=3;ticks=0;
        }
    }

    /** An MCA villager becomes a real guard through the recruit gesture, and goes home again. */
    private static void enlistStage(Minecraft mc) {
        if(ticks==40) {
            recruit=nearestRecruitable(mc);
            if(recruit==null){RuntimeProbe.log("RECRUIT_CLIENT FAIL no villager to recruit");finish(mc);return;}
            recruitId=recruit.getUUID();holdSneak(mc,true);
        } else if(ticks>=50 && ticks<=85 && !clicked) {
            // Click once the crouch has actually taken hold; a paused single-player tick can delay it.
            if(mc.player.isCrouching() || ticks==85) {click(mc,recruit);clicked=true;}
        } else if(ticks==90) {
            holdSneak(mc,false);
            Entity seen=mc.level.getEntity(recruit.getId());
            boolean present=seen==recruit && !recruit.isRemoved() && recruitId.equals(recruit.getUUID());
            enlisted=ProxyPairs.clientGuardOf(recruit);
            boolean hidden=ProxyPairs.isClientProxy(recruit);
            RuntimeProbe.log("RECRUIT_CLIENT "+(present&&hidden&&enlisted!=null&&isGuardActor(enlisted)?"PASS":"FAIL")
                    +" user="+mc.getUser().getName()+" person="+recruitId+" present="+present+" hidden="+hidden
                    +" actor="+(enlisted==null?"none":EntityType.getKey(enlisted.getType())+" "+enlisted.getUUID())
                    +" actorHand="+(enlisted==null?"":enlisted.getMainHandItem().getHoverName().getString()));
            mc.player.connection.sendCommand("mcamobcompatprobe recruitcheck first");
            if(enlisted!=null) { views.remove(enlisted.getUUID()); substituted.remove(enlisted.getUUID()); }
        } else if(ticks==130) {
            if(enlisted!=null) RuntimeProbe.log("RECRUIT_DRAWN "+(views.getOrDefault(enlisted.getUUID(),0)>0?"PASS":"FAIL")
                    +" user="+mc.getUser().getName()+" actor="+enlisted.getUUID()+" asPerson="+views.getOrDefault(enlisted.getUUID(),0)
                    +" nativeCancelled="+substituted.getOrDefault(enlisted.getUUID(),0));
            capture(mc,"recruit");
        } else if(ticks==140) {
            mc.player.connection.sendCommand("mcamobcompatprobe resword");clicked=false;
        } else if(ticks==160) {
            holdSneak(mc,true);
        } else if(ticks==165) {
            // The enlisted guard has its own AI and wanders, and 1.21.1's interaction reach is short:
            // walk up to it just before the click.
            mc.player.connection.sendCommand("mcamobcompatprobe approach");
        } else if(ticks>=170 && ticks<=195 && !clicked) {
            // A player clicks the guard they can see; the person behind it is never enlisted twice.
            if(enlisted!=null && (mc.player.isCrouching() || ticks==195)) {click(mc,enlisted);clicked=true;}
        } else if(ticks==200) {
            holdSneak(mc,false);
            RuntimeProbe.log("SECOND_CLICK_SCREEN user="+mc.getUser().getName()+" screen="+(mc.screen==null?"none":mc.screen.getClass().getName()));
            if(mc.player.containerMenu!=mc.player.inventoryMenu) mc.player.closeContainer(); else mc.setScreen(null);
            mc.player.connection.sendCommand("mcamobcompatprobe recruitcheck second");
        } else if(ticks==210) {
            // Turn to another player's enlisted guard, if there is one, so its drawing can be counted too.
            for(var entry:ProxyPairs.clientPairs(mc.level).values()) {
                Entity actor=mc.level.getEntity(entry.guardId());
                if(actor instanceof Mob mob && mob!=guard && mob!=enlisted && isGuardActor(mob) && actor.distanceTo(mc.player)<16) {
                    mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,mob.position().add(0,1,0));
                    observed=mob.getUUID();views.remove(observed);break;
                }
            }
        } else if(ticks==240) {
            RuntimeProbe.log("RECRUIT_OBSERVER user="+mc.getUser().getName()+" lookedAt="+observed
                    +(observed==null?"":" asPerson="+views.getOrDefault(observed,0)));
            capture(mc,"recruit-final");
        } else if(ticks==241) {
            // A look-targeted command: the guard is brought beside the player, clear of the others.
            mc.player.connection.sendCommand("mcamobcompatprobe present");
        } else if(ticks>241 && ticks<=250) {
            // The enlisted guard has its own AI and walks: keep the crosshair on it, as a player does, and
            // run the role action while looking at it.
            if(enlisted!=null) mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    enlisted.getBoundingBox().getCenter());
            if(ticks==250) {
                renders.remove(recruitId);
                mc.player.connection.sendCommand("mcamobcompat character release");
            }
        } else if(ticks==300) {
            boolean present=mc.level.getEntity(recruit.getId())==recruit && !recruit.isRemoved();
            boolean visible=present && !recruit.isInvisible() && !ProxyPairs.isClientProxy(recruit);
            int drawn=renders.getOrDefault(recruitId,0);
            RuntimeProbe.log("RELEASE_CLIENT "+(present&&visible&&drawn>0?"PASS":"FAIL")+" user="+mc.getUser().getName()
                    +" person="+recruitId+" present="+present+" visible="+visible+" rendered="+drawn
                    +" actorGone="+(enlisted==null || enlisted.isRemoved()));
            capture(mc,"released");
            mc.player.connection.sendCommand("mcamobcompatprobe releasecheck");
        } else if(ticks==340) {
            finish(mc);
        }
    }

    private static void finish(Minecraft mc) {
        observeAppearance(mc);
        RuntimeProbe.log("CLIENT_FINISHED " + mc.getUser().getName()); mc.stop();
    }

    /** Logs every change in any character's synced appearance this client can see: its revision and SIZE gene. */
    private static void observeAppearance(Minecraft mc) {
        for(var entry:ProxyPairs.clientPairs(mc.level).values()) {
            Entity identity=mc.level.getEntity(entry.proxyId());
            String now=entry.appearanceRevision()+"/"+(identity instanceof Villager v ? RuntimeProbe.gene(v) : "unloaded");
            String before=appearance.put(entry.character(),now);
            if(before!=null && !before.equals(now))
                RuntimeProbe.log("APPEARANCE user="+mc.getUser().getName()+" character="+entry.character()+" from="+before+" to="+now);
        }
    }

    static boolean isGuardActor(Mob mob) {
        return "guardvillagers:guard".equals(String.valueOf(EntityType.getKey(mob.getType())));
    }

    /** A field of MCA's editor screen, found by name up its class hierarchy. */
    static Object field(Object owner,String name) {
        for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass()) {
            try { var f=c.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException absent) { }
            catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    /** Sets the SIZE gene on MCA's editor preview villager, which is what MCA's save sends. */
    static boolean setSize(Object villager,float size) {
        try {
            Object genetics=villager.getClass().getMethod("getGenetics").invoke(villager);
            Object gene=genetics.getClass().getField("SIZE").get(null);
            genetics.getClass().getMethod("setGene",gene.getClass(),float.class).invoke(genetics,gene,size);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RuntimeProbe.log("EDITOR_GENE unavailable: "+e);
            return false;
        }
    }

    /**
     * Holds the sneak key the way a player does, at the input the player reads each tick, so the
     * crouching pose, the client-side click event and the shift state sent to the server all agree.
     * (A key mapping set from here is released again by the unfocused headless window.)
     */
    static void holdSneak(Minecraft mc,boolean held) {
        sneaking=held;
        if(!(mc.player.input instanceof HeldSneak)) mc.player.input=new HeldSneak(mc.options);
    }
    static final class HeldSneak extends net.minecraft.client.player.KeyboardInput {
        HeldSneak(net.minecraft.client.Options options) {super(options);}
        @Override public void tick(boolean slow,float factor) {super.tick(slow,factor); if(sneaking) this.shiftKeyDown=true;}
    }
    /** The nearest plain MCA villager -- not an identity, not a guard -- within five blocks. */
    static Villager nearestRecruitable(Minecraft mc) {
        Villager best=null;
        for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && !ProxyPairs.isClientProxy(v)
                && dev.otectus.mcamobcompat.mca.McaEntities.isMcaVillager(v) && v.distanceTo(mc.player)<5 && !v.isInvisible()
                && !"mca:guard".equals(String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession())))
                && (best==null || v.distanceTo(mc.player)<best.distanceTo(mc.player))) best=v;
        return best;
    }
    /** Vanilla's own entity right-click: the positional interaction first, the plain one if it passed. */
    static void click(Minecraft mc,Entity target) {
        var hit=new EntityHitResult(target,target.position().add(0,1,0));
        InteractionResult result=mc.gameMode.interactAt(mc.player,target,hit,InteractionHand.MAIN_HAND);
        if(!result.consumesAction()) result=mc.gameMode.interact(mc.player,target,InteractionHand.MAIN_HAND);
        RuntimeProbe.log("CLICK user="+mc.getUser().getName()+" target="+EntityType.getKey(target.getType())+" result="+result
                +" crouching="+mc.player.isCrouching());
    }
    /** MCA's {@code DestinyManager.allowClosing()}, found by name from the screen's own package. */
    static void allowDestinyClosing(net.minecraft.client.gui.screens.Screen screen) {
        String name = screen.getClass().getName();
        int gui = name.indexOf(".client.gui.");
        if (gui < 0) return;
        try {
            Object manager = Class.forName(name.substring(0, gui) + ".MCAClient").getMethod("getDestinyManager").invoke(null);
            manager.getClass().getMethod("allowClosing").invoke(manager);
        } catch (ReflectiveOperationException e) {
            RuntimeProbe.log("DESTINY_SCREEN allowClosing unavailable: " + e);
        }
    }
    static boolean press(Minecraft mc,String text) {
        if (mc.screen == null) return false;
        for(var child:mc.screen.children()) if(child instanceof net.minecraft.client.gui.components.Button button) {
            RuntimeProbe.log("BUTTON "+button.getMessage().getString()+" active="+button.active);
            if(button.active && button.getMessage().getString().equalsIgnoreCase(text)) {button.onPress();return true;}
        }
        RuntimeProbe.log("BUTTON_NOT_FOUND "+text);return false;
    }
    static void capture(Minecraft mc,String label) {
        Screenshot.grab(mc.gameDirectory, "probe-"+label+".png",mc.getMainRenderTarget(), message->RuntimeProbe.log("SCREENSHOT "+message.getString()));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true)
    public static void rendered(RenderLivingEvent.Pre<?,?> e) {
        var mc=Minecraft.getInstance();
        var entity=e.getEntity();
        if(!e.isCanceled()) renders.merge(entity.getUUID(),1,Integer::sum);
        if(drawingPortrait) {
            // Inside the probe's portrait: the hidden identity is cancelled, and what is drawn in its place.
            if(entity==proxy && e.isCanceled()) hiddenPortrait++;
            else if(entity==guard && !e.isCanceled()) guardPortrait++;
            else if(entity instanceof Villager v && !e.isCanceled() && mc.level!=null && mc.level.getEntity(v.getId())!=v) viewPortrait++;
            return;
        }
        if(entity==proxy && e.isCanceled()) hiddenWorld++;
        if(mc.level==null) return;
        if(entity instanceof Mob mob && e.isCanceled() && ProxyPairs.clientPairOfActor(mob)!=null)
            substituted.merge(mob.getUUID(),1,Integer::sum);
        // A detached MCA villager -- in no level's entity list -- is a person drawn for an actor, or MCA's own
        // editor preview. Outside any screen and at an actor's exact position, it is that actor's person.
        if(entity instanceof Villager v && !e.isCanceled() && mc.level.getEntity(v.getId())!=v) {
            if(mc.screen!=null && !(mc.screen instanceof PortraitScreen)) return;
            for(var entry:ProxyPairs.clientPairs(mc.level).values()) {
                Entity actor=mc.level.getEntity(entry.guardId());
                if(actor!=null && actor.position().distanceToSqr(v.position())<1.0E-4) { views.merge(actor.getUUID(),1,Integer::sum); break; }
            }
        }
    }
    static final class PortraitScreen extends Screen {
        PortraitScreen() {super(Component.literal("Proxy portrait validation"));}
        @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            graphics.fill(0,0,width,height,0xFF17202A);
            graphics.drawCenteredString(font,"Production JAR: the person in the portrait",width/2,25,0xFFFFFF);
            drawingPortrait=true;
            try { InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,width/2,height-20,70,width/2-mouseX,height/2-mouseY,proxy); }
            finally { drawingPortrait=false; }
        }
        @Override public boolean isPauseScreen() {return false;}
    }
}
