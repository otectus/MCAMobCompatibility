package dev.otectus.mcamobcompat.gametest;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The hidden-proxy bridge against the real MCA and Guard Villagers, in a running server.
 *
 * <p>Each test builds its own guard (brainless, so it stands still) and asks {@link GuardProxy} for
 * its proxy, then drives the exact path a player or a mob would: a click event, a real projectile,
 * a real hit, a real death. Listeners registered here filter on the test's own entities, since a
 * batch of tests runs side by side in one level.
 */
@GameTestHolder(McaMobCompatTests.CORE)
@PrefixGameTestTemplate(false)
public final class ProxyGameTests {

    private ProxyGameTests() {
    }

    static Mob guard(GameTestHelper h) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("guardvillagers", "guard")).orElse(null);
        h.assertTrue(type != null && McaMobCompat.location("guardvillagers", "guard")
                .equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)), "Guard Villagers is not installed");
        Mob guard = (Mob) type.create(h.getLevel());
        guard.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 1, 5))));
        guard.setNoAi(true);
        guard.tickCount = GuardProxy.CREATE_GRACE_TICKS + 20;
        h.getLevel().addFreshEntity(guard);
        h.assertTrue(MobCompatibilityAPI.isBridgedGuard(guard), "guardvillagers:guard is not a bridged guard");
        return guard;
    }

    static Villager proxy(GameTestHelper h, Mob guard) {
        Villager proxy = GuardProxy.create(h.getLevel(), guard);
        h.assertTrue(proxy != null, "Proxy creation failed; see /mcamobcompat mca for the binding state");
        return proxy;
    }

    static Villager mcaVillager(GameTestHelper h, BlockPos relative) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(McaMobCompat.location("mca", "male_villager")).orElse(null);
        Villager villager = (Villager) type.create(h.getLevel());
        villager.moveTo(Vec3.atBottomCenterOf(h.absolutePos(relative)));
        h.getLevel().addFreshEntity(villager);
        return villager;
    }

    private static <T extends Event> Consumer<T> listen(
            EventPriority priority, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(priority, true, type, listener);
        return listener;
    }

    private static void stop(Object listener) {
        NeoForge.EVENT_BUS.unregister(listener);
    }

    /** An arrow with its pickup stack, the 1.21.1 constructor; no weapon fired it. */
    private static Arrow arrow(GameTestHelper h, ItemStack stack, double x, double y, double z) {
        return new Arrow(h.getLevel(), x, y, z, stack, null);
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void classificationsAndMcaPersonality(GameTestHelper h) {
        h.assertTrue(MobCompatibilityAPI.getRoles(EntityType.COW).contains(MobRole.CATTLE), "Cow is not cattle");
        h.assertTrue(MobCompatibilityAPI.getRoles(EntityType.COW).contains(MobRole.LIVESTOCK), "Nested livestock tag missing");
        h.assertFalse(MobCompatibilityAPI.getRoles(EntityType.HORSE).contains(MobRole.LIVESTOCK), "A horse became livestock");
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        h.assertTrue(McaBinding.setPersonality(proxy, "friendly"), "MCA personality setter failed");
        h.assertTrue(McaBinding.personality(proxy).orElse("").endsWith("friendly"), "MCA personality getter failed");
        h.assertFalse(McaBinding.setPersonality(proxy, "removed:unknown"), "An unknown personality was accepted");
        h.assertTrue(McaBinding.personality(proxy).orElse("").endsWith("friendly"), "An unknown personality overwrote MCA's");
        h.assertTrue(GuardReflection.variant(guard).isPresent(), "Guard Villagers variant binding failed");
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void stableLinksAndTwoWayNames(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        UUID uuid = proxy.getUUID();
        h.assertTrue(GuardProxy.create(h.getLevel(), guard) == proxy, "A repeated create made a second proxy");

        guard.getPersistentData().remove(GuardProxy.KEY_PROXY);
        proxy.getPersistentData().remove(GuardProxy.KEY_GUARD);
        h.assertTrue(GuardProxy.isProxy(proxy), "The store did not recover the proxy-side link");
        h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "The store did not recover the guard-side link");

        proxy.tickCount = 20;
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        proxy.setCustomName(Component.literal("MCA rename"));
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        h.assertTrue(guard.getName().getString().equals("MCA rename"), "An MCA-side rename did not reach the guard");
        guard.setCustomName(Component.literal("Name tag rename"));
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        h.assertTrue(proxy.getName().getString().equals("Name tag rename"), "A guard-side rename did not reach MCA");
        h.assertTrue(uuid.equals(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID()).orElse(null)),
                "The proxy UUID changed");
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 260)
    public static void unloadedOwnerNeverCausesProxyDeletion(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        guard.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.runAtTickTime(230, () -> {
            h.assertTrue(!proxy.isRemoved() && proxy.isAlive(), "A proxy whose guard was unloaded was deleted");
            h.assertTrue(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID()).isPresent(),
                    "The unloaded guard's link was lost");
            GuardProxy.discard(proxy);
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void rejectedSpawnDoesNotCommitLinks(GameTestHelper h) {
        Mob guard = guard(h);
        Consumer<EntityJoinLevelEvent> reject = listen(EventPriority.HIGH, EntityJoinLevelEvent.class, event -> {
            if (event.getEntity() instanceof Villager
                    && guard.getUUID().equals(GuardProxy.linkedGuardUuid(event.getEntity()))) {
                event.setCanceled(true);
            }
        });
        CompoundTag familyBefore = familySnapshot(h);
        try {
            h.assertTrue(GuardProxy.create(h.getLevel(), guard) == null, "A cancelled proxy spawn succeeded");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID());
            h.assertTrue(entry.proxyUuid() == null && !entry.seeded(), "A cancelled spawn committed identity data");
            h.assertFalse(guard.getPersistentData().hasUUID(GuardProxy.KEY_PROXY), "A cancelled spawn linked the guard");
            h.assertTrue(familyBefore.equals(familySnapshot(h)), "A rejected proxy left a family-tree node behind");
        } finally {
            stop(reject);
        }
        h.assertTrue(GuardProxy.create(h.getLevel(), guard) != null, "A failed spawn could not be retried");
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void cancelledInitializationNeverCommits(GameTestHelper h) {
        Mob guard = guard(h);
        Consumer<FinalizeSpawnEvent> reject = listen(EventPriority.LOWEST, FinalizeSpawnEvent.class,
                event -> {
                    if (event.getEntity() instanceof Villager
                            && event.getEntity().distanceToSqr(guard) < 1.0D) {
                        event.setCanceled(true);
                    }
                });
        try {
            h.assertTrue(GuardProxy.create(h.getLevel(), guard) == null, "A cancelled initialisation produced a proxy");
            GuardIdentityData.Entry entry = GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID());
            h.assertTrue(!entry.seeded() && entry.proxyUuid() == null, "A cancelled initialisation committed identity data");
        } finally {
            stop(reject);
        }
        proxy(h, guard);
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void protectedAnimalsAndFriendlyFire(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        Mob cow = h.spawnWithNoFreeWill(EntityType.COW, new BlockPos(8, 1, 5));
        cow.setCustomName(Component.literal("Protected"));
        float before = cow.getHealth();
        cow.hurt(h.getLevel().damageSources().mobAttack(proxy), 5.0F);
        h.assertTrue(cow.getHealth() == before, "A named animal was hurt by an MCA villager");

        Villager villager = mcaVillager(h, new BlockPos(8, 1, 8));
        before = villager.getHealth();
        villager.hurt(h.getLevel().damageSources().mobAttack(guard), 4.0F);
        h.assertTrue(villager.getHealth() == before, "A guard's hit landed on an MCA villager");

        Arrow stray = arrow(h, new ItemStack(Items.ARROW), guard.getX(), guard.getY() + 1, guard.getZ());
        stray.setOwner(guard);
        villager.hurt(h.getLevel().damageSources().arrow(stray, guard), 4.0F);
        h.assertTrue(villager.getHealth() == before, "A guard's arrow landed on an MCA villager");
        stray.discard();
        villager.discard();
        cow.discard();
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void proxyHitboxIsNestedInsideTheGuard(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        proxy.tickCount = 20;
        GuardProxySync.tick(h.getLevel(), guard, proxy);
        AABB inner = proxy.getBoundingBox();
        AABB outer = guard.getBoundingBox();
        h.assertTrue(proxy.getBbWidth() <= GuardProxy.PROXY_WIDTH + 1.0E-3F, "The proxy kept MCA's full width");
        h.assertTrue(inner.minX > outer.minX && inner.maxX < outer.maxX && inner.minZ > outer.minZ
                && inner.maxZ < outer.maxZ && inner.maxY < outer.maxY, "The proxy box is not inside the guard's");
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void arrowsUseNativeGuardCollision(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        Arrow arrow = arrow(h, new ItemStack(Items.ARROW), guard.getX() - 3, guard.getY() + 1, guard.getZ());
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(1.5D, 0.0D, 0.0D);
        arrow.setBaseDamage(2.0D);
        // The pierce level's setter is private on 1.21.1; its saved form is the public way in.
        CompoundTag pierce = arrow.saveWithoutId(new CompoundTag());
        pierce.putByte("PierceLevel", (byte) 1);
        arrow.load(pierce);
        h.getLevel().addFreshEntity(arrow);
        float before = guard.getHealth();
        h.runAtTickTime(8, () -> {
            h.assertTrue(guard.getHealth() < before, "The arrow missed the guard because of its proxy");
            h.assertTrue(proxy.isAlive() && proxy.getHealth() == proxy.getMaxHealth(),
                    "The arrow hurt the MCA identity independently");
            arrow.discard();
            guard.discard();
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void tippedArrowsKeepNativePotionEffects(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        Arrow arrow = arrow(h, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON),
                guard.getX() - 3, guard.getY() + 1, guard.getZ());
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(1.5D, 0.0D, 0.0D);
        h.getLevel().addFreshEntity(arrow);
        h.runAtTickTime(8, () -> {
            h.assertTrue(guard.hasEffect(MobEffects.POISON), "The tipped arrow lost its effect");
            h.assertFalse(proxy.hasEffect(MobEffects.POISON), "The tipped arrow's effect hit the proxy");
            arrow.discard();
            guard.discard();
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void tridentsUseOneNativeGuardImpact(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        ThrownTrident trident = new ThrownTrident(h.getLevel(), guard.getX() - 3, guard.getY() + 1, guard.getZ(),
                new ItemStack(Items.TRIDENT));
        trident.setNoGravity(true);
        trident.setDeltaMovement(1.5D, 0.0D, 0.0D);
        nativeImpact(h, guard, proxy, trident, true);
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void snowballsConsumeAtTheGuard(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        Snowball snowball = new Snowball(h.getLevel(), guard.getX() - 3, guard.getY() + 1, guard.getZ());
        snowball.setNoGravity(true);
        snowball.setDeltaMovement(1.5D, 0.0D, 0.0D);
        nativeImpact(h, guard, proxy, snowball, false);
    }

    private static void nativeImpact(GameTestHelper h, Mob guard, Villager proxy, Projectile projectile, boolean damage) {
        List<Entity> hits = new ArrayList<>();
        Consumer<ProjectileImpactEvent> observe = listen(EventPriority.HIGHEST, ProjectileImpactEvent.class, event -> {
            if (event.getProjectile() == projectile && event.getRayTraceResult() instanceof EntityHitResult hit) {
                hits.add(hit.getEntity());
            }
        });
        float before = guard.getHealth();
        h.getLevel().addFreshEntity(projectile);
        h.runAtTickTime(8, () -> {
            try {
                h.assertTrue(hits.equals(List.of(guard)), "The projectile hit the wrong or a duplicate entity: " + hits);
                h.assertTrue(!damage || guard.getHealth() < before, "The projectile did no native damage");
                h.assertTrue(proxy.isAlive() && proxy.getHealth() == proxy.getMaxHealth(),
                        "The projectile hurt the MCA identity independently");
            } finally {
                stop(observe);
                projectile.discard();
                guard.discard();
            }
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void damageRelaysTheFinalAmountAndHonoursCancellation(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        guard.setAbsorptionAmount(2.0F);
        List<Float> guardHits = new ArrayList<>();
        List<Float> proxyHits = new ArrayList<>();
        Consumer<LivingDamageEvent.Post> observe = listen(EventPriority.LOWEST, LivingDamageEvent.Post.class, event -> {
            if (event.getEntity() == guard) {
                guardHits.add(event.getNewDamage());
            } else if (event.getEntity() == proxy) {
                proxyHits.add(event.getNewDamage());
            }
        });
        Consumer<LivingIncomingDamageEvent> reject = event -> {
            if (event.getEntity() == guard) {
                event.setCanceled(true);
            }
        };
        try {
            guard.hurt(h.getLevel().damageSources().generic(), 9.0F);
            h.assertTrue(guardHits.size() == 1 && proxyHits.equals(guardHits),
                    "The proxy did not receive exactly the guard's final damage: " + guardHits + " / " + proxyHits);
            h.assertTrue(proxy.getHealth() == proxy.getMaxHealth(), "A relayed hit left the proxy injured");
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, LivingIncomingDamageEvent.class, reject);
            guard.invulnerableTime = 0;
            guard.hurt(h.getLevel().damageSources().generic(), 9.0F);
            h.assertTrue(guardHits.size() == 1 && proxyHits.size() == 1, "A cancelled hit was relayed");
        } finally {
            stop(observe);
            stop(reject);
        }
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void totemSurvivalKeepsIdentity(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        guard.hurt(h.getLevel().damageSources().generic(), 1000.0F);
        h.runAtTickTime(5, () -> {
            h.assertTrue(guard.isAlive() && proxy.isAlive(), "A totem revival killed the proxy");
            h.assertTrue(guard.getOffhandItem().isEmpty(), "The totem was not consumed");
            h.assertFalse(GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(),
                    "A totem survivor was marked deceased");
            h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "A totem survivor lost its identity link");
            guard.discard();
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void lateCancelledDeathNeverKillsIdentity(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        Consumer<LivingDeathEvent> reject = listen(EventPriority.LOWEST, LivingDeathEvent.class, event -> {
            if (event.getEntity() == guard) {
                event.setCanceled(true);
                guard.setHealth(3.0F);
            }
        });
        try {
            guard.hurt(h.getLevel().damageSources().generic(), 1000.0F);
        } finally {
            stop(reject);
        }
        h.runAtTickTime(5, () -> {
            h.assertTrue(guard.isAlive() && proxy.isAlive(), "A cancelled death killed the pair");
            h.assertFalse(GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(),
                    "A cancelled death was recorded");
            guard.discard();
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 60)
    public static void confirmedDeathRelaysOnce(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        AtomicInteger deaths = new AtomicInteger();
        Consumer<LivingDeathEvent> observe = listen(EventPriority.LOWEST, LivingDeathEvent.class, event -> {
            if (event.getEntity() == proxy) {
                deaths.incrementAndGet();
            }
        });
        guard.hurt(h.getLevel().damageSources().generic(), 1000.0F);
        h.runAtTickTime(8, () -> {
            try {
                h.assertTrue(deaths.get() == 1, "The MCA death was relayed " + deaths.get() + " times");
                h.assertFalse(proxy.isAlive(), "A confirmed guard death left the proxy alive");
                h.assertTrue(GuardIdentityStore.get(h.getLevel().getServer()).find(guard.getUUID()).deceased(),
                        "A confirmed death is missing from the store");
                h.assertTrue(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID()).isEmpty(),
                        "A dead guard is still linked to its proxy");
            } finally {
                stop(observe);
                guard.discard();
                proxy.discard();
            }
            h.succeed();
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 100)
    public static void killingTheProxyReleasesTheLink(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        UUID first = proxy.getUUID();
        proxy.kill();
        h.runAtTickTime(60, () -> {
            h.assertTrue(proxy.isRemoved(), "An operator's kill did not remove the proxy");
            h.assertFalse(first.equals(GuardIdentityStore.proxyUuid(h.getLevel().getServer(), guard.getUUID())
                    .orElse(null)), "The store still links the removed proxy");
            // The living guard is given a fresh proxy on its own tick; asking again resolves that one.
            Villager second = GuardProxy.create(h.getLevel(), guard);
            h.assertTrue(second != null && !second.getUUID().equals(first), "The guard could not get a new proxy");
            guard.discard();
            second.discard();
            h.succeed();
        });
    }

    /**
     * The operator's repair command replaces a guard's proxy with a new, linked one. Its own batch,
     * because the command acts on every loaded guard and must not reach another test's.
     */
    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80, batch = "mcamobcompat_rebuild")
    public static void rebuildReplacesTheProxyAndRelinks(GameTestHelper h) {
        Mob guard = guard(h);
        Villager first = proxy(h, guard);
        var server = h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "mcamobcompat guards rebuild");
        h.assertTrue(first.isRemoved(), "Rebuild left the old proxy in the world");
        Villager second = GuardProxy.proxyOf(guard);
        h.assertTrue(second != null && !second.getUUID().equals(first.getUUID()), "Rebuild gave the guard no new proxy");
        h.assertTrue(second.getUUID().equals(GuardIdentityStore.proxyUuid(server, guard.getUUID()).orElse(null)),
                "The store does not link the rebuilt proxy");
        h.assertTrue(guard.equals(GuardProxy.guardOf(second)), "The rebuilt proxy does not resolve its guard");
        guard.discard();
        second.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void aStaleGuardKeyDoesNotBlockCreation(GameTestHelper h) {
        Mob guard = guard(h);
        GuardIdentityStore.getOrCreate(guard);
        guard.getPersistentData().putUUID(GuardProxy.KEY_PROXY, UUID.randomUUID());
        Villager proxy = GuardProxy.create(h.getLevel(), guard);
        h.assertTrue(proxy != null, "A key the store had released blocked proxy creation for good");
        guard.discard();
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void recruitmentPreservesIdentityAndItemData(GameTestHelper h) {
        var player = FakePlayerFactory.getMinecraft(h.getLevel());
        Villager villager = mcaVillager(h, new BlockPos(5, 1, 5));
        UUID uuid = villager.getUUID();
        player.setShiftKeyDown(true);
        player.getAbilities().instabuild = false;
        ItemStack weapon = new ItemStack(Items.IRON_SWORD);
        weapon.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        try {
            var offhand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, villager);
            NeoForge.EVENT_BUS.post(offhand);
            h.assertTrue(offhand.isCanceled() && player.getMainHandItem().getCount() == 1,
                    "The off-hand pass consumed the weapon");
            var main = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
            NeoForge.EVENT_BUS.post(main);
            h.assertTrue(main.isCanceled() && !villager.isRemoved() && uuid.equals(villager.getUUID()),
                    "Recruiting replaced the MCA villager");
            h.assertTrue(villager.getMainHandItem().getHoverName().getString().equals("Heirloom")
                    && player.getMainHandItem().isEmpty(), "Recruiting lost the item's data or failed to transfer it");
            h.assertTrue(GuardProxy.GUARD_PROFESSION.equals(
                    BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession())),
                    "The villager did not become an MCA guard");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
            h.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD) && villager.getMainHandItem().is(Items.IRON_SWORD),
                    "A repeated recruit replaced the guard's weapon");
        } finally {
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            villager.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 600)
    public static void dimensionTravelPreservesBothUuids(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        UUID guardId = guard.getUUID();
        UUID proxyId = proxy.getUUID();
        ServerLevel destination = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(destination != null, "No nether in the test server");
        destination.setChunkForced(0, 0, true);
        // An entity is visible by UUID only once its chunk ticks entities, which needs every chunk
        // within two of it generated. The GameTest server ticks flat out, so generate them now
        // rather than hope the timeout outlasts world generation.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                destination.getChunk(x, z);
            }
        }
        List<String> seen = new ArrayList<>();
        Consumer<EntityJoinLevelEvent> joins = listen(EventPriority.LOWEST, EntityJoinLevelEvent.class, event -> {
            if (event.getEntity().getUUID().equals(guardId) || event.getEntity().getUUID().equals(proxyId)) {
                seen.add((event.getEntity().getUUID().equals(guardId) ? "guard" : "proxy") + " joined "
                        + event.getLevel().dimension().location());
            }
        });
        h.assertTrue(guard.teleportTo(destination, 5.5D, 130.0D, 5.5D, Set.of(), 0.0F, 0.0F),
                "The guard could not change dimension");
        h.succeedWhen(() -> {
            Entity moved = destination.getEntity(guardId);
            h.assertTrue(moved instanceof Mob, "The guard has not arrived yet " + seen);
            Villager carried = GuardProxy.proxyOf((Mob) moved);
            h.assertTrue(carried != null && carried.level() == destination && carried.getUUID().equals(proxyId),
                    "Changing dimension replaced or lost the MCA identity");
            moved.discard();
            carried.discard();
            destination.setChunkForced(0, 0, false);
            stop(joins);
        });
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void graceAndModuleToggleKeepThePair(GameTestHelper h) {
        Mob guard = guard(h);
        guard.tickCount = 1;
        h.assertTrue(GuardProxy.create(h.getLevel(), guard) == null, "An immediate create bypassed the grace period");
        guard.tickCount = GuardProxy.CREATE_GRACE_TICKS + 20;
        Villager proxy = proxy(h, guard);
        try {
            ConfigAccess.override(McaMobCompatConfig.COMMON.guardVillagersModule, false, () -> {
                ClassificationCache.invalidate();
                guard.moveTo(guard.position().add(2.0D, 0.0D, 0.0D));
                proxy.tickCount = 20;
                GuardProxySync.tick(h.getLevel(), guard, proxy);
                h.assertTrue(proxy.position().equals(guard.position()) && proxy.isInvisible() && proxy.isNoAi(),
                        "Turning the module off detached or exposed an existing proxy");
                h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "Turning the module off lost the durable link");
            });
        } finally {
            ClassificationCache.invalidate();
            guard.discard();
        }
        h.succeed();
    }

    @GameTest(template = McaMobCompatTests.EMPTY, timeoutTicks = 80)
    public static void disabledBridgeRoutesASelectedProxyToTheGuard(GameTestHelper h) {
        Mob guard = guard(h);
        Villager proxy = proxy(h, guard);
        var player = FakePlayerFactory.getMinecraft(h.getLevel());
        player.moveTo(guard.getX(), guard.getY(), guard.getZ() + 2);
        AtomicInteger hooks = new AtomicInteger();
        Consumer<PlayerInteractEvent.EntityInteract> observe = listen(EventPriority.LOWEST,
                PlayerInteractEvent.EntityInteract.class, event -> {
                    if (event.getTarget() == guard) {
                        hooks.incrementAndGet();
                    }
                });
        try {
            var click = new PlayerInteractEvent.EntityInteractSpecific(player, InteractionHand.MAIN_HAND, proxy, Vec3.ZERO);
            ConfigAccess.override(McaMobCompatConfig.SERVER.guardMcaInteraction, false,
                    () -> NeoForge.EVENT_BUS.post(click));
            h.assertTrue(click.isCanceled() && hooks.get() == 1,
                    "With the bridge off, a click on the proxy did not become exactly one click on the guard: "
                            + "cancelled=" + click.isCanceled() + ", result=" + click.getCancellationResult()
                            + ", guard clicks=" + hooks.get() + ", shift=" + player.isShiftKeyDown()
                            + ", distanceSqr=" + player.distanceToSqr(guard) + ", alive=" + guard.isAlive());
            h.assertTrue(GuardProxy.proxyOf(guard) == proxy, "A disabled click replaced the identity");
        } finally {
            stop(observe);
            guard.discard();
        }
        h.succeed();
    }

    /** MCA's whole family tree, serialised, so a test can prove a rejected spawn left no trace. */
    private static CompoundTag familySnapshot(GameTestHelper h) {
        String root = McaBinding.packageRoot().orElseThrow();
        try {
            Class<?> type = Class.forName(root + ".server.world.data.FamilyTree");
            SavedData tree = (SavedData) type.getMethod("get", ServerLevel.class).invoke(null, h.getLevel());
            return tree.save(new CompoundTag(), h.getLevel().registryAccess());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
