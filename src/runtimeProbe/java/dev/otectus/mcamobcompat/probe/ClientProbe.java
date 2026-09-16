package dev.otectus.mcamobcompat.probe;

import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

@EventBusSubscriber(modid="mcamobcompat_probe", value=Dist.CLIENT)
public final class ClientProbe {
    private static int ticks, stage;
    private static Villager proxy;
    private static Mob guard;
    private static int hiddenWorld, hiddenPortrait, guardPortrait;
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        var mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null) return;
        ticks++;
        if (!net.neoforged.fml.ModList.get().isLoaded("guardvillagers") && ticks == 100) {
            RuntimeProbe.log("MINIMAL_CLIENT_READY " + mc.getUser().getName());capture(mc,"minimal");mc.stop();return;
        }
        if (mc.screen != null && mc.screen.getClass().getName().endsWith("DestinyScreen")) {
            for (var child : mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button
                    && button.getMessage().getString().equals("Accept")) { button.onPress(); break; }
        }
        if(stage==0) {
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof Villager v && ProxyPairs.isProxy(v)) {
                Mob candidate=ProxyPairs.guardOf(v);
                if(candidate!=null && candidate.distanceTo(mc.player)<4) { proxy=v;guard=candidate;break; }
            }
            if(proxy==null) return;
            RuntimeProbe.log("CLIENT_PAIR user="+mc.getUser().getName()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()+" routed="+ProxyPairs.routesGuard(guard));
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
        } else if(stage==3 && ticks==100) {
            RuntimeProbe.log("CLIENT_FINISHED " + mc.getUser().getName()); mc.stop();
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
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,width/2-60,45,width/2+60,height-20,70,0.0625F,mouseX,mouseY,proxy);
        }
        @Override public boolean isPauseScreen() {return false;}
    }
}
