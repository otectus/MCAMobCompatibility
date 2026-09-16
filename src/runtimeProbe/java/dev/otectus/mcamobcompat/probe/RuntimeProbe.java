package dev.otectus.mcamobcompat.probe;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.bridge.*;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
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
            })));
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
                setter.invoke(brain,follow,p); proxy.tickCount=20;
                GuardProxySync.noteInteraction(guard, UUID.randomUUID());
                GuardProxySync.tick(level,guard,proxy);
                if(!p.getUUID().equals(guard.getClass().getMethod("getOwnerId").invoke(guard))
                        || !Boolean.TRUE.equals(guard.getClass().getMethod("isFollowing").invoke(guard)))
                    throw new IllegalStateException("Follow chose the last click instead of MCA's authorized player");
                setter.invoke(brain,stay,p); GuardProxySync.tick(level,guard,proxy);
                if(!Boolean.TRUE.equals(guard.getClass().getMethod("isPatrolling").invoke(guard))) throw new IllegalStateException("Stay did not set patrol");
                setter.invoke(brain,walk,p); GuardProxySync.tick(level,guard,proxy);
                if(Boolean.TRUE.equals(guard.getClass().getMethod("isFollowing").invoke(guard))
                        || Boolean.TRUE.equals(guard.getClass().getMethod("isPatrolling").invoke(guard))) throw new IllegalStateException("Move did not clear stay/follow");
                log("MOVEMENT_VERIFIED player="+p.getName().getString());
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
            pairIds.put(guard.getUUID(),proxy.getUUID());
            log("PAIR player="+p.getName().getString()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()+" hearts="+identity.hearts(p.getUUID()));
        }
    }
    @SubscribeEvent(receiveCanceled=true)
    public static void generic(PlayerInteractEvent.EntityInteract e) {
        if(!e.getLevel().isClientSide && GuardProxy.isProxy(e.getTarget())) log("PROXY_GENERIC player="+e.getEntity().getName().getString()+" canceled="+e.isCanceled());
    }
    @SubscribeEvent(receiveCanceled=true)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific e) {
        if(!e.getLevel().isClientSide && GuardProxy.isProxy(e.getTarget())) log("PROXY_SPECIFIC player="+e.getEntity().getName().getString()+" canceled="+e.isCanceled());
    }
}
