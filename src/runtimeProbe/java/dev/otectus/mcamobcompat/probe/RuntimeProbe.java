package dev.otectus.mcamobcompat.probe;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.bridge.*;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.*;

@Mod("mcamobcompat_probe")
@EventBusSubscriber(modid = "mcamobcompat_probe")
public final class RuntimeProbe {
    private static final Set<UUID> prepared = new HashSet<>();
    private static final Map<UUID, UUID> pairIds = new HashMap<>();
    /** Each player's own guard, for the editor check. */
    private static final Map<UUID, UUID> guards = new HashMap<>();
    private static int nextSlot = 2;
    private static final Properties expected = new Properties();
    static {
        var path = java.nio.file.Path.of("probe-pairs.properties");
        if (java.nio.file.Files.exists(path)) try (var reader=java.nio.file.Files.newBufferedReader(path)) { expected.load(reader); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    public static void log(String message) { McaMobCompat.LOGGER.info("RUNTIME_PROBE {}", message); }
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(net.minecraft.commands.Commands.literal("mcamobcompatprobe")
            .then(net.minecraft.commands.Commands.literal("ready").executes(c -> {
                log("CLIENT_READY " + c.getSource().getTextName()); return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("recruit").executes(c -> recruitSetup(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("resword").executes(c -> {
                var p=c.getSource().getPlayerOrException(); p.setItemInHand(InteractionHand.MAIN_HAND, sword("Probe sword 2")); return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("editor").executes(c -> {
                var p=c.getSource().getPlayerOrException();
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(McaMobCompat.location("mca","villager_editor"))));
                return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("emptyhand").executes(c -> {
                c.getSource().getPlayerOrException().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("ai")
                .then(net.minecraft.commands.Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                    .executes(c -> {
                        // Guard Villagers opens its inventory only for a guard with AI; the probe's stands still.
                        var p=c.getSource().getPlayerOrException(); UUID id=guards.get(p.getUUID());
                        if(id!=null && p.serverLevel().getEntity(id) instanceof Mob g)
                            g.setNoAi(!com.mojang.brigadier.arguments.BoolArgumentType.getBool(c,"on"));
                        return 1;
                    })))
            .then(net.minecraft.commands.Commands.literal("approach").executes(c -> approach(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("present").executes(c -> present(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("editorcheck").executes(c -> editorCheck(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("releasecheck").executes(c -> releaseCheck(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("recruitcheck")
                .then(net.minecraft.commands.Commands.argument("phase", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> recruitCheck(c.getSource().getPlayerOrException(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "phase"))))));
    }
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        var server = e.getServer();
        if (server.getTickCount() % 20 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!prepared.add(p.getUUID())) continue;
            var level = p.serverLevel(); int x = nextSlot++ * 8;
            for (int dx=-5;dx<13;dx++) for(int dz=-5;dz<8;dz++) {
                level.setBlockAndUpdate(new BlockPos(x+dx, 99, dz), Blocks.STONE.defaultBlockState());
                for (int y=100;y<105;y++) level.setBlockAndUpdate(new BlockPos(x+dx,y,dz), Blocks.AIR.defaultBlockState());
            }
            p.setGameMode(GameType.CREATIVE); p.teleportTo(x+.5,100,3.0); p.setYRot(180); p.setXRot(0);
            var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("guardvillagers:guard")).orElse(null);
            if(type==null) { log("MINIMAL_PLAYER_JOIN " + p.getName().getString()); continue; }
            Mob guard;
            String saved = expected.getProperty(p.getName().getString());
            if (saved != null) {
                level.getChunk(0,0); level.getChunk(0,-1); level.getChunk(-1,0); level.getChunk(-1,-1);
                String[] fields = saved.split(" ");
                guard=(Mob)level.getEntity(UUID.fromString(fields[0]));
                if (guard==null) { prepared.remove(p.getUUID()); continue; }
                p.teleportTo(guard.getX(),guard.getY(),guard.getZ()+2.5);
                Villager old=GuardProxy.proxyOf(guard);
                if (old==null || !old.getUUID().toString().equals(fields[1])) throw new IllegalStateException("Restart changed proxy UUID");
                if (MobCompatibilityAPI.getGuardIdentity(guard).orElseThrow().hearts(p.getUUID()) != Integer.parseInt(fields[2]))
                    throw new IllegalStateException("Restart lost or duplicated hearts");
                log("RESTART_PRESERVED player="+p.getName().getString()+" guard="+fields[0]+" proxy="+fields[1]+" hearts="+fields[2]);
            } else {
                guard=(Mob)type.create(level);guard.moveTo(x+.5,100,.5,0,0);guard.setNoAi(true);guard.tickCount=60;
                guard.addTag("port_probe_guard");level.addFreshEntity(guard);
            }
            Villager proxy=GuardProxy.create(level,guard);
            if(proxy==null) throw new IllegalStateException("Runtime proxy spawn failed");
            var identity=MobCompatibilityAPI.getGuardIdentity(guard).orElseThrow();
            int before=identity.hearts(p.getUUID());identity.modHearts(p.getUUID(),7);
            if(identity.hearts(p.getUUID())!=before+7 || McaBinding.hearts(proxy,p.getUUID()).orElseThrow()!=before+7)
                throw new IllegalStateException("Live MCA hearts accounting failed");
            GuardIdentityStore.synchronize(guard);
            if(identity.hearts(p.getUUID())!=before+7) throw new IllegalStateException("Heartbeat duplicated hearts");
            try {
                Object brain=proxy.getClass().getMethod("getVillagerBrain").invoke(proxy);
                Class<?> move=brain.getClass().getMethod("getMoveState").getReturnType();
                Object follow=java.util.Arrays.stream(move.getEnumConstants()).filter(v -> v.toString().equals("FOLLOW")).findFirst().orElseThrow();
                Object stay=java.util.Arrays.stream(move.getEnumConstants()).filter(v -> v.toString().equals("STAY")).findFirst().orElseThrow();
                Object walk=java.util.Arrays.stream(move.getEnumConstants()).filter(v -> v.toString().equals("MOVE")).findFirst().orElseThrow();
                var setter=brain.getClass().getMethod("setMoveState",move,net.minecraft.world.entity.player.Player.class);
                // Guard Villagers' getOwner() admits only a Hero of the Village while its followHero
                // option is on (its default), on both loaders; 1.20.1's isFollowing() also needs it.
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE, 6000));
                // 0.3.0 applies an MCA order once per change: the pair's first heartbeat takes MCA's state as
                // it stands as the starting point, and only the orders given after it are carried out.
                proxy.tickCount=20; GuardProxySync.tick(level,guard,proxy);
                setter.invoke(brain,follow,p);
                GuardProxySync.noteInteraction(guard, UUID.randomUUID());
                GuardProxySync.tick(level,guard,proxy);
                if(!p.getUUID().equals(guard.getClass().getMethod("getOwnerId").invoke(guard))
                        || !Boolean.TRUE.equals(guard.getClass().getMethod("isFollowing").invoke(guard)))
                    throw new IllegalStateException("Follow chose the last click instead of MCA's authorized player: moveState="
                            + McaBinding.moveState(proxy) + " memory=" + dev.otectus.mcamobcompat.mca.McaMemories.followTarget(proxy)
                            + " owner=" + guard.getClass().getMethod("getOwnerId").invoke(guard)
                            + " following=" + guard.getClass().getMethod("isFollowing").invoke(guard) + " player=" + p.getUUID());
                setter.invoke(brain,stay,p); GuardProxySync.tick(level,guard,proxy);
                if(!Boolean.TRUE.equals(guard.getClass().getMethod("isPatrolling").invoke(guard))) throw new IllegalStateException("Stay did not set patrol");
                setter.invoke(brain,walk,p); GuardProxySync.tick(level,guard,proxy);
                if(Boolean.TRUE.equals(guard.getClass().getMethod("isFollowing").invoke(guard))
                        || Boolean.TRUE.equals(guard.getClass().getMethod("isPatrolling").invoke(guard))) throw new IllegalStateException("Move did not clear stay/follow");
                log("MOVEMENT_VERIFIED player="+p.getName().getString());
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
            pairIds.put(guard.getUUID(),proxy.getUUID());
            guards.put(p.getUUID(),guard.getUUID());
            log("PAIR player="+p.getName().getString()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()+" hearts="+identity.hearts(p.getUUID()));
            // The other native mods' NPCs, adopted where they stand: each gets its own MCA person.
            // In front of the player, inside the camera's view, clear of the villager the recruit stage adds.
            nativeActor(level, "recruits:bowman", x-1.5, .5, p);
            nativeActor(level, "workers:farmer", x-1.0, -2.5, p);
        }
    }
    /** An unowned native NPC beside the player's guard, standing still; its MCA person comes from the bridge. */
    private static void nativeActor(net.minecraft.server.level.ServerLevel level, String id, double x, double z, ServerPlayer p) {
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(id)).orElse(null);
        if(type==null) return;
        Mob actor=(Mob)type.create(level);
        try { actor.getClass().getMethod("initSpawn").invoke(actor); } catch (ReflectiveOperationException ignored) { }
        actor.moveTo(x,100,z,0,0); actor.setNoAi(true); actor.tickCount=60; actor.addTag("port_probe_native");
        level.addFreshEntity(actor);
        log("NATIVE_ACTOR player="+p.getName().getString()+" type="+id+" actor="+actor.getUUID());
    }
    private static final Map<UUID, UUID> recruits = new HashMap<>();
    private static ItemStack sword(String name) {
        ItemStack stack=new ItemStack(Items.IRON_SWORD); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }
    /** A plain adult MCA villager in front of the player, and a named sword in a survival player's hand. */
    private static int recruitSetup(ServerPlayer p) {
        var level=p.serverLevel();
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("mca","male_villager")).orElseThrow();
        Villager villager=(Villager)type.create(level);
        villager.moveTo(p.getX()+1.5,p.getY(),p.getZ()-2.0,0,0);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(villager,level,level.getCurrentDifficultyAt(villager.blockPosition()),
                net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED,null);
        villager.setAge(0); villager.setNoAi(true); villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        level.addFreshEntity(villager);
        p.setGameMode(GameType.SURVIVAL); p.setYRot(180); p.setXRot(20);
        p.connection.teleport(p.getX(),p.getY(),p.getZ(),180,20);
        p.setItemInHand(InteractionHand.MAIN_HAND, sword("Probe sword 1"));
        recruits.put(p.getUUID(), villager.getUUID());
        log("RECRUIT_SETUP player="+p.getName().getString()+" villager="+villager.getUUID());
        return 1;
    }
    /**
     * What the server holds after the click. 0.3.0's default recruitment makes the same person the MCA
     * identity of a real Guard Villagers guard: the person hidden behind it, the sword moved to the guard
     * exactly once. The second click, on that guard, opens its own inventory and enlists nobody again.
     */
    private static int recruitCheck(ServerPlayer p, String phase) {
        UUID id=recruits.get(p.getUUID());
        var entity=id==null?null:p.serverLevel().getEntity(id);
        boolean alive=entity instanceof Villager v && v.isAlive() && !v.isRemoved();
        Villager v=alive?(Villager)entity:null;
        Mob guard=v==null?null:GuardProxy.guardOf(v);
        boolean hidden=v!=null && GuardProxy.isProxy(v) && v.isInvisible();
        String guardType=guard==null?"none":String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(guard.getType()));
        String profession=v==null?"none":String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()));
        String guardHand=guard==null?"":guard.getMainHandItem().getHoverName().getString();
        String playerHand=p.getMainHandItem().isEmpty()?"empty":p.getMainHandItem().getHoverName().getString();
        // Every actor near the person that names it as its identity: exactly one, however often it is clicked.
        long actors=v==null?0:p.serverLevel().getEntitiesOfClass(Mob.class, v.getBoundingBox().inflate(8.0D),
                m -> m.getPersistentData().hasUUID(GuardProxy.KEY_PROXY)
                        && v.getUUID().equals(m.getPersistentData().getUUID(GuardProxy.KEY_PROXY))).size();
        boolean base=alive && hidden && "guardvillagers:guard".equals(guardType) && "mca:guard".equals(profession)
                && "Probe sword 1".equals(guardHand) && actors==1;
        boolean ok="first".equals(phase) ? base && "empty".equals(playerHand) : base && "Probe sword 2".equals(playerHand);
        log(("first".equals(phase)?"RECRUIT_SERVER ":"ALREADY_ENLISTED ")+(ok?"PASS":"FAIL")+" player="+p.getName().getString()
                +" person="+id+" alive="+alive+" hidden="+hidden+" actor="+guardType+" actors="+actors+" profession="+profession
                +" guardHand="+guardHand+" playerHand="+playerHand);
        return 1;
    }
    /**
     * Walks the player up to the guard their enlisted person now serves as, facing it: a real guard has its
     * own AI and wanders, and the next interaction must be within reach. Nothing about the guard changes.
     */
    private static int approach(ServerPlayer p) {
        UUID id=recruits.get(p.getUUID());
        var entity=id==null?null:p.serverLevel().getEntity(id);
        Mob guard=entity instanceof Villager v ? GuardProxy.guardOf(v) : null;
        if(guard==null) { log("APPROACH none player="+p.getName().getString()); return 0; }
        var away=p.position().subtract(guard.position()); away=new net.minecraft.world.phys.Vec3(away.x,0,away.z);
        away=away.lengthSqr()<1.0E-4 ? new net.minecraft.world.phys.Vec3(0,0,2) : away.normalize().scale(2.0);
        double x=guard.getX()+away.x, z=guard.getZ()+away.z;
        float yaw=(float)(net.minecraft.util.Mth.atan2(guard.getZ()-z, guard.getX()-x)*(180F/Math.PI))-90F;
        p.connection.teleport(x, guard.getY(), z, yaw, 10F);
        return 1;
    }
    /**
     * Has the player's enlisted guard stand still beside the player, clear of the other NPCs, with the
     * player facing it: a look-targeted command needs an unobstructed look, and the guard's own AI would
     * otherwise walk it behind its neighbours. Only its position and its AI flag change.
     */
    private static int present(ServerPlayer p) {
        UUID id=recruits.get(p.getUUID());
        var entity=id==null?null:p.serverLevel().getEntity(id);
        Mob guard=entity instanceof Villager v ? GuardProxy.guardOf(v) : null;
        if(guard==null) { log("PRESENT none player="+p.getName().getString()); return 0; }
        guard.setNoAi(true);
        guard.teleportTo(p.getX()+1.5, p.getY(), p.getZ());
        p.connection.teleport(p.getX(), p.getY(), p.getZ(), -90F, 20F);
        log("PRESENT player="+p.getName().getString()+" guard="+guard.getUUID());
        return 1;
    }
    /** The player's own guard's MCA person after an editor save: the gene the client sent, and the revision. */
    private static int editorCheck(ServerPlayer p) {
        UUID id=guards.get(p.getUUID());
        Mob guard=id==null?null:(Mob)p.serverLevel().getEntity(id);
        Villager identity=guard==null?null:GuardProxy.proxyOf(guard);
        var entry=guard==null?null:GuardIdentityStore.record(guard);
        log("EDITOR_SERVER player="+p.getName().getString()+" character="+(identity==null?"none":identity.getUUID())
                +" size="+(identity==null?"none":gene(identity))+" revision="+(entry==null?-1:entry.appearanceRevision())
                +" invisible="+(identity!=null && identity.isInvisible())+" noAi="+(identity!=null && identity.isNoAi()));
        return 1;
    }
    static String gene(Villager identity) {
        try {
            Object genetics=identity.getClass().getMethod("getGenetics").invoke(identity);
            Object size=genetics.getClass().getField("SIZE").get(null);
            return String.valueOf(genetics.getClass().getMethod("getGene", size.getClass()).invoke(genetics, size));
        } catch (ReflectiveOperationException e) { return "unreadable"; }
    }
    /** After the release: the same person back in the world, visible, with the guard's sword, and no guard. */
    private static int releaseCheck(ServerPlayer p) {
        UUID id=recruits.get(p.getUUID());
        var entity=id==null?null:p.serverLevel().getEntity(id);
        boolean alive=entity instanceof Villager v && v.isAlive() && !v.isRemoved();
        Villager v=alive?(Villager)entity:null;
        boolean visible=v!=null && !v.isInvisible() && !GuardProxy.isProxy(v);
        boolean sword=false;
        if(v!=null) for(int i=0;i<v.getInventory().getContainerSize();i++)
            if(v.getInventory().getItem(i).getHoverName().getString().equals("Probe sword 1")) sword=true;
        String profession=v==null?"none":String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()));
        boolean ok=alive && visible && sword && v.getUUID().equals(id) && GuardProxy.guardOf(v)==null;
        log("RELEASE_SERVER "+(ok?"PASS":"FAIL")+" player="+p.getName().getString()+" person="+id+" alive="+alive
                +" visible="+visible+" swordInInventory="+sword+" profession="+profession);
        return 1;
    }
    @SubscribeEvent(receiveCanceled=true)
    public static void generic(PlayerInteractEvent.EntityInteract e) {
        if(!e.getLevel().isClientSide && GuardProxy.isProxy(e.getTarget())) log("PROXY_GENERIC player="+e.getEntity().getName().getString()+" canceled="+e.isCanceled());
        nativeClick(e, "generic");
    }
    @SubscribeEvent(receiveCanceled=true)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific e) {
        if(!e.getLevel().isClientSide && GuardProxy.isProxy(e.getTarget())) log("PROXY_SPECIFIC player="+e.getEntity().getName().getString()+" canceled="+e.isCanceled());
        nativeClick(e, "specific");
    }
    /** What a native mod's own interaction will see on a click on one of its NPCs. */
    private static void nativeClick(PlayerInteractEvent e, String kind) {
        net.minecraft.world.entity.Entity target = e instanceof PlayerInteractEvent.EntityInteract i ? i.getTarget()
                : ((PlayerInteractEvent.EntityInteractSpecific) e).getTarget();
        if(!e.getLevel().isClientSide && target instanceof Mob m && !(m instanceof Villager) && GuardProxy.proxyOf(m)!=null)
            log("NATIVE_CLICK "+kind+" player="+e.getEntity().getName().getString()+" actor="+BuiltInRegistries.ENTITY_TYPE.getKey(m.getType())
                    +" crouching="+e.getEntity().isShiftKeyDown()+" effectiveAi="+m.isEffectiveAi()
                    +" targetsPlayer="+(m.getTarget()==e.getEntity())+" hero="+e.getEntity().hasEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE)
                    +" hand="+e.getEntity().getItemInHand(e.getHand()).getHoverName().getString()+" canceled="+(e instanceof net.neoforged.bus.api.ICancellableEvent c && c.isCanceled()));
    }
}
