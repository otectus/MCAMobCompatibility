package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.*;
import dev.otectus.mcamobcompat.bridge.*;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.event.GuardBridgeHandler;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Mod("mcamobcompat_tests")
@GameTestHolder("mcamobcompat")
@PrefixGameTestTemplate(false)
public final class PortGameTests {
    private static Mob guard(GameTestHelper h) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("guardvillagers:guard")).orElseThrow();
        Mob guard = (Mob) type.create(h.getLevel());
        guard.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        guard.setNoAi(true); guard.tickCount = 60;
        h.getLevel().addFreshEntity(guard);
        h.assertTrue(MobCompatibilityAPI.isBridgedGuard(guard), "Guard classification/bridge failed");
        return guard;
    }
    private static Villager proxy(GameTestHelper h, Mob guard) {
        Villager proxy = GuardProxy.create(h.getLevel(), guard);
        h.assertTrue(proxy != null, "Proxy creation failed");
        return proxy;
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void classificationsAndMcaPersonality(GameTestHelper h) {
        h.assertTrue(MobCompatibilityAPI.getRoles(EntityType.COW).contains(MobRole.CATTLE), "Cow cattle tag missing");
        h.assertTrue(MobCompatibilityAPI.getRoles(EntityType.COW).contains(MobRole.LIVESTOCK), "Nested livestock tag missing");
        h.assertTrue(!MobCompatibilityAPI.getRoles(EntityType.HORSE).contains(MobRole.LIVESTOCK), "Horse became livestock");
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        h.assertTrue(McaBinding.setPersonality(proxy, "friendly"), "MCA extensible setter failed");
        h.assertTrue(McaBinding.personality(proxy).orElse("").equals("mca:friendly"), "MCA namespaced getter failed");
        h.assertTrue(!McaBinding.setPersonality(proxy, "removed:unknown"), "Unknown personality accepted");
        h.assertTrue(McaBinding.personality(proxy).orElse("").equals("mca:friendly"), "Unknown personality overwrote state");
        h.assertTrue(GuardReflection.variant(guard).isPresent(), "Guard getVariant binding failed");
        guard.discard(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void stableLinksAndTwoWayNames(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard); UUID uuid = proxy.getUUID();
        h.assertTrue(GuardProxy.create(h.getLevel(), guard) == proxy, "Repeated creation duplicated the proxy");
        guard.getPersistentData().remove(GuardProxy.KEY_PROXY); proxy.getPersistentData().remove(GuardProxy.KEY_GUARD);
        h.assertTrue(GuardProxy.isProxy(proxy), "SavedData did not recover the legacy proxy-side link");
        h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "SavedData did not recover guard-side link");
        proxy.tickCount = 20; GuardProxySync.tick(h.getLevel(), guard, proxy);
        proxy.setCustomName(Component.literal("MCA rename"));
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        h.assertTrue(guard.getName().getString().equals("MCA rename"), "MCA rename overwritten");
        guard.setCustomName(Component.literal("Name tag rename"));
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        h.assertTrue(proxy.getName().getString().equals("Name tag rename"), "Guard rename lost");
        h.assertTrue(uuid.equals(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID()).orElse(null)), "UUID changed");
        guard.discard(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 260)
    public static void unloadedOwnerNeverCausesProxyDeletion(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        guard.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.runAtTickTime(220, () -> {
            h.assertTrue(!proxy.isRemoved() && proxy.isAlive(), "Unresolved guard destroyed its identity after ten seconds");
            h.assertTrue(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID()).isPresent(), "Unloaded owner link lost");
            GuardProxy.discard(proxy); h.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void rejectedSpawnDoesNotCommitLinks(GameTestHelper h) {
        Mob guard = guard(h);
        Consumer<EntityJoinLevelEvent> reject = event -> {
            if (event.getEntity() instanceof Villager && event.getEntity().getPersistentData().hasUUID(GuardProxy.KEY_GUARD)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, reject);
        CompoundTag familyBefore = familySnapshot(h);
        try {
            h.assertTrue(GuardProxy.create(h.getLevel(), guard) == null, "Canceled proxy spawn succeeded");
            var entry = GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID());
            h.assertTrue(entry.proxyUuid() == null && !entry.seeded(), "Canceled spawn committed data");
            h.assertTrue(!guard.getPersistentData().hasUUID(GuardProxy.KEY_PROXY), "Canceled spawn changed guard link");
            h.assertTrue(familyBefore.equals(familySnapshot(h)), "Rejected proxy leaked family-tree nodes");
        } finally { NeoForge.EVENT_BUS.unregister(reject); }
        h.assertTrue(GuardProxy.create(h.getLevel(), guard) != null, "Failed spawn could not retry");
        guard.discard(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void protectedAnimalsAndFriendlyFire(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        var cow = h.spawnWithNoFreeWill(EntityType.COW, new BlockPos(7, 1, 5)); cow.setCustomName(Component.literal("Protected"));
        float before = cow.getHealth(); cow.hurt(h.getLevel().damageSources().mobAttack(proxy), 5);
        h.assertTrue(cow.getHealth() == before, "Named animal was hurt by MCA");
        var villager = (Villager) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mca:male_villager")).create(h.getLevel());
        villager.moveTo(cow.position()); h.getLevel().addFreshEntity(villager);
        before = villager.getHealth(); villager.hurt(h.getLevel().damageSources().mobAttack(guard), 4);
        h.assertTrue(villager.getHealth() == before, "Guard friendly-fire damage was not canceled");
        villager.discard(); cow.discard(); guard.discard(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void arrowsUseNativeGuardCollision(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        Arrow arrow = new Arrow(h.getLevel(), guard.getX() - 3, guard.getY() + 1, guard.getZ(), new ItemStack(Items.ARROW), null);
        arrow.setNoGravity(true); arrow.setDeltaMovement(1.5, 0, 0); arrow.setBaseDamage(2); CompoundTag arrowTag = arrow.saveWithoutId(new CompoundTag()); arrowTag.putByte("PierceLevel", (byte) 1); arrow.load(arrowTag);
        h.getLevel().addFreshEntity(arrow); float before = guard.getHealth();
        h.runAtTickTime(8, () -> {
            h.assertTrue(guard.getHealth() < before, "Arrow missed the guard because of its proxy");
            h.assertTrue(proxy.isAlive() && proxy.getHealth() == proxy.getMaxHealth(), "Arrow injured the identity proxy independently");
            h.assertTrue(proxy.getBoundingBox().minY > h.getLevel().getMinBuildHeight(), "Projectile collision bounds were not restored");
            arrow.discard(); guard.discard(); h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void canceledInitializationNeverCommits(GameTestHelper h) {
        Mob guard = guard(h);
        Consumer<net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent> reject = e -> {
            if (e.getEntity() instanceof Villager) e.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, reject);
        try {
            h.assertTrue(GuardProxy.create(h.getLevel(), guard) == null, "Canceled initialization produced a proxy");
            var entry = GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID());
            h.assertTrue(!entry.seeded() && entry.proxyUuid() == null, "Canceled initialization committed identity");
        } finally { NeoForge.EVENT_BUS.unregister(reject); }
        proxy(h, guard); guard.discard(); h.succeed();
    }

    @GameTest(template = "empty")
    public static void damageUsesFinalAmountAndHonorsCancellation(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        guard.setAbsorptionAmount(2);
        java.util.List<Float> guardHits = new java.util.ArrayList<>(), proxyHits = new java.util.ArrayList<>();
        Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> observe = e -> {
            if (e.getEntity() == guard) guardHits.add(e.getNewDamage());
            if (e.getEntity() == proxy) proxyHits.add(e.getNewDamage());
        };
        Consumer<net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent> reject = e -> {
            if (e.getEntity() == guard) e.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, observe);
        try {
            guard.hurt(h.getLevel().damageSources().generic(), 9);
            h.assertTrue(guardHits.size() == 1 && proxyHits.equals(guardHits), "Proxy did not receive exactly the final health damage: " + guardHits + "/" + proxyHits);
            h.assertTrue(proxy.getHealth() == proxy.getMaxHealth(), "Synthetic damage left proxy injured");
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, reject);
            guard.invulnerableTime = 0;
            guard.hurt(h.getLevel().damageSources().generic(), 9);
            h.assertTrue(guardHits.size() == 1 && proxyHits.size() == 1, "Canceled incoming hit was relayed");
        } finally { NeoForge.EVENT_BUS.unregister(observe); NeoForge.EVENT_BUS.unregister(reject); }
        guard.discard(); h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void totemSurvivalKeepsIdentity(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        guard.hurt(h.getLevel().damageSources().generic(), 1000);
        h.runAtTickTime(5, () -> {
            h.assertTrue(guard.isAlive() && proxy.isAlive(), "Totem revival killed the proxy");
            h.assertTrue(guard.getOffhandItem().isEmpty(), "Totem was not consumed");
            h.assertTrue(!GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(), "Totem survivor marked deceased");
            h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "Totem survivor lost identity link");
            guard.discard(); h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void lateCanceledDeathNeverKillsIdentity(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard);
        Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> reject = e -> {
            if (e.getEntity() == guard) { e.setCanceled(true); guard.setHealth(3); }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, reject);
        try { guard.hurt(h.getLevel().damageSources().generic(), 1000); }
        finally { NeoForge.EVENT_BUS.unregister(reject); }
        h.runAtTickTime(5, () -> {
            h.assertTrue(guard.isAlive() && proxy.isAlive(), "Late canceled death killed a pair");
            h.assertTrue(!GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(), "Late cancellation marked deceased");
            guard.discard(); h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void confirmedDeathRelaysOnce(GameTestHelper h) {
        Mob guard = guard(h); Villager proxy = proxy(h, guard); AtomicInteger deaths = new AtomicInteger();
        Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> observe = e -> {
            if (e.getEntity() == proxy) deaths.incrementAndGet();
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, observe);
        guard.hurt(h.getLevel().damageSources().generic(), 1000);
        h.runAtTickTime(8, () -> {
            try {
                h.assertTrue(deaths.get() == 1, "Synthetic MCA death count was " + deaths.get());
                h.assertTrue(!proxy.isAlive(), "Confirmed guard death left proxy alive");
                h.assertTrue(GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(), "Confirmed death missing from store");
            } finally { NeoForge.EVENT_BUS.unregister(observe); guard.discard(); proxy.discard(); }
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void recruitmentPreservesIdentityAndItemComponents(GameTestHelper h) {
        var player = FakePlayerFactory.getMinecraft(h.getLevel());
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mca:male_villager"));
        Villager villager = (Villager) type.create(h.getLevel());
        villager.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        h.getLevel().addFreshEntity(villager); UUID uuid = villager.getUUID();
        player.setShiftKeyDown(true); player.getAbilities().instabuild = false;
        ItemStack weapon = new ItemStack(Items.IRON_SWORD);
        weapon.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        try {
            var offhand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, villager);
            NeoForge.EVENT_BUS.post(offhand);
            h.assertTrue(offhand.isCanceled() && player.getMainHandItem().getCount() == 1, "Offhand conversion gate consumed a weapon");
            var main = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
            NeoForge.EVENT_BUS.post(main);
            h.assertTrue(main.isCanceled() && !villager.isRemoved() && uuid.equals(villager.getUUID()), "Recruitment replaced MCA entity");
            h.assertTrue(villager.getMainHandItem().getHoverName().getString().equals("Heirloom") && player.getMainHandItem().isEmpty(), "Recruitment lost item components or failed transfer");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
            h.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD) && villager.getMainHandItem().is(Items.IRON_SWORD), "Repeated recruitment replaced equipment");
        } finally { player.setShiftKeyDown(false); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); villager.discard(); }
        h.succeed();
    }

    private static CompoundTag familySnapshot(GameTestHelper h) {
        try {
            Class<?> type=Class.forName("net.conczin.mca.server.world.data.FamilyTree");
            var data=(net.minecraft.world.level.saveddata.SavedData)type.getMethod("get",net.minecraft.server.level.ServerLevel.class).invoke(null,h.getLevel());
            return data.save(new CompoundTag(),h.getLevel().registryAccess());
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void tippedArrowsKeepNativePotionEffects(GameTestHelper h) {
        Mob guard=guard(h);Villager proxy=proxy(h,guard);
        ItemStack stack=net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW,net.minecraft.world.item.alchemy.Potions.POISON);
        Arrow arrow=new Arrow(h.getLevel(),guard.getX()-3,guard.getY()+1,guard.getZ(),stack,null);
        arrow.setNoGravity(true);arrow.setDeltaMovement(1.5,0,0);h.getLevel().addFreshEntity(arrow);
        h.runAtTickTime(8,()->{
            h.assertTrue(guard.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Tipped arrow lost its potion effect");
            h.assertTrue(!proxy.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Native potion effect hit proxy");
            arrow.discard();guard.discard();h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void tridentsUseOneNativeGuardImpact(GameTestHelper h) {
        Mob guard=guard(h);Villager proxy=proxy(h,guard);
        var trident=new net.minecraft.world.entity.projectile.ThrownTrident(h.getLevel(),guard.getX()-3,guard.getY()+1,guard.getZ(),new ItemStack(Items.TRIDENT));
        trident.setNoGravity(true);trident.setDeltaMovement(1.5,0,0);
        nativeImpact(h,guard,proxy,trident,true);
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void snowballsConsumeAtTheGuard(GameTestHelper h) {
        Mob guard=guard(h);Villager proxy=proxy(h,guard);
        var snowball=new net.minecraft.world.entity.projectile.Snowball(h.getLevel(),guard.getX()-3,guard.getY()+1,guard.getZ());
        snowball.setNoGravity(true);snowball.setDeltaMovement(1.5,0,0);
        nativeImpact(h,guard,proxy,snowball,false);
    }
    private static void nativeImpact(GameTestHelper h,Mob guard,Villager proxy,net.minecraft.world.entity.projectile.Projectile projectile,boolean damage) {
        java.util.List<Entity> hits=new java.util.ArrayList<>();
        Consumer<net.neoforged.neoforge.event.entity.ProjectileImpactEvent> observe=e->{
            if(e.getProjectile()==projectile && e.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit) hits.add(hit.getEntity());
        };
        NeoForge.EVENT_BUS.addListener(observe);float before=guard.getHealth();h.getLevel().addFreshEntity(projectile);
        h.runAtTickTime(8,()->{
            try {
                h.assertTrue(hits.equals(java.util.List.of(guard)),"Native projectile hit wrong/duplicate entity: "+hits);
                h.assertTrue(!damage || guard.getHealth()<before,"Trident caused no native damage");
                h.assertTrue(proxy.isAlive() && proxy.getHealth()==proxy.getMaxHealth(),"Projectile damaged identity independently");
            } finally {NeoForge.EVENT_BUS.unregister(observe);projectile.discard();guard.discard();}
            h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void dimensionTravelPreservesBothUuids(GameTestHelper h) {
        Mob guard=guard(h);Villager proxy=proxy(h,guard);UUID guardId=guard.getUUID(),proxyId=proxy.getUUID();
        var destination=h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        java.util.List<String> transitions=new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Entity> arrived=new java.util.concurrent.atomic.AtomicReference<>();
        Consumer<EntityJoinLevelEvent> join=e->{if(e.getEntity().getUUID().equals(guardId)){arrived.set(e.getEntity());transitions.add("join:"+e.getLevel().dimension()+":"+e.isCanceled());}};
        Consumer<net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent> leave=e->{if(e.getEntity().getUUID().equals(guardId))transitions.add("leave:"+e.getLevel().dimension()+":"+e.getEntity().getRemovalReason());};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,join);NeoForge.EVENT_BUS.addListener(leave);
        destination.setChunkForced(0,0,true);
        destination.getChunk(0,0);
        h.assertTrue(guard.teleportTo(destination,5,130,5,java.util.Set.of(),0,0),"Guard dimension change failed");
        h.succeedWhen(()->{
            Mob moved=(Mob)destination.getEntity(guardId);
            h.assertTrue(moved!=null,"Guard missing: "+transitions+" arrived="+(arrived.get()==null?"none":arrived.get().getRemovalReason()+"/"+arrived.get().level().dimension()+"/"+arrived.get().position()));
            Villager carried=GuardProxy.proxyOf(moved);
            h.assertTrue(carried!=null && carried.level()==destination && carried.getUUID().equals(proxyId),"Dimension change replaced/lost MCA identity");
            moved.discard();destination.setChunkForced(0,0,false);NeoForge.EVENT_BUS.unregister(join);NeoForge.EVENT_BUS.unregister(leave);
        });
    }

    @GameTest(template="empty")
    public static void immediateCreationRespectsGraceAndToggleKeepsPair(GameTestHelper h) {
        Mob guard=guard(h);guard.tickCount=1;
        h.assertTrue(GuardProxy.create(h.getLevel(),guard)==null,"Immediate click bypassed grace");
        guard.tickCount=60;Villager proxy=proxy(h,guard);
        boolean old=McaMobCompatConfig.COMMON.guardVillagersModule.get();
        try {
            McaMobCompatConfig.COMMON.guardVillagersModule.set(false);ClassificationCache.invalidate();
            guard.moveTo(guard.position().add(2,0,0));proxy.tickCount=20;GuardProxySync.tick(h.getLevel(),guard,proxy);
            h.assertTrue(proxy.position().equals(guard.position()) && proxy.isInvisible() && proxy.isNoAi(),"Toggle detached or exposed existing identity");
            h.assertTrue(GuardProxy.proxyOf(guard)==proxy,"Toggle lost durable link");
        } finally {McaMobCompatConfig.COMMON.guardVillagersModule.set(old);ClassificationCache.invalidate();guard.discard();}
        h.succeed();
    }

    @GameTest(template="empty")
    public static void disabledBridgeRoutesSelectedProxyToNativeGuard(GameTestHelper h) {
        Mob guard=guard(h);Villager proxy=proxy(h,guard);
        var player=FakePlayerFactory.getMinecraft(h.getLevel());player.moveTo(guard.getX(),guard.getY(),guard.getZ()+2);
        boolean old=McaMobCompatConfig.SERVER.guardMcaInteraction.get();AtomicInteger hooks=new AtomicInteger();
        Consumer<PlayerInteractEvent.EntityInteract> observe=e->{if(e.getTarget()==guard)hooks.incrementAndGet();};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,observe);
        try {
            McaMobCompatConfig.SERVER.guardMcaInteraction.set(false);
            var click=new PlayerInteractEvent.EntityInteractSpecific(player,InteractionHand.MAIN_HAND,proxy,Vec3.ZERO);
            NeoForge.EVENT_BUS.post(click);
            h.assertTrue(click.isCanceled() && hooks.get()==1,"Disabled bridge did not dispatch exactly one native guard interaction");
            h.assertTrue(GuardProxy.proxyOf(guard)==proxy,"Disabled click replaced identity");
        } finally {McaMobCompatConfig.SERVER.guardMcaInteraction.set(old);NeoForge.EVENT_BUS.unregister(observe);guard.discard();}
        h.succeed();
    }
}
