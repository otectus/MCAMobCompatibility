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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="mcamobcompat_probe", value=Dist.CLIENT)
public final class ClientProbe {
    private static int ticks, stage;
    private static Villager proxy;
    private static Mob guard;
    private static int hiddenWorld, hiddenPortrait, guardPortrait, recruitRendered;
    private static Villager recruit;
    private static boolean sneaking, clicked;
    private static int editorPresses;
    private static String lastScreen="";
    private static java.util.UUID observed;
    private static java.util.UUID recruitId;
    private static final java.util.Map<java.util.UUID,Integer> renders=new java.util.HashMap<>();
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
        // so the probe's screenshots show the world rather than the editor.
        if (mc.screen != null && (mc.screen.getClass().getName().endsWith("DestinyScreen")
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
        if(stage==0) {
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && ProxyPairs.isClientProxy(v)) {
                Mob candidate=ProxyPairs.clientGuardOf(v);
                if(candidate!=null && candidate.distanceTo(mc.player)<4) { proxy=v;guard=candidate;break; }
            }
            if(proxy==null) return;
            RuntimeProbe.log("CLIENT_PAIR user="+mc.getUser().getName()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()+" routed="+ProxyPairs.clientRoutesGuard(guard));
            mc.player.connection.sendCommand("mcamobcompatprobe ready");
            stage=1;ticks=0;
        } else if(stage==1 && ticks==60) {
            mc.setScreen(null);
            mc.player.setYRot(180); mc.player.setXRot(0);
        } else if(stage==1 && ticks==100) {
            capture(mc,"world");
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
            RuntimeProbe.log("RENDER_COUNTS hiddenWorld="+hiddenWorld+" hiddenPortrait="+hiddenPortrait+" guardPortrait="+guardPortrait);
            mc.setScreen(null);stage=3;ticks=0;
        } else if(stage==3 && ticks==60) {
            capture(mc,"world-final");
        } else if(stage==3 && ticks==80) {
            // Recruit: the real client click path, crouched, holding a sword, on a plain MCA villager.
            mc.player.connection.sendCommand("mcamobcompatprobe recruit");stage=8;ticks=0;
        } else if(stage==8 && ticks==40) {
            recruit=nearestRecruitable(mc);
            if(recruit==null){RuntimeProbe.log("RECRUIT_CLIENT FAIL no villager to recruit");stage=3;ticks=99;return;}
            recruitId=recruit.getUUID();holdSneak(mc,true);
        } else if(stage==8 && ticks>=50 && ticks<=85 && !clicked) {
            // Click once the crouch has actually taken hold; a paused single-player tick can delay it.
            if(mc.player.isCrouching() || ticks==85) {click(mc,recruit);clicked=true;}
        } else if(stage==8 && ticks==90) {
            holdSneak(mc,false);
            Entity seen=mc.level.getEntity(recruit.getId());
            boolean present=seen==recruit && !recruit.isRemoved() && recruitId.equals(recruit.getUUID());
            String profession=String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(recruit.getVillagerData().getProfession()));
            RuntimeProbe.log("RECRUIT_CLIENT "+(present&&recruitRendered>0&&"mca:guard".equals(profession)?"PASS":"FAIL")
                    +" user="+mc.getUser().getName()+" villager="+recruitId+" present="+present+" removed="+recruit.getRemovalReason()
                    +" rendered="+recruitRendered+" profession="+profession+" mainhand="+recruit.getMainHandItem().getHoverName().getString());
            capture(mc,"recruit");
            mc.player.connection.sendCommand("mcamobcompatprobe recruitcheck first");
        } else if(stage==8 && ticks==110) {
            mc.player.connection.sendCommand("mcamobcompatprobe resword");clicked=false;
        } else if(stage==8 && ticks==130) {
            holdSneak(mc,true);
        } else if(stage==8 && ticks>=140 && ticks<=165 && !clicked) {
            if(mc.player.isCrouching() || ticks==165) {click(mc,recruit);clicked=true;}
        } else if(stage==8 && ticks==170) {
            holdSneak(mc,false);
            mc.player.connection.sendCommand("mcamobcompatprobe recruitcheck second");
        } else if(stage==8 && ticks==200) {
            // Turn to another player's recruit, if there is one, so its rendering can be counted too.
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && !ProxyPairs.isClientProxy(v)
                    && !v.getUUID().equals(recruitId) && isGuard(v)) {
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,v.position().add(0,1,0));
                observed=v.getUUID();break;
            }
            renders.clear();
        } else if(stage==8 && ticks==260) {
            // Every recruited villager this client holds: its own, and on a shared server the other player's.
            StringBuilder seen=new StringBuilder();
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && !ProxyPairs.isClientProxy(v) && isGuard(v))
                seen.append(' ').append(v.getUUID()).append(v.getUUID().equals(recruitId)?"(own)":"(other)")
                        .append(":removed=").append(v.isRemoved()).append(":rendered=").append(renders.getOrDefault(v.getUUID(),0));
            RuntimeProbe.log("RECRUIT_OBSERVER user="+mc.getUser().getName()+" lookedAt="+observed+seen);
            capture(mc,"recruit-final");
        } else if(stage==8 && ticks==300) {
            RuntimeProbe.log("CLIENT_FINISHED " + mc.getUser().getName()); mc.stop();
        } else if(stage==3 && ticks==100) {
            RuntimeProbe.log("CLIENT_FINISHED " + mc.getUser().getName()); mc.stop();
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
    static boolean isGuard(Villager v) {
        return "mca:guard".equals(String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession())));
    }
    /** The nearest plain MCA villager -- not a proxy, not already a guard -- within five blocks. */
    static Villager nearestRecruitable(Minecraft mc) {
        Villager best=null;
        for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && !ProxyPairs.isClientProxy(v)
                && dev.otectus.mcamobcompat.mca.McaEntities.isMcaVillager(v) && v.distanceTo(mc.player)<5
                && !"mca:guard".equals(String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession())))
                && (best==null || v.distanceTo(mc.player)<best.distanceTo(mc.player))) best=v;
        return best;
    }
    /** Vanilla's own entity right-click: the positional interaction first, the plain one if it passed. */
    static void click(Minecraft mc,Villager target) {
        var hit=new EntityHitResult(target,target.position().add(0,1,0));
        InteractionResult result=mc.gameMode.interactAt(mc.player,target,hit,InteractionHand.MAIN_HAND);
        if(!result.consumesAction()) result=mc.gameMode.interact(mc.player,target,InteractionHand.MAIN_HAND);
        RuntimeProbe.log("RECRUIT_CLICK user="+mc.getUser().getName()+" result="+result+" crouching="+mc.player.isCrouching());
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
        if(!e.isCanceled()) renders.merge(e.getEntity().getUUID(),1,Integer::sum);
        if(e.getEntity()==recruit && !e.isCanceled()) recruitRendered++;
        if(e.getEntity()==proxy && e.isCanceled()) {
            if(Minecraft.getInstance().screen instanceof PortraitScreen) hiddenPortrait++; else hiddenWorld++;
        }
        if(e.getEntity()==guard && Minecraft.getInstance().screen instanceof PortraitScreen) guardPortrait++;
    }
    static final class PortraitScreen extends Screen {
        PortraitScreen() {super(Component.literal("Proxy portrait validation"));}
        @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            graphics.fill(0,0,width,height,0xFF17202A);
            graphics.drawCenteredString(font,"Production JAR: exact guard portrait",width/2,25,0xFFFFFF);
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,width/2,height-20,70,width/2-mouseX,height/2-mouseY,proxy);
        }
        @Override public boolean isPauseScreen() {return false;}
    }
}
