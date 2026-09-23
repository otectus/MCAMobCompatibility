package dev.otectus.mcamobcompat.probe;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.bridge.*;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
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
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod("mcamobcompat_probe")
@Mod.EventBusSubscriber(modid = "mcamobcompat_probe")
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
            }))
            .then(net.minecraft.commands.Commands.literal("recruit").executes(c -> recruitSetup(c.getSource().getPlayerOrException())))
            .then(net.minecraft.commands.Commands.literal("resword").executes(c -> {
                var p=c.getSource().getPlayerOrException(); p.setItemInHand(InteractionHand.MAIN_HAND, sword("Probe sword 2")); return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("recruitcheck")
                .then(net.minecraft.commands.Commands.argument("phase", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> recruitCheck(c.getSource().getPlayerOrException(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "phase"))))));
    }
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
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
            var type=BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("guardvillagers", "guard")).orElse(null);
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
                setter.invoke(brain,follow,p); proxy.tickCount=20;
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
            log("PAIR player="+p.getName().getString()+" guard="+guard.getUUID()+" proxy="+proxy.getUUID()+" hearts="+identity.hearts(p.getUUID()));
        }
    }
    private static final Map<UUID, UUID> recruits = new HashMap<>();
    private static ItemStack sword(String name) {
        ItemStack stack=new ItemStack(Items.IRON_SWORD); stack.setHoverName(Component.literal(name)); return stack;
    }
    /** A plain adult MCA villager in front of the player, and a named sword in a survival player's hand. */
    private static int recruitSetup(ServerPlayer p) {
        var level=p.serverLevel();
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("mca","male_villager")).orElseThrow();
        Villager villager=(Villager)type.create(level);
        villager.moveTo(p.getX()+1.5,p.getY(),p.getZ()-2.0,0,0);
        ForgeEventFactory.onFinalizeSpawn(villager,level,level.getCurrentDifficultyAt(villager.blockPosition()),
                net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED,null,null);
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
    /** What the server holds after the click: the same villager, now a guard, with exactly one sword moved. */
    private static int recruitCheck(ServerPlayer p, String phase) {
        UUID id=recruits.get(p.getUUID());
        var entity=id==null?null:p.serverLevel().getEntity(id);
        boolean alive=entity instanceof Villager v && v.isAlive() && !v.isRemoved();
        Villager v=alive?(Villager)entity:null;
        String profession=v==null?"none":String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()));
        String villagerHand=v==null?"":v.getMainHandItem().getHoverName().getString();
        String playerHand=p.getMainHandItem().isEmpty()?"empty":p.getMainHandItem().getHoverName().getString();
        boolean ok="first".equals(phase)
                ? alive && "mca:guard".equals(profession) && "Probe sword 1".equals(villagerHand) && "empty".equals(playerHand)
                : alive && "mca:guard".equals(profession) && "Probe sword 1".equals(villagerHand) && "Probe sword 2".equals(playerHand);
        log(("first".equals(phase)?"RECRUIT_SERVER ":"ALREADY_GUARD ")+(ok?"PASS":"FAIL")+" player="+p.getName().getString()
                +" villager="+id+" alive="+alive+" profession="+profession+" villagerHand="+villagerHand+" playerHand="+playerHand);
        return 1;
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
