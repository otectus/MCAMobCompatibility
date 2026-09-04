# MCA: Mob Compatibility — Research & Implementation Specification (v0.1.0 shipped, 0.2.0 guard bridge shipped, 0.3.0 chore safety implemented)

*Target platform: Minecraft 1.20.1, Minecraft Forge. Deliverable: a research-backed engineering specification an autonomous coding agent can implement from scratch.*

> **Evidence key used throughout:** **[C]** = confirmed from source/official docs; **[I]** = inferred from source structure; **[RRV]** = REQUIRES RUNTIME VERIFICATION (a concrete test is given). Do not treat **[I]** or **[RRV]** items as facts during implementation.

> **Status — 0.1.0 shipped.** Every **[RRV]** item in this document has since been resolved by inspecting the actual mod jars, and three load-bearing findings turned out to be wrong. Corrections are marked **[CORRECTED]** inline; `CHANGELOG.md` summarises them. The mod id is **`mcamobcompat`** (no underscore, matching the rest of the MCA addon suite), the package root is `dev.otectus.mcamobcompat`, and the licence is GPL-3.0-only.
>
> **0.2.0 shipped: Guard Villagers bridge.** Treating Guard Villagers' `guardvillagers:guard` entities as if they were MCA guards for all four addons (MCAQuests, MCACrime, MCAReputation, MCAConversations), without converting their model, skin or AI. Behind a SERVER config option `treatGuardsAsMcaGuards` (default true). The bridge cannot reach MCA's own interaction screen, marriage/children/family tree, genetics, armory equipment, or village residency/population/guard quota — those require a real `VillagerEntityMCA`, which a guard is not. They remain out of reach (§10A).
>
> **0.3.0 implemented: Chore safety and MCA reflection layer.** MCA's hunting chore is now restrained from animals you care about (five new SERVER config options under `[chores]`). Brings the first reflective MCA binding layer: `mca/McaBinding` resolves MCA's package root at runtime and reads `VillagerBrain` to check and abandon the hunting chore when a protected target blocks it.

---

## TL;DR
- **This is a Forge 1.20.1 addon (`mcamobcompat`) that hard-depends only on MCA Reborn (mod id `mca`) and treats SWEM, Genetic Animals, DragN's Livestock Overhaul, and Guard Villagers as optional integrations** driven by datapack `TagKey<EntityType<?>>` tags plus a small classification API — recognition and delegation, never reimplementation.
- **[CORRECTED] Classloading isolation turned out to need no machinery at all.** Every 0.1.0 behaviour is reachable through vanilla APIs and registry ids, so the shipped jar references **no type from MCA or from any optional mod** — no reflective binding layer, no Mixin, no access transformer, and not one `compileOnly` optional dependency. `NoOptionalModStaticLinkTest` fails the build if that ever stops being true. 0.2.0 implements an identity bridge to treat Guard Villagers guards as MCA guards for the addon suite, still with no direct class references (§10A).
- **0.1.0 ships the framework, semantic classification, shipped default tags, a read-only public API, a `/mcamobcompat` diagnostic command, and the Guard Villagers safety work:** classifying guards, *blocking* the MCA→Guard conversion that destroys `FamilyTree`/marriage/genetics data, alerting guards when an MCA villager is attacked, and stopping guards attacking MCA villagers.
- **0.2.0 implemented: Guard Villagers as MCA guards (bridge).** Identity store + predicate + Forge events, with the four addons patched to accept the bridge predicate. See §10A for scope, design, and the features that remain unreachable.
- **[CORRECTED] Every [RRV] is now resolved**, three of them against the spec's own expectations: MCA villagers *are* vanilla `Villager`s (§5), Guard Villagers reads *no* entity-type tags so targeting cannot be tag-steered (§10), and Genetic Animals' mod id is **`eanimod`** (§12).

---

## 1. Executive Summary
MCA: Mob Compatibility (`mcamobcompat`) provides deep, extensible, gracefully-degrading compatibility between **MCA Reborn** and mods that add/replace/modify animals, NPCs, mounts, livestock, pets, and guards. It is architected as a **tag-and-classification framework**, not a bundle of hardcoded patches. Its non-negotiable principles: (1) never replace another mod's internal behavior unless unavoidable; (2) prefer recognition + delegation; (3) never assume a modded animal behaves like its vanilla equivalent; (4) never silently destroy MCA relationship/family/genetics data; (5) optional integrations are genuinely optional; (6) no direct class references to optional mods outside isolated integration modules.

The mod hard-requires `mca` (and its transitive `architectury`) and treats `swem`, `guardvillagers`, `eanimod` (Genetic Animals) and `dragnlivestock` as optional. **[C, mod id CORRECTED — see §4.5]**

## 2. Goals
- A semantic entity-classification layer (`livestock`, `cattle`, `sheep`, `pig`, `poultry`, `horse`, `mount`, `pet`, `guard`, `humanoid_npc`) driven primarily by Forge entity-type tags, extensible by modpack authors via datapacks without recompiling.
- Bridges from MCA systems (villager/guard recognition, village membership, farmer/livestock chores, mounts, pets) to modded mobs.
- Optional-dependency isolation with zero `NoClassDefFoundError` risk.
- Absolute preservation of MCA relationship/family/genetics/persistent data.

## 3. Non-Goals
- No new mobs, breeding systems, or genetics.
- No replacement of SWEM/Genetic Animals/DLO internal AI, breeding, or needs logic.
- No conversion of MCA villagers into Guard Villagers (explicitly blocked — §10).
- No proxy or shadow villager, and no conversion of Guard Villagers guards into MCA villagers; the bridge operates through identity store and event predicates, never entity replacement (§10A).
- No MCA family, marriage or genetics for guards (those require a real `VillagerEntityMCA`; out of reach for the bridge — §10A).
- No autonomous equestrian AI. MCA villagers already ride rideable entities, and MCA never initiated autonomous mounts. Equestrian mounts are player-commanded and remain so; they need no independent AI (§11).
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

## 5. MCA Reborn Architecture Analysis (integration surface + preferred mechanism)
| MCA integration point | Preferred mechanism (least invasive first) |
|---|---|
| Recognize external NPCs/guards as villagers | Forge entity-type tags + our classifier (tag registration) |
| Treat foreign guards as MCA guards (0.2.0) | Identity store + predicate + Forge events; addons patched to accept bridge predicate (§10A) |
| Guard defense / react to attacks on MCA villagers | Forge `LivingHurtEvent`/`LivingDeathEvent` + tag membership (event) |
| Farmer/livestock chores recognize modded animals | classification tags queried via our API (tag + API) |
| Mounts / pets | tags + **read-only** NBT/owner inspection (tags + optional reflection) |
| MCA family/genetics/relationships | **READ-ONLY**; never written by this mod |

**Critical constraint [CORRECTED].** The original claim here was inverted. `VillagerEntityMCA` **extends vanilla `net.minecraft.world.entity.npc.Villager`** (§4.1), so external logic using `instanceof Villager` *does* recognise MCA villagers. Two consequences, and they pull in opposite directions:

- **Helpful:** every vanilla `Villager` API works on an MCA villager, and vanilla village mechanics already include them. Iron golems defend them for free, via `DefendVillageTargetGoal`.
- **Dangerous:** so does every mod that gates on `instanceof Villager` without knowing about MCA. That is precisely how the Guard Villagers conversion reaches them and destroys them (§10).

Detection in this addon still uses the entity types `mca:male_villager` / `mca:female_villager` rather than a cast — not because a cast would fail, but because a registry-id check needs **no MCA class**, and so is immune to MCA's package renames (§4.1). The authoritative set is a Java constant in `McaEntities`, deliberately *not* a datapack tag: the conversion blocker is the one piece of this mod whose failure destroys unrecoverable player data, and it must not be switchable from data. The tag `mcamobcompat:mca_villagers` is additive on top of it.

## 6. Compatibility Definition
Full compatibility means: (1) no crashes / `NoClassDefFoundError`; (2) MCA villagers and modded mobs coexist without breaking each other's AI or data; (3) MCA behaviors (guard defense, farmer chores, mount/pet recognition) treat compatible modded mobs sensibly via semantic categories; (4) each external mod retains **sole** ownership of its breeding/genetics/needs logic. It is explicitly **not** merely crash-prevention.

## 7. Core Architecture
```
mcamobcompat  (dev.otectus.mcamobcompat)
 ├── api/            MobCompatibilityAPI, MobRole, ClassificationProvider
 │   └── event/      GuardInteractEvent, GuardIdentityCreatedEvent, GuardHeartsChangedEvent (0.2.0)
 ├── classification/ TagClassifier, ClassificationCache, RoleCache, ProviderRegistry,
 │                   TamedMountProvider
 ├── mca/            McaEntities (knows MCA entity ids), McaBinding (0.3.0 reflective layer)
 ├── bridge/         GuardIdentityData, GuardIdentity interface (0.2.0)
 ├── event/          ConversionGuardHandler, GuardDefenseHandler, ClassificationReloadHandler,
 │                   ChoreSafetyHandler (0.3.0), ChoreProtectionPolicy (0.3.0),
 │                   guard-interaction handlers (0.2.0)
 ├── integration/    IntegrationModule, ModIntegrations,
 │                   guardvillagers/, swem/, geneticanimals/, dragnlivestock/
 ├── command/        McaMobCompatCommand
 ├── util/           ModIds, ConfigAccess
 ├── data/mcamobcompat/tags/entity_types/*.json  (shipped default tags)
 ├── McaMobCompat.java        (@Mod entrypoint)
 └── McaMobCompatConfig.java  (COMMON + SERVER ForgeConfigSpec)
```

**[CORRECTED] 0.1.0 has no `bridge/` and no `config/` package.** Config is one file, following suite convention. **0.2.0 adds `bridge/` and `api/event/`** for guard identity and the bridge events. The bridge was deferred in 0.1.0; it arrives with the per-addon changes in 0.2.0.

Each `integration/<mod>` package is entered only behind a `ModList.get().isLoaded("<modid>")` gate
and remains the only place permitted to reference that mod's classes. **In 0.1.0 none of them does**
— the seam exists for later.

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
  declared. Optional: `swem`, `guardvillagers`, `eanimod` (Genetic Animals), `dragnlivestock`.
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

  So there is **no `compileOnly` optional dependency and no Mixin** (0.1.0), and
  no `@Mod.EventBusSubscriber` needs gating, because none of them touches a foreign class.
  `NoOptionalModStaticLinkTest` and the `checkJarContents` Gradle task both fail the build if that
  changes. **[CORRECTED] 0.3.0 adds the reflective binding layer (`mca/McaBinding`) needed for chore safety, and both guards were tightened: byte-scans now catch dot-form `Class.forName` literals, not just slash-form bytecode references, with an exact-match allowlist of one file.**
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
  *while holding a sword or crossbow* also does not open MCA's own screen.

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
  only vanilla `Mob.setTarget` and our own tag, so it works for any mod's tagged guard NPC.
  Retaliation against *player* attackers is off by default, because MCA has its own reputation and
  guard response for that.

- **Prevent friendly fire — [RESOLVED].** The spec marked this **[I]**; it is real. The Guard
  Villagers `MobBlackList` defaults to `minecraft:villager, minecraft:iron_golem,
  minecraft:wandering_trader, guardvillagers:guard, minecraft:creeper, alexsmobs:komodo_dragon,
  minecraft:enderman` — again matched by entity id, and again MCA villagers are not on it.
  **Implemented:** `GuardDefenseHandler` cancels `LivingChangeTargetEvent` when a `GUARD`-role mob
  acquires an MCA villager as its target.

- **Deliberately not done:** appending the MCA ids to `mobsGuardsProtectTargeted` at runtime would
  be a smaller change, but `ConfigValue.set()` writes to the user's `guardvillagers-common.toml`,
  and writing to another mod's config file is exactly the reaching-in this mod refuses to do.

- **Known limitation:** `guardArrowsHurtVillagers` lives inside the Guard Villagers config and is
  out of reach without referencing one of its classes.

- **No reversible conversion is offered.** MCA identity is not derivable from a `Guard`, so blocking
  is the correct policy rather than restoration.

- **Harmless finding:** the Guard Villagers `VillagerMixin` writes `timesThrownPotion`,
  `timesHealedGolem` and `timesRepairedGuard` NBT onto every `Villager`, MCA's included. It is
  purely additive and does not disturb MCA's data.

## 10A. Guard Villagers as MCA Guards — 0.2.0 Bridge

**Objective:** Treat `guardvillagers:guard` entities as MCA guards for MCAQuests, MCACrime, MCAReputation, and MCAConversations, without converting their model, skin or AI, behind a SERVER config option `treatGuardsAsMcaGuards` (default true).

**Approach:** Identity store (guard name, gender, personality, hearts per player) + predicate + Forge events. No proxy villager, no entity replacement. The bridge cannot reach MCA's own interaction screen, marriage/children/family tree, genetics, armory equipment, or village residency/population/guard quota — those require a real `VillagerEntityMCA`, which a guard is not. **§10A summarizes the design; implementation is tracked in the four addon repos.**

### Scope

An entity qualifies as a **bridged guard** if and only if: the option `treatGuardsAsMcaGuards` is on, the module is not suppressed (see §16), the entity carries `MobRole.GUARD` (tag `mcamobcompat:guard`, shipped with `guardvillagers:guard`), **and** `McaEntities.isMcaVillager(entity)` returns false.

### Implementation notes

Two deliberate departures from naive design:

- **Names, gender and personality are generated from a bundled list, not read from MCA's resources.** This mod never imports a class from MCA or any optional mod, by architecture (§3, §9). Rather than load a resource file from MCA's jar (which would require opening it to find the right path and entail a version-specific binding), names and gender are rolled once from the guard's UUID using a small bundled list and then persisted, so the same guard always gets the same identity across restarts and across changes to the generation lists. The personality vocabulary is drawn from MCA's canonical roster (`confident`, `peppy`, `friendly`, `flirty`, `playful`, `gloomy`, `sensitive`, `greedy`, `odd`, `crabby`, `extroverted`, `introverted`, `relaxed`, `anxious`, `peaceful`, `upbeat`), the same strings MCAConversations uses.
- **`GuardHeartsChangedEvent` carries a nullable guard entity.** The `GuardIdentity.modHearts(UUID, int)` call is keyed on player UUID and has no entity reference. The hearts can be changed while the guard is not loaded (in an unloaded chunk or a dimension nobody is in), in which case there is no mob to hand over. Listeners must handle null guard gracefully; the hearts change is already stored and the event is already fired.

### Configuration — Server-side

`mcamobcompat-server.toml`, section `[guard_villagers]`:
- **`treatGuardsAsMcaGuards`** = `true` (boolean). When false, the bridge is disabled: no events fire, no identities are assigned, but the store is not deleted — turning it back on resumes from where it left off.
- **`guardHeartsLostPerHit`** = `-5` (integer). Analogous to MCA guard heart penalties: a player hitting a bridged guard loses this many hearts with it (default `-5`, negative numbers hurt reputation).

### Identity Store — Runtime

**Package:** `dev.otectus.mcamobcompat.bridge`. **Class:** `GuardIdentityData extends SavedData`, one per world (stored in overworld `DataStorage`), keyed by guard UUID.

**Fields:** `name` (String), `gender` (enum `MALE`/`FEMALE`, 50/50 at creation), `personality` (String from MCA's name lists, persisted as data so addons can interpret it), `hearts` (Map<UUID, Integer> of player UUID to heart value), `created` (long game time), `deceased` (boolean flag + game time).

**Creation & Updates:**
- Created lazily on `EntityJoinLevelEvent` (server) for any bridged guard without an entry.
- **Name:** if the guard has a custom name, adopt it; otherwise generate from a bundled list (`GuardNames`) using a `Random` seeded by the guard's UUID. The generated name is immediately set as the guard's custom name, so it is visible in-game. Because name and gender are rolled from the UUID and persisted, the same guard always gets the same identity across restarts and across changes to the generation lists.
- **Death:** marked on `LivingDeathEvent` at priority `LOWEST`; kept, never auto-deleted. `/mcamobcompat guards purge <days>` (admin only) removes deceased entries older than N days.

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
  int hearts(UUID player);
  void modHearts(UUID player, int delta);  // the one write; fires GuardHeartsChangedEvent
  boolean deceased();
}
```

**Events:** Forge bus, package `dev.otectus.mcamobcompat.api.event`. Cancelable:

- **`GuardInteractEvent(Player, Mob guard, InteractionHand)`** — fired on plain (non-sneak) right-click of a bridged guard, from a `PlayerInteractEvent.EntityInteract` handler at priority `HIGH`. If any listener cancels it, the underlying interact is cancelled. Sneak right-click is never intercepted **[RRV: Guard.mobInteract behavior when player is sneaking]**; Guard Villagers' GUI opens as before.

Non-cancelable:

- **`GuardIdentityCreatedEvent(Mob guard, GuardIdentity)`** — lets MCAConversations seed its personality/identity data.
- **`GuardHeartsChangedEvent(Mob guard, UUID player, int old, int new)`** — fired by `GuardIdentity.modHearts`.

### MCA-Like Behaviour Implemented

| MCA behaviour on an MCA guard | For a bridged guard |
|---|---|
| Name, gender, personality | Stored in `GuardIdentity`, name shown as custom name on the entity. `GuardIdentityCreatedEvent` seeded at creation. |
| Hearts per player | Stored in identity; `-guardHeartsLostPerHit` applied on `LivingHurtEvent` by a player. Fired as `GuardHeartsChangedEvent`. |
| Guard defends villagers / no friendly fire | Already 0.1.0 behaviour (§10; applies to any `GUARD`-tagged mob). |
| Guard is defended when attacked | Guard Villagers native (`mobsGuardsProtectHurt` includes `guardvillagers:guard`). No work. |
| Interaction screen (quest offer, dialogue, reputation gain/loss) | **Not reachable; requires `VillagerEntityMCA`.** Replaced by `GuardInteractEvent`, consumed by addons. See per-addon notes below. |
| Village residency, population cap, guard quota | **Not reachable; `Residency` and `Village` are hard-typed to `VillagerEntityMCA`.** Addons compute "home village" via `VillageManager.findNearestVillage(Entity)` (accepts any `Entity`). |
| Marriage, children, family tree, genetics, armory equipment | **Out of reach; stated as non-goals (§3).** These require a real `VillagerEntityMCA`. |
| MCA guards targeting (incl. `GuardEnemiesSensor`) | Unchanged. MCA guards ignore `guardvillagers:guard` by default (§4.1 sensor priority logic). No interaction. |

### Per-Addon Changes — Summary (implementation tracked in each repo)

All four addons gate every villager feature on `isMcaVillager` (reflective `instanceof VillagerEntityMCA`). The bridge requires each to accept an additional predicate: `isMcaVillager(e) || MobCompatibilityAPI.isBridgedGuard(e)` (with soft dependency on `mcamobcompat` via `ModList.isLoaded`). Fields bridged from `GuardIdentity` replace hard MCA class reads on bridged guards:

- **MCAQuests** (§6 in 02-addons.md): `McaVillagerRef` snapshot from identity; `ProfessionMatcher` treats `mca:guard` profession id from a bridged guard; quest offer/turn-in fired from `GuardInteractEvent`; village Projects use nearest village. Hard casts to vanilla `Villager` in `DeliverToVillagerObjective`/`DeliveryDestination` stay (deliveries to guards unsupported, documented).
- **MCACrime** (§6): default `responderEntities` / `protectedEntities` config gain `#mcamobcompat:guard`; `McaCompat.isGuard` true for bridged; `WitnessChecker` accepts them (witness weight 1.0 in `ObserverRole`); `GuardEnforcement.moveVillagerTo`, `makeVillagerFlee`, `EscortService` drive a bridged guard via vanilla `Mob.setTarget` + `getNavigation().moveTo`; `GuardPopulationService` counts bridged guards toward the guard quota; `CrimeDialogueService` / `GuardChallengeService` accept bridged guards as dialogue speaker.
- **MCAReputation** (§6): predicate only; assault on a bridged guard is an incident against a villager; community from nearest village.
- **MCAConversations** (§6): predicate; identity seeded on `GuardIdentityCreatedEvent`; chat opened from `GuardInteractEvent` (cancels it); profession profile `mca:guard` → `WorkArchetype.DEFENSE`; mood/memories read from identity hearts + neutral mood.

### Diagnostics

Command `/mcamobcompat guards` (subcommand, admin):
- **`status`** — count of bridged guards in the world, store size.
- **`inspect`** — looked-at guard's `GuardIdentity` (UUID, name, gender, personality, hearts per online player).
- **`purge <days>`** — remove deceased entries older than N days.

`/mcamobcompat classify` output gains a "bridged MCA guard" line when applicable.

### Evidence Markers — 0.2.0

New **[RRV]** items (each with a concrete test to be performed before release):

1. **MCA name-file path/format.** Not applicable: implementation uses a bundled list (`GuardNames`), see §10A Implementation notes.
2. **Guard.mobInteract sneak behaviour.** Confirm whether `Guard.mobInteract` opens the GUI when the player is sneaking, or whether sneak suppresses the interaction entirely.
3. **Handler priority ordering.** Confirm that cancelling `PlayerInteractEvent.EntityInteract` (when a `GuardInteractEvent` listener cancelled it) prevents the vanilla `Entity.interact` → `Guard.mobInteract` call, so the Guard Villagers GUI does not open; and that when nothing cancels, the GUI opens as before. Keep the `HIGH` priority mention for consistency with `ConversionGuardHandler`, not as pre-empting a Guard Villagers subscriber.
4. **`VillageManager.findNearestVillage(Entity)` compatibility.** Confirm the four addons' bindings accept a `PathfinderMob` (or plain `Entity`) without casting to `Villager` or `LivingEntity`.
5. **MCA villager brain overrides.** Confirm MCA villagers' `makeBrain` overrides prevent Guard Villagers' `VillagerGoalPackagesMixin` from injecting its brain tasks (including `ShareGossipWithGuard`), so MCA villagers ignore that task.

## 11. SWEM Integration
Entity `swem:swem_horse` (confirmed first-party from `EntitySetup`; the mod also registers
`swem:horse_poop`, `swem:rope_knot` and `swem:wormieboi`). SWEM owns ownership, needs, skills and
breeding.

- **Classify `swem:swem_horse` as `horse` + `mount`** (shipped tag). **[C]**
- **[CORRECTED] MCA villagers can mount rideable entities, but only through the player-issued `ridehorse` command, never autonomously. On SWEM horses, the mount succeeds but the horse has no steering rider and will not follow commands.** `MCA.ridehorse` filters on `AbstractHorse implements Saddleable` and `isSaddled()`, both of which SWEM horses pass. But `SWEMHorseEntityBase.getControllingPassenger()` filters to `Player` only, so MCA's `MixinHorseBaseEntity` never engages. The villager mounts but the horse is inert. This is not reachable from this mod without a mixin, so 0.3.0 documents it as a limitation and ships a diagnostic instead of attempting a fix.
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
  { "id": "dragnlivestock:o_camel", "required": false }
] }
```
SWEM horses are classified as mounts through the `#mcamobcompat:horse` tag reference; `swem:swem_horse` lives in `horse.json`.

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

## 16. Configuration
Two specs, not three. **[CORRECTED: no client config]** — nothing in 0.1.0 runs client-side, and
`debugLogging` plus the `/mcamobcompat` command cover the diagnostic need the client file was for. **0.2.0 additions** noted below.

- **`mcamobcompat-common.toml`** — `[general] debugLogging`; `[modules]` on/off per integrated mod.
  Turning a module off makes this addon completely blind to that mod's entities, which is what "off"
  has to mean if it is to settle a fight between two mods.
- **`mcamobcompat-server.toml`** (world-scoped, server-authoritative) —
  `[guard_villagers]` (0.1.0): `blockGuardConversion`, `protectMcaVillagers`, `preventGuardFriendlyFire`,
  `retaliateAgainstPlayers`, `guardHelpRange`, `maxGuardsAlerted`.
  (0.2.0): `treatGuardsAsMcaGuards` (boolean, default true), `guardHeartsLostPerHit` (int, default -5).
  (0.3.0): `[chores]` section with `protectOwnedAnimals` (boolean, default true), `protectedRoles` (list of `MobRole` names, default `["mount", "pet"]`), `protectNamedAnimals` (boolean, default true), `protectLeashedAnimals` (boolean, default true), `abandonHuntOnProtectedTarget` (boolean, default true).
  `[swem] recognizeAsMounts`; `[genetic_animals] recognizeLivestock`;
  `[dragn_livestock] recognizeLivestock`.

`protectMcaChildren` was dropped: MCA children are the same entity types as adults, so the villager
setting already covers them and a second flag would only imply otherwise. `enableDatapackMappings`
was dropped too — datapack tags are the mechanism, not an optional extra, and a switch to disable
them would just be a way to break the mod.

Gameplay lives in the server spec because all of it is server-authoritative. Options are kept few on
purpose: classification is extended with datapack tags, never with config lists, so there is no
per-entity toggle and there should never be one.

## 17. Forge Events / Hooks
- Mod constructor: register both config specs, add the mod-bus listeners.
- `FMLCommonSetupEvent`, inside `enqueueWork`: detect optional mods and log one line each, then
  register the classification providers. Inside `enqueueWork` because only there is `ModList`
  authoritative.
- `ModConfigEvent.Reloading`: drop the classification cache, since a module toggle changes it.
- **`TagsUpdatedEvent`** (Forge bus): invalidate the classification cache. **[CORRECTED]** — the spec
  suggested `AddReloadListenerEvent`; `TagsUpdatedEvent` is the right hook, because tags are what is
  cached and it fires after they are bound, on world load as well as `/reload`.
- `PlayerInteractEvent.EntityInteract` at **`EventPriority.HIGH`**: cancel the MCA→Guard conversion
  (§10). The priority is load-bearing — Guard Villagers subscribes at `NORMAL`. **0.2.0: also fires
  `GuardInteractEvent` at `HIGH` for bridged guards before the Guard Villagers handler runs.**
- `LivingChangeTargetEvent` and `LivingHurtEvent` (server side only): guard defence and friendly-fire
  prevention (§10). **[CORRECTED]** — `LivingDeathEvent` is not used; by then the villager is dead and
  there is nothing to defend. **0.2.0: `LivingDeathEvent` at `LOWEST` marks a bridged guard as deceased in the identity store.**
- **`LivingAttackEvent` (0.3.0): cancel attacks from MCA villagers on protected animals** (primary chore safety hook). **`LivingChangeTargetEvent` (0.3.0): cancel target-changes and abandon hunts** (belt-and-braces). Driven by `ChoreSafetyHandler` reading `ChoreProtectionPolicy` to decide protection; uses `McaBinding` to abandon hunts if configured.
- `EntityJoinLevelEvent` (server, 0.2.0): create `GuardIdentity` entry lazily for a bridged guard joining the level.
- `RegisterCommandsEvent`: the `/mcamobcompat` command. **0.2.0: adds `/mcamobcompat guards` subcommand. 0.3.0: adds `/mcamobcompat mca` subcommand.**
- **`FMLCommonSetupEvent.enqueueWork` (0.3.0): call `McaBinding.resolve()` to probe MCA's package root and bind reflective methods.** Called once at startup.
- Subscribers are split across `bus = MOD` (setup, via explicit `addListener`) and `bus = FORGE`
  (gameplay, via `@Mod.EventBusSubscriber(modid = "mcamobcompat")`). **No subscriber needs gating**,
  because none of them touches an optional mod's classes. **0.2.0 event declarations go in `api.event` package, not gated; they are pure API.**

## 18. Mixins / Access Transformers
**None, and none were needed [RESOLVED].** Both conditions the spec set for justifying a Mixin were
tested against the real jar and both came back negative:

- *Can the MCA→Guard conversion be cancelled via `PlayerInteractEvent`?* **Yes.** The Guard Villagers
  handler is a bare `@SubscribeEvent` at `NORMAL` with `receiveCanceled = false`, so cancelling at
  `HIGH` pre-empts it (§10).
- *Can guard targeting be steered without patching goals?* **Yes, though not the way the spec
  imagined.** Tags cannot do it, because Guard Villagers reads none — but `LivingChangeTargetEvent`
  and `LivingHurtEvent` plus vanilla `Mob.setTarget` cover both defence and friendly fire (§10).

So the shipped jar contains no Mixin, no refmap and no access transformer. It also contains no
reference to any MCA or optional-mod type at all, which is a stronger property than the spec asked
for and is enforced by `NoOptionalModStaticLinkTest`.

## 19. Package Structure
As in §7. Responsibilities:
- **`McaMobCompat`** — `@Mod("mcamobcompat")` entrypoint; config registration; setup ordering.
- **`McaMobCompatConfig`** — both `ForgeConfigSpec`s, in one file, per suite convention.
- **`api/`** — the public surface: `MobCompatibilityAPI`, `MobRole`, `ClassificationProvider` (0.1.0); `isBridgedGuard`, `getGuardIdentity` (0.2.0).
  - **`api/event/`** (0.2.0) — `GuardInteractEvent`, `GuardIdentityCreatedEvent`, `GuardHeartsChangedEvent`.
- **`classification/`** — `TagClassifier`, `ClassificationCache` (per-`EntityType`, reload-invalidated),
  `RoleCache` (its generic, unit-testable core), `ProviderRegistry`, `TamedMountProvider`.
- **`mca/McaEntities`** — the only place that knows MCA entity ids. Recognises
  `mca:male_villager` / `mca:female_villager` by registry id; holds the authoritative Java constant
  set behind the conversion blocker and the additive `mcamobcompat:mca_villagers` tag.
- **`mca/McaBinding`** (0.3.0) — the reflective MCA binding layer. Resolves MCA's package root at runtime (probes four known layouts) and reflectively reads `VillagerEntityMCA.getVillagerBrain()`, `VillagerBrain.getCurrentJob()` and `VillagerBrain.abandonJob()`. Holds no MCA types; degrades gracefully if MCA is absent or moved. Used by `ChoreSafetyHandler` to abandon hunts.
- **`bridge/`** (0.2.0) — `GuardIdentityData extends SavedData`, `GuardIdentity interface`. **No MCA or Guard Villagers class reference, per the invariant (§6, §9).**
- **`event/`** — `ConversionGuardHandler`, `GuardDefenseHandler`, `ClassificationReloadHandler` (0.1.0); `ChoreSafetyHandler`, `ChoreProtectionPolicy` (0.3.0); bridge event handlers (0.2.0).
- **`integration/<mod>/`** — the only places permitted to reference each optional mod's classes.
  **In 0.1.0 none of them does**; they hold presence detection, the config gate, and (for Guard
  Villagers) the id of the item tag the conversion reads. **0.2.0: still no direct references; the bridge is vanilla-only.**
- **`command/McaMobCompatCommand`** — `status`, `classify`, `dump <namespace>` (0.1.0); `/mcamobcompat guards` subcommand (0.2.0).
- **`util/ModIds`, `util/ConfigAccess`** — id constants, and config reads that tolerate an unloaded
  spec.

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

# Optional, all four with an EMPTY versionRange (= any version) and ordering="AFTER".
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

// MCA -- runtimeOnly, for dev runs only. Not a compile dependency: no class here names an MCA type.
runtimeOnly fg.deobf("maven.modrinth:minecraft-comes-alive-reborn:7.6.20+1.20.1")
runtimeOnly fg.deobf("dev.architectury:architectury-forge:9.2.14")

// NO compileOnly optional-mod dependency of any kind. [CORRECTED]
// The optional jars in libs/ are added to the dev run only; libs/ is gitignored because SWEM,
// Genetic Animals and DLO are All Rights Reserved and must not be redistributed.
```

`checkJarContents` fails the build if a class from MCA, Architectury or any optional mod reaches the
jar; `verifyReobfJar` fails it if the artifact was never reobfuscated; `installMod` copies the
verified jar into a real instance so nobody has to race `reobfJar` by hand.

## 21. Version Scope: 0.1.0 Shipped, 0.2.0 Shipped, 0.3.0 Implemented

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

### 0.3.0 Feature Scope (Implemented)
- Chore safety: `ChoreSafetyHandler` and `ChoreProtectionPolicy` prevent MCA villagers' hunting chore from killing protected animals.
- Five new SERVER config options under `[chores]`: `protectOwnedAnimals`, `protectedRoles`, `protectNamedAnimals`, `protectLeashedAnimals`, `abandonHuntOnProtectedTarget`.
- Reflective MCA binding layer: `McaBinding` resolves MCA's package root at runtime and reads `VillagerBrain` to check and abandon hunts.
- Build-time guard tightening: both `NoOptionalModStaticLinkTest` and `checkJarContents` now scan for dot-form `Class.forName` literals, with an exact-match allowlist.
- Mount diagnostics: `/mcamobcompat classify` reports saddled/owner/controllingPassenger; new `/mcamobcompat mca` subcommand reports MCA binding state.

**Deferred beyond 0.3.0:** pet ownership graphs, GA/DLO
breeding hooks, animal-death emotional reactions, MCA farmer/livestock chores on modded animals beyond the hunting restraint, and
the `FamilyTree`/`Village` reads for future chore features.

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

## 23. Testing Matrix
**0.1.0 baseline:** MCA only · MCA+SWEM · MCA+Genetic Animals · MCA+DLO · MCA+Guard Villagers · MCA+all. Run each on **dedicated server** AND **integrated singleplayer**.

**0.2.0 additions (guard bridge):** six configs above, plus each with `treatGuardsAsMcaGuards=true` and `=false` variants. Test **MCA+Guard Villagers** and **MCA+Guard Villagers+each addon**.

**Per-config tests (0.1.0):** world creation; existing-world loading; missing optional mods; adding/removing an optional integration mid-save; datapack reload; entity spawning; breeding; ownership; death; village detection; guard targeting; AI behavior; save/reload; server restart; dimension travel; entity unload/reload; multiplayer sync.

**Per-config tests (0.2.0):** above, plus:
- **Option off keeps store:** toggle `treatGuardsAsMcaGuards=false`, reload, confirm guards are not bridged; toggle `=true` again, confirm identity store is intact and guards are recognized again.
- **Sneak-click opens Guard Villagers GUI:** sneak + right-click a bridged guard, confirm the Guard Villagers inventory/status screen opens (not cancelled).
- **Plain click fires `GuardInteractEvent`:** with MCAConversations installed, right-click a bridged guard and confirm the chat window opens (event consumed).
- **Guard death marks deceased:** kill a bridged guard with `/kill @e[type=guardvillagers:guard]`, inspect store entry with `/mcamobcompat guards inspect`, confirm `deceased=true`.
- **Hearts lost on player attack:** have a player attack a bridged guard with fists; check hearts change by `-guardHeartsLostPerHit`.

**Per-config tests (0.3.0):** above, plus:
- **Chore safety with all protection modes:** each of the five `[chores]` options on and off; for each mode, verify that the correct animal types are protected and unprotected from hunting.
- **Livestock not protected by default:** in fresh config, `protectedRoles` includes only `mount` and `pet`; spawn an MCA villager and a Genetic Animals / DLO livestock animal and verify the villager hunts the livestock.
- **Pack author adds livestock protection:** add `livestock` to `protectedRoles`; verify villager now refuses to hunt livestock.
- **Self-defence exemption:** tag a vanilla wolf as `pet`, attack the wolf with the villager holding it; verify the villager fights back (defence) even though `pet` is protected.
- **Abandon hunt on protected target:** have a villager hunting a protected animal; verify the villager abandons the hunt when an attack is blocked (if `abandonHuntOnProtectedTarget` is on).
- **MCA binding resolution:** with MCA installed, run `/mcamobcompat mca` and verify the package root is identified and all bindings show `ok`; remove MCA and verify the binding reports gracefully with single warning.
- **Mount diagnostics:** with a tamed rideable (vanilla horse, SWEM), use `/mcamobcompat classify` and verify `saddled`, `owner` and `controllingPassenger` fields are reported.

**Explicit failure checks:** crashes; `NoClassDefFoundError`; invalid casts; broken AI goals; duplicated interaction handlers; accidental breeding overrides; corrupted entity NBT; lost MCA relationships; server/client desync; reflective binding throwing on malformed MCA; hunts not abandoning when binding is unavailable.

## 24. Performance Considerations
- **No per-tick global entity scans** (never iterate `server.getAllEntities()` each tick).
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
  *(Defence arrived a release early. It was scheduled for 0.3.0 on the assumption it needed guard-AI
  work; once §10 established it was two event handlers and vanilla `Mob.setTarget`, holding it back
  would have meant shipping a release that knew guards ignore MCA villagers and did nothing about it.)*
- **0.2.0 — shipped.** Guard Villagers as MCA guards (bridge): identity store, predicate, events, and
  per-addon adoption (tracked in each addon repo: MCAQuests, MCACrime, MCAReputation, MCAConversations).
  Addon scope: extended `isMcaVillager` predicate, identity-driven reads. No FamilyTree/Village reads.
- **0.3.0 — implemented.** Chore safety: restraining the hunting chore from killing protected animals. Brings the first reflective MCA binding layer (`McaBinding`) to read `VillagerBrain` and check/abandon hunts. Build-time guard tightening to catch dot-form class names. Mount diagnostics improvements. No autonomous mount AI, no full farmer chores (those are deferred).
- **0.4.0** — MCA farmer/livestock chores on modded animals (recognition-driven hunting/breeding tasking). Brings `FamilyTree`/`Village` reads, and with them the reflective MCA binding layer the rest of the suite already uses.
- **0.5.0** — Reputation-aware guard behaviour: village membership, gossip, and the MCA: Reputation and MCA: Crime seams.
- **0.6.0** — SWEM mounts as family and village property; stable/pasture recognition.
- **0.7.0** — Household pets and family ownership graphs; animal-death emotional reactions.
- **1.0.0** — Stabilised, versioned public compatibility API.

*(Guard Villagers bridge is prioritised in 0.2.0 because its interaction with the addon suite is essential for them to recognize guards. Chore safety (restraint, not enablement) moves from 0.2.0 to 0.3.0. Full farmer chores move to 0.4.0.)*

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
- **Unreachable features documented:** interaction screen, village residency, marriage/children/family tree, genetics, armory (§10A MCA-like behaviour table, §3 Non-Goals).
- **Per-addon adoption:** tracked in each repo; `isMcaVillager` extended; identity reads working (§22.0.2 task 9).
- **Every [RRV] from §10A resolved before release** (§22.0.2 tasks, §10A Evidence Markers).

**Release criteria met:** testing matrix passed (§23); [RRV] items verified; per-addon adoption merged.

### 0.3.0 (Implemented)
- **Chore safety:** `ChoreSafetyHandler` blocks hunts on protected animals; `ChoreProtectionPolicy` decides protection; configurable via five new SERVER options under `[chores]` (§16).
- **Reflection binding:** `McaBinding` resolves MCA's package root at runtime and reflectively reads `VillagerBrain` to check and abandon hunts (§7, §9, §17).
- **Graceful degradation:** `McaBinding` degrades to unavailable with a single log line, not an error; hunts are still blocked by `LivingAttackEvent` even if chore abandonment fails.
- **Build-time guard tightening:** `NoOptionalModStaticLinkTest` and `checkJarContents` now scan for dot-form `Class.forName` literals in addition to slash-form bytecode, with exact-match allowlist.
- **Mount diagnostics:** `/mcamobcompat classify` reports saddled/owner/controllingPassenger; `/mcamobcompat mca` reports binding state.
- **Livestock not protected by default:** `protectedRoles` ships with `["mount", "pet"]` only; packs must explicitly add `livestock` to protect them.
- **Self-defence exemption:** animals already attacking a villager are not protected, so the villager can defend itself.

**Release criteria:** all 0.3.0 items built and verified; testing matrix (§23) passed for chore configs; MCA binding gracefully handles missing/moved MCA; no foreign class references broken by tightened guards.
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

**Jar inspection** (0.1.0 and 0.2.0 research):
- **MCA 7.6.20+1.20.1:** `VillagerEntityMCA extends Villager`, implements `VillagerLike<Self>`. `VillagerLike<E>` abstract contract: `VillagerDataHolder`, `Infectable`, `Messenger`, genetics/traits/brain/interactions. `GuardEnemiesSensor` priority logic: (1) `instanceof VillagerEntityMCA`, (2) current target, (3) config map by entity-type registry name, (4) `instanceof Enemy`. Reads zero entity-type tags. `Village`, `Residency` hard-typed to `VillagerEntityMCA`. `VillageManager.findNearestVillage(Entity)` accepts any `Entity`.
- **Guard Villagers 1.6.19:** `Guard extends PathfinderMob`, fields: `GossipContainer gossips`, `SimpleContainer guardInventory`, `OWNER_UNIQUE_ID`, `GUARD_POS`, `PATROLLING`, `GUARD_VARIANT`. Methods: `openGui(ServerPlayer)`, `gossip(Villager, long)`, `getGossips()`, `int getPlayerReputation(Player)`. `HandlerEvents` class: `onEntityTarget`, `onEntityHurt`, `onEntityInteract`, `onLivingSpawned`. `VillagerToGuard.onEntityInteract` checks: `#guardvillagers:convertible_guard_items` item tag, `player.isCrouching()`, `instanceof Villager`, `getVillagerData().getProfession()`. Mixins: `VillagerMixin` (adds time fields), `VillagerGoalPackagesMixin` (injects brain tasks).
- **Addon seams** (from per-addon source inspection):
  - MCAQuests: `McaCompat.isMcaVillager(Entity)` → `McaHandles.isVillager(Object)` (reflective `instanceof VillagerEntityMCA`); `McaBinding` for `Residency`, `Village`, `VillagerBrain`; no `GuardVillagersGuard` predicate or reader.
  - MCACrime: `McaCompat.isMcaVillager/isGuard`; config `responderEntities`/`protectedEntities` via `EntitySelectors` (bare id or #tag); `WitnessChecker`, `GuardEnforcement`, `GuardPopulationService` all hard-gate on `isMcaVillager/isGuard`.
  - MCAReputation: `McaCompat.isMcaVillager`; no guard-specific logic.
  - MCAConversations: `McaCompat.isMcaVillager`; `ProfessionProfile` data-driven; mixins into MCA (own CLAUDE.md).

**Forge 1.20.1:** docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles · forge.gemwire.uk/wiki/Mods.toml · docs.minecraftforge.net/en/latest/concepts/registries