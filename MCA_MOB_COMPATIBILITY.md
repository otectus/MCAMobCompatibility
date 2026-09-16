> Historical Forge 1.20.1 research and implementation record. It does not describe the current NeoForge port or prove target-version behavior. Current architecture, migration and validation: [PORTING.md](docs/PORTING.md), [VALIDATION.md](docs/VALIDATION.md), [README.md](README.md). Historical package names, loader APIs, blanket feature claims and proxy lifetime/rendering descriptions below are superseded.

# MCA: Mob Compatibility — Research & Implementation Specification (v0.1.0 shipped, 0.2.0 guard bridge shipped, 0.3.0 chore safety implemented)

*Target platform: Minecraft 1.20.1, Minecraft Forge. Deliverable: a research-backed engineering specification an autonomous coding agent can implement from scratch.*

> **Evidence key used throughout:** **[C]** = confirmed from source/official docs; **[I]** = inferred from source structure; **[RRV]** = REQUIRES RUNTIME VERIFICATION (a concrete test is given). Do not treat **[I]** or **[RRV]** items as facts during implementation.

> **Status — 0.1.0 shipped.** Every **[RRV]** item in this document has since been resolved by inspecting the actual mod jars, and three load-bearing findings turned out to be wrong. Corrections are marked **[CORRECTED]** inline; `CHANGELOG.md` summarises them. The mod id is **`mcamobcompat`** (no underscore, matching the rest of the MCA addon suite), the package root is `dev.otectus.mcamobcompat`, and the licence is GPL-3.0-only.
>
> **0.2.0 shipped: Guard Villagers bridge.** Treating Guard Villagers' `guardvillagers:guard` entities as if they were MCA guards for all four addons (MCAQuests, MCACrime, MCAReputation, MCAConversations), without converting their model, skin or AI. Behind a SERVER config option `treatGuardsAsMcaGuards` (default true).
>
> **0.2.x reworked: hidden MCA proxy.** The identity-store-only bridge below could not reach MCA's own interaction screen, marriage/family/genetics or village residency, because all four addons and MCA's own screen resolve a real `VillagerEntityMCA` (by class or by UUID) and `VillagerLike` is a closed interface no mixin-free guard can implement. Every bridged guard now gets a real, hidden `mca:*_villager` (profession `mca:guard`) that carries all of that state and receives every MCA interaction; the guard forwards its plain click to it and relays damage and death onto it. See §10A.
>
> **0.3.0 implemented: Chore safety and MCA reflection layer.** MCA's hunting chore is now restrained from animals you care about (five new SERVER config options under `[chores]`). Brings the first reflective MCA binding layer: `mca/McaBinding` resolves MCA's package root at runtime and reads `VillagerBrain` to check and abandon the hunting chore when a protected target blocks it.

---

## TL;DR
- **This is a Forge 1.20.1 addon (`mcamobcompat`) that hard-depends only on MCA Reborn (mod id `mca`) and treats SWEM, Genetic Animals, DragN's Livestock Overhaul, and Guard Villagers as optional integrations** driven by datapack `TagKey<EntityType<?>>` tags plus a small classification API — recognition and delegation, never reimplementation.
- **[CORRECTED] Classloading isolation turned out to need no machinery at all.** Every 0.1.0 behaviour is reachable through vanilla APIs and registry ids, so the shipped jar references **no type from MCA or from any optional mod** — no reflective binding layer, no Mixin, no access transformer, and not one `compileOnly` optional dependency. `NoOptionalModStaticLinkTest` fails the build if that ever stops being true. 0.2.0 implements an identity bridge to treat Guard Villagers guards as MCA guards for the addon suite, still with no direct class references (§10A).
- **0.1.0 ships the framework, semantic classification, shipped default tags, a read-only public API, a `/mcamobcompat` diagnostic command, and the Guard Villagers safety work:** classifying guards, *blocking* the MCA→Guard conversion that destroys `FamilyTree`/marriage/genetics data, alerting guards when an MCA villager is attacked, and stopping guards attacking MCA villagers.
- **0.2.0 implemented: Guard Villagers as MCA guards (bridge).** Identity store + predicate + Forge events, with the four addons patched to accept the bridge predicate. **0.2.x reworked this into a hidden MCA proxy** (a real, invisible `mca:*_villager` per bridged guard) so MCA's own screen, marriage, family tree, genetics and village residency are reachable after all. See §10A for scope and design.
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
- No conversion of Guard Villagers guards into MCA villagers, and no mixin on Guard Villagers' `Guard` class. **[REVERSED in 0.2.x]** a hidden, invisible real MCA villager *is* now paired with every bridged guard — see §10A for why the identity-store-only design could not reach MCA's own screen, marriage or genetics, and could not be made to without one.
- No MCA family, marriage or genetics *for the guard entity itself* — those live on its hidden proxy, which is a real `VillagerEntityMCA` (§10A).
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
  *while holding a sword or crossbow* also does not open MCA's own screen. **With
  `recruitMcaVillagersAsGuards` on (default), that gesture recruits the villager as a guard in
  place instead of doing nothing at all — see §10A "Recruit gesture".**

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

## 10A. Guard Villagers as MCA Guards — Hidden Proxy Bridge

**Objective:** Treat `guardvillagers:guard` entities as MCA guards for MCAQuests, MCACrime, MCAReputation, and MCAConversations, without converting their model, skin or AI, behind a SERVER config option `treatGuardsAsMcaGuards` (default true) — and, when `guardMcaInteraction` is also on, give the guard MCA's own interaction screen and every MCA option that goes with it.

**Approach — reworked from the original identity-store-only bridge.** All four addons identify a villager by reflectively checking for MCA's real `VillagerEntityMCA` class or by resolving a live entity by UUID, and MCA's own `InteractScreen` opens for an entity MCA itself owns; an identity store with no matching entity is invisible to all of that. MCA's `VillagerLike` is also a closed interface, so a Guard Villagers guard (a plain `PathfinderMob`) cannot be made MCA-like in place without a mixin, which this project bans. So the design pairs every bridged guard with a real, hidden `mca:*_villager` — the **proxy** — that carries all of the guard's MCA state and receives every MCA interaction, while the guard itself keeps its own model, combat AI and Guard Villagers inventory GUI. **§10A summarizes the design; implementation is `bridge/GuardProxy`, `bridge/GuardProxySync`, and the handlers below.**

### Scope

An entity qualifies as a **bridged guard** if and only if: the option `treatGuardsAsMcaGuards` is on, the module is not suppressed (see §16), the entity carries `MobRole.GUARD` (tag `mcamobcompat:guard`, shipped with `guardvillagers:guard`), **and** `McaEntities.isMcaVillager(entity)` returns false (`bridge/GuardBridge.isBridgedGuard`). A bridged guard additionally gets a proxy only while `guardMcaInteraction` is also on (`GuardVillagersModule.opensMcaInteraction`).

### The proxy: creation, hiding, linking

`bridge/GuardProxy.create(ServerLevel, Mob guard)` resolves the guard's stored identity, picks `mca:male_villager` or `mca:female_villager` by its rolled gender, spawns it through `EntityType#create` and MCA's own `finalizeSpawn` (so it gets a name, genetics, an age state and a family-tree node the way any newly summoned MCA villager does), sets its profession to `mca:guard` and its villager type from the guard's Guard Villagers appearance variant (`GuardVariants`, via `GuardReflection.variant`), then applies five flags — `setNoAi`, `setInvisible`, `setSilent`, `setNoGravity`, and vanilla `noPhysics = true` — that keep it out of the world. The pair is linked in both directions through persistent data, `mcamobcompat:proxy` on the guard and `mcamobcompat:guard` on the proxy, plus a scoreboard tag `mcamobcompat_proxy` on every proxy so an operator can find or remove them by selector (`/kill @e[tag=mcamobcompat_proxy]`). The stored name and personality are pushed onto the proxy exactly once (`GuardIdentityData.Entry.seeded`); after that MCA owns them.

Proxies are created only from `event/GuardProxyHandler`'s `LivingTickEvent` handler, never from `EntityJoinLevelEvent`, and only after a 40-tick grace period: a guard and its existing proxy are added to a loading chunk in list order, and creating one from the join event would race that order and produce a duplicate on every reload. A proxy whose guard cannot be found for 200 ticks discards itself. A guard that changes level takes its proxy with it via `teleportTo`, never by recreating it, because a new proxy is a new villager to MCA and would lose the family-tree node and every heart earned so far.

### Hiding it from the client

`setInvisible(true)` alone is not enough: MCA draws clothing, hair, face and skin as separate render layers that ignore invisibility, so an invisible MCA villager would be a suit of floating clothes with a face. `client/ProxyRenderHandler` — the mod's only `Dist.CLIENT` subscriber — cancels the whole `RenderLivingEvent.Pre` for anything `GuardProxy.looksLikeProxy` accepts: an MCA villager type with `isInvisible()`, `isNoAi()`, `isSilent()` and `isNoGravity()` all true, which is the only client-safe test since persistent data does not sync. The shadow is already skipped by vanilla for an invisible entity. The one exception is inside a screen: MCAConversations' dialogue frame and MCAQuests' quest menu draw the proxy through vanilla's `InventoryScreen.renderEntityInInventory` helpers, so `ProxyRenderHandler` tracks `ScreenEvent.Render.Pre`/`Post` and, while a screen is rendering, draws the guard found by `GuardProxy.nearbyGuardOf` (the bridged guard whose bounding box overlaps the proxy's) in the proxy's place instead, copying the proxy's rotation fields onto the guard for that one call so its portrait shows the guard rather than an empty box.

### Keeping the pair one NPC

`bridge/GuardProxySync.tick` runs every tick from `GuardProxyHandler`: it pins the proxy's position, rotation and head/body rotation to the guard's, re-asserts the five hiding flags, and mirrors `proxy.setTarget(guard.getTarget())` so MCACrime's self-defence check (which reads `getTarget()`) sees the same enemy the guard does. Once a second it re-asserts the `mca:guard` profession (in case MCA's own profession-change option moved it), reconciles names (a guard-side name that differs from the proxy's came from a player's name tag and wins; otherwise the proxy's MCA-rolled name is copied down), and translates MCA's movement intent into Guard Villagers' own state through `integration/guardvillagers/GuardReflection` — MOVE/STAY/FOLLOW mirrored onto `setFollowing`/`setPatrolling`/`setPatrolPos`/`setOwnerId`, called only by method name since Guard Villagers has no API and its class cannot be named. Any path MCA lays for the proxy (for example MCACrime's `moveVillagerTo`) is replayed onto the guard's own navigation and then cancelled on the proxy, because the proxy is brainless and cannot walk anywhere itself.

### Interaction routing

`event/GuardBridgeHandler.onEntityInteract` (main hand only, `HIGH` priority) decides the gesture: with `guardMcaInteraction` off it falls back to the pre-proxy behaviour of offering `GuardInteractEvent` and otherwise leaving the click alone; with it on, a crouching or shift-down click is replayed into Guard Villagers via `bridge/GuardInteractions.delegateToGuardVillagers` (which clears the shift flag and pose for the length of one call so Guard Villagers' own gesture check passes); a plain click resolves or creates the proxy, posts `GuardInteractEvent` for the addon suite, and — if nothing cancelled it — forwards the click by firing `ForgeHooks.onInteractEntityAt` and `onInteractEntity` on the proxy by hand (so an addon listening for those events on an MCA villager, which is how MCAQuests notices a conversation, still sees the click) and finally calling `proxy.interactAt` then `player.interactOn(proxy, ...)`. The client cancels the click blind, because it cannot open MCA's screen itself; the screen arrives from the server. `event/GuardProxyHandler`'s `EntityInteract` handler additionally re-routes a crouch click that happened to land on the proxy (they share a hitbox) back onto the guard's own crouch gesture, at `HIGHEST` so `ConversionGuardHandler` never mistakes the proxy for a crouch-clicked MCA villager.

### Damage and death relay

`event/GuardDamageRelayHandler` replays every hit the guard takes onto the proxy with the same damage source (`LivingHurtEvent` at `LOWEST`, so the relayed amount is post-mitigation), topping the proxy's health back up either side so it cannot die from an accumulation of relayed hits. On `LivingDeathEvent` the guard's death is relayed through `proxy.hurt(source, Float.MAX_VALUE)` rather than `kill()` — `kill()` hides the killer, and MCACrime/MCAReputation need it — and the link is dropped immediately afterwards so MCA's own death handling (family tree, grave, village announcement) finishes on the now-unlinked proxy rather than being interrupted by a discard. A `LivingAttackEvent` on the proxy is cancelled at `HIGHEST` unless the relay itself is in progress, so nothing — fall damage, fire, a stray mob — can kill the proxy independently of its guard.

`event/GuardProxyHandler` also redirects everything else that could otherwise land on the proxy instead of the guard, because the two share a hitbox and `isPickable` cannot be overridden without a mixin: a melee swing (`AttackEntityEvent`, server side only), a mob's target choice (`LivingChangeTargetEvent`), and a projectile (`ProjectileImpactEvent`, skipped onto the entity behind it) are all re-aimed at the guard.

### Recruit gesture

With `recruitMcaVillagersAsGuards` on (the default), crouch-and-right-click with a convertible weapon on an MCA villager no longer only blocks Guard Villagers' destructive conversion — it recruits the villager as a guard in place: `event/ConversionGuardHandler.recruit` sets its profession to `mca:guard` via `setVillagerData` (MCA's own profession-change entry point) and moves one item from the player's hand into the villager's main-hand slot, leaving name, family tree, genetics, personality and inventory untouched. With the option off, the plain block and its message run instead.

### Configuration — Server-side

`mcamobcompat-server.toml`, section `[guard_villagers]` (current bridge-related options; see `McaMobCompatConfig.Server`):
- **`treatGuardsAsMcaGuards`** = `true` (boolean). Whether a bridged guard gets an MCA-style identity at all.
- **`guardMcaInteraction`** = `true` (boolean, renamed from `guardInteractionScreen`). On: a plain right-click opens MCA's own screen through the hidden proxy; crouch-right-click opens Guard Villagers' own inventory GUI. Off: stock Guard Villagers gestures, no proxy is ever created.
- **`recruitMcaVillagersAsGuards`** = `true` (boolean). Whether the crouch-and-weapon gesture recruits an MCA villager as a guard in place instead of only blocking the conversion.
- **`guardShowNameTag`** = `true` (boolean). Whether a newly identified guard's rolled name shows permanently above its head.

### Identity Store — Runtime

**Package:** `dev.otectus.mcamobcompat.bridge`. **Class:** `GuardIdentityData extends SavedData`, schema version 2, one per world (stored in overworld `DataStorage`), keyed by guard UUID.

**Fields (version 2):** `name`, `gender`, `personality`, `hearts` (per-player map) and `created`/`deceased`/`deceasedAt` as in version 1, plus `proxyUuid` (nullable), `seeded` (whether the stored name/personality have been pushed onto a proxy), `heartsMigrated` (the set of players whose pre-proxy stored hearts have already been folded into MCA's own memories), and `pendingHearts` (a heart change banked for a player while the proxy was not loaded). Every version-2 field is absent-means-default, so a version-1 save file loads unchanged and migrates automatically the first time each guard gets a proxy.

**Creation & Updates:**
- Created lazily, on the guard's own `EntityJoinLevelEvent` and on first interaction, never deleted when the option is turned off.
- **Name/gender/personality** are still rolled from the guard's UUID (`bridge/GuardNames`) and persisted as the seed for its first proxy; once a proxy exists, `GuardIdentityView` reads the live name, personality and hearts from MCA's own villager brain (`mca/McaBinding`) and falls back to the stored roll only when the proxy is unloaded or MCA is unreachable. `GuardNames` is the fallback and the seed only — it is not consulted once MCA owns the values.
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
- **`GuardHeartsChangedEvent(Mob guard, UUID player, int old, int new)`** — fired by `GuardIdentity.modHearts`.

### MCA-Like Behaviour Implemented

| MCA behaviour on an MCA guard | For a bridged guard |
|---|---|
| Name, gender, personality | Rolled and stored for the seed; once a proxy exists, read live from MCA's own brain (`GuardIdentityView`, `mca/McaBinding.personality`). `GuardIdentityCreatedEvent` fires at creation. |
| Hearts per player | MCA's own memories, on the proxy, read and written through `McaBinding.hearts`/`modHearts`; a change while the proxy is unreachable is banked as `pendingHearts` and folded in later. `GuardHeartsChangedEvent` still fires. The old flat `-guardHeartsLostPerHit` penalty is gone — MCA applies its own hurt reaction to the proxy instead. |
| Guard defends villagers / no friendly fire | Unchanged from 0.1.0 (§10; applies to any `GUARD`-tagged mob). |
| Guard is defended when attacked | Guard Villagers native (`mobsGuardsProtectHurt` includes `guardvillagers:guard`). No work. |
| Interaction screen (quest offer, dialogue, reputation gain/loss) | **Reachable.** A plain right-click opens MCA's real `InteractScreen` for the hidden proxy, with every MCA option working except profession changes (re-asserted back to `mca:guard` within a second by `GuardProxySync.reassertProfession`) and the mount/pick-up ones (neutralised because the sync dismounts the proxy every tick), because the proxy is a genuine `VillagerEntityMCA`. Crouch-right-click still opens Guard Villagers' own inventory GUI on the guard. See "Interaction routing" above. MCAConversations' dialogue frame and MCAQuests' quest menu portray the guard itself, not the proxy: `client/ProxyRenderHandler` substitutes the guard for its proxy while either screen is rendering (see "Hiding it from the client"). |
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
- **`status`** — bridge state, whether `guardMcaInteraction` is on, identity-store size and deceased count, and a proxy census (loaded, orphaned, guards without one).
- **`inspect`** — looked-at or targeted guard's `GuardIdentity`, plus its proxy's UUID, loaded state, live MCA name, live profession, and the caller's own hearts with it.
- **`proxies`** — every loaded proxy across every level with its guard (or `ORPHAN`), and every bridged guard still waiting for one.
- **`rebuild`** — discards and recreates the proxy of every loaded bridged guard; destructive (a rebuilt proxy is a new villager to MCA), for a world where the link has already broken.
- **`purge <days>`** — remove deceased entries older than N days.

`/mcamobcompat classify` output gains a "bridged MCA guard" line when applicable.

### Evidence Markers

The **[RRV]** items opened for the earlier, identity-store-only interaction screen (MCA's own name-file format, which gesture opens Guard Villagers' inventory, handler priority and cancellation) are superseded: that screen no longer exists, and MCA's own screen is now what opens, resolved by MCA itself. The remaining open items:

1. **`VillageManager.findNearestVillage(Entity)` compatibility.** Confirm the four addons' bindings accept a `PathfinderMob` (or plain `Entity`) without casting to `Villager` or `LivingEntity` — relevant only where an addon still resolves a guard rather than its proxy.
2. **MCA villager brain overrides.** Confirm MCA villagers' `makeBrain` overrides prevent Guard Villagers' `VillagerGoalPackagesMixin` from injecting its brain tasks (including `ShareGossipWithGuard`), so MCA villagers ignore that task.

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
  (0.2.0): `treatGuardsAsMcaGuards` (boolean, default true).
  (0.3.0): `[chores]` section with `protectOwnedAnimals` (boolean, default true), `protectedRoles` (list of `MobRole` names, default `["mount", "pet"]`), `protectNamedAnimals` (boolean, default true), `protectLeashedAnimals` (boolean, default true), `abandonHuntOnProtectedTarget` (boolean, default true).
  (hidden MCA proxy, 0.2.x): three more `[guard_villagers]` options, replacing the earlier interaction-screen ones — `guardMcaInteraction` (boolean, default true, renamed from `guardInteractionScreen`; a plain click opens MCA's own screen through a hidden proxy villager, crouch opens Guard Villagers' inventory), `recruitMcaVillagersAsGuards` (boolean, default true, crouch-and-weapon recruits an MCA villager as a guard in place instead of only blocking the conversion), and `guardShowNameTag` (boolean, default true, a permanent nameplate on newly identified guards). `guardInteractionCooldownSeconds`, `guardSocialHearts`, `guardGiftHearts`, `guardGiftHeartsLoved` and `guardHeartsLostPerHit` are gone: MCA's own memories on the proxy replace all five, and `data/mcamobcompat/tags/items/guard_gift_*` no longer exist. `ForgeConfigSpec` has no migration path, so an existing config file has the renamed and removed keys rewritten to defaults on first load with the new spec.
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

### 0.2.x Feature Scope (Hidden Proxy Rework)
- Every bridged guard with `guardMcaInteraction` on is paired with a real, hidden `mca:*_villager` (`bridge/GuardProxy`), synced onto the guard every tick (`bridge/GuardProxySync`) and hidden from rendering client-side (`client/ProxyRenderHandler`).
- MCA's own interaction screen, hearts/memories, movement intent, marriage, family tree, genetics and village residency are all reachable through the proxy — see §10A "MCA-Like Behaviour Implemented".
- Guard Villagers' follow/patrol state is driven only by reflection on the guard's runtime class, by member name (`integration/guardvillagers/GuardReflection`), never by naming its class.
- Damage and death on the guard are relayed onto the proxy (`event/GuardDamageRelayHandler`) so MCA, MCACrime and MCAReputation react to it as a villager.
- Crouch-and-weapon on an MCA villager can now recruit it as a guard in place (`recruitMcaVillagersAsGuards`) instead of only being blocked.
- `GuardIdentityData` bumped to schema version 2 (proxy link, seeded flag, hearts migration bookkeeping), migrating a version-1 save automatically.
- Configuration renamed/removed: `guardInteractionScreen` → `guardMcaInteraction`; `guardInteractionCooldownSeconds`, `guardSocialHearts`, `guardGiftHearts`, `guardGiftHeartsLoved`, `guardHeartsLostPerHit` removed; `recruitMcaVillagersAsGuards` added. The item-gift tags and the earlier emulated screen (`client/GuardInteractionScreen`, the `network` package, `bridge/GuardAction`/`GuardResponses`/`McaNameSource`) are deleted.
- Diagnostics gain `/mcamobcompat guards proxies` and `/mcamobcompat guards rebuild`.

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

### 0.2.x Tasks (Hidden Proxy Rework)
1. **Store schema v2 and migration.** `GuardIdentityData` bumped to version 2 with `proxyUuid`/`seeded`/`heartsMigrated`/`pendingHearts`; a version-1 file loads unchanged. *Test:* `bridge/GuardIdentityDataMigrationTest`.
2. **`bridge/GuardProxy` and `bridge/GuardProxySync`.** Create, hide, link and tick the proxy; mirror MCA's movement intent onto the guard. *Test:* manual `runClient` checklist below.
3. **`integration/guardvillagers/GuardReflection` and `GuardVariants`.** By-name method handles on the guard's runtime class; variant-to-villager-type mapping. *Test:* `integration/guardvillagers/GuardVariantsTest`.
4. **`event/GuardProxyHandler`.** Proxy lifecycle (creation, orphan discard, level-follow), and redirecting interact/attack/target/projectile from the proxy to the guard.
5. **`event/GuardBridgeHandler` rewrite.** Routes the plain click to the proxy through `ForgeHooks` and `interactAt`/`interactOn`; the crouch click to Guard Villagers.
6. **`event/GuardDamageRelayHandler`.** Relays hurt and death onto the proxy; blocks independent damage to it.
7. **Recruit gesture.** `recruitMcaVillagersAsGuards` in `ConversionGuardHandler`.
8. **Config rename/removal.** `guardInteractionScreen` → `guardMcaInteraction`; five options removed. *Note:* `ForgeConfigSpec` has no migration, so an existing config is rewritten on first load.
9. **Deletion of the emulated screen.** `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, the `network` package, `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource`, and the item-gift tags.
10. **Diagnostics.** `/mcamobcompat guards proxies` and `/mcamobcompat guards rebuild`.

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

**Per-config tests (0.2.x, hidden proxy):** the manual `runClient` checklist in the guard-bridge implementation plan — plain click opens MCA's own screen; chat/gift change MCA hearts and `guards inspect` agrees; Follow/Stay/Move actually move or pin the guard; sneak click still opens Guard Villagers' inventory; hitting or killing the guard drives MCA/MCACrime/MCAReputation reactions on the proxy with no orphaned proxy left behind; no floating clothes/hair/face/shadow when circling the guard; portal travel keeps the same proxy; save/reload creates no duplicate; `recruitMcaVillagersAsGuards` recruits in place; `guardMcaInteraction=false` creates no proxy at all.

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
- **Unreachable features documented (superseded in 0.2.x, see below):** interaction screen, village residency, marriage/children/family tree, genetics, armory (§10A MCA-like behaviour table, §3 Non-Goals).
- **Per-addon adoption:** tracked in each repo; `isMcaVillager` extended; identity reads working (§22.0.2 task 9).
- **Every [RRV] from §10A resolved before release** (§22.0.2 tasks, §10A Evidence Markers).

**Release criteria met:** testing matrix passed (§23); [RRV] items verified; per-addon adoption merged.

### 0.2.x (Hidden Proxy Rework)
- **Every previously "unreachable" feature is now reachable**, on the hidden proxy: MCA's own interaction screen, hearts/memories, movement intent, marriage, family tree, genetics and village residency (§10A MCA-like behaviour table).
- **Proxy lifecycle:** created from the guard's own tick with a grace period, never on join; linked by persistent data on both sides; discarded only when its guard is really gone (`bridge/GuardProxy`, `event/GuardProxyHandler`).
- **Interaction routing:** a plain click forwards to the proxy through `ForgeHooks` and `interactAt`/`interactOn`; a crouch click still reaches Guard Villagers; the proxy's own click is redirected back to the guard for the crouch gesture and for a melee swing.
- **Damage/death relay:** hits and death on the guard replay onto the proxy with the original damage source; nothing else can damage the proxy independently (`event/GuardDamageRelayHandler`).
- **Store migrated to schema 2** with no data loss from version 1.
- **Emulated screen removed:** the `network` package, `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource` and the item-gift tags no longer exist.

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