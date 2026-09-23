# MCA: Mob Compatibility — Research & Implementation Specification (0.1.0 and 0.2.0 shipped, 0.2.1 implemented)

*Target platform: Minecraft 1.20.1, Minecraft Forge. Deliverable: a research-backed engineering specification an autonomous coding agent can implement from scratch.*

> **Evidence key used throughout:** **[C]** = confirmed from source/official docs; **[I]** = inferred from source structure; **[RRV]** = REQUIRES RUNTIME VERIFICATION (a concrete test is given). Do not treat **[I]** or **[RRV]** items as facts during implementation.

> **Status — 0.1.0 shipped.** Every **[RRV]** item in this document has since been resolved by inspecting the actual mod jars, and three load-bearing findings turned out to be wrong. Corrections are marked **[CORRECTED]** inline; `CHANGELOG.md` summarises them. The mod id is **`mcamobcompat`** (no underscore, matching the rest of the MCA addon suite), the package root is `dev.otectus.mcamobcompat`, and the licence is GPL-3.0-only.
>
> **0.2.0 shipped: Guard Villagers bridge.** Treating Guard Villagers' `guardvillagers:guard` entities as if they were MCA guards for all four addons (MCAQuests, MCACrime, MCAReputation, MCAConversations), without converting their model, skin or AI. Behind a SERVER config option `treatGuardsAsMcaGuards` (default true).
>
> **Reworked after 0.2.0, released in 0.2.1: hidden MCA proxy.** The identity-store-only bridge below could not reach MCA's own interaction screen, marriage/family/genetics or village residency, because all four addons and MCA's own screen resolve a real `VillagerEntityMCA` (by class or by UUID) and `VillagerLike` is a closed interface no mixin-free guard can implement. Every bridged guard now gets a real, hidden `mca:*_villager` (profession `mca:guard`) that carries all of that state and receives every MCA interaction; the guard forwards its plain click to it and relays damage and death onto it. See §10A.
>
> **0.2.1 implemented: real MCA guards, chore safety, creeper awareness, a mount policy, Icy's Better Horses, Alex's Mobs and MCA 7.7.** It releases the hidden-proxy rework above (§10A), hardened with exact client pairing, transactional creation, confirmed-death relay and a schema-3 identity store. It restrains MCA's hunting chore from animals you care about (§16) through the first reflective MCA binding layer, `mca/McaBinding` (§9). It lets MCA villagers see a creeper coming through MCA's own memories (§13C), refuses a villager a mount it cannot steer (§11, §11A), and adds Icy's Better Horses (§13A) and Alex's Mobs (§13B). Every behaviour is exercised by a GameTest against the real mods (§23); `docs/VALIDATION.md` records the runs.

---

## TL;DR
- **This is a Forge 1.20.1 addon (`mcamobcompat`) that hard-depends only on MCA Reborn (mod id `mca`) and treats SWEM, Genetic Animals, DragN's Livestock Overhaul, Guard Villagers, Icy's Better Horses and Alex's Mobs as optional integrations** driven by datapack `TagKey<EntityType<?>>` tags plus a small classification API — recognition and delegation, never reimplementation.
- **[CORRECTED] Classloading isolation turned out to need no machinery at all.** Every 0.1.0 behaviour is reachable through vanilla APIs and registry ids, so the shipped jar references **no type from MCA or from any optional mod** — no reflective binding layer, no Mixin, no access transformer, and not one `compileOnly` optional dependency. `NoOptionalModStaticLinkTest` fails the build if that ever stops being true. 0.2.0 implements an identity bridge to treat Guard Villagers guards as MCA guards for the addon suite, still with no direct class references (§10A).
- **0.1.0 ships the framework, semantic classification, shipped default tags, a read-only public API, a `/mcamobcompat` diagnostic command, and the Guard Villagers safety work:** classifying guards, *blocking* the MCA→Guard conversion that destroys `FamilyTree`/marriage/genetics data, alerting guards when an MCA villager is attacked, and stopping guards attacking MCA villagers.
- **0.2.0 implemented: Guard Villagers as MCA guards (bridge).** Identity store + predicate + Forge events, with the four addons patched to accept the bridge predicate. **After 0.2.0 this was reworked into a hidden MCA proxy** (a real, invisible `mca:*_villager` per bridged guard) so MCA's own screen, marriage, family tree, genetics and village residency are reachable after all. See §10A for scope and design.
- **[CORRECTED] Every [RRV] is now resolved**, three of them against the spec's own expectations: MCA villagers *are* vanilla `Villager`s (§5), Guard Villagers reads *no* entity-type tags so targeting cannot be tag-steered (§10), and Genetic Animals' mod id is **`eanimod`** (§12).
- **0.2.1 implemented:** the hidden proxy released and hardened (§10A), chore safety, creeper awareness through MCA's own memories (§13C), a mount policy built on the corrected SWEM root cause (§11, §11A), Icy's Better Horses (§13A), Alex's Mobs (§13B) and MCA 7.7. Still no mixin and no access transformer (§18).

---

## 1. Executive Summary
MCA: Mob Compatibility (`mcamobcompat`) provides deep, extensible, gracefully-degrading compatibility between **MCA Reborn** and mods that add/replace/modify animals, NPCs, mounts, livestock, pets, and guards. It is architected as a **tag-and-classification framework**, not a bundle of hardcoded patches. Its non-negotiable principles: (1) never replace another mod's internal behavior unless unavoidable; (2) prefer recognition + delegation; (3) never assume a modded animal behaves like its vanilla equivalent; (4) never silently destroy MCA relationship/family/genetics data; (5) optional integrations are genuinely optional; (6) no direct class references to optional mods outside isolated integration modules.

The mod hard-requires `mca` (and, on MCA 7.6, its transitive `architectury`) and treats `swem`, `guardvillagers`, `eanimod` (Genetic Animals), `dragnlivestock`, `icys_better_horses` and `alexsmobs` as optional. **[C, mod id CORRECTED — see §4.5]**

## 2. Goals
- A semantic entity-classification layer (`livestock`, `cattle`, `sheep`, `pig`, `poultry`, `horse`, `mount`, `pet`, `guard`, `humanoid_npc`) driven primarily by Forge entity-type tags, extensible by modpack authors via datapacks without recompiling.
- Bridges from MCA systems (villager/guard recognition, village membership, farmer/livestock chores, mounts, pets) to modded mobs.
- Optional-dependency isolation with zero `NoClassDefFoundError` risk.
- Absolute preservation of MCA relationship/family/genetics/persistent data.

## 3. Non-Goals
- No new mobs, breeding systems, or genetics.
- No replacement of SWEM/Genetic Animals/DLO internal AI, breeding, or needs logic.
- No conversion of MCA villagers into Guard Villagers (explicitly blocked — §10).
- No conversion of Guard Villagers guards into MCA villagers, and no mixin on Guard Villagers' `Guard` class. **[REVERSED after 0.2.0]** a hidden, invisible real MCA villager *is* now paired with every bridged guard — see §10A for why the identity-store-only design could not reach MCA's own screen, marriage or genetics, and could not be made to without one.
- No MCA family, marriage or genetics *for the guard entity itself* — those live on its hidden proxy, which is a real `VillagerEntityMCA` (§10A).
- No autonomous equestrian AI. MCA villagers already ride rideable entities, and MCA never initiated autonomous mounts. Equestrian mounts are player-commanded and remain so; they need no independent AI (§11). **0.2.1:** a mount only a player can steer refuses an MCA villager rather than carrying an inert one (§11A). No mount-steering framework is built, because on 1.20.1 nothing would drive it: MCA already steers vanilla horses, and SWEM cannot be steered from outside it without a mixin.
- No redistribution of any external mod's assets (SWEM, Genetic Animals, and DLO assets are All Rights Reserved — §27). **[C]**

## 4. Research Findings

### 4.1 MCA Reborn **[C except where noted]**
- **Mod id:** `mca`. **License:** GPL-3.0. **CurseForge project 535291**, maintained (per install guides) by jahx_senpoopie with CDAGaming_ and Conczin. Requires **Architectury API** on 1.20.1 (README, 1.20.1 branch: "Architectury API is required on Minecraft 1.18.2 and above"). Fabric build additionally needs Fabric API; these do not affect the Forge addon.
- **Latest 1.20.1 build:** per CurseForge, the newest 1.20.1 file is **MCA Reborn 7.6.20 (updated June 14, 2026)**; 7.6.5+1.20.1 (May 17, 2025) is superseded. Build against a recent 7.6.x file. **[C from jar]**
- **Package root [CORRECTED]:** neither `net.mca` nor `mca`. The shipped 1.20.1 jars are Forgix-merged, so classes carry a loader prefix: **`forge.net.mca.*`** up to 7.7.0-beta.2 and **`forge.net.conczin.mca.*`** from 7.7.1-alpha.2 onward. Confirmed from the jars and corroborated by their own `forge-mca.mixin.json`, which declares `"package": "forge.net.conczin.mca.mixin"`. *This is moot for this addon, which names no MCA class at all* — but it is exactly why the rest of the suite needs a runtime binding layer.
- **Main entity [CORRECTED]:** `VillagerEntityMCA` **does extend vanilla `net.minecraft.world.entity.npc.Villager`.** Verified with `javap` on 7.7.1-alpha.3:
  ```
  public class forge.net.conczin.mca.entity.VillagerEntityMCA
      extends net.minecraft.world.entity.npc.Villager
      implements VillagerLike<VillagerEntityMCA>, MenuProvider,
                 CompassionateEntity<BreedableRelationship>, CrossbowAttackMob
  ```
  The earlier claim that it descends from `PathAwareEntity` and is *not* a vanilla `Villager` was backwards, and inverting it changes two things: every vanilla `Villager` API works on an MCA villager (good), and every mod that gates on `instanceof Villager` reaches them (which is the whole danger in §10).
- **Contracts [C from jar]:** `VillagerLike<E extends Entity & VillagerLike<E>>` is an abstract contract (not a concrete class); `VillagerEntityMCA` implements it. `Village` and `Residency` are hard-typed to `VillagerEntityMCA`; neither accepts a plain `Entity` or `Mob`. `GuardEnemiesSensor` is a guard-targeting sensor with priority logic; it checks **(1)** `candidate instanceof VillagerEntityMCA` (priority 10 if not hostile, -1 otherwise); **(2)** is current target (9); **(3)** config map lookup by entity-type registry name; **(4)** `guardsTargetMonsters` + `instanceof Enemy` (3); **(5)** else -1. `guardvillagers:guard` is not in the default map and is not an `Enemy`, so MCA guards ignore it by default. MCA reads **no entity-type tags** for targeting.
- **Entity registry:** `net.mca.entity.EntitiesMCA`, exposing `MALE_VILLAGER` and `FEMALE_VILLAGER` (Architectury `RegistrySupplier`, accessed via `.get()`). Registered resource names: **`mca:male_villager`** and **`mca:female_villager`**. There is **no single `mca:villager`**.
- **Relationship/family data:** `net.mca.entity.ai.relationship.EntityRelationship`, `RelationshipState` (values include `SINGLE`, `WIDOW`); `net.mca.server.world.data.FamilyTree` + `FamilyTreeNode` (methods `getOrEmpty(uuid)`, `getAllWithName`, `node.partner()`, `updatePartner(...)`, `setDeceased(...)`).
- **Synced/persistent villager data:** `net.mca.util.network.datasync.CDataManager` (registered in `VillagerEntityMCA.initDataTracker`). Player data: `net.mca.server.world.data.PlayerSaveData`.
- **Village system:** `net.mca.server.world.data.Village`, `VillageManager` (`VillageManager.get(world)`, `findNearestVillage(Entity)` accepts any `Entity`, `findVillages`), `Building`. `Residency` is a `VillagerEntityMCA`-only class with methods `getHomeVillage()`, `seekHome()`, `leaveHome()`, `setHome(ServerPlayer)`, etc.
- **Genetics/personality/mood:** genetics is a headline feature; villager memories/hearts accessed via `getVillagerBrain().getMemoriesForPlayer(player)` (`modHearts`, `setHearts`). Exact `Genetics`/`VillagerBrain`/reputation (`Rank`) class paths are **[I]** (likely `net.mca.entity.ai.Genetics`, `net.mca.entity.ai.brain.VillagerBrain`).
- **Guard/Archer:** MCA has its **own** Guard and Archer professions and combat AI; guard target priorities support entity-type tags (e.g. `#minecraft:undead`) per changelog. Exact guard AI goal class paths **[I]**.
- **MCA 7.7 [C from jar, 0.2.1]:** 7.7.1-alpha.2+1.20.1 roots at `forge.net.conczin.mca` and makes `Personality` a registry type (`getId()`, static `get(ResourceLocation)`) where 7.6 has an enum; `FamilyTreeNode.probablyGenerated()` exists only in 7.7. `McaBinding` handles both shapes, and `/mcamobcompat mca` reports which it found.
- **Brain [C from jar, 0.2.1]:** `VillagerEntityMCA.makeBrain` builds from MCA's own `VillagerTasksMCA`, never from vanilla `VillagerGoalPackages`. MCA registers its own memory types by registry id — `mca:player_following_memory` (Follow) and `mca:nearest_guard_enemy` (guard combat) — which `mca/McaMemories` reads and writes through vanilla `Brain` with no MCA class. `Brain.getMemory` throws for a memory the brain never registered, so every access first checks `checkMemory(type, MemoryStatus.REGISTERED)`, and a registry lookup is checked by key because the memory registry is defaulted.
- **Creepers [C from jar, 0.2.1]:** MCA's `ExplodingCreeperSensor` writes vanilla `NEAREST_HOSTILE` only for a creeper that `isIgnited()` — lit with flint and steel. A creeper that walks up and swells is invisible to MCA until it explodes. MCA's panic package runs `SetWalkTargetAwayFrom` on `NEAREST_HOSTILE` and `HURT_BY_ENTITY`, and MCA's `guardsTargetMonsters` defaults to false.
- **Riding [C from jar, 0.2.1]:** MCA seats a villager only on the player-issued "Ride Horse" order, which picks a saddled `AbstractHorse`. While riding, `VillagerEntityMCA.getNavigation()` and `getMoveControl()` delegate to the horse, so on a vanilla horse MCA steers. See §11 for why SWEM's horse does not move.
- **Profession change [C from jar, 0.2.1]:** MCA's `setProfession` rebuilds the villager's brain; setting `VillagerData` directly does not, so an in-place recruit must call `refreshBrain` itself.
- **Public API:** **No confirmed formal public `net.mca.api` Java developer API.** Existing third-party addons (Vampirism compat, Easy Villagers compat) depend directly on internal classes such as `VillagerEntityMCA`. Plan for internal-class coupling inside the MCA bridge, not a stable API.

### 4.2 Guard Villagers **[C]**
- **Mod id:** `guardvillagers`. **Package:** `tallestegg.guardvillagers`. **Entity [CORRECTED]:** `tallestegg.guardvillagers.entities.Guard` (not `common.entities`); entity type **`guardvillagers:guard`**. Source: github.com/seymourimadeit/guardvillagers (Java 100%). **CurseForge project 360203.**
- **License:** custom; CurseForge states verbatim "the mod's license permits you to make ports yourself" — addons/ports are explicitly allowed.
- **Latest 1.20.1 Forge build:** **guardvillagers-1.20.1-1.6.19.jar** (confirmed in study jar for 0.2.0). **[C]**
- **Class facts [C from jar]:** `Guard extends net.minecraft.world.entity.PathfinderMob`, implements `CrossbowAttackMob`, `RangedAttackMob`, `NeutralMob`, `ContainerListener`, `ReputationEventHandler`. Fields: `private final GossipContainer gossips`, `public SimpleContainer guardInventory`, `OWNER_UNIQUE_ID`, `GUARD_POS`, `PATROLLING`, `GUARD_VARIANT (String)`. Methods: `GossipContainer getGossips()`, `int getPlayerReputation(Player)`, `void gossip(Villager, long)`, `void openGui(ServerPlayer)`. NBT keys: `Owner`, `Gossips`, `LastGossipTime`, `Inventory`, `ArmorItems`, `HandItems`, `PatrolPosX/Y/Z`, `Following`, `Patrolling`, and others.
- **Handler events [C]:** `HandlerEvents` class; `onEntityTarget(LivingChangeTargetEvent)`, `onEntityHurt(LivingHurtEvent)`, `onEntityInteract(PlayerInteractEvent.RightClickBlock)`, `onLivingSpawned(EntityJoinLevelEvent)`.
- **Conversion mechanic:** crouch + right-click a **nitwit or unemployed (vanilla) villager** holding a sword/crossbow. Guards gossip with villagers; inventory trust requires high reputation, Hero of the Village, or player-conversion. **Equipment is datapack-driven** (`armor_sets` loot tables under `data/guardvillagers/loot_table/entities` in 1.20.1).

### 4.3 SWEM (Star Worm Equestrian Mod) **[C]**
- **Mod id:** `swem`. **Entity type:** `swem:swem_horse`. Requires **GeckoLib + PlayerAnimator**. **License All Rights Reserved** (addons allowed if ARR respected; source code private). **CurseForge project 582697** (by AlaharranHonor); latest 1.20.1 Forge file **swem-1.20.1-1.6.6.jar**.
- **Architecture:** SWEM horses are passive, tamable mobs with their **own ownership** (`Owner` UUID NBT + trusted players), needs (food/water), trainable skills, breeding tokens/foals, `HorseVariant`, and a `NoAI` tag. They **do not spawn naturally and do not override vanilla horses.** **[RESOLVED — there is no bespoke key.]** SWEM extends vanilla `AbstractHorse` and overrides the vanilla owner accessors, so ownership reads back from `AbstractHorse.getOwnerUUID()` with no SWEM class and no NBT parsing. See §11.

### 4.4 DragN's Livestock Overhaul (DLO) **[C]**
- **Mod id:** `dragnlivestock`. **Entities ("O-Animals"):** confirmed ids include `dragnlivestock:o_horse`, `o_mule`, `o_donkey`, `o_rabbit`, `caribou`, `overworld_unicorn`, `nether_unicorn`, `end_unicorn`. Covered species also include cows/oxen, mooshrooms, pigs, sheep, llamas, goats, camels, bees, cod, salmon, frogs, undead horses. **Code license GNU/GPL; assets All Rights Reserved (no redistribution).** Source: github.com/DragN0007/Overhauled-Livestock (and the 1.20.1 repo `Overhauled-Livestock-1.20.1`). Modrinth project **fNM5n4ac** (Maven `maven.modrinth:fNM5n4ac`).
- **Latest 1.20.1 build:** Modrinth lists **1.20.1-2.3 (Mar 5, 2025)**; CurseForge additionally shows newer 1.20.1 files (e.g. 2.8, 3.x). Verify current at build time.
- **Behavior:** auto-converts vanilla animals into **separate** O-Animal entities on load; conversion is configurable via `livestock-overhaul-common.toml`. Changelog verbatim: "**O-Animals now only replace vanilla animals**" and "**Pigs have been switched to the enum gender code**" — DLO owns its gender/breeding/genetics logic. **[RESOLVED]** The complete entity list is in §13; DLO also ships its animals into the Forge conventional entity tags, which is worth more than the id list.

### 4.5 Genetic Animals **[C]**
- **Mod id [CORRECTED]: `eanimod`** — *not* `geneticanimals`. Confirmed from the jar's own `META-INF/mods.toml` (`modId="eanimod"`, `version="0.11.9"`, `license="All Rights Reserved"`, authors Moki, BeardedDev, Nicole, Mika, Blazing). The Java package root is `mokiyoki.enhancedanimals` — the mod began life as Enhanced Animals, which is where the id comes from. **CurseForge project 313213**; Modrinth Maven `maven.modrinth:DYjPZExA`.
- **Behavior:** **replaces** cows, pigs, chickens, sheep, llamas, rabbits, mooshrooms, sea turtles, and axolotls with genetically-driven entities (genes, genders, pregnancy/egg fertility, grazing, hunger, sleeping, healing). Adds **Forge tags to GA items** and recipes converting GA drops to vanilla equivalents; GA animals spawn in the same biomes as vanilla counterparts. **[RESOLVED]** Entity ids, from `assets/eanimod/lang/en_us.json`: `eanimod:enhanced_cow`, `enhanced_pig`, `enhanced_sheep`, `enhanced_chicken`, `enhanced_llama`, `enhanced_rabbit`, `enhanced_mooshroom`, `enhanced_moobloom`, `enhanced_turtle`, `enhanced_axolotl`, `enhanced_horse`.

### 4.6 Icy's Better Horses **[C from jar, 0.2.1]**
- **Mod id:** `icys_better_horses`, version 1.1.6 for Forge 1.20.1 (Modrinth version `M2ySgx5C`); needs GeckoLib 4.7.2 or later and Patchouli. Package root `icy.betterhorses.net`.
- **Registers no entity types.** It mixes into vanilla `AbstractHorse` and adds `IHorseData`: `bh_getOwner`, `bh_isOwned`, `bh_getCommand` (FOLLOW, STAY, RETURN_HOME, SET_HOME, WANDER) and `bh_isHitched`. Its owner is its own, separate from vanilla's tamed owner.
- **Owner-only riding** (its `horse_exclusivity` option, on by default): `bh_gateOwnerOnlyMount` refuses a non-owner *player*, and `bh_enforceOwnerPrimaryRider` bucks a non-owner *player* out of the driver's seat. Both test `instanceof Player`, so a villager is never checked.
- **Commands** run as goals only while a horse is owned and carries no rider, and a fresh horse's command is FOLLOW. A villager's ride therefore never competes with a command. No carts exist on 1.20.1.

### 4.7 Alex's Mobs **[C from jar, 0.2.1]**
- **Mod id:** `alexsmobs`, version 1.22.9 for Forge 1.20.1 (Modrinth version `XoIASRVU`); needs Citadel 2.6.0 or later. It registers 116 entity types, body parts and projectiles included. The per-type audit is `docs/ALEXS_MOBS_AUDIT.md`.
- Its tamable species extend vanilla `TamableAnimal`, so vanilla `OwnableEntity` reports their owners.
- Six rideable species — elephant, grizzly bear, Komodo dragon, tusklin, endergrade and laviathan — return a controlling passenger only when it is a `Player`, the same pattern as SWEM's horse.
- Its entity tag `alexsmobs:villagers`, which its predators use to choose prey, names only `minecraft:villager` and `minecraft:wandering_trader`. Ten of its classes refer to vanilla `Villager` directly, and those do reach MCA villagers.

### 4.8 Golems Kill Creepers **[C from jar, 0.2.1]**
- **Mod id:** `golemskillcreepers`, version 1.0.0+1.20.1 for Forge. Iron-golem mixins and two gamerules, `doIronGolemsAttackCreepers` and `doIronGolemsInstakillCreepers`; no villager code. Nothing needs integrating: this mod changes no golem, creeper or gamerule, and `/mcamobcompat threats inspect` shows the two gamerules.

## 5. MCA Reborn Architecture Analysis (integration surface + preferred mechanism)
| MCA integration point | Preferred mechanism (least invasive first) |
|---|---|
| Recognize external NPCs/guards as villagers | Forge entity-type tags + our classifier (tag registration) |
| Treat foreign guards as MCA guards (0.2.1) | A hidden, real MCA villager paired with each guard, which MCA and the addons see natively (§10A) |
| Guard defense / react to attacks on MCA villagers | Forge `LivingHurtEvent`/`LivingDeathEvent` + tag membership (event) |
| Farmer/livestock chores recognize modded animals | classification tags queried via our API (tag + API) |
| Mounts / pets | tags + **read-only** NBT/owner inspection (tags + optional reflection) |
| Warn villagers of creepers (0.2.1) | Write vanilla `NEAREST_HOSTILE`/`WALK_TARGET` and MCA's `mca:nearest_guard_enemy` memories (§13C) |
| Keep villagers off mounts they cannot steer (0.2.1) | Cancel `EntityMountEvent` (§11A) |
| MCA family/genetics/relationships | **Read-only**, with two narrow exceptions on a guard's proxy: hearts, through MCA's own methods, and removing the proxy's own family-tree node when its spawn is rolled back (§10A) |

**Critical constraint [CORRECTED].** The original claim here was inverted. `VillagerEntityMCA` **extends vanilla `net.minecraft.world.entity.npc.Villager`** (§4.1), so external logic using `instanceof Villager` *does* recognise MCA villagers. Two consequences, and they pull in opposite directions:

- **Helpful:** every vanilla `Villager` API works on an MCA villager, and vanilla village mechanics already include them. Iron golems defend them for free, via `DefendVillageTargetGoal`.
- **Dangerous:** so does every mod that gates on `instanceof Villager` without knowing about MCA. That is precisely how the Guard Villagers conversion reaches them and destroys them (§10).

Detection in this addon still uses the entity types `mca:male_villager` / `mca:female_villager` rather than a cast — not because a cast would fail, but because a registry-id check needs **no MCA class**, and so is immune to MCA's package renames (§4.1). The authoritative set is a Java constant in `McaEntities`, deliberately *not* a datapack tag: the conversion blocker is the one piece of this mod whose failure destroys unrecoverable player data, and it must not be switchable from data. The tag `mcamobcompat:mca_villagers` is additive on top of it.

## 6. Compatibility Definition
Full compatibility means: (1) no crashes / `NoClassDefFoundError`; (2) MCA villagers and modded mobs coexist without breaking each other's AI or data; (3) MCA behaviors (guard defense, farmer chores, mount/pet recognition) treat compatible modded mobs sensibly via semantic categories; (4) each external mod retains **sole** ownership of its breeding/genetics/needs logic. It is explicitly **not** merely crash-prevention.

## 7. Core Architecture
```
mcamobcompat  (dev.otectus.mcamobcompat)
 ├── api/            MobCompatibilityAPI, MobRole, ClassificationProvider, GuardIdentity, GuardGender
 │   └── event/      GuardInteractEvent, GuardIdentityCreatedEvent, GuardHeartsChangedEvent
 ├── bridge/         GuardBridge, GuardIdentityData, GuardIdentityStore, GuardIdentityView,
 │                   GuardNames, GuardInteractions, GuardProxy, GuardProxySync
 ├── classification/ TagClassifier, ClassificationCache, RoleCache, ProviderRegistry,
 │                   TamedMountProvider, OwnedAnimalProvider
 ├── client/         ProxyRenderHandler, ClientProxyPairs   (the only Dist.CLIENT code)
 ├── command/        McaMobCompatCommand
 ├── event/          ConversionGuardHandler, GuardDefenseHandler, GuardAlerts,
 │                   ClassificationReloadHandler, ChoreSafetyHandler, ChoreProtectionPolicy,
 │                   GuardBridgeHandler, GuardProxyHandler, GuardDamageRelayHandler,
 │                   ProxySpawnHandler, ProxyShapeHandler, MountPolicyHandler,
 │                   ServerLifecycleHandler
 ├── integration/    IntegrationModule, ModIntegrations, guardvillagers/, swem/,
 │                   geneticanimals/, dragnlivestock/, icysbetterhorses/, alexsmobs/
 ├── mca/            McaEntities, McaBinding, McaMemories
 ├── mount/          MountPolicy, MountDecision, MountRefusal, NpcRidingPolicy
 ├── network/        ProxyPairs   (the mcamobcompat:proxy_pair channel)
 ├── threat/         ThreatPolicy, ThreatAssessor, ThreatHandler
 ├── util/           ModIds, ConfigAccess
 ├── data/mcamobcompat/tags/entity_types/*.json  (shipped default tags)
 ├── McaMobCompat.java        (@Mod entrypoint)
 └── McaMobCompatConfig.java  (COMMON + SERVER ForgeConfigSpec)
```

**[CORRECTED] 0.1.0 has no `bridge/` and no `config/` package.** Config is one file, following suite convention. **0.2.0 adds `bridge/` and `api/event/`** for guard identity and the bridge events. **0.2.1 adds `client/`, `network/`, `mount/` and `threat/`**, and `mca/McaBinding` and `mca/McaMemories`.

Each `integration/<mod>` package is entered only behind a `ModList.get().isLoaded("<modid>")` gate
and remains the only place permitted to reference that mod's classes. **In 0.1.0 none of them does**
— the seam exists for later. **In 0.2.1 none does either:** `guardvillagers/GuardReflection` and
`icysbetterhorses/IcyReflection` look methods up by their owners' own member names on the entity's
runtime class, never by naming a class, which is safe because those names are not SRG-mangled.

## 8. Entity Classification System
- **Primary mechanism: Forge `TagKey<EntityType<?>>` datapack tags** under
  `data/mcamobcompat/tags/entity_types/` — one file per role: `livestock`, `cattle`, `sheep`, `pig`,
  `poultry`, `horse`, `mount`, `pet`, `guard`, `humanoid_npc`.
- **`MobRole` enum mirrors the tags** (renamed from `MobCategory` — see §15).
  `TagClassifier.rolesFor(EntityType<?>)` returns the matching set; results are **cached per
  `EntityType`** and invalidated on `TagsUpdatedEvent` (world load and every `/reload`) and on config
  reload. Only type-level results are cached — per-entity provider results never are, because they
  are allowed to differ between two entities of the same type.
- **Java `ClassificationProvider`s** supply roles where a static tag cannot. 0.1.0 ships exactly one,
  `TamedMountProvider`, which marks any tamed `AbstractHorse` as a `mount` — a fact about the
  individual animal that no tag could carry, and one that covers modded equines nobody has tagged.
  **0.2.1 adds `OwnedAnimalProvider`**, which marks any owned `OwnableEntity` other than a horse as a
  `pet`. Providers are skipped for a namespace whose module is switched off.
- **Behaviour tags (0.2.1)** sit beside the roles: `npc_threats` (§13C) and `player_only_mounts`
  (§11A). They are read directly, not through `MobRole`.
- **Tags compose.** `livestock` references `#mcamobcompat:cattle`, `#mcamobcompat:sheep`,
  `#mcamobcompat:pig` and `#mcamobcompat:poultry` rather than repeating their contents, so adding a
  mob to `cattle` makes it livestock automatically.
- **Shipped tags lean on the Forge conventional tags** (`#forge:cow`, `#forge:pig`, `#forge:horses`,
  …) wherever one exists, so any mod that registers its animals into those is classified with no work
  at all. This is how most of DragN's Livestock Overhaul is covered (§13).
- **Horses are deliberately not inside `livestock`.** A player's horse is not a farm animal for chore
  purposes. A pack that disagrees adds one line; the reverse would need `"replace": true`, so the
  narrower default is the one that is easy to undo.
- **`guard` deliberately excludes `minecraft:iron_golem`.** Golems already defend MCA villagers,
  because `DefendVillageTargetGoal` matches `instanceof Villager` and MCA villagers pass it (§5).

## 9. Optional Dependency Strategy (Forge 1.20.1) **[C on mechanism]**
- Hard dependency `mca` (mandatory); Architectury is a transitive runtime need of MCA 7.6 and is not
  declared. Optional: `swem`, `guardvillagers`, `eanimod` (Genetic Animals), `dragnlivestock`,
  `icys_better_horses`, `alexsmobs`.
- Each integration is a self-contained module consulted only after `ModList.get().isLoaded(id)`
  returns true, and each mod's package is the only place permitted to reference its classes. This is
  the standard Forge pattern for avoiding `NoClassDefFoundError`.
- **[CORRECTED] In 0.1.0 the pattern is not exercised, because nothing needs it.** Every behaviour
  turned out to be reachable through vanilla APIs, registry ids and datapack tags:

  | Need | Mechanism | Foreign classes |
  |---|---|---|
  | Detect an MCA villager | registry id `mca:male_villager` / `mca:female_villager` | none |
  | Block the guard conversion | cancel `PlayerInteractEvent.EntityInteract` | none |
  | Guard defends a villager | `Mob.setTarget` on `guard`-tagged mobs | none |
  | Stop guard friendly fire | cancel `LivingChangeTargetEvent` | none |
  | SWEM / DLO horse owner | `AbstractHorse.getOwnerUUID()` | none |
  | Classify GA / DLO / SWEM mobs | datapack entity-type tags | none |
  | Read the convertible-item set | `#guardvillagers:convertible_guard_items` item tag | none |
  | Guard follow/patrol state (0.2.1) | method handles by name on the guard's runtime class (`GuardReflection`) | none named |
  | Icy's owner, command, hitch (0.2.1) | `bh_*` methods by name on the horse's runtime class (`IcyReflection`) | none named |
  | MCA brain, hearts, personality, family tree (0.2.1) | reflection from dotted class-name strings (`McaBinding`, the one allowlisted class) | named as strings only |
  | MCA memories (0.2.1) | registry ids through vanilla `Brain` (`McaMemories`) | none |
  | Refuse a mount (0.2.1) | cancel `EntityMountEvent` | none |

  So there is **no `compileOnly` optional dependency and no Mixin** (0.1.0), and
  no `@Mod.EventBusSubscriber` needs gating, because none of them touches a foreign class.
  `NoOptionalModStaticLinkTest` and the `checkJarContents` Gradle task both fail the build if that
  changes. **[CORRECTED] 0.2.1 adds the reflective binding layer (`mca/McaBinding`) needed for chore safety, and both guards were tightened: byte-scans now catch dot-form `Class.forName` literals, not just slash-form bytecode references, with an exact-match allowlist of one file.**
- Optional-mod jars are never shaded, and are never a compile dependency. Detection and module
  registration are logged once at startup.

## 10. Guard Villagers Integration
Entity `guardvillagers:guard`. All 0.1.0 work is event- and tag-based; no invasive patches, and no
reference to a Guard Villagers class anywhere.

- **Classify `guardvillagers:guard` as `guard` + `humanoid_npc`** (shipped default tag). **[C]**

- **Block MCA→Guard conversion (safety-critical) — [RESOLVED, and worse than feared].** The spec
  hoped the conversion could not fire on an MCA villager. It can, and it does so under **default
  config**. `tallestegg.guardvillagers.VillagerToGuard.onEntityInteract` gates, in order, on: the
  main-hand item being in `#guardvillagers:convertible_guard_items`; `player.isCrouching()`;
  **`target instanceof net.minecraft.world.entity.npc.Villager`** — which an MCA villager passes
  (§5); `!isBaby()`; and `GuardConfig.COMMON.convertibleProfessions` containing
  `getVillagerData().getProfession().name()`, whose **default is `["nitwit", "none"]`** — and a
  jobless MCA villager reports `"none"`. `convertVillager` then copies across only position, custom
  name, invulnerability and the main-hand stack before calling `villager.discard()`. Nothing reads
  MCA's `CDataManager` data, so family tree, marriage, children, genetics, personality and inventory
  are destroyed.

  **Implemented:** `ConversionGuardHandler` cancels `PlayerInteractEvent.EntityInteract` at
  `EventPriority.HIGH`. That is sufficient because the Guard Villagers handler is a bare
  `@SubscribeEvent` — priority `NORMAL`, `receiveCanceled = false` — so it never sees a cancelled
  event. The block is deliberately a **superset** of the conversion: its final gate is a check
  against its own config, which cannot be read without naming one of its classes, so we block on
  MCA villager + crouching + convertible item and accept that crouch-right-clicking an MCA villager
  *while holding a sword or crossbow* also does not open MCA's own screen. **With
  `recruitMcaVillagersAsGuards` on (default), that gesture recruits the villager as a guard in
  place instead of doing nothing at all — see §10A "Recruit gesture".**

  **Both logical sides [C, 0.2.1].** `VillagerToGuard.convertVillager` runs `addFreshEntity` and
  `villager.discard()` on the client as well; only its particle loop is client-gated. So the handler
  cancels on both sides, before any client-side early return. Cancelling on the server alone leaves
  the recruiting player's client with the villager discarded, which looks like an invisible villager
  until the chunk reloads.

- **Protect MCA villagers — [RESOLVED, approach replaced].** The spec proposed doing this by
  including MCA villagers in "the guard-relevant tag set". **That is impossible: Guard Villagers
  reads no entity-type tags at all.** Its `GuardTags` class declares exactly one tag and it is a
  `TagKey<Item>`. Protection is instead string-matched config:
  ```
  HandlerEvents.onEntityTarget(LivingChangeTargetEvent):
      GuardConfig.COMMON.mobsGuardsProtectTargeted.get().contains( newTarget.getEncodeId() )
  HandlerEvents.onEntityHurt(LivingHurtEvent):
      GuardConfig.COMMON.mobsGuardsProtectHurt.get().contains( ... )

  defaults for both: "minecraft:villager", "guardvillagers:guard", "minecraft:iron_golem"
  ```
  `getEncodeId()` on an MCA villager returns `mca:male_villager`, which is on neither list, so
  stock guards simply do not defend them.

  **Implemented:** `GuardDefenseHandler` mirrors the behaviour for MCA villagers — on
  `LivingHurtEvent` and on `LivingChangeTargetEvent`, nearby `Mob`s carrying `MobRole.GUARD` and
  holding no target of their own are pointed at the attacker, capped by `maxGuardsAlerted`. It uses
  only vanilla `Mob.setTarget` and our own tag, so it works for any mod's tagged guard NPC. **0.2.1:**
  the bounded alert lives in `event/GuardAlerts`, shared with creeper awareness (§13C).
  Retaliation against *player* attackers is off by default, because MCA has its own reputation and
  guard response for that.

- **Prevent friendly fire — [RESOLVED].** The spec marked this **[I]**; it is real. The Guard
  Villagers `MobBlackList` defaults to `minecraft:villager, minecraft:iron_golem,
  minecraft:wandering_trader, guardvillagers:guard, minecraft:creeper, alexsmobs:komodo_dragon,
  minecraft:enderman` — again matched by entity id, and again MCA villagers are not on it.
  **Implemented:** `GuardDefenseHandler` cancels `LivingChangeTargetEvent` when a `GUARD`-role mob
  acquires an MCA villager as its target. **0.2.1:** it also cancels `LivingAttackEvent` at `HIGH`
  when the damage source's entity carries `MobRole.GUARD` and the victim is an MCA villager, so a
  stray arrow or blow never lands.

- **Deliberately not done:** appending the MCA ids to `mobsGuardsProtectTargeted` at runtime would
  be a smaller change, but `ConfigValue.set()` writes to the user's `guardvillagers-common.toml`,
  and writing to another mod's config file is exactly the reaching-in this mod refuses to do.

- **[RESOLVED in 0.2.1] `guardArrowsHurtVillagers`** lives inside the Guard Villagers config and is
  out of reach without referencing one of its classes. It no longer matters: the `LivingAttackEvent`
  veto above covers a guard's arrows without reading it.

- **No reversible conversion is offered.** MCA identity is not derivable from a `Guard`, so blocking
  is the correct policy rather than restoration.

- **Guard Villagers' brain mixin cannot reach MCA villagers [C, 0.2.1].** `VillagerGoalPackagesMixin`
  injects `ShareGossipWithGuard` and its other tasks into vanilla `VillagerGoalPackages`, which MCA's
  `makeBrain` never calls (§4.1).

- **Harmless finding:** the Guard Villagers `VillagerMixin` writes `timesThrownPotion`,
  `timesHealedGolem` and `timesRepairedGuard` NBT onto every `Villager`, MCA's included. It is
  purely additive and does not disturb MCA's data.

## 10A. Guard Villagers as MCA Guards — Hidden Proxy Bridge

**Objective:** Treat `guardvillagers:guard` entities as MCA guards for MCAQuests, MCACrime, MCAReputation, and MCAConversations, without converting their model, skin or AI, behind a SERVER config option `treatGuardsAsMcaGuards` (default true) — and, when `guardMcaInteraction` is also on, give the guard MCA's own interaction screen and every MCA option that goes with it.

**Approach — reworked from the original identity-store-only bridge.** All four addons identify a villager by reflectively checking for MCA's real `VillagerEntityMCA` class or by resolving a live entity by UUID, and MCA's own `InteractScreen` opens for an entity MCA itself owns; an identity store with no matching entity is invisible to all of that. MCA's `VillagerLike` is also a closed interface, so a Guard Villagers guard (a plain `PathfinderMob`) cannot be made MCA-like in place without a mixin, which this project bans. So the design pairs every bridged guard with a real, hidden `mca:*_villager` — the **proxy** — that carries all of the guard's MCA state and receives every MCA interaction, while the guard itself keeps its own model, combat AI and Guard Villagers inventory GUI. **§10A summarizes the design; implementation is `bridge/GuardProxy`, `bridge/GuardProxySync`, and the handlers below.**

### Scope

An entity qualifies as a **bridged guard** if and only if: the option `treatGuardsAsMcaGuards` is on, the module is not suppressed (see §16), the entity carries `MobRole.GUARD` (tag `mcamobcompat:guard`, shipped with `guardvillagers:guard`), **and** `McaEntities.isMcaVillager(entity)` returns false (`bridge/GuardBridge.isBridgedGuard`). A bridged guard additionally gets a proxy only while `guardMcaInteraction` is also on (`GuardVillagersModule.opensMcaInteraction`).

### The proxy: creation, hiding, linking

`bridge/GuardProxy.create(ServerLevel, Mob guard)` is the only way a proxy comes into being. It resolves an existing proxy first, then adopts an unambiguous orphan, and only then creates one, in that order and only when it is safe. It returns null and creates nothing while the guard is inside its 40-tick loading grace period, while a recorded proxy UUID is unresolved (the proxy may only be unloaded), when two orphans claim the guard, when the identity is quarantined or the store is read-only, and when MCA or another mod rejects the new villager. Each of those is survivable: the guard keeps its stock behaviour and the next attempt tries again. A stored link whose persistent key the store has already released is cleaned up rather than blocking creation for good.

A new proxy is `mca:male_villager` or `mca:female_villager` by the guard's rolled gender. It is initialised through `ForgeEventFactory.onFinalizeSpawn` with `MobSpawnType.MOB_SUMMONED`, so it gets a name, genetics, an age state and a family-tree node the way any summoned MCA villager does, and so other mods see Forge's `MobSpawnEvent.FinalizeSpawn` for it. `event/ProxySpawnHandler` captures that event at `HIGHEST` with `receiveCanceled = true`. **Creation is transactional:** a cancelled event, `isSpawnCancelled()`, a wrong villager type or a failed `addFreshEntity` removes the family-tree node MCA just made (`McaBinding.rollbackSpawn`: the proxy's own node always, its generated parents only when MCA 7.7 marks them `probablyGenerated` and they have no other child), discards the villager, and commits neither the seed nor the link. If the family tree cannot be bound at all, no proxy is created, because a rejected one could not be undone.

The proxy gets profession `mca:guard`, a villager type from the guard's Guard Villagers appearance variant (`GuardVariants`, via `GuardReflection.variant`), and five flags that keep it out of the world: `setNoAi`, `setInvisible`, `setSilent`, `setNoGravity` and vanilla `noPhysics`. `event/ProxyShapeHandler` gives it a collision box at most 0.2 wide and nine tenths of a villager's height, nested inside the guard's, through `EntityEvent.Size` at `LOWEST`. Every ray — a projectile's, a player's pick, a melee swing — therefore meets the guard first, and vanilla's own hit code lands on the entity that fights. The pair is linked in both directions through persistent data, `mcamobcompat:proxy` on the guard and `mcamobcompat:guard` on the proxy, and in the identity store; every proxy also carries the scoreboard tag `mcamobcompat_proxy` so an operator can find it by selector. The stored name and personality are pushed onto the proxy exactly once (`GuardIdentityData.Entry.seeded`); after that MCA owns them.

Proxies are created only from `event/GuardProxyHandler`'s `LivingTickEvent` handler (every 20 ticks), never from `EntityJoinLevelEvent`: a guard and its existing proxy are added to a loading chunk in list order, and creating one from the join event would race that order and produce a duplicate on every reload. **No proxy is ever deleted on a timer.** A proxy whose guard is not loaded is kept, because "unloaded" and "gone" cannot be told apart. A guard that changes level takes its proxy with it via `teleportTo`, from its own tick as well as on joining the level, never by recreating it, because a new proxy is a new villager to MCA and would lose the family-tree node and every heart earned so far.

A proxy removed as `KILLED` or `DISCARDED` — an operator's `/kill`, say — releases its link, and the living guard is given a fresh proxy on its next attempt. A guard discarded outright takes its proxy with it; a guard killed is handed to the death relay below.

### Hiding it from the client

`setInvisible(true)` alone is not enough: MCA draws clothing, hair, face and skin as separate render layers that ignore invisibility, so an invisible MCA villager would be a suit of floating clothes with a face. **0.2.1 tells the client exactly which entity is a proxy.** `network/ProxyPairs` registers a `SimpleChannel`, `mcamobcompat:proxy_pair`, protocol `"1"`, required on both sides. One message, `Pair(proxyUuid, proxyId, guardUuid, guardId, enabled, remove)`, is sent when a player starts tracking either entity (`PlayerEvent.StartTracking`), on stop-tracking as a removal, whenever a pair is created, relinked or discarded (`PacketDistributor.TRACKING_ENTITY`), and from the heartbeat whenever the runtime ids change. `client/ClientProxyPairs` keeps a bounded per-level map (4096 pairs), matches on UUID *and* runtime id, re-hides a paired proxy every client tick, and clears on `ClientPlayerNetworkEvent.LoggingOut`. `GuardProxy.isProxy` and `nearbyGuardOf` answer from that map on the client, so a real villager made invisible by a potion is never hidden, and two guards standing together never swap portraits. The flag heuristic used before 0.2.1 is gone.

`client/ProxyRenderHandler` — with `ClientProxyPairs` the mod's only `Dist.CLIENT` code — cancels the whole `RenderLivingEvent.Pre` for a paired proxy. The shadow is already skipped by vanilla for an invisible entity. The one exception is inside a screen: MCAConversations' dialogue frame and MCAQuests' quest menu draw the proxy through vanilla's `InventoryScreen.renderEntityInInventory` helpers. When a screen is open and a `Screen` frame is on the render call's stack (found with `StackWalker`, so no flag can be left set by a cancelled render), the handler draws the paired guard in the proxy's place, with the proxy's rotation fields copied onto the guard for that one call and restored in a `finally`.

### Keeping the pair one NPC

`bridge/GuardProxySync.tick` runs every tick from `GuardProxyHandler`: it pins the proxy's position, rotation and head/body rotation to the guard's, keeps it dismounted, re-asserts the hiding flags, and sets `proxy.setTarget(guard.getTarget())` when the guard's target changes, so MCACrime's self-defence check (which reads `getTarget()`) sees the same enemy the guard does. Mirroring MCA's intent runs only while the guard is bridged and `guardMcaInteraction` is on. Once a second it:

- re-asserts the `mca:guard` profession, in case MCA's own profession-change option moved it;
- reconciles names in both directions with last-seen tracking: a name tag on the guard reaches MCA, and a rename in MCA reaches the guard, with the guard's side winning a simultaneous edit;
- translates MCA's movement intent into Guard Villagers' own state through `integration/guardvillagers/GuardReflection` — MOVE, STAY and FOLLOW onto `setFollowing`, `setPatrolling`, `setPatrolPos` and `setOwnerId`, called only by method name. FOLLOW's owner is read from MCA's own `mca:player_following_memory` (`McaMemories.followTarget`), persisted on the proxy as `mcamobcompat:follow_target` so it survives a restart, with the last player to click the guard only as a fallback;
- flushes pending hearts and refreshes the identity snapshot (`GuardIdentityStore.synchronize`), and resends the pair to trackers if the runtime ids changed.

Any path MCA lays for the proxy (for example MCACrime's `moveVillagerTo`) is replayed onto the guard's own navigation and cancelled on the proxy, because the proxy is brainless and cannot walk.

### Interaction routing

`event/GuardBridgeHandler` handles both `PlayerInteractEvent.EntityInteractSpecific` (the positional packet the client sends first) and `EntityInteract`, at `HIGHEST`, through one `route` method. A click can land on the guard or, where their boxes overlap, on its proxy; the router resolves the visible guard and decides the gesture once, main hand only:

- with `guardMcaInteraction` on, a crouching or shift-down click is replayed into Guard Villagers via `bridge/GuardInteractions.delegateToGuardVillagers` (which clears the shift flag and pose for one call so Guard Villagers' gesture check passes, then runs vanilla's item fallback without re-posting the Forge hooks); a plain click posts `GuardInteractEvent` for the addon suite and, if nothing cancelled it, resolves or creates the proxy and forwards the click by firing `ForgeHooks.onInteractEntityAt` and `onInteractEntity` on the proxy (so an addon listening for those events on an MCA villager, which is how MCAQuests notices a conversation, still sees the click), then `proxy.interactAt`, then vanilla's `interactOn` fallback minus the hooks. If no proxy can be created yet, the player sees `message.mcamobcompat.proxy_pending`;
- with it off, a click on the guard does what it did before the proxy existed — offer `GuardInteractEvent`, then leave the click alone — and a click that lands on an already existing proxy runs the guard's stock gesture instead of opening MCA.

The server checks the guard is alive, in the player's level and within 8 blocks, and handles each click at most once per player per tick, whichever packet carries it. A `ThreadLocal` guards against re-entry while a click is being forwarded. The client cancels the click blind, because it cannot open MCA's screen itself; the screen arrives from the server.

### Damage and death relay

`event/GuardDamageRelayHandler` replays each hit the guard takes onto the proxy with the same damage source. It listens to `LivingDamageEvent` at `LOWEST`, which on Forge carries the final health damage after armour, enchantments, absorption and every other mod's changes, and only for a hit nothing cancelled. The proxy receives exactly that amount (its own mitigation is overridden for the relay, scoped by a `ThreadLocal`) and is topped back up afterwards, so relayed hits never accumulate into a death.

**Death is confirmed, not assumed.** A lethal hit and a `LivingDeathEvent` (recorded at `LOWEST`) are held until the end of the server tick (`TickEvent.ServerTickEvent`, phase END), when the guard's real outcome is known. A totem of undying, or a listener that cancels the death late, leaves a living guard and both halves linked. A death that stands marks the identity deceased and is relayed once, through `proxy.hurt(source, maxHealth + 1)` rather than `kill()` — `kill()` hides the killer, and MCACrime and MCAReputation need it. The link is released only once the proxy is really dying, so MCA's own death handling (family tree, grave, village announcement) finishes undisturbed. A relayed death another mod cancels is never retried, because MCA's death override may already have run its relationship callbacks; the link is kept and a warning logged.

A `LivingAttackEvent` on the proxy is cancelled at `HIGHEST` unless the relay itself is in progress, so nothing — fall damage, fire, a stray mob — can kill it independently of its guard. The one exception is damage that bypasses invulnerability (`DamageTypeTags.BYPASSES_INVULNERABILITY`: an operator's `/kill`, the void), which is how a proxy is removed deliberately. A proxy drops no loot (`LivingDropsEvent`) and no experience (`LivingExperienceDropEvent`).

`event/GuardProxyHandler` also redirects what could otherwise land on the proxy instead of the guard: a melee swing (`AttackEntityEvent`, server side) and a mob's target choice (`LivingChangeTargetEvent`) are re-aimed at the guard. A projectile that still selects the proxy, from exactly where its narrow box pokes out between two ticks, passes through (`ProjectileImpactEvent` with `SKIP_ENTITY`).

### Recruit gesture

With `recruitMcaVillagersAsGuards` on (the default), crouch-and-right-click with a convertible weapon on an MCA villager no longer only blocks Guard Villagers' destructive conversion — it recruits the villager as a guard in place: `event/ConversionGuardHandler.recruit` sets its profession to `mca:guard` via `setVillagerData`, calls `refreshBrain` so MCA's guard behaviour takes over (MCA's own `setProfession` does that, `setVillagerData` does not), and moves one item from the player's hand into the villager's main-hand slot — a copy in creative — leaving name, family tree, genetics, personality and inventory untouched. A hidden proxy is never recruited. A child, a villager already holding something in its main hand, and an existing guard are refused with a localized message (`message.mcamobcompat.recruit.*`). With the option off, the plain block and its message run instead.

### Configuration — Server-side

`mcamobcompat-server.toml`, section `[guard_villagers]` (current bridge-related options; see `McaMobCompatConfig.Server`):
- **`treatGuardsAsMcaGuards`** = `true` (boolean). Whether a bridged guard gets an MCA-style identity at all.
- **`guardMcaInteraction`** = `true` (boolean, renamed from `guardInteractionScreen`). On: a plain right-click opens MCA's own screen through the hidden proxy; crouch-right-click opens Guard Villagers' own inventory GUI. Off: stock Guard Villagers gestures and no new proxy; an existing proxy stays hidden and keeps its MCA state.
- **`recruitMcaVillagersAsGuards`** = `true` (boolean). Whether the crouch-and-weapon gesture recruits an MCA villager as a guard in place instead of only blocking the conversion.
- **`guardShowNameTag`** = `true` (boolean). Whether a newly identified guard's rolled name shows permanently above its head.

### Identity Store — Runtime

**Package:** `dev.otectus.mcamobcompat.bridge`. **Class:** `GuardIdentityData extends SavedData`, schema version 3, one per world (stored in the overworld's `DataStorage` under `mcamobcompat_guard_identities`), keyed by guard UUID. It is world-scoped rather than per dimension because a guard UUID is global and must survive portal travel. `GuardIdentityStore` is the access layer and `GuardIdentityView` the `GuardIdentity` it hands out.

**Fields:** `name`, `gender`, `personality`, `created`, `deceased`/`deceasedAt`, `proxyUuid` (nullable), `seeded` (whether the stored name and personality have been pushed onto a proxy), `heartsMigrated` (players whose pre-proxy stored hearts have been folded into MCA's own memories), and two separate hearts ledgers:

- **`Hearts`** — the last snapshot read from MCA, or an unmigrated legacy base;
- **`PendingHearts`** — deltas not yet applied to MCA, stored as `long` (an old `int` still reads).

**Hearts accounting.** `GuardIdentity.modHearts` queues one delta, tries to apply it to the live proxy through `McaBinding`, clears it only on success, and posts `GuardHeartsChangedEvent` once. Reads prefer MCA's live value and add any outstanding pending delta exactly once. The heartbeat retries the flush. **[CORRECTED in 0.2.1]** Schema 2 added a change made while MCA was unreachable to both `hearts` and `pendingHearts`, and `hearts()` returned their sum, so an offline change counted twice; migration to schema 3 subtracts the pending amount from the stored hearts once.

**Migration and safety.**
- Version 1 and version 2 files migrate automatically; each version-2 record is kept verbatim under `LegacyV2`.
- A file from a *newer* schema loads read-only: nothing is created, and `save` refuses to overwrite it.
- A record that cannot be read is kept verbatim as rejected. Two guards claiming one proxy, or two records for the same guard, are quarantined: preserved, reported by `guards status`, never relinked or recreated, and skipped by `purge`.
- A proxy-to-guard index answers "whose proxy is this?" without a scan.

**Creation & Updates:**
- Created lazily, on the guard's own `EntityJoinLevelEvent` and on first interaction, never deleted when the option is turned off.
- **Name/gender/personality** are rolled from the guard's UUID (`bridge/GuardNames`) and persisted as the seed for its first proxy. Once a proxy exists, `GuardIdentityView` reads the live name, personality and hearts from MCA's own villager (`mca/McaBinding`) and falls back to the stored values only when the proxy is unloaded or MCA is unreachable. A personality is stored lowercase and unnamespaced, whichever MCA line supplied it.
- **Death:** marked by the confirmed-death relay above; kept, never auto-deleted. `/mcamobcompat guards purge <days>` (admin only) removes deceased entries older than N days.

### Public API — Read-Mostly

**Package:** `dev.otectus.mcamobcompat.api`. **Additions:**

```java
MobCompatibilityAPI.isBridgedGuard(Entity) -> boolean                // D2 predicate
MobCompatibilityAPI.getGuardIdentity(Entity) -> Optional<GuardIdentity>

interface GuardIdentity {
  UUID uuid();
  String name();
  GuardGender gender();              // enum MALE/FEMALE
  String personality();
  String professionId();             // always "mca:guard"
  Optional<UUID> proxyUuid();        // the hidden MCA villager's UUID, once one exists
  int hearts(UUID player);
  void modHearts(UUID player, int delta);  // the one write; fires GuardHeartsChangedEvent
  boolean deceased();
}
```

**Events:** Forge bus, package `dev.otectus.mcamobcompat.api.event`. Cancelable:

- **`GuardInteractEvent(Player, Mob guard, InteractionHand)`** — posted on a plain (non-sneak), main-hand right-click on a bridged guard, before the click is forwarded to the guard's hidden MCA proxy. If a listener cancels it, its cancellation result is honoured and the click is never forwarded — the addon has taken MCA's place for that click. The crouch gesture belongs to Guard Villagers and never posts this event.

Non-cancelable:

- **`GuardIdentityCreatedEvent(Mob guard, GuardIdentity)`** — lets MCAConversations seed its personality/identity data.
- **`GuardHeartsChangedEvent(GuardIdentity, Mob guard, UUID player, int old, int new)`** — fired once by `GuardIdentity.modHearts`, on the server; the guard is null when it is not loaded.

### MCA-Like Behaviour Implemented

| MCA behaviour on an MCA guard | For a bridged guard |
|---|---|
| Name, gender, personality | Rolled and stored for the seed; once a proxy exists, read live from MCA's own brain (`GuardIdentityView`, `mca/McaBinding.personality`). `GuardIdentityCreatedEvent` fires at creation. |
| Hearts per player | MCA's own memories, on the proxy, read and written through `McaBinding`; a change while the proxy is unreachable is queued in `PendingHearts` and applied once, later. `GuardHeartsChangedEvent` fires once per change. The old flat `-guardHeartsLostPerHit` penalty is gone — MCA applies its own hurt reaction to the proxy instead. |
| Guard defends villagers / no friendly fire | Unchanged from 0.1.0 (§10; applies to any `GUARD`-tagged mob). |
| Guard is defended when attacked | Guard Villagers native (`mobsGuardsProtectHurt` includes `guardvillagers:guard`). No work. |
| Interaction screen (quest offer, dialogue, reputation gain/loss) | **Reachable.** A plain right-click opens MCA's real `InteractScreen` for the hidden proxy, with every MCA option working except profession changes (re-asserted back to `mca:guard` within a second by `GuardProxySync.reassertProfession`) and the mount/pick-up ones (neutralised because the sync dismounts the proxy every tick), because the proxy is a genuine `VillagerEntityMCA`. Crouch-right-click still opens Guard Villagers' own inventory GUI on the guard. See "Interaction routing" above. MCAConversations' dialogue frame and MCAQuests' quest menu portray the guard itself, not the proxy: `client/ProxyRenderHandler` substitutes the exactly paired guard for its proxy inside a screen (see "Hiding it from the client"). |
| Village residency, population cap, guard quota | **Reachable.** The proxy is a real resident with profession `mca:guard`, so it is counted by MCA's own `Village`/`Residency`/guard-quota logic the same as any MCA guard — a genuine side effect of being a real villager, not something this mod computes. |
| Marriage, children, family tree, genetics, armory equipment | **Reachable, on the proxy.** These are MCA's own systems operating on a real `VillagerEntityMCA`; this mod neither reads nor writes any of them directly. |
| MCA guards targeting (incl. `GuardEnemiesSensor`) | Unchanged. MCA guards ignore `guardvillagers:guard` by default (§4.1 sensor priority logic); the proxy is invisible and `NoAI` and is not itself a target MCA's sensors would normally acquire. |

### Per-Addon Compatibility

All four addons identify a villager by reflectively checking for `VillagerEntityMCA` or by resolving a live entity by UUID; none of them need to change, because the proxy answers both checks as a real MCA villager. Grounded in the handlers above rather than in the addon source (not part of this repository):

| Addon | How it sees a bridged guard |
|---|---|
| MCAQuests | Resolves the proxy by UUID; `onTalkToVillager` fires from the forwarded `PlayerInteractEvent.EntityInteract` the same as for any MCA villager. Its quest-menu portrait shows the guard, substituted client-side for the proxy (see "Hiding it from the client"). |
| MCACrime | Keys off `LivingHurtEvent`/`LivingDamageEvent`/`LivingDeathEvent` with the proxy as victim (relayed by `GuardDamageRelayHandler`), reads the proxy's `getTarget()` (mirrored every tick by `GuardProxySync`) for self-defence, and drives the proxy's own `getNavigation()` to move it — which `GuardProxySync.relayNavigation` replays onto the guard, since the proxy itself is `NoAI` and cannot walk. |
| MCAReputation | Keyed by the proxy's UUID on `LivingHurtEvent`/`LivingDeathEvent`, both relayed from the guard. |
| MCAConversations | Resolves the proxy by UUID; its dialogue is what a plain click on the guard forwards to via `interactAt`/`interactOn`. Its dialogue-frame portrait shows the guard, substituted client-side for the proxy (see "Hiding it from the client"). |

### Diagnostics

Command `/mcamobcompat guards` (subcommand, admin):
- **`status`** — bridge state, whether `guardMcaInteraction` is on, identity-store size and deceased count, whether the store is read-only, the quarantined and rejected record counts, and a proxy census (loaded, guard not loaded, guards without one).
- **`inspect`** — looked-at or targeted guard's `GuardIdentity`, plus its proxy's UUID, loaded state, live MCA name, live profession, and the caller's own hearts with it.
- **`proxies`** — every loaded proxy across every level with its guard (or "guard not loaded (kept)"), and every bridged guard still waiting for one.
- **`rebuild`** — discards and recreates the proxy of every loaded bridged guard, skipping the loading grace period; destructive (a rebuilt proxy is a new villager to MCA), for a world where the link has already broken. Covered by the GameTest `rebuildReplacesTheProxyAndRelinks`.
- **`purge <days>`** — remove deceased entries older than N days.

`/mcamobcompat classify` output gains a "bridged MCA guard" line when applicable.

### Evidence Markers

The **[RRV]** items opened for the earlier, identity-store-only interaction screen (MCA's own name-file format, which gesture opens Guard Villagers' inventory, handler priority and cancellation) are superseded: that screen no longer exists, and MCA's own screen is now what opens, resolved by MCA itself. The remaining open items:

1. **`VillageManager.findNearestVillage(Entity)` compatibility.** Confirm the four addons' bindings accept a `PathfinderMob` (or plain `Entity`) without casting to `Villager` or `LivingEntity` — relevant only where an addon still resolves a guard rather than its proxy.
2. **[RESOLVED in 0.2.1] MCA villager brain overrides.** MCA 7.6.20's `VillagerEntityMCA.makeBrain` builds from `VillagerTasksMCA` and never calls vanilla `VillagerGoalPackages`, so Guard Villagers' `VillagerGoalPackagesMixin` (including `ShareGossipWithGuard`) cannot reach an MCA villager.

## 11. SWEM Integration
Entity `swem:swem_horse` (confirmed first-party from `EntitySetup`; the mod also registers
`swem:horse_poop`, `swem:rope_knot` and `swem:wormieboi`). SWEM owns ownership, needs, skills and
breeding.

- **Classify `swem:swem_horse` as `horse` + `mount`** (shipped tag). **[C]**
- **[CORRECTED twice] MCA villagers can mount rideable entities, but only through the player-issued `ridehorse` command, never autonomously. On a SWEM horse the villager mounts and the horse ignores it.** `MCA.ridehorse` filters on `AbstractHorse implements Saddleable` and `isSaddled()`, both of which SWEM horses pass, so the villager does mount. An earlier revision blamed MCA's `MixinHorseBaseEntity` never engaging; that mixin only lifts `isImmobile` and is not the cause. **The cause [C from bytecode, corrected in 0.2.1]:** `SWEMHorseEntityBase.getControllingPassenger()` answers for a `Player` only, and its `isVehicle()` is true only when the first passenger is a `Player`. With a villager aboard, `isControlledByLocalInstance()` therefore falls through to `!isVehicle() && isEffectiveAi()`, which is true: the horse runs its own AI as if unridden. A mount is steered by its controlling passenger, and MCA's riding support keys on it: MCA 7.6's `MixinHorseBaseEntity` acts only while an MCA villager is a horse's controlling passenger (it keeps the horse from freezing to eat or rear), and MCA 7.7 relies on vanilla `Mob.getNavigation()` handing a rider's navigation to a mount that names it. A SWEM horse never names a villager. An earlier revision said the horse could never move; a GameTest run with every optional mod loaded showed a villager-ridden SWEM horse walking under its own navigation. On a vanilla horse the same villager steers, because vanilla `AbstractHorse.getControllingPassenger()` returns a `Mob` first passenger.
- **Observed [C, GameTest `aVillagerCannotSteerASwemHorseButCanAVanillaOne`]:** with the same MCA villager aboard, a vanilla horse names it the controlling passenger and, sent somewhere, walks more than two blocks, while a SWEM horse has no controlling passenger.
- **No mixin-free fix exists**, so 0.2.1 refuses the ride instead (§11A): `swem:swem_horse` ships in `mcamobcompat:player_only_mounts`. The change SWEM itself would need — return the vanilla answer when the first passenger is a non-player `Mob` — is written up in `docs/UPSTREAM.md`.
- **Read-only owner recognition — [RESOLVED, and far simpler than planned].** The spec expected to
  read a bespoke `Owner` UUID NBT key by reflection. No reflection is needed:
  ```
  public abstract class com.alaharranhonor.swem.forge.entities.horse.SWEMHorseEntityBase
      extends net.minecraft.world.entity.animal.horse.AbstractHorse
    ...
    public void            m_30586_(java.util.UUID)   // setOwnerUUID
    public java.util.UUID  m_21805_()                 // getOwnerUUID
    public boolean         isOwner(net.minecraft.world.entity.player.Player)
  ```
  SWEM extends vanilla `AbstractHorse` and overrides the vanilla owner accessors, so ownership comes
  back from `AbstractHorse.getOwnerUUID()` with **no SWEM class involved**. Exposed as
  `MobCompatibilityAPI.getOwner(Entity)`, which for the same reason also works for DragN's equines,
  vanilla horses, and tamed cats and wolves.
- Because it is vanilla-only, `TamedMountProvider` can classify *any* tamed equine as a `mount`,
  including from mods nobody has written a tag for. This is the one classification that cannot be a
  tag: whether a horse is a mount is a fact about the individual animal, not its entity type.
- Breeding, feeding, needs, training and tack are delegated entirely to SWEM. Nothing is ever
  written back.

## 11A. Mount Policy (0.2.1)

**Recognition is not capability.** A mount in `mcamobcompat:mount` is something a villager could sit on; whether it can then be steered is a separate fact. `mount/MountPolicy` decides, for an MCA villager and a mount:

- **Refuse** when the mount is in `mcamobcompat:player_only_mounts` (shipped: `swem:swem_horse` and six Alex's Mobs species, all `required: false`), because it only ever lets a player steer.
- **Refuse** when an integration's `MountRefusal` says the mount is not the villager's to take — Icy's Better Horses registers one for a horse owned by a player or hitched to a post (§13A).
- Otherwise **allow**.

`event/MountPolicyHandler` applies it on `EntityMountEvent` at `HIGH`, mounting only, server only. Under `[mounts] npcRidingPolicy = REFUSE` (the default) it cancels the mount; `ALLOW_INERT` restores the pre-0.2.1 behaviour of a seated, stationary villager. The player who gave the order is the last one to have clicked the villager (remembered briefly from `EntityInteract` at `LOWEST`), and is told the reason once, not once per retry. `/mcamobcompat mounts inspect` shows the whole decision for any mount.

`player_only_mounts` is a fact about the mob, not a classification, so it applies even when that mod's module is switched off.

**No mount-steering framework.** Nothing on Forge 1.20.1 would drive one: MCA already steers vanilla-derived horses itself, and SWEM cannot be steered from outside it. Revisit if SWEM adopts the contract in `docs/UPSTREAM.md`.

## 12. Genetic Animals Integration
Genetic Animals replaces cows, pigs, chickens, sheep, llamas, rabbits, mooshrooms, sea turtles and
axolotls with genetically-driven entities of its own.

- **Mod id [CORRECTED]: `eanimod`.** Not `geneticanimals` — see §4.5. The spec's guess would have
  produced tag entries that silently never matched and a dead `mods.toml` dependency edge, which is
  the worst kind of wrong because nothing reports it.
- **Entity ids [RESOLVED]:** `eanimod:enhanced_cow`, `enhanced_pig`, `enhanced_sheep`,
  `enhanced_chicken`, `enhanced_llama`, `enhanced_rabbit`, `enhanced_mooshroom`,
  `enhanced_moobloom`, `enhanced_turtle`, `enhanced_axolotl`, `enhanced_horse`.
- Classified into `livestock` plus the matching species role via shipped tags. Turtles and axolotls
  are deliberately left unclassified: they are not farm animals for chore purposes.
- MCA farmer chores recognise them as livestock, but **breeding and feeding are delegated entirely
  to Genetic Animals**. There is no call to `Animal.breed` or `spawnChildFromBreeding` anywhere in
  this codebase, so genetics and pregnancy cannot be disturbed by it.

## 13. DragN's Livestock Overhaul Integration
DLO "O-Animals" auto-convert vanilla animals into separate entities of its own.

- **Most of this is already done by DLO [CORRECTED — better than planned].** DLO ships
  `data/forge/tags/entity_types/` mapping the Forge conventional tags onto its animals:
  `forge:cow → dragnlivestock:o_cow`, and likewise `forge:pig`, `forge:sheep`, `forge:chicken`,
  `forge:rabbit`, `forge:goat`, `forge:horse`, `forge:donkey`, `forge:mule`, `forge:llamas`,
  `forge:camel`, `forge:bee`, `forge:cod`, `forge:salmon`, `forge:frogs`, `forge:horses`. **The
  shipped role tags reference those Forge tags**, so DLO — and any other mod following the same
  conventions — is classified with no per-mod work at all. The explicit ids below are belt-and-braces
  for species Forge has no conventional tag for.
- **Complete entity list [RESOLVED]**, from `EntityTypes.class` cross-checked against loot tables,
  spawn eggs and lang keys:
  - Non-equine: `o_cow, o_pig, o_sheep, o_chicken, o_llama, o_rabbit, o_mooshroom, o_goat,
    farm_goat, o_bee, o_frog, o_cod, o_salmon, grub, caribou`, plus eleven `*_moobloom` variants
    (`wheat, sweet_berry, pumpkin, potato, melon, glow_berry, flowering, carrot, beetroot, azalea,
    peach`).
  - Equine / rideable: `o_horse, o_donkey, o_mule, o_camel, unicorn, headless_horseman`.
  - Vehicles (not classified): `covered_wagon, livestock_wagon, lumber_wagon, goods_cart, dog_sled,
    mining_cart, transport_cart, plow, mower, coupe, cabriolet, sleigh`.
- **Two traps — do not tag either.** `dragnlivestock:freight_wagon` and
  `dragnlivestock:o_undead_horse` have lang keys and loot tables in the jar but are **not registered
  entity types**: undead is a body-layer variant of `o_horse`, and the freight wagon no longer
  exists. `TagFileValidityTest` fails the build if either appears in a shipped tag.
- Genders (DLO uses an enum gender code), breeding and genetics are delegated entirely to DLO.

## 13A. Icy's Better Horses Integration (0.2.1)
Icy's Better Horses adds no entity types (§4.6): its horses are vanilla horses, already classified by
the vanilla horse tags. `integration/icysbetterhorses/IcysBetterHorsesModule` reads three facts, by
Icy's own member names on the horse's runtime class (`IcyReflection`, cached per class):

- **Owner.** `bh_getOwner`, when `bh_isOwned`. `MobCompatibilityAPI.getOwner` falls back to it when
  vanilla reports no owner, so the hunting chore's `protectOwnedAnimals` covers Icy-owned horses.
  Gated by `[icys_better_horses] recognizeOwnership`.
- **Riding.** Icy's keeps other *players* off an owned horse but never checks a villager, so an MCA
  villager could ride off with somebody's horse. The module applies Icy's rule to villagers: an
  owned horse refuses one (`message.mcamobcompat.mount.icy_owned`).
- **Hitching.** A horse tied to Icy's hitching post refuses a villager
  (`message.mcamobcompat.mount.icy_hitched`), whatever `recognizeOwnership` says.

Icy's commands need nothing from here: they run only while the horse is owned and unridden (§4.6).
An earlier draft refused any horse whose command was not WANDER; it was removed after a GameTest
showed Icy's default command, FOLLOW, would have refused every unowned vanilla horse for no reason.
Nothing is ever written to an Icy's horse.

## 13B. Alex's Mobs Integration (0.2.1)
Deliberately narrow; `docs/ALEXS_MOBS_AUDIT.md` lists every registered type and what is done with it.

- **Mounts:** `mount.json` gains the four ground species a player steers — elephant, grizzly bear,
  Komodo dragon, tusklin. Flying, swimming and vehicle "mounts" are left out, and so are species
  that carry passengers under their own control.
- **Player-only:** all six player-steered species are in `player_only_mounts`. MCA's "Ride Horse"
  order never picks them, since it wants a saddled horse; the policy stops anything else seating a
  villager on one.
- **Pets:** `classification/OwnedAnimalProvider` gives `pet` to any `OwnableEntity` with an owner that
  is not an `AbstractHorse` (horses are `TamedMountProvider`'s). That covers Alex's tamable species,
  and wolves, cats and parrots.
- **Module off** (`[modules] alexsMobs` or `[alexs_mobs] recognizeAnimals`): every Alex's Mobs entity
  gets no role at all, providers included, because `TagClassifier` skips providers for a suppressed
  namespace. A tamed one is still spared by the hunting chore, which reads ownership directly.
- **Not done:** `alexsmobs:villagers` is not extended with MCA's villagers, since that would make
  Alex's predators hunt them; `DATAPACK.md` shows the opt-in pack. The ten classes that already
  reach MCA villagers by class are left alone.

## 13C. Threat Awareness: Creepers (0.2.1)
MCA notices only a creeper lit with flint and steel (§4.1). `threat/ThreatHandler` closes the gap
without adding a goal or task to MCA's brain: it writes the memories MCA's own sensors write, and
MCA's own behaviours do the rest.

- **Perception.** Each MCA villager, every 10 ticks staggered by entity id, looks for mobs in the
  `mcamobcompat:npc_threats` tag (shipped: `minecraft:creeper`) within `creeperAlertRange`, weighs at
  most four, and acts on the most urgent. Proxies (`NoAI`) and sleeping villagers are skipped.
- **Decision** (`threat/ThreatPolicy`, pure Java, unit-tested): out of range or out of sight is
  ignored, unless a fuse is burning close enough to hear; a burning fuse within `swellingFleeRange`
  (doubled for a charged creeper) sends everyone running, guards included; a healthy adult MCA guard
  or archer engages when `guardsEngageCreepers` allows; anyone else runs from a burning fuse, from a
  threat coming for them or someone beside them, or from one inside half the alert range; otherwise
  the villager watches it.
- **Flee:** vanilla `NEAREST_HOSTILE` (40 ticks), which MCA's panic package reacts to, and a
  `WALK_TARGET` away from the threat, which MCA's movement follows. The threat is cleared from
  `ATTACK_TARGET` and from MCA's guard-enemy memory. A mounted villager dismounts first when
  `dismountToFlee` allows, since nothing can steer its mount.
- **Emergency hold:** while a burning fuse is within blast reach, that creeper is cleared from the
  villager's attack and guard-enemy memories every tick, because MCA's guard brain (7.7 especially)
  otherwise re-acquires it on its next tick and walks into the blast.
- **Engage:** MCA's own `mca:nearest_guard_enemy` memory, which MCA's guard combat acts on.
- **Watch:** a look target, nothing else.
- **Guard Villagers guards** are alerted through `GuardAlerts` when a villager flees a creeper, but
  their own `MobBlackList` names `minecraft:creeper` by default and is respected, never edited.
- **Golems Kill Creepers** (§4.8): no code path touches golems, creepers or gamerules.

Every memory access checks the brain registered it (§4.1). `/mcamobcompat threats inspect` shows
what a villager sees and how it reacted.

## 14. Data-Driven Compatibility
- **File location:** `data/mcamobcompat/tags/entity_types/<role>.json` (standard Forge entity-type
  tags). Reloads with datapacks; the classification cache invalidates on `TagsUpdatedEvent`. The
  built-in datapack ships at lowest priority, so pack authors extend or override it and later packs
  win on conflict.
- Every entry from a mod that might not be installed uses `"required": false`, so a missing mod is
  skipped silently instead of logging a datapack error on every world load. `TagFileValidityTest`
  enforces this, and also forbids the two DLO ids that look real but are not (§13).
- `DATAPACK.md` is the pack-author-facing version of this section.

Example — `mount.json`:
```json
{ "replace": false, "values": [
  "#mcamobcompat:horse",
  "minecraft:camel",
  "minecraft:strider",
  { "id": "#forge:camel", "required": false },
  { "id": "#dragnlivestock:draught_animals", "required": false },
  { "id": "dragnlivestock:o_camel", "required": false },
  { "id": "alexsmobs:elephant", "required": false },
  { "id": "alexsmobs:grizzly_bear", "required": false },
  { "id": "alexsmobs:komodo_dragon", "required": false },
  { "id": "alexsmobs:tusklin", "required": false }
] }
```
SWEM horses are classified as mounts through the `#mcamobcompat:horse` tag reference; `swem:swem_horse` lives in `horse.json`.

Two tags added in 0.2.1 are not roles; they change behaviour. `npc_threats.json` ships
`minecraft:creeper` (§13C). `player_only_mounts.json` ships `swem:swem_horse` and six Alex's Mobs ids,
all `required: false` (§11A).

Example — `cattle.json` (note the corrected Genetic Animals id, and the Forge tag that covers DLO):
```json
{ "replace": false, "values": [
  "minecraft:cow",
  { "id": "#forge:cow",           "required": false },
  { "id": "eanimod:enhanced_cow", "required": false }
] }
```

Example — a modpack author adds a third-party cow without recompiling
(`data/<pack>/tags/entity_types/cattle.json`; `livestock` follows automatically):
```json
{ "replace": false, "values": [ { "id": "some_mod:highland_cow", "required": false } ] }
```

Example — `guard.json`:
```json
{ "replace": false, "values": [ { "id": "guardvillagers:guard", "required": false } ] }
```

Use `/mcamobcompat dump <namespace>` to confirm ids against the live registry before writing them.
A wrong entity id does not error — it silently never matches — which is how the `geneticanimals`
guess in §12 would have failed invisibly.

## 15. Public API
Small, conservative and read-mostly, so it does not need a breaking change soon. **0.1.0 base:**

```java
public final class MobCompatibilityAPI {
  public static boolean isLivestock(Entity e);
  public static boolean isMount(Entity e);
  public static boolean isGuard(Entity e);
  public static boolean isPet(Entity e);
  public static Set<MobRole> getRoles(Entity e);
  public static Set<MobRole> getRoles(EntityType<?> type);
  public static Optional<UUID> getOwner(Entity e);
  public static void registerProvider(ClassificationProvider provider);
}
```

**0.2.0 additions (guard bridge):**

```java
  public static boolean isBridgedGuard(Entity e);
  public static Optional<GuardIdentity> getGuardIdentity(Entity e);
```

Changes from the original sketch:

- **`MobCategory` is renamed `MobRole` [CORRECTED].** `net.minecraft.world.entity.MobCategory`
  already exists — it is the vanilla spawn-group enum (CREATURE, MONSTER, AMBIENT) — and sharing the
  simple name would force a fully-qualified reference in every file that touched both.
- **`getRoles(EntityType<?>)` added.** It answers straight from the per-type cache and needs no live
  entity, which suits menus, tooltips and bulk checks. Per-entity providers are not consulted.
- **`getOwner(Entity)` added.** It became free once §11 established that SWEM uses the vanilla owner
  API, and it is what §11's "read-only owner recognition" actually needs.
- **`isBridgedGuard` and `getGuardIdentity` added in 0.2.0** to support the guard bridge and addons.
- **0.2.1:** `GuardIdentity.proxyUuid()` exposes the hidden villager's UUID; `getOwner` falls back to
  Icy's Better Horses' owner; `isPet` is true for any owned `OwnableEntity` through
  `OwnedAnimalProvider`. No signature changed.

## 16. Configuration
Two specs, not three. **[CORRECTED: no client config]** — nothing in 0.1.0 runs client-side (0.2.1's client code only hides proxies and needs no options), and
`debugLogging` plus the `/mcamobcompat` command cover the diagnostic need the client file was for. **0.2.0 additions** noted below.

- **`mcamobcompat-common.toml`** — `[general] debugLogging`; `[modules]` on/off per integrated mod.
  Turning a module off makes this addon completely blind to that mod's entities, which is what "off"
  has to mean if it is to settle a fight between two mods.
- **`mcamobcompat-server.toml`** (world-scoped, server-authoritative) —
  `[guard_villagers]` (0.1.0): `blockGuardConversion`, `protectMcaVillagers`, `preventGuardFriendlyFire`,
  `retaliateAgainstPlayers`, `guardHelpRange`, `maxGuardsAlerted`.
  (0.2.0): `treatGuardsAsMcaGuards` (boolean, default true).
  (0.2.1): `[chores]` section with `protectOwnedAnimals` (boolean, default true), `protectedRoles` (list of `MobRole` names, default `["mount", "pet"]`), `protectNamedAnimals` (boolean, default true), `protectLeashedAnimals` (boolean, default true), `abandonHuntOnProtectedTarget` (boolean, default true).
  (hidden MCA proxy, after 0.2.0): three more `[guard_villagers]` options, replacing the earlier interaction-screen ones — `guardMcaInteraction` (boolean, default true, renamed from `guardInteractionScreen`; a plain click opens MCA's own screen through a hidden proxy villager, crouch opens Guard Villagers' inventory), `recruitMcaVillagersAsGuards` (boolean, default true, crouch-and-weapon recruits an MCA villager as a guard in place instead of only blocking the conversion), and `guardShowNameTag` (boolean, default true, a permanent nameplate on newly identified guards). `guardInteractionCooldownSeconds`, `guardSocialHearts`, `guardGiftHearts`, `guardGiftHeartsLoved` and `guardHeartsLostPerHit` are gone: MCA's own memories on the proxy replace all five, and `data/mcamobcompat/tags/items/guard_gift_*` no longer exist. `ForgeConfigSpec` has no migration path, so an existing config file has the renamed and removed keys rewritten to defaults on first load with the new spec.
  (0.2.1): `[threats]` — `creeperAwareness` (default true), `creeperAlertRange` (8, range 2–32),
  `swellingFleeRange` (4, range 1–16), `guardsEngageCreepers` (true), `dismountToFlee` (true);
  `[mounts] npcRidingPolicy` (`REFUSE` or `ALLOW_INERT`, default `REFUSE`);
  `[icys_better_horses] recognizeOwnership` (true); `[alexs_mobs] recognizeAnimals` (true).
  `[swem] recognizeAsMounts`; `[genetic_animals] recognizeLivestock`;
  `[dragn_livestock] recognizeLivestock`.
- **`mcamobcompat-common.toml`, 0.2.1:** `[modules] icysBetterHorses` and `alexsMobs` (both true).

`protectMcaChildren` was dropped: MCA children are the same entity types as adults, so the villager
setting already covers them and a second flag would only imply otherwise. `enableDatapackMappings`
was dropped too — datapack tags are the mechanism, not an optional extra, and a switch to disable
them would just be a way to break the mod.

Gameplay lives in the server spec because all of it is server-authoritative. Options are kept few on
purpose: classification is extended with datapack tags, never with config lists, so there is no
per-entity toggle and there should never be one.

## 17. Forge Events / Hooks
- Mod constructor: register both config specs, add the mod-bus listeners, register the
  `mcamobcompat:proxy_pair` channel (0.2.1).
- `FMLCommonSetupEvent`, inside `enqueueWork`: detect optional mods and log one line each, call
  `McaBinding.resolve()` (0.2.1), then register the classification providers. Inside `enqueueWork`
  because only there is `ModList` authoritative.
- `ModConfigEvent.Reloading`: drop the classification cache and the chore-safety config cache.
- **`TagsUpdatedEvent`** (Forge bus): invalidate the classification cache. **[CORRECTED]** — the spec
  suggested `AddReloadListenerEvent`; `TagsUpdatedEvent` is the right hook, because tags are what is
  cached and it fires after they are bound, on world load as well as `/reload`.
- `PlayerInteractEvent.EntityInteract` at **`HIGH`**: cancel the MCA→Guard conversion on both sides,
  or recruit in place (§10). The priority is load-bearing — Guard Villagers subscribes at `NORMAL`.
- `PlayerInteractEvent.EntityInteractSpecific` and `EntityInteract` at **`HIGHEST`** (0.2.1): the
  guard/proxy interaction router, which posts `GuardInteractEvent` (§10A).
- `EntityInteract` at `LOWEST` (0.2.1): remember who last clicked an MCA villager, for mount refusal
  messages.
- `LivingChangeTargetEvent`, `LivingAttackEvent` at `HIGH` and `LivingHurtEvent` (server side only):
  guard defence and friendly-fire prevention (§10).
- **Chore safety (0.2.1):** `LivingAttackEvent` cancels an MCA villager's attack on a protected animal
  (primary hook); `LivingChangeTargetEvent` cancels the target change and abandons the hunt
  (belt-and-braces). Driven by `ChoreSafetyHandler` reading `ChoreProtectionPolicy`.
- **Proxy lifecycle (0.2.1):** `LivingTickEvent` (guard: sync, cross-level follow, creation every 20
  ticks; proxy: re-hide and pin), `EntityJoinLevelEvent` (identity for a joining guard),
  `EntityLeaveLevelEvent` (release or confirm on removal), `MobSpawnEvent.FinalizeSpawn` at
  `HIGHEST`, `receiveCanceled` (capture the proxy's own spawn), `EntityEvent.Size` at `LOWEST` (the
  nested proxy box), `AttackEntityEvent`, `LivingChangeTargetEvent` and `ProjectileImpactEvent`
  (redirect or skip the proxy).
- **Damage and death relay (0.2.1):** `LivingDamageEvent` at `LOWEST` (relay the final amount, and
  force the exact amount on the proxy), `LivingDeathEvent` at `LOWEST` (record), `ServerTickEvent`
  END (confirm), `LivingAttackEvent` at `HIGHEST` (veto independent proxy damage),
  `LivingDropsEvent` and `LivingExperienceDropEvent` at `LOWEST` (no proxy loot or XP).
- **Pairing (0.2.1):** `PlayerEvent.StartTracking` and `StopTracking` send and retract pairs; on the
  client, `LivingTickEvent` re-hides paired proxies and `ClientPlayerNetworkEvent.LoggingOut` clears
  them; `RenderLivingEvent.Pre` cancels a proxy's render or draws its guard inside a screen.
- **Threats (0.2.1):** `LivingTickEvent` for MCA villagers, staggered (§13C).
- **Mounts (0.2.1):** `EntityMountEvent` at `HIGH` (§11A).
- `ServerStoppedEvent` (0.2.1): clear every piece of static state held between ticks.
- `RegisterCommandsEvent`: the `/mcamobcompat` command. **0.2.0: adds `guards`. 0.2.1: adds `mca`,
  `mounts inspect`, `threats inspect`, `guards proxies` and `guards rebuild`.**
- Subscribers are split across `bus = MOD` (setup, via explicit `addListener`) and `bus = FORGE`
  (gameplay, via `@Mod.EventBusSubscriber(modid = "mcamobcompat")`). **No subscriber needs gating**,
  because none of them touches an optional mod's classes. The client subscribers are
  `Dist.CLIENT`-only and live in `client/`.

## 18. Mixins / Access Transformers
**None, and none were needed [RESOLVED].** Both conditions the spec set for justifying a Mixin were
tested against the real jar and both came back negative:

- *Can the MCA→Guard conversion be cancelled via `PlayerInteractEvent`?* **Yes.** The Guard Villagers
  handler is a bare `@SubscribeEvent` at `NORMAL` with `receiveCanceled = false`, so cancelling at
  `HIGH` pre-empts it (§10).
- *Can guard targeting be steered without patching goals?* **Yes, though not the way the spec
  imagined.** Tags cannot do it, because Guard Villagers reads none — but `LivingChangeTargetEvent`
  and `LivingHurtEvent` plus vanilla `Mob.setTarget` cover both defence and friendly fire (§10).

**0.2.1 still needs none.** The one thing that would have needed a mixin — letting a villager steer a
SWEM horse — is refused instead (§11A) and written up for SWEM (`docs/UPSTREAM.md`). The proxy's
hitbox is changed through `EntityEvent.Size`, and MCA's memories through vanilla `Brain`.

So the shipped jar contains no Mixin, no refmap and no access transformer. It also contains no
reference to any MCA or optional-mod type at all, which is a stronger property than the spec asked
for and is enforced by `NoOptionalModStaticLinkTest`.

## 19. Package Structure
As in §7. Responsibilities:
- **`McaMobCompat`** — `@Mod("mcamobcompat")` entrypoint; config registration; setup ordering.
- **`McaMobCompatConfig`** — both `ForgeConfigSpec`s, in one file, per suite convention.
- **`api/`** — the public surface: `MobCompatibilityAPI`, `MobRole`, `ClassificationProvider` (0.1.0);
  `isBridgedGuard`, `getGuardIdentity`, `GuardIdentity`, `GuardGender` (0.2.0).
  - **`api/event/`** (0.2.0) — `GuardInteractEvent`, `GuardIdentityCreatedEvent`, `GuardHeartsChangedEvent`.
- **`bridge/`** — the guard identity store (`GuardIdentityData`, `GuardIdentityStore`,
  `GuardIdentityView`, `GuardNames`), the bridge predicate (`GuardBridge`), the crouch gesture
  (`GuardInteractions`), and the hidden proxy (`GuardProxy`, `GuardProxySync`). No MCA or Guard
  Villagers class reference.
- **`classification/`** — `TagClassifier`, `ClassificationCache` (per-`EntityType`, reload-invalidated),
  `RoleCache` (its generic, unit-testable core), `ProviderRegistry`, `TamedMountProvider`,
  `OwnedAnimalProvider` (0.2.1).
- **`client/`** (0.2.1) — `ProxyRenderHandler` and `ClientProxyPairs`: hiding proxies and drawing their
  guards in portraits. Common code never imports it.
- **`command/McaMobCompatCommand`** — `status`, `classify`, `dump <namespace>` (0.1.0); `guards` (0.2.0);
  `mca`, `mounts inspect`, `threats inspect`, `guards proxies`, `guards rebuild` (0.2.1).
- **`event/`** — `ConversionGuardHandler`, `GuardDefenseHandler`, `ClassificationReloadHandler` (0.1.0);
  `ChoreSafetyHandler`, `ChoreProtectionPolicy`, `GuardAlerts`, `GuardBridgeHandler`,
  `GuardProxyHandler`, `GuardDamageRelayHandler`, `ProxySpawnHandler`, `ProxyShapeHandler`,
  `MountPolicyHandler`, `ServerLifecycleHandler` (0.2.1).
- **`integration/<mod>/`** — the only places permitted to reference each optional mod's classes.
  **None does.** They hold presence detection and the config gate; `guardvillagers/` also holds
  `GuardReflection` and `GuardVariants`, and `icysbetterhorses/` holds `IcyReflection`, both by
  member name only.
- **`mca/McaEntities`** — the only place that knows MCA entity ids. Recognises
  `mca:male_villager` / `mca:female_villager` by registry id; holds the authoritative Java constant
  set behind the conversion blocker and the additive `mcamobcompat:mca_villagers` tag.
- **`mca/McaBinding`** (0.2.1) — the reflective MCA binding layer and the single class allowed to name
  MCA classes, as dotted strings. It resolves MCA's package root at runtime (four known layouts) and
  binds MCA's own members: the villager brain, current job and `abandonJob`, move state, hearts
  (`getMemoriesForPlayer`, `modHearts`, `setHearts`), personality in both the 7.6 enum and 7.7
  registry shapes, and the family tree (`FamilyTree.get`, `getOrEmpty`, `remove`) for rollback. Each
  binding reports ok or missing through `/mcamobcompat mca`, and a missing one disables only its
  feature.
- **`mca/McaMemories`** (0.2.1) — MCA's memories by registry id through vanilla `Brain`, no class.
- **`mount/`** (0.2.1) — `MountPolicy`, `MountDecision`, `MountRefusal`, `NpcRidingPolicy`.
- **`network/ProxyPairs`** (0.2.1) — the `proxy_pair` channel, its message and the server-side sends.
- **`threat/`** (0.2.1) — `ThreatPolicy` (pure decision), `ThreatAssessor` (facts), `ThreatHandler`.
- **`util/ModIds`, `util/ConfigAccess`** — id constants, and config reads that tolerate an unloaded
  spec (with a thread-local override that GameTests use instead of writing the config file).

## 20. mods.toml / Dependency Design
```toml
modLoader="javafml"
loaderVersion="[47,)"                  # Forge for 1.20.1 is 47.x
license="GPL-3.0-only"                 # CORRECTED from MIT: matches the rest of the MCA suite

[[mods]]
modId="mcamobcompat"                   # CORRECTED from mca_compat -- suite ids carry no underscore
version="${mod_version}"
displayName="MCA: Mob Compatibility"

# forge / minecraft / mca as before; mca is mandatory, versionRange="[7.6,8)", ordering="AFTER".
#
# Architectury is deliberately NOT declared. This mod names no Architectury type, MCA declares its
# own dependency on it, and MCA 7.7 drops it entirely -- a redundant mandatory edge here would
# refuse to load on that.

# Optional, all six with an EMPTY versionRange (= any version) and ordering="AFTER".
[[dependencies.mcamobcompat]]
    modId="guardvillagers"
    mandatory=false
    versionRange=""
    ordering="AFTER"
    side="BOTH"
# ... and the same shape for:
#   swem
#   eanimod          # CORRECTED from "geneticanimals" -- see section 4.5
#   dragnlivestock
#   icys_better_horses   # 0.2.1
#   alexsmobs            # 0.2.1
```

**[CORRECTED] Why every optional range is empty.** Forge enforces an optional dependency's
`versionRange` when the mod *is* present, so any floor would refuse to launch for someone running an
older build — for mods this addon only ever touches through registry ids and datapack tags. There is
nothing to be version-incompatible with, because no class of theirs is referenced anywhere in the
jar. A genuine incompatibility degrades in Java instead: an entity that is not there is simply not
classified.

Gradle (ForgeGradle 1.20.1), as actually built:
```gradle
minecraft "net.minecraftforge:forge:1.20.1-47.4.10"      // mappings: official / 1.20.1

// MCA and every optional mod are dev-run and GameTest dependencies only -- never compile deps.
// They go through remapDevMods, not fg.deobf: MCA is built with Loom and Forgix and ships its mixins
// without a refmap, naming Minecraft members by SRG name in the annotations, which fg.deobf does not
// touch. remapDevMods rewrites every SRG member name in a copy of each jar -- class files, refmaps and
// access transformers -- to the official names a dev run uses, into build/devmods.
devModSource "maven.modrinth:minecraft-comes-alive-reborn:${mcaDevVersion}"   // 7.6.20, or 7.7 with -PmcaDev77
devModSource "dev.architectury:architectury-forge:${architectury_version}"    // MCA 7.6 only
// -PwithSwem / -PwithIcy / -PwithAlex / -PwithGolems add those mods and their libraries,
// pinned by Modrinth version id in gradle.properties (SWEM comes from libs/).
runtimeOnly files({ /* build/devmods/*-dev.jar */ }).builtBy('remapDevMods')

// NO compileOnly optional-mod dependency of any kind. [CORRECTED]
// libs/ is gitignored because Guard Villagers, SWEM, Genetic Animals and DLO jars must not be
// redistributed.
```

A `gameTest` source set holds the GameTests in a separate mod (`mcamobcompat_tests`), run by
`runGameTestServer`, which fails unless the server log reports every required test passed. It never
reaches the shipped jar; `checkJarContents` verifies that.

`checkJarContents` fails the build if a class from MCA, Architectury or any optional mod reaches the
jar; `verifyReobfJar` fails it if the artifact was never reobfuscated; `installMod` copies the
verified jar into a real instance so nobody has to race `reobfJar` by hand.

## 21. Version Scope: 0.1.0 Shipped, 0.2.0 Shipped, 0.2.1 Implemented

### 0.1.0 Feature Scope (Shipped)
- Optional-dependency framework, mod detection and startup logging (`ModIntegrations`).
- Tag-based classification (`MobRole`, `TagClassifier`, per-type cache, `TagsUpdatedEvent`
  invalidation) with shipped default tags for vanilla, SWEM, Genetic Animals and DLO, composed from
  the Forge conventional tags where they exist.
- Guard Villagers: classification, **the MCA→Guard conversion block**, guard defence of MCA
  villagers, and guard friendly-fire prevention.
- SWEM / Genetic Animals / DLO classification, plus read-only mount ownership through vanilla
  `AbstractHorse`.
- The read-only public API (`isLivestock`, `isMount`, `isGuard`, `isPet`, `getRoles`, `getOwner`), plus `TamedMountProvider`.
- `/mcamobcompat status | classify | dump <namespace>`.
- Four build-time tests, the important one being `NoOptionalModStaticLinkTest`.

### 0.2.0 Feature Scope (Shipped)
- Guard Villagers `guardvillagers:guard` entities treated as MCA guards for the addon suite (MCAQuests, MCACrime, MCAReputation, MCAConversations).
- Identity store (`GuardIdentityData`): name, gender, personality, hearts per player, deceased flag. Lazily created, never deleted on config toggle.
- Public API additions: `isBridgedGuard`, `getGuardIdentity`, `GuardIdentity` interface, three events (`GuardInteractEvent`, `GuardIdentityCreatedEvent`, `GuardHeartsChangedEvent`).
- Configuration additions: `treatGuardsAsMcaGuards` (default true), `guardHeartsLostPerHit` (default -5).
- Bridge events and handlers: `GuardInteractEvent` on right-click, identity creation on entity join, heart loss on hurt.
- Diagnostics: `/mcamobcompat guards status | inspect | purge`.
- Per-addon implementation in each repo: `isMcaVillager` predicate extended to accept `isBridgedGuard`, identity-driven reads.

### Hidden Proxy Rework Feature Scope (after 0.2.0, released in 0.2.1)
- Every bridged guard with `guardMcaInteraction` on is paired with a real, hidden `mca:*_villager` (`bridge/GuardProxy`), synced onto the guard every tick (`bridge/GuardProxySync`) and hidden from rendering client-side (`client/ProxyRenderHandler`).
- MCA's own interaction screen, hearts/memories, movement intent, marriage, family tree, genetics and village residency are all reachable through the proxy — see §10A "MCA-Like Behaviour Implemented".
- Guard Villagers' follow/patrol state is driven only by reflection on the guard's runtime class, by member name (`integration/guardvillagers/GuardReflection`), never by naming its class.
- Damage and death on the guard are relayed onto the proxy (`event/GuardDamageRelayHandler`) so MCA, MCACrime and MCAReputation react to it as a villager.
- Crouch-and-weapon on an MCA villager can now recruit it as a guard in place (`recruitMcaVillagersAsGuards`) instead of only being blocked.
- `GuardIdentityData` bumped to schema version 2 (proxy link, seeded flag, hearts migration bookkeeping), migrating a version-1 save automatically.
- Configuration renamed/removed: `guardInteractionScreen` → `guardMcaInteraction`; `guardInteractionCooldownSeconds`, `guardSocialHearts`, `guardGiftHearts`, `guardGiftHeartsLoved`, `guardHeartsLostPerHit` removed; `recruitMcaVillagersAsGuards` added. The item-gift tags and the earlier emulated screen (`client/GuardInteractionScreen`, the `network` package, `bridge/GuardAction`/`GuardResponses`/`McaNameSource`) are deleted.
- Diagnostics gain `/mcamobcompat guards proxies` and `/mcamobcompat guards rebuild`.

### 0.2.1 Feature Scope (Implemented)
- **The hidden proxy, released and hardened** (§10A): transactional creation through Forge's spawn
  event with family-tree rollback; no timer deletes a proxy; exact client pairing over
  `mcamobcompat:proxy_pair`; a nested proxy hitbox so every hit lands on the guard; final-amount
  damage relay and confirmed-death relay; no proxy loot or XP; two-way names; FOLLOW read from MCA's
  own memory; one router for both interaction packets with reach and dedupe; schema-3 identity store
  with separate hearts ledgers, `LegacyV2`, read-only future schema and quarantine.
- **Guard Villagers fixes** (§10): conversion cancelled on both sides; recruit refuses a child, an
  occupied hand and an existing guard, and refreshes MCA's brain; friendly fire vetoed at
  `LivingAttackEvent`, arrows included.
- **Chore safety:** `ChoreSafetyHandler` and `ChoreProtectionPolicy` keep MCA villagers' hunting chore
  off protected animals; five SERVER options under `[chores]`.
- **Reflective MCA binding layer** (`McaBinding`) for MCA 7.6 and 7.7, and registry-id memory access
  (`McaMemories`).
- **Creeper awareness** (§13C), **mount policy** (§11A), **Icy's Better Horses** (§13A),
  **Alex's Mobs** (§13B), and `OwnedAnimalProvider`.
- **Diagnostics:** `/mcamobcompat mca`, `mounts inspect`, `threats inspect`, `guards proxies`,
  `guards rebuild`; `classify` reports saddle, owner and rider.
- **Build-time guard tightening:** both `NoOptionalModStaticLinkTest` and `checkJarContents` scan for
  dot-form `Class.forName` literals, with an exact-match allowlist of one class, and forbid Icy's and
  Alex's (and Citadel's) package roots.
- **Verification:** GameTests against the real mods (`runGameTestServer`, with `-PmcaDev77` and
  `-PwithSwem`/`-PwithIcy`/`-PwithAlex`/`-PwithGolems`), and `remapDevMods` so those mods can run in dev.

**Deferred beyond 0.2.1:** pet ownership graphs, GA/DLO breeding hooks, animal-death emotional
reactions, MCA farmer/livestock chores on modded animals beyond the hunting restraint, the
`Village` reads for future chore features, SWEM steering (needs SWEM's change), and steering for
Alex's flying and swimming mounts.

## 22. Detailed Implementation Tasks

### 0.1.0 Tasks
All complete; kept as the record of what was built and how each piece was validated.

1. **Bootstrap.** `@Mod("mcamobcompat")`, two config specs (not three — §16). *Validated:* builds and
   launches with only MCA installed.
2. **Dependencies.** MCA `runtimeOnly`; **no `compileOnly` optional mod at all** — the spec expected
   several, and none turned out to be necessary. *Validated:* compiles with an empty `libs/`.
3. **Classification core.** `MobRole` + lazily-resolved `TagKey` constants + shipped tag JSONs.
   *Validated:* `TagFileValidityTest`; `/reload` succeeds.
4. **`TagClassifier` + `ClassificationCache` + `ClassificationReloadHandler`.** *Validated:*
   `RoleCacheTest`; cache clears on `/reload`.
5. **`MobCompatibilityAPI` + `ProviderRegistry`.** *Validated:* `/mcamobcompat classify`.
6. **`util/ModIds` + `ModIntegrations` gating and startup logging.** *Validated:*
   `/mcamobcompat status` with and without each optional mod.
7. **`GuardVillagersModule` + `ConversionGuardHandler`.** *Validated:* crouch + right-click an MCA villager with a sword; no guard produced, villager survives.
8. **`GuardDefenseHandler`.** *Validated:* zombie attacks villager; nearby guard engages zombie; guard never targets villager.
9. **`SwemModule` / `GeneticAnimalsModule` / `DragnLivestockModule` + `TamedMountProvider`.**
   *Validated:* `/mcamobcompat dump swem | dump eanimod | dump dragnlivestock`.
10. **`mca/McaEntities`.** *Validated:* `McaEntityIdTest`.

### 0.2.0 Tasks
1. **Config additions.** `GuardVillagersModule.treatsGuardsAsMcaGuards()` and `.guardHeartsLostPerHit()` methods reading SERVER spec options. *Test:* config toggles control guard recognition and hearts.
2. **Identity store.** `GuardIdentityData extends SavedData`; GUID-keyed map of `GuardIdentity` snapshots. Created on `EntityJoinLevelEvent` for bridged guards; marked deceased on `LivingDeathEvent` at `LOWEST`. *Test:* round-trip via `GuardIdentityDataTest` (creation, save, load, hearts modification).
3. **Predicate.** `MobCompatibilityAPI.isBridgedGuard(Entity)` returns true iff `treatGuardsAsMcaGuards` on AND module active AND `MobRole.GUARD` AND NOT `isMcaVillager`. *Test:* predicate never true for MCA villagers; true for `guardvillagers:guard` when configured on.
4. **Events.** Three Forge-bus events: `GuardInteractEvent` (cancelable, right-click), `GuardIdentityCreatedEvent` (creation), `GuardHeartsChangedEvent` (hearts write). *Test:* events fire at correct times, cancellation prevents Guard Villagers GUI.
5. **Name generation.** On creation: adopt guard's custom name if present, else generate from a bundled name list (`GuardNames`) using a `Random` seeded by the guard's UUID. Set as guard's custom name. The generated name is persisted so the same guard always gets the same identity across restarts. *Test:* name persists across save/reload; same UUID always gets same name.
6. **Hurt/death handlers.** `LivingHurtEvent` at `NORMAL`: if player attacker, apply `-guardHeartsLostPerHit` and fire `GuardHeartsChangedEvent`. `EntityJoinLevelEvent` at `NORMAL`: create identity. `LivingDeathEvent` at `LOWEST`: mark deceased. *Test:* player attacking guard loses hearts; death marks store entry as deceased.
7. **Diagnostics command.** `/mcamobcompat guards status | inspect <guard> | purge <days>`. *Test:* command output correct; purge removes old deceased entries.
8. **Unit tests:** `GuardIdentityDataTest` round-trip; predicate never true for MCA villagers; store persistence across toggle-off/on. *Test:* each in isolation.
9. **Per-addon adoption.** (Tracked in each addon repo.) Extend `isMcaVillager` to accept `isBridgedGuard`; route `GuardIdentity` reads on bridged guards. *Test per addon:* quest acceptance from bridged guard; crime witnessing; reputation incidents; conversations.

### Hidden Proxy Rework Tasks
1. **Store schema v2 and migration.** `GuardIdentityData` bumped to version 2 with `proxyUuid`/`seeded`/`heartsMigrated`/`pendingHearts`; a version-1 file loads unchanged. *Test:* `bridge/GuardIdentityDataMigrationTest`.
2. **`bridge/GuardProxy` and `bridge/GuardProxySync`.** Create, hide, link and tick the proxy; mirror MCA's movement intent onto the guard. *Test:* manual `runClient` checklist below.
3. **`integration/guardvillagers/GuardReflection` and `GuardVariants`.** By-name method handles on the guard's runtime class; variant-to-villager-type mapping. *Test:* `integration/guardvillagers/GuardVariantsTest`.
4. **`event/GuardProxyHandler`.** Proxy lifecycle (creation, orphan discard, level-follow), and redirecting interact/attack/target/projectile from the proxy to the guard. *(0.2.1 removed the orphan discard and moved click routing into `GuardBridgeHandler`.)*
5. **`event/GuardBridgeHandler` rewrite.** Routes the plain click to the proxy through `ForgeHooks` and `interactAt`/`interactOn`; the crouch click to Guard Villagers.
6. **`event/GuardDamageRelayHandler`.** Relays hurt and death onto the proxy; blocks independent damage to it.
7. **Recruit gesture.** `recruitMcaVillagersAsGuards` in `ConversionGuardHandler`.
8. **Config rename/removal.** `guardInteractionScreen` → `guardMcaInteraction`; five options removed. *Note:* `ForgeConfigSpec` has no migration, so an existing config is rewritten on first load.
9. **Deletion of the emulated screen.** `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, the `network` package, `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource`, and the item-gift tags.
10. **Diagnostics.** `/mcamobcompat guards proxies` and `/mcamobcompat guards rebuild`.

### 0.2.1 Tasks
All complete. Each names the check that covers it; results are in `docs/VALIDATION.md`.

1. **Housekeeping.** Version 0.2.1 in `gradle.properties`; `.port-work/` ignored; optional
   `icys_better_horses` and `alexsmobs` in `mods.toml`; forbidden roots widened. *Check:* `build`.
2. **Identity store schema 3.** Separate snapshot and pending ledgers, migration from 1 and 2,
   `LegacyV2`, read-only future schema, rejected and quarantined records. *Check:*
   `GuardIdentityDataMigrationTest`, `GuardHeartsAccountingTest`.
3. **MCA binding for 7.6 and 7.7.** Personality in both shapes, hearts, family-tree rollback.
   *Check:* `McaBindingTest`, `PersonalityBindingTest`, GameTests on both MCA lines.
4. **Proxy lifecycle.** Transactional creation, grace, adoption, no timer, stale-key cleanup,
   dimension travel, rebuild. *Check:* `ProxyGameTests`.
5. **Damage and death.** Final-amount relay, totem, late cancellation, one relay per death, proxy
   loot and XP. *Check:* `ProxyGameTests`.
6. **Projectiles and melee.** Nested hitbox; arrows, tipped arrows, tridents and snowballs hit the
   guard natively. *Check:* `ProxyGameTests`.
7. **Interaction router and recruit.** Both packets, reach, dedupe, disabled bridge, recruit refusals.
   *Check:* `ProxyGameTests`.
8. **Exact client pairing.** Channel, codec, tracking sends, client map, renderer. *Check:*
   `ProxyPairCodecTest`; the client half is on the manual checklist.
9. **GameTest scaffolding and dev remapping.** `gameTest` source set, per-mod namespaces and
   templates, `remapDevMods`, log-summary check. *Check:* `runGameTestServer` in every flag
   combination.
10. **Creeper awareness.** *Check:* `ThreatPolicyTest`, `ThreatGameTests`, `GolemGameTests`.
11. **Mount policy, SWEM, Icy's.** *Check:* `MountPolicyTest`, `MountGameTests`, `SwemGameTests`
    (including the steering observation), `IcyGameTests`.
12. **Alex's Mobs.** Audit, tags, `OwnedAnimalProvider`. *Check:* `AlexGameTests`,
    `TagFileValidityTest`, `docs/ALEXS_MOBS_AUDIT.md`.
13. **Documentation.** README, DATAPACK, CHANGELOG, this spec, CLAUDE.md, MODMAP.md, VALIDATION,
    DEPENDENCIES, UPSTREAM.

## 23. Testing Matrix
**0.1.0 baseline:** MCA only · MCA+SWEM · MCA+Genetic Animals · MCA+DLO · MCA+Guard Villagers · MCA+all. Run each on **dedicated server** AND **integrated singleplayer**.

**0.2.0 additions (guard bridge):** six configs above, plus each with `treatGuardsAsMcaGuards=true` and `=false` variants. Test **MCA+Guard Villagers** and **MCA+Guard Villagers+each addon**.

**Per-config tests (0.1.0):** world creation; existing-world loading; missing optional mods; adding/removing an optional integration mid-save; datapack reload; entity spawning; breeding; ownership; death; village detection; guard targeting; AI behavior; save/reload; server restart; dimension travel; entity unload/reload; multiplayer sync.

**Per-config tests (0.2.0):** above, plus:
- **Option off keeps store:** toggle `treatGuardsAsMcaGuards=false`, reload, confirm guards are not bridged; toggle `=true` again, confirm identity store is intact and guards are recognized again.
- **Sneak-click opens Guard Villagers GUI:** sneak + right-click a bridged guard, confirm the Guard Villagers inventory/status screen opens (not cancelled).
- **Plain click fires `GuardInteractEvent`:** with MCAConversations installed, right-click a bridged guard and confirm the chat window opens (event consumed).
- **Guard death marks deceased:** kill a bridged guard with `/kill @e[type=guardvillagers:guard]`, inspect store entry with `/mcamobcompat guards inspect`, confirm `deceased=true`.
- **Hearts lost on player attack:** have a player attack a bridged guard with fists; check MCA's own hearts on the proxy fall (`guardHeartsLostPerHit` no longer exists — MCA's own hurt reaction on the proxy replaces it).

**Per-config tests (hidden proxy rework):** the manual `runClient` checklist in the guard-bridge implementation plan — plain click opens MCA's own screen; chat/gift change MCA hearts and `guards inspect` agrees; Follow/Stay/Move actually move or pin the guard; sneak click still opens Guard Villagers' inventory; hitting or killing the guard drives MCA/MCACrime/MCAReputation reactions on the proxy with no orphaned proxy left behind; no floating clothes/hair/face/shadow when circling the guard; portal travel keeps the same proxy; save/reload creates no duplicate; `recruitMcaVillagersAsGuards` recruits in place; `guardMcaInteraction=false` creates no proxy at all.

**Per-config tests (0.2.1):** above, plus:
- **Chore safety with all protection modes:** each of the five `[chores]` options on and off; for each mode, verify that the correct animal types are protected and unprotected from hunting.
- **Livestock not protected by default:** in fresh config, `protectedRoles` includes only `mount` and `pet`; spawn an MCA villager and a Genetic Animals / DLO livestock animal and verify the villager hunts the livestock.
- **Pack author adds livestock protection:** add `livestock` to `protectedRoles`; verify villager now refuses to hunt livestock.
- **Self-defence exemption:** tag a vanilla wolf as `pet`, attack the wolf with the villager holding it; verify the villager fights back (defence) even though `pet` is protected.
- **Abandon hunt on protected target:** have a villager hunting a protected animal; verify the villager abandons the hunt when an attack is blocked (if `abandonHuntOnProtectedTarget` is on).
- **MCA binding resolution:** with MCA installed, run `/mcamobcompat mca` and verify the package root is identified and all bindings show `ok`; remove MCA and verify the binding reports gracefully with single warning.
- **Mount diagnostics:** with a tamed rideable (vanilla horse, SWEM), use `/mcamobcompat classify` and verify `saddled`, `owner` and `controllingPassenger` fields are reported.

**0.2.1: what runs automatically.** `runGameTestServer` starts a real dedicated server with the real
MCA and Guard Villagers and runs the `mcamobcompat_tests` GameTests:

| Run | Mods | Suites |
|---|---|---|
| core | MCA 7.6.20, Architectury, Guard Villagers 1.6.19 | proxy, threats, mounts |
| core, `-PmcaDev77` | MCA 7.7.1-alpha.2, Guard Villagers | the same |
| `-PwithSwem` | core + SWEM 1.6.6, GeckoLib, PlayerAnimator | + SWEM |
| all mods | core + SWEM, Icy's Better Horses, Alex's Mobs, Golems Kill Creepers and their libraries | + SWEM, Icy's, Alex's, Golems |

A production server check installs the built jar with `installMod` into a Forge 1.20.1-47.4.10 server
with MCA 7.6.20, Architectury and Guard Villagers. The exact commands, counts and logs are in
`docs/VALIDATION.md`.

**0.2.1: what remains manual.** Anything needing a real client: MCA's screen opening from a plain
click, the Guard Villagers inventory from a crouch click, portraits in MCAConversations and MCAQuests
with two guards side by side, no floating clothes around a guard, a potion-invisible real villager
staying visible, the recruiting player still seeing the villager, and the four sibling addons
reacting to a guard's death. Also the cells of the matrix above with Genetic Animals or DLO, and
integrated singleplayer. `docs/VALIDATION.md` lists which of these were run.

**Explicit failure checks:** crashes; `NoClassDefFoundError`; invalid casts; broken AI goals; duplicated interaction handlers; accidental breeding overrides; corrupted entity NBT; lost MCA relationships; server/client desync; reflective binding throwing on malformed MCA; hunts not abandoning when binding is unavailable.

## 24. Performance Considerations
- **No per-tick global entity scans** (never iterate `server.getAllEntities()` each tick).
- **0.2.1 per-entity work is bounded.** Proxy sync is a few field copies per guard per tick, with
  everything else once a second. Threat perception runs per MCA villager every 10 ticks, staggered,
  over a small box, weighing at most four threats; the emergency hold touches only villagers in an
  emergency. The client pair map is capped at 4096 entries per level.
- Classification is **O(1) cached per `EntityType`**, invalidated only on datapack reload.
- Event handlers perform constant-work tag-set membership checks on the single entity involved in the event.
- No client-side gameplay logic.
- **Estimated cost:** negligible — a handful of hash-set lookups on discrete events; no measurable server tick overhead expected.

## 25. Failure Handling
- Optional mod absent → module skipped; one info log line.
- Expected entity id missing → tag entry with `required:false` skipped; debug log.
- Supported mod changes an internal class → only that integration module fails; exception caught + logged; core continues.
- API method not found (reflection paths) → feature disabled with a warning.
- Datapack references invalid entity → skipped with warning.
- Another mod overrides the same AI → do not fight it; log the conflict.
- Entity cannot safely participate in a requested MCA mechanic → skip that feature with a meaningful warning. **Never crash.**

## 26. Logging / Diagnostics
**0.1.0 startup**, one line per optional mod:
```
[MCA: Mob Compatibility] MCA: Mob Compatibility initialising (mod id 'mcamobcompat')
[MCA: Mob Compatibility] Detected Guard Villagers 1.20.1-1.6.19; integration enabled.
[MCA: Mob Compatibility] SWEM not installed; integration skipped.
[MCA: Mob Compatibility] Detected Genetic Animals 0.11.9, but the integration is disabled in the config.
[MCA: Mob Compatibility] MCA: Mob Compatibility ready. MCA villagers recognised as [mca:male_villager, mca:female_villager].
```
**0.2.0 addition:** if `treatGuardsAsMcaGuards` is on, one more line:
```
[MCA: Mob Compatibility] Guard bridge enabled: Guard Villagers guards will be treated as MCA guards.
```
`general.debugLogging` adds per-decision detail: blocked conversions, guards alerted, friendly-fire
cancellations, provider failures (0.1.0); bridged-guard creation, identity assignment, heart changes, interaction events (0.2.0). It is off by default and genuinely verbose when on — the
`/mcamobcompat` command gives the same information on demand, which is usually what is wanted.
No spam during normal gameplay.

## 27. Compatibility Risks
- **[CORRECTED — the original risk was stated backwards.] MCA villagers *are* vanilla `Villager`s.**
  The danger is not that other mods fail to see them; it is that they *do*. Any mod gating on
  `instanceof Villager` reaches MCA villagers with no idea what they are carrying, which is exactly
  how the Guard Villagers conversion destroys them (§10). Worth auditing other installed mods for the
  same pattern.
- **MCA package renames are a non-risk for this addon**, which names no MCA class. They remain a real
  risk for the rest of the suite.
- **SWEM, Genetic Animals and DLO assets are All Rights Reserved** — no asset is redistributed here
  and they are referenced by registry id only. `libs/` is gitignored for this reason. MCA is GPLv3;
  this addon is GPL-3.0-only, and it copies no MCA source.
- **Guard Villagers config drift (0.1.0).** Defence and friendly fire are mirrored against behaviour driven by
  *its* config lists. If it changes those defaults, this mod's mirroring could double up (harmless —
  both call `setTarget` with the same attacker) or become redundant. Both halves are individually
  switchable.
- **A pack could weaken classification** by emptying a role tag with `"replace": true`. That is
  acceptable for classification and unacceptable for safety, which is why the MCA villager set behind
  the conversion blocker is a Java constant rather than a tag (§5).
- **Entity ids can change between mod versions.** A wrong id fails silently, so
  `/mcamobcompat dump <namespace>` exists and `TagFileValidityTest` bans the two known phantom ids.
- **Guard Villagers reputation gating (0.2.0).** Guard Villagers gates its inventory GUI on reputation (`reputationRequirementToBeAttacked`). For low-rep players, sneak-clicking a guard may do nothing (Guard Villagers' own rule, not this bridge's). Documented in the relevant addons.
- **Addon soft-dependency drift (0.2.0).** Each addon must check `ModList.isLoaded("mcamobcompat")` before calling the bridge API. If an addon is updated to add hard-dependency on mcamobcompat before this mod is, or skips the check, the suite becomes tightly coupled. Each addon's update is responsibility of its maintainer.
- **SWEM steering (0.2.1).** Villagers cannot drive SWEM horses until SWEM changes; the ride is
  refused (§11A) and the contract offered upstream (`docs/UPSTREAM.md`).
- **By-name reflection (0.2.1).** `GuardReflection` and `IcyReflection` bind members by their owners'
  own names. A rename in Guard Villagers or Icy's disables only that feature, logged once.
- **MCA 7.7 is alpha.** The binding supports both lines and was tested against 7.7.1-alpha.2; a later
  7.7 build may move members, which `/mcamobcompat mca` would show.
- **Alex's predators (0.2.1).** Ten Alex's Mobs classes already reach MCA villagers by class; the
  opt-in `alexsmobs:villagers` pack would add the rest.
- **Name collisions (0.2.0).** If a player names a guard the same as another bridge guard, the name is adopted; this addon never overwrites a custom name once set. Rare; documented.

## 28. Future Integrations
Prioritize mods overlapping MCA's village/pet/settlement ecosystem. Candidates and rationale:
- **Additional villager/NPC & settlement mods** (e.g. Illager-focused, settlement-simulation, extra-profession mods) — overlap MCA village population/defense; most covered by generic `humanoid_npc`/`guard` tags (low difficulty).
- **Pet mods** (owned-companion animals) — map to `pet`; generic tags cover recognition; ownership graphs deferred (medium difficulty).
- **Additional mount mods** — map to `mount`/`horse` via tags (low difficulty).
Almost all can be onboarded via datapack tags alone; only reserve bespoke code for mods (like Guard Villagers) whose mechanics touch MCA identity/data. Do **not** inflate the 0.1.0 list.

## 29. Version Roadmap
- **0.1.0 — shipped.** Core framework, entity classification, shipped tags, read-only API,
  diagnostics command, and the Guard Villagers safety work: conversion block, defence of MCA
  villagers, and friendly-fire prevention.
  *(Defence arrived a release early. It was scheduled for the third release, now 0.2.1, on the
  assumption it needed guard-AI work; once §10 established it was two event handlers and vanilla
  `Mob.setTarget`, holding it back would have meant shipping a release that knew guards ignore MCA
  villagers and did nothing about it.)*
- **0.2.0 — shipped.** Guard Villagers as MCA guards (bridge): identity store, predicate, events, and
  per-addon adoption (tracked in each addon repo: MCAQuests, MCACrime, MCAReputation, MCAConversations).
  Addon scope: extended `isMcaVillager` predicate, identity-driven reads. No FamilyTree/Village reads.
- **0.2.1 — implemented.** The hidden-proxy guard bridge, released and hardened; chore safety; the
  reflective MCA binding layer for MCA 7.6 and 7.7; creeper awareness; the mount policy; Icy's Better
  Horses and Alex's Mobs; GameTests against the real mods. No autonomous mount AI, no full farmer
  chores.
- **0.4.0** — MCA farmer/livestock chores on modded animals (recognition-driven hunting/breeding
  tasking), extending `McaBinding` with the `Village` reads it needs.
- **0.5.0** — Reputation-aware guard behaviour: village membership, gossip, and the MCA: Reputation and MCA: Crime seams.
- **0.6.0** — SWEM mounts as family and village property; stable/pasture recognition.
- **0.7.0** — Household pets and family ownership graphs; animal-death emotional reactions.
- **1.0.0** — Stabilised, versioned public compatibility API.

*(The hidden-proxy rework after 0.2.0 was never released on its own; it ships in 0.2.1. Guard Villagers bridge is prioritised in 0.2.0 because its interaction with the addon suite is essential for them to recognize guards. Chore safety (restraint, not enablement) moves from 0.2.0 to 0.2.1. Full farmer chores move to 0.4.0.)*

## 30. Acceptance Criteria

### 0.1.0 (Shipped)
Met. The design let implementation begin without rediscovering the architecture.

- **MCA systems integrated:** villager recognition (by registry id), guard defence, livestock/mount
  classification.
- **External systems integrated:** Guard Villagers (`guardvillagers:guard`), SWEM
  (`swem:swem_horse`), Genetic Animals (`eanimod:enhanced_*`), DLO (`dragnlivestock:o_*`).
- **Optional-mod detection** via `ModList.get().isLoaded` with isolated integration modules (§9), and
  **zero foreign class references anywhere in the jar** — enforced by `NoOptionalModStaticLinkTest`.
- **Entity classification** via Forge `TagKey<EntityType<?>>` tags, providers and a per-type cache.
- **Guard Villagers handling:** classification, conversion blocked, defence and friendly fire (§10).
- **SWEM horses** recognised read-only as mounts, ownership through the vanilla owner API (§11).
- **No Mixin needed** — both conditions were tested and both came back negative (§18).

**Release criteria met:** all 0.1.0 items implemented; builds green.

### 0.2.0 (Shipped)
- **Guard bridge:** identity store created and populated lazily, never deleted on option toggle, persists across saves (§10A, §22.0.2 task 2).
- **Predicate:** `isBridgedGuard` never true for MCA villagers, true for `guardvillagers:guard` when `treatGuardsAsMcaGuards=true` (§22.0.2 task 3).
- **Events:** `GuardInteractEvent` fires on right-click; sneak-click passes through to Guard Villagers (§22.0.2 task 4, [RRV] item 2); cancellation prevents Guard Villagers GUI (§22.0.2 task 4).
- **Hearts system:** hearts lost on player attack, persisted in store, modified via `GuardIdentity.modHearts` (§22.0.2 task 6).
- **Name generation:** from guard's custom name or MCA name lists, visible in-game (§22.0.2 task 5, [RRV] item 1).
- **Unreachable features documented (superseded by the hidden-proxy rework, see below):** interaction screen, village residency, marriage/children/family tree, genetics, armory (§10A MCA-like behaviour table, §3 Non-Goals).
- **Per-addon adoption:** tracked in each repo; `isMcaVillager` extended; identity reads working (§22.0.2 task 9).
- **Every [RRV] from §10A resolved before release** (§22.0.2 tasks, §10A Evidence Markers).

**Release criteria met:** testing matrix passed (§23); [RRV] items verified; per-addon adoption merged.

### Hidden Proxy Rework (after 0.2.0, released in 0.2.1)
- **Every previously "unreachable" feature is now reachable**, on the hidden proxy: MCA's own interaction screen, hearts/memories, movement intent, marriage, family tree, genetics and village residency (§10A MCA-like behaviour table).
- **Proxy lifecycle:** created from the guard's own tick with a grace period, never on join; linked by persistent data on both sides; discarded only when its guard is really gone, never on a timer (`bridge/GuardProxy`, `event/GuardProxyHandler`).
- **Interaction routing:** a plain click forwards to the proxy through `ForgeHooks` and `interactAt`/`interactOn`; a crouch click still reaches Guard Villagers; the proxy's own click is redirected back to the guard for the crouch gesture and for a melee swing.
- **Damage/death relay:** hits and death on the guard replay onto the proxy with the original damage source; nothing else can damage the proxy independently (`event/GuardDamageRelayHandler`).
- **Store migrated to schema 2** with no data loss from version 1, and to schema 3 in 0.2.1.
- **Emulated screen removed:** its three-packet `network` package (0.2.1's `network` holds only the pairing message), `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource` and the item-gift tags no longer exist.

### 0.2.1 (Implemented)
- **Chore safety:** `ChoreSafetyHandler` blocks hunts on protected animals; `ChoreProtectionPolicy` decides protection; configurable via five new SERVER options under `[chores]` (§16).
- **Reflection binding:** `McaBinding` resolves MCA's package root at runtime and reflectively reads `VillagerBrain` to check and abandon hunts (§7, §9, §17).
- **Graceful degradation:** `McaBinding` degrades to unavailable with a single log line, not an error; hunts are still blocked by `LivingAttackEvent` even if chore abandonment fails.
- **Build-time guard tightening:** `NoOptionalModStaticLinkTest` and `checkJarContents` now scan for dot-form `Class.forName` literals in addition to slash-form bytecode, with exact-match allowlist.
- **Mount diagnostics:** `/mcamobcompat classify` reports saddled/owner/controllingPassenger; `/mcamobcompat mca` reports binding state.
- **Livestock not protected by default:** `protectedRoles` ships with `["mount", "pet"]` only; packs must explicitly add `livestock` to protect them.
- **Self-defence exemption:** animals already attacking a villager are not protected, so the villager can defend itself.

- **Real MCA guards:** plain click reaches MCA's own screen through a real proxy; the proxy survives an unloaded guard, a totem and a late-cancelled death; one relay per real death; hits land on the guard; the client hides exactly the paired proxies (§10A).
- **Conversion safety:** cancelled on both logical sides; recruit keeps identity and refuses children, occupied hands and existing guards (§10).
- **Creeper awareness:** a civilian runs from a creeper hunting it before any damage; even an MCA guard runs from a burning fuse; an MCA guard engages a creeper that is not swelling; a proxy never reacts; golems and gamerules are untouched (§13C).
- **Mount policy:** a SWEM horse refuses a villager under `REFUSE` and, under `ALLOW_INERT`, seats one that cannot steer it while a vanilla horse takes its villager's orders; Alex's player-steered mounts are in the same tag; an owned or hitched Icy's horse refuses one; an unowned horse still carries one, and a player is never refused (§11A, §13A).
- **Alex's Mobs:** a tamed animal is a protected pet, four ground species are mounts, and the module switch removes every role (§13B).
- **MCA 7.6 and 7.7:** every binding reports ok on both lines.

**Release criteria:** unit tests and both jar gates pass in `build`; every GameTest run in §23 passes; the built jar loads on a production Forge server; `docs/VALIDATION.md` records the runs and lists what was not run.
## 31. Research Sources

**Mod repositories:**
- **MCA Reborn:** github.com/Luke100000/minecraft-comes-alive · github.com/SakuraRK/MCA_Reborn (branch 1.20.1) · curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn (project 535291) · modrinth.com/mod/minecraft-comes-alive-reborn
- **Guard Villagers:** github.com/seymourimadeit/guardvillagers · curseforge.com/minecraft/mc-mods/guard-villagers (project 360203)
- **MCA: Quests:** C:\Projects\MCAQuests (fork maintainer: joshmorris8550@gmail.com)
- **MCA: Crime:** C:\Projects\MCACrime
- **MCA: Reputation:** C:\Projects\MCAReputation
- **MCA: Conversations:** C:\Projects\MCAConversations
- **SWEM:** curseforge.com/minecraft/mc-mods/swem (project 582697) · wiki.swequestrian.com · modrinth.com/mod/swem
- **Genetic Animals:** curseforge.com/minecraft/mc-mods/genetic-animals (project 313213) · modrinth.com/mod/genetic-animals (Maven `DYjPZExA`)
- **DragN's Livestock Overhaul:** curseforge.com/minecraft/mc-mods/dragns-livestock-overhaul · github.com/DragN0007/Overhauled-Livestock · modrinth.com/mod/dragns-livestock-overhaul (Maven `fNM5n4ac`)

- **Icy's Better Horses:** modrinth.com/mod/icys-better-horses (Forge 1.20.1, version `M2ySgx5C`)
- **Alex's Mobs:** curseforge.com/minecraft/mc-mods/alexs-mobs · modrinth.com/mod/alexs-mobs (version `XoIASRVU`)
- **Golems Kill Creepers:** modrinth.com/mod/golemskillcreepers (version `5A4SVFRo`)

**Jar inspection** (0.1.0 and 0.2.0 research; 0.2.1 additions below):
- **MCA 7.6.20+1.20.1:** `VillagerEntityMCA extends Villager`, implements `VillagerLike<Self>`. `VillagerLike<E>` abstract contract: `VillagerDataHolder`, `Infectable`, `Messenger`, genetics/traits/brain/interactions. `GuardEnemiesSensor` priority logic: (1) `instanceof VillagerEntityMCA`, (2) current target, (3) config map by entity-type registry name, (4) `instanceof Enemy`. Reads zero entity-type tags. `Village`, `Residency` hard-typed to `VillagerEntityMCA`. `VillageManager.findNearestVillage(Entity)` accepts any `Entity`.
- **Guard Villagers 1.6.19:** `Guard extends PathfinderMob`, fields: `GossipContainer gossips`, `SimpleContainer guardInventory`, `OWNER_UNIQUE_ID`, `GUARD_POS`, `PATROLLING`, `GUARD_VARIANT`. Methods: `openGui(ServerPlayer)`, `gossip(Villager, long)`, `getGossips()`, `int getPlayerReputation(Player)`. `HandlerEvents` class: `onEntityTarget`, `onEntityHurt`, `onEntityInteract`, `onLivingSpawned`. `VillagerToGuard.onEntityInteract` checks: `#guardvillagers:convertible_guard_items` item tag, `player.isCrouching()`, `instanceof Villager`, `getVillagerData().getProfession()`. Mixins: `VillagerMixin` (adds time fields), `VillagerGoalPackagesMixin` (injects brain tasks).
- **Addon seams** (from per-addon source inspection):
  - MCAQuests: `McaCompat.isMcaVillager(Entity)` → `McaHandles.isVillager(Object)` (reflective `instanceof VillagerEntityMCA`); `McaBinding` for `Residency`, `Village`, `VillagerBrain`; no `GuardVillagersGuard` predicate or reader.
  - MCACrime: `McaCompat.isMcaVillager/isGuard`; config `responderEntities`/`protectedEntities` via `EntitySelectors` (bare id or #tag); `WitnessChecker`, `GuardEnforcement`, `GuardPopulationService` all hard-gate on `isMcaVillager/isGuard`.
  - MCAReputation: `McaCompat.isMcaVillager`; no guard-specific logic.
  - MCAConversations: `McaCompat.isMcaVillager`; `ProfessionProfile` data-driven; mixins into MCA (own CLAUDE.md).
- **0.2.1 additions:** MCA 7.6.20 and 7.7.1-alpha.2 (`makeBrain`, `ExplodingCreeperSensor`, memory ids,
  personality shapes, `FamilyTree`); Guard Villagers 1.6.19 `VillagerToGuard.convertVillager` (runs
  on both sides); SWEM 1.6.6 `SWEMHorseEntityBase.isControlledByLocalInstance`/`getControllingPassenger`;
  Icy's Better Horses 1.1.6 `AbstractHorseMixin`, `BhConfig`, command goals; Alex's Mobs 1.22.9
  `AMEntityRegistry` and every entity class (`docs/ALEXS_MOBS_AUDIT.md`); Golems Kill Creepers
  1.0.0+1.20.1 gamerules. Every artifact and its SHA-256 is in `docs/DEPENDENCIES.json`.

**Forge 1.20.1:** docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles · forge.gemwire.uk/wiki/Mods.toml · docs.minecraftforge.net/en/latest/concepts/registries