# MCA: Mob Compatibility — Minecraft 1.21.1 NeoForge port

Repository: https://github.com/otectus/MCAMobCompatibility

Prepared: 2026-09-14. Source baseline: `main` at `7062152e3f1a370a9173be89a0bc11f7ed5b2f08`.

This is an implementation handoff based on repository inspection and upstream research. No port was implemented or tested in Minecraft while preparing it. Treat code observations below as source findings, and runtime expectations as acceptance criteria to prove.

## 1. Assignment and boundaries

Act as a senior Minecraft mod engineer. Fully port the current MCA: Mob Compatibility implementation to **Minecraft 1.21.1, NeoForge 21.1.x, and Java 21**. Implement the port, run meaningful checks, exercise the finished production JAR, and document results and remaining external limitations.

Port the complete checked-out implementation, including changes described as Unreleased. Do not reduce the task to updating Gradle, translating imports, or making compilation pass. Preserve classification, chore protection, conversion protection, recruitment, guard identities, the hidden MCA proxy, interactions, movement mirroring, portraits, damage/death integration, commands, configuration, and the extension API.

Use a dedicated `port/neoforge-1.21.1` branch or isolated worktree. Preserve the Forge line and unrelated local work. Do not reset the working tree, replace the repository with an MDK, introduce a multiloader rewrite, publish a release, or merge into `main` as part of this assignment unless separately requested.

Retain these architectural rules from `CLAUDE.md`:

- Mod ID `mcamobcompat`; Java package `dev.otectus.mcamobcompat`; GPL-3.0-only licensing.
- No blocks, items, or entity types registered by this addon.
- No static references to MCA, Guard Villagers, SWEM, Genetic Animals, DragN's Livestock Overhaul, or Architectury classes.
- MCA class-name reflection belongs only in `mca/McaBinding`; the exact-class allowlist remains narrow.
- Guard Villagers reflection operates on runtime objects through verified member names and vanilla/JDK parameter types.
- Preserve the no-mixin/no-access-transformer design. If a required behavior genuinely cannot be implemented through supported target APIs, document the exact obstruction and smallest proposed exception; complete independent work and do not silently omit the behavior.
- Animal genetics, breeding, pregnancy, ownership, training, feeding, and mod configuration remain owned by their source mods. Classification does not grant support for those operations.
- Gameplay decisions and persistent writes are server authoritative; the client handles presentation and verified synchronization.

## 2. Re-establish the baseline before editing

Read applicable `AGENTS.md`, `CLAUDE.md`, and `MODMAP.md`, then `gradle.properties`, `build.gradle`, `settings.gradle`, metadata, `README.md`, `CHANGELOG.md`, and relevant sections of `MCA_MOB_COMPATIBILITY.md` and `DATAPACK.md`. Use the source to resolve contradictory documentation.

Record branch, commit, dirty files, Java/Gradle versions, dependency artifacts, and the actual test infrastructure. The inspected baseline is:

| Property | Observed value |
|---|---|
| Declared mod version | `0.2.0` |
| Minecraft / Forge | `1.20.1` / `47.4.10` |
| Java / Gradle | `17` / `8.8` |
| Build plugin | ForgeGradle `[6.0,6.2)` |
| Development MCA dependency | `7.6.20+1.20.1`, runtime only |
| Development Architectury dependency | Forge `9.2.14`, runtime only |
| Production Java files | 45 |
| Existing JUnit coverage | 10 test classes, 45 `@Test` methods |
| Entity tag files | 11 |
| Guard identity data | Overworld `SavedData`, schema version 2 |
| Remote branches observed | `main` only |

The declared version is not a reliable feature boundary. Current source already contains the proxy rework and chore safety. Some documentation still says the mod never changes MCA state or never recruits villagers, and dependency comments say it does not bind MCA reflectively. Those statements no longer describe the source. Do not remove implemented features to match them.

## 3. Resolve the target dependency matrix

Inspect actual **1.21.1 NeoForge artifacts**, not similarly named Forge or Fabric downloads. Record file/version IDs, SHA-256 hashes, mod IDs, declared Minecraft/loader ranges, transitive dependencies, and corresponding source commits where available.

The following are research starting points, not a claim that their binaries have passed this port's tests:

| Integration | Availability observed on 2026-09-14 | Required action |
|---|---|---|
| MCA Reborn | Published 1.21.1 NeoForge releases; the listing showed `7.7.36` | Select and pin a real NeoForge artifact; inspect its metadata and reflective surface. |
| Guard Villagers | Published `guardvillagers-2.4.12-1.21.1.jar` | Use the NeoForge build; verify conversion, inventory, follow/patrol, and appearance APIs. |
| SWEM | Published `swem-1.21.1-1.6.12.jar` for NeoForge | Resolve its own dependencies and verify entity IDs, inheritance, and ownership access. |
| Genetic Animals, `eanimod` | Official file listing showed Forge releases through 1.20.1; no target build found | Preserve optional metadata, module controls, and optional tag entries. Mark runtime validation unavailable unless a genuine target build becomes available. |
| DragN's Livestock Overhaul, `dragnlivestock` | Official listings showed Forge 1.20.1/1.18.2; no target build found | Preserve optional recognition data and module controls without advertising tested NeoForge gameplay support. |
| MCAQuests, MCAConversations, MCACrime, MCAReputation | Companion integrations must be checked individually | Obtain their actual target builds and test the proxy interactions described below. |

Do not port third-party mods as an implicit expansion of this task. Do not load their 1.20.1 JARs into a 1.21.1 runtime, fabricate dependency coordinates, or mark an unavailable integration as tested.

Inspect whether the chosen MCA build needs Architectury. Current upstream 1.21.1 source uses a different build layout from the baseline; the addon itself does not need Architectury. Include it only if the selected MCA artifact actually requires it, and only in development runtime configuration.

Keep third-party mods out of the compile classpath. Prefer explicit development-only `localRuntime` dependencies or equivalent isolated run configurations. Use a Modrinth version ID or otherwise unambiguous artifact selector when the same human-readable version exists for multiple loaders. A fresh clone must build without proprietary JARs in `libs/`.

## 4. Migrate the build and artifact checks

Use the official **1.21.1 ModDevGradle MDK** as a reference for the existing project. Pin a compatible NeoForge patch version, ModDevGradle version, and wrapper. The MDK inspected during research used NeoForge `21.1.250` and ModDevGradle `2.0.147`; recheck those artifacts and their wrapper requirements before adopting them.

Make these changes:

1. Replace `net.minecraftforge.gradle` and the ForgeGradle `minecraft { ... }` block with `net.neoforged.moddev` and `neoForge { ... }`.
2. Set the Java toolchain and intended bytecode target to Java 21. Keep paths portable; do not add an absolute `org.gradle.java.home`.
3. Replace Forge properties with target properties. Use `minecraft_version=1.21.1` and an exact Minecraft range such as `[1.21.1]`. Define a separate tested NeoForge dependency range; do not use the FML loader range as the NeoForge version range.
4. Update plugin repositories and remove obsolete `fg.repository` routing and `fg.deobf(...)` calls.
5. Configure client, dedicated server, and real GameTest runs. Use the selected MDK's run DSL and `neoforge.enabledGameTestNamespaces`. Do not invent GameTests merely by leaving an empty run configuration in place.
6. Preserve resource expansion, JUnit execution, generated-resource support where useful, and the local install task.
7. Select development mods explicitly by platform/version. Do not change the old hardcoded filenames into an unrestricted `libs/*.jar` runtime dependency.
8. Give the output a platform-qualified name, for example `mcamobcompat-neoforge-<version>+1.21.1.jar`, to distinguish it from the Forge artifact.

**Replace the Forge-specific reobfuscation gate.** The current `verifyReobfJar` requires SRG member names such as `m_12345_`. That is not a valid success condition for the target NeoForge artifact. Remove the `reobfJar` finalizer/dependencies and replace the gate with `verifyProductionJar` or an equivalently named target-appropriate check. Do not manufacture SRG references to satisfy the old test.

The replacement gate must verify:

- Correct NeoForge metadata and expanded version properties.
- All expected resources and target tag paths in the actual distributable JAR.
- No legacy loader references in production bytecode.
- No bundled MCA/optional-mod/Architectury classes or third-party nested JARs.
- No forbidden static links and no widening of the reflection allowlist.
- No test fixtures or unrelated development files packaged as production content.
- Java 21 compatibility and successful loading of this exact JAR in a production instance.

Keep `checkJarContents` and `NoOptionalModStaticLinkTest` effective. Update their package-prefix coverage from inspected artifacts, including relocated MCA layouts when needed. Correct existing gaps or spelling mistakes in package roots; never weaken a scan to make it pass.

Wire `build` and `installMod` to **all** applicable artifact gates. The current install task depends on the reobfuscation check but does not explicitly require every content check. The ported task must copy the verified artifact, warn about duplicate versions, and leave unrelated mods untouched.

## 5. Port metadata, lifecycle, configuration, and events

Replace `META-INF/mods.toml` with `META-INF/neoforge.mods.toml` using the selected MDK's schema. Change dependency `mandatory=true/false` to `type="required"/"optional"`. Declare Minecraft, NeoForge, and MCA as required; retain optional integrations and appropriate ordering. Use tested compatibility ranges rather than assuming all future reflective APIs remain compatible.

Register both configuration specs through the injected `ModContainer`, and obtain the mod event bus by constructor injection. Preserve deferred setup through `FMLCommonSetupEvent.enqueueWork` where it accesses other mods.

| Existing API | Target direction |
|---|---|
| `ForgeConfigSpec` | `net.neoforged.neoforge.common.ModConfigSpec` |
| `net.minecraftforge.eventbus.api.*` | `net.neoforged.bus.api.*` |
| `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| `@Mod.EventBusSubscriber` | `net.neoforged.fml.common.EventBusSubscriber` |
| `@Cancelable` on custom events | Implement `ICancellableEvent` |
| `if (EVENT_BUS.post(event))` | Post, then inspect `event.isCanceled()`; NeoForge returns the event rather than a boolean cancellation result. |
| `ForgeHooks.onInteractEntityAt/onInteractEntity` | Verified `CommonHooks` counterparts, with routing corrections in section 9. |
| `ForgeRegistries.ENTITY_TYPES` | `BuiltInRegistries.ENTITY_TYPE` |
| `ForgeRegistries.VILLAGER_PROFESSIONS` | `BuiltInRegistries.VILLAGER_PROFESSION` |
| `LivingEvent.LivingTickEvent` | A concrete `EntityTickEvent.Pre` or `.Post`, selected deliberately. |

Do not subscribe to abstract parent events. Preserve priorities where conversion protection and proxy routing depend on them. Do not register the same static subscriber both automatically and manually.

Preserve the COMMON/SERVER division and existing keys/defaults:

- COMMON: `general.debugLogging`; module toggles for Guard Villagers, SWEM, Genetic Animals, and DLO.
- SERVER guard settings: conversion blocking, MCA-villager protection, friendly-fire prevention, player retaliation, help range, alert cap, identity bridge, MCA interaction, in-place recruitment, and nameplate visibility.
- SERVER chore settings: owned/named/leashed animal protection, protected roles, and hunt abandonment.
- SERVER animal-module recognition settings.

Retain defaults including retaliation off, guard help range 32, alert cap 8, and protected roles `mount`/`pet`. Livestock must remain huntable by default unless another protection rule applies.

Invalidate classification and parsed configuration caches on relevant load, reload, and unload transitions. Account for server config synchronization and joining a second world/server in the same process. COMMON config is not automatically a substitute for server-authoritative client decisions: reconcile any module-setting differences that affect interaction routing or proxy presentation.

## 6. Port vanilla APIs and tag resources

Replace removed `ResourceLocation` constructors throughout production and tests:

```java
ResourceLocation.fromNamespaceAndPath(namespace, path);
ResourceLocation.parse(knownValidId);
ResourceLocation.tryParse(untrustedInput);
```

Use registry lookups that distinguish an absent optional ID from a defaulted registry value. Never accidentally create a fallback entity because an MCA entity or profession was missing.

Verify the target spawn and teleport signatures. MCA's inspected 1.21.1 source uses a four-argument `finalizeSpawn(level, difficulty, spawnType, groupData)`; the addon currently passes five arguments. Use the correct NeoForge spawn-finalization path, preserving MCA initialization and respecting canceled/rejected spawns.

For cross-dimension movement, use the actual 1.21.1 APIs, including `DimensionTransition` where appropriate. Check operation results and reacquire any replacement entity instance by UUID. Do not import later 1.21.x teleport or render-state APIs by mistake.

Move **all 11** files from:

```text
data/mcamobcompat/tags/entity_types/
```

to:

```text
data/mcamobcompat/tags/entity_type/
```

Update resource tests, diagnostics, docs, and examples. Ten files are public semantic roles; `mca_villagers.json` is the additional recognition tag. Preserve all tag IDs and membership relationships, including horse-to-mount and species-to-livestock composition. Horses must not become livestock implicitly.

Preserve optional foreign entries using `"required": false` and shipped merge behavior using `"replace": false`. Verify real registry IDs in-game with `dump` and `classify`.

**Do not mechanically replace every `forge:` tag with `c:` or `neoforge:`.** The inspected NeoForge `Tags.EntityTypes` does not provide matching cow/pig/sheep/horse categories. Add conventional tag aliases only after verifying that target mods actually populate them. Legacy `forge:` tag references may remain optional compatibility aliases; they are data identifiers, not forbidden Forge Java links. Keep explicit verified entity entries as the reliable fallback.

Fix the current `DATAPACK.md` namespace mistake: a file under `data/<your_pack>/tags/.../cattle.json` creates `<your_pack>:cattle`, which this classifier does not automatically read. The direct extension example must be:

```text
data/mcamobcompat/tags/entity_type/cattle.json
```

```json
{
  "replace": false,
  "values": [
    { "id": "exampleanimals:highland_cow", "required": false }
  ]
}
```

A separately named pack tag works only when an `mcamobcompat` tag explicitly references it. Test the documented example using a real registered entity.

Update `pack.mcmeta` consistently with the target MDK/loader. Minecraft 1.21 uses data-pack format 48 and resource-pack format 34; do not assign one format blindly to both external pack types. If relying on loader-generated mod-pack metadata, verify the produced behavior and document it. Remove the old hardcoded format 15.

## 7. Preserve classification semantics and the public API

Port `api/*`, `classification/*`, `integration/*`, `McaEntities`, and `ConfigAccess` without broad redesign.

Preserve:

- Null-safe queries and immutable returned role sets.
- Cached entity-type classification versus uncached per-entity provider contributions.
- Ownership reads through supported vanilla `AbstractHorse`/`OwnableEntity` APIs, without rewriting ownership.
- Dynamic recognition of tame/owned mounts without inventing autonomous riding or steering.
- Module suppression and reload invalidation.
- Guard tagging remaining distinct from eligibility for MCA bridging; ordinary MCA villagers must not receive another proxy.
- Provider registration and containment of faulty providers, with bounded diagnostics.

Fix the suppression inconsistency while porting: `TagClassifier.computeTypeRoles` suppresses disabled namespaces, but the per-entity provider pass can add roles back. `TamedMountProvider` currently does exactly that for a tamed, owned horse. Apply the intended suppression contract to both paths and test disabled SWEM recognition against a real tame horse.

Keep existing public method names and event payload semantics where possible. Document that `getGuardIdentity` can create a persisted identity and fire its creation event; it is not a purely read-only getter. Audit consumers of `GuardInteractEvent`, `GuardIdentityCreatedEvent`, and `GuardHeartsChangedEvent` for the NeoForge event bus and cancellation changes. Do not claim binary compatibility with Forge addons.

## 8. Rebind MCA and Guard Villagers against actual target builds

### MCA binding

Inspect the selected production MCA JAR, its package layout, method signatures, and return types. Do not infer a packaged class name from the source tree alone. Current upstream 1.21.1 source was inspected at `82552b8e577b7632ba36b71de8698f7e972f6298`; it is a reference, not proof that a published release contains the same code.

Revalidate these members and all code that interprets their results:

| Object | Required surface |
|---|---|
| MCA villager | `getVillagerBrain`, initialization, interaction and death behavior |
| Villager brain | `getCurrentJob`, `abandonJob`, `getPersonality`, `setPersonality`, `getMemories`, `getMemoriesForPlayer`, `getMoveState` |
| Player memories | `getHearts`, `modHearts` |

The inspected upstream `Personality` is an extensible class with `getId()` and `get(...)`, not an enum. The current addon's `personality()` accepts only `Enum`, and `setPersonality()` rejects a non-enum parameter. This can fail silently even when method resolution reports success.

Support the actual chosen API shape. If the supported release range spans both layouts, handle both explicitly: enum constants for the old shape, verified stable identifiers and lookup methods for the extensible shape. Preserve namespaced custom identities and old saved personality strings. Unknown historical values must not cause live MCA state to be rerolled or overwritten. Do not invent replacement personalities for removed values.

Use precise signatures where vanilla/JDK types are available. Distinguish overloads and validate return types instead of relying on first-match name/arity scans. Keep initialization bounded and method failures isolated; avoid permanently disabling healthy bindings because one malformed entity caused an invocation error.

Extend `/mcamobcompat mca` to show the loaded MCA version, resolved class/root, supported API shape, required and optional binding status, and meaningful failures. A successful class lookup is not proof of functional interaction, movement, or personality support.

### Guard Villagers binding

The inspected upstream 1.21.1 branch at `e329cd33a53e409fc5a09ead5dd107da6cd5bc61` still uses the item tag `guardvillagers:convertible_guard_items`, and its conversion handler runs at default event priority. It exposes:

```text
setFollowing(boolean)
setPatrolling(boolean)
setPatrolPos(BlockPos)
setOwnerId(UUID)
getVariant() -> String
```

The current addon probes `getGuardVariant()`. Update appearance binding for the verified target version, retaining the old spelling only for a deliberately supported layout. Avoid serializing the entire guard as the normal appearance getter. An NBT fallback must be bounded and diagnostic, not a frequent replacement for a working accessor.

Verify inventory gestures and the selected JAR's permission/reputation checks. Missing movement methods should disable only those commands, without repeated reflection failures or crashes. A third-party mob tagged as a guard must not accidentally receive a method call merely because it has an unrelated overload with the same name.

## 9. Port conversion protection and interaction routing

Review `ConversionGuardHandler`, `GuardBridgeHandler`, `GuardInteractions`, and the interaction portions of `GuardProxyHandler` together.

Preserve this behavior:

| Target and gesture | Expected result |
|---|---|
| Ordinary MCA villager, crouch + convertible main-hand item, blocking enabled | Prevent destructive Guard Villagers conversion. |
| Same, in-place recruitment enabled | Safely change the existing MCA villager to MCA guard profession and transfer one item under the intended gamemode rules. |
| Bridged Guard Villagers guard, plain main-hand click | Offer the custom interaction event and route to its MCA proxy when enabled. |
| Bridged guard, crouch click | Open Guard Villagers' own interaction/inventory path. |
| Proxy hitbox, crouch click | Route to the corresponding visible guard; do not recruit the proxy. |
| Proxy hitbox, plain click | Reach the same MCA/addon interaction path exactly once. |
| MCA interaction disabled | Restore the stock guard interaction contract without deleting relationship state. |

Read NeoForge's actual client packet and server interaction paths before canceling client events. A cancellation that prevents the packet from being sent cannot be repaired by a server handler. Verify physical crouching versus shift intent, both hands, main-hand conversion tags, creative/survival behavior, and canceled addon events.

**Remove duplicate hook delivery.** The current `forwardToProxy` manually fires both interaction hooks and can then call `player.interactOn`, which fires the generic hook again. NeoForge's `Player` patch confirms that `interactOn` invokes `CommonHooks.onInteractEntity`. Implement one coherent dispatch route so the specific hook, generic hook, custom event, and underlying interaction each occur only as required. Test the `PASS` fallback and consuming/canceled results with event counters.

Record the interacting player even when the client selected the proxy directly, so Follow cannot target a stale player. Respect reach, dimension, entity lifetime, and ownership/permission requirements; never turn a redirected interaction into a remote-access path.

When temporarily adjusting player pose/shift state for Guard Villagers, restore everything in `finally`. Avoid recurring delegation and ensure a single click cannot consume an item or open a menu twice.

Recruitment must preserve the same MCA UUID, family, genetics, relationships, inventory contents, and item components. The existing method blindly replaces main-hand equipment with `split(1)`: define and implement safe handling of existing equipment, repeated recruitment, babies, failed profession lookup, and creative item consumption. Do not introduce an item-loss or duplication path during the port.

## 10. Make proxy creation, linking, and lifetime reliable

Treat `GuardProxy`, `GuardProxySync`, `GuardProxyHandler`, the identity store, and client presentation as one lifecycle.

Required invariants:

1. One live guard has at most one active proxy, and each proxy belongs to one guard.
2. Persisted guard/proxy UUIDs remain stable across save/reload, travel, and reconnect.
3. A chunk being unloaded is not proof an entity died or ceased to exist.
4. A failed spawn never commits links or marks seeding successful.
5. Proxy entities remain hidden and inert while MCA retains the identity they represent.

Unify all creation paths in one idempotent resolve/adopt/create operation. Source inspection found an important mismatch: the tick path waits 40 ticks to avoid duplicates, but `GuardBridgeHandler` creates a missing proxy immediately on interaction. Clicking during chunk loading can bypass the grace policy. Both paths must use the same link lookup, adoption rules, and load-state checks. A short pending/retry interaction is preferable to creating a second identity.

Check `addFreshEntity` and spawn-finalization outcomes. Commit guard persistent data, proxy persistent data, store links, and one-time seeding only after success. Define rollback for failures after MCA initialization, including any MCA state created during initialization.

Keep server identity keys stable:

```text
mcamobcompat_guard_identities
mcamobcompat:proxy
mcamobcompat:guard
mcamobcompat_proxy
```

Repair links from the world store before creating replacements. Validate both sides, entity type, dimension, and UUID. Reject ambiguous pairings rather than selecting an arbitrary nearby villager. Preserve/recover loaded pairs after teleport APIs return a different Java object.

Do not discard a proxy solely because its guard has been unresolved for 200 ticks. The existing orphan timeout can mistake an unloaded guard for a deleted one. Use durable lifecycle evidence, bounded recovery, and operator diagnostics. Never force-load every chunk or dimension to answer this question.

On config disable/re-enable, keep identity-bearing proxy state recoverable. Stop interaction/movement behavior as configured without letting an old proxy remain at an unrelated location or lose its owner. Separate lifecycle cleanup of an existing pair from new-bridge eligibility, so toggles cannot prevent necessary handling of a genuinely removed guard.

Use a single appropriate tick phase. Maintain position/hiding updates cheaply and expensive synchronization at bounded intervals. Do not cancel the proxy's entire entity tick without proving that MCA synchronization, relationships, and death cleanup still work. Clear transient interaction/movement maps at server shutdown/world changes.

Fix bidirectional rename handling: the current sync treats every name disagreement as a guard-side name-tag edit, so a valid MCA-side rename can be overwritten. Track the last synchronized value or another explicit authority rule, and test edits from each side.

Preserve Follow/Stay/Move and addon navigation relay without repeatedly resetting guard combat or patrol goals. Resolve the actual authorized follow target, account for player logout/restart, and ensure escort destinations do not fight the slower movement-state mirroring. Mount/pick-up actions must not leave the proxy detached from its visible guard.

## 11. Port SavedData and preserve relationship state

Use the 1.21.1 `SavedData.Factory` and registry-aware save/load signatures. Keep the store attached to `server.overworld().getDataStorage()` under its existing key. A representative target shape is:

```java
public static GuardIdentityData load(
        CompoundTag tag, HolderLookup.Provider registries) {
    // Parse the existing schema and explicitly supported migrations.
}

@Override
public CompoundTag save(
        CompoundTag tag, HolderLookup.Provider registries) {
    // Write the current schema.
}
```

Adapt pure NBT helpers separately so existing serialization tests remain useful. Preserve valid schema-1 and schema-2 records, UUIDs, timestamps, deceased state, seeding flags, proxy links, personality strings, and per-player migration/pending state. Mark data dirty when persisted values change. Handle malformed individual records predictably and protect unknown future schemas from silent destructive rewriting.

**Account for the Forge-to-NeoForge entity-data envelope.** The inspected NeoForge Entity patch stores persistent data under `NeoForgeData`. Do not assume old `ForgeData` links automatically appear through `getPersistentData()`. Use copied fixtures from a real Forge save. Prefer recovering this addon's guard/proxy links from its existing SavedData and matching entity UUIDs. If a targeted legacy migration is needed, migrate only this addon's known keys; do not rewrite unrelated mods' data.

Saving this addon's schema successfully does not prove a whole modpack world can move between Minecraft versions and loaders. Test the addon-owned data migration explicitly, and state the limits imposed by missing target mods or upstream entity migrations.

**Fix exact-once hearts accounting.** In current `GuardIdentityView.modHearts`, a failed/offline update increments both stored hearts and pending hearts, while the fallback getter sums both. With a zero balance, an offline `+5` can read as `10`. Pending migration is also tied to another `modHearts` call rather than guaranteed when player and proxy become available.

Define a consistent model for the last authoritative MCA balance, unmigrated legacy balance, and queued deltas. Required behavior:

- Offline `+5` changes the observable total by exactly five.
- Repeated updates, negative changes, zero delta, restart, and reconnect do not duplicate or lose deltas.
- Pending deltas flush when their prerequisites become available, without requiring another earned change.
- Migration markers change only after successful application.
- MCA-originating gifts/penalties and name/personality changes are reflected in the fallback snapshot before it is needed offline.
- `GuardHeartsChangedEvent` reports the actual before/after values once per applied public change, with nullable guard handling retained.
- Arithmetic is bounded deliberately; do not let integer overflow turn accumulated hearts into an unrelated result.

If fixing the persisted representation requires schema 3, implement explicit schema-1/schema-2 migrations and fixtures. Do not guess how ambiguous historic values were produced or clear them to simplify the migration; preserve source data and document any irreducible ambiguity.

## 12. Port damage, death, defense, and projectiles by semantics

Do not perform a one-for-one event-name replacement without reviewing the damage pipeline.

| Purpose | Target direction |
|---|---|
| Block protected-animal damage from MCA | `LivingIncomingDamageEvent` cancellation, retaining attacker/source checks. |
| Prevent unsolicited damage to a proxy | `LivingIncomingDamageEvent` with scoped relay recognition. |
| Observe final health damage to a guard | `LivingDamageEvent.Post`, reading `getNewDamage()`, after checking the intended zero-damage policy. |
| Alert guards after real damage to MCA villagers | Use the chosen target damage stage consistently and avoid duplicate reactions to synthetic relays. |
| Record/relay death | `LivingDeathEvent` plus verified final outcome and idempotent lifecycle handling. |
| Redirect targets | `LivingChangeTargetEvent`, preserving cancellation and avoiding recursion. |

The baseline comment that `LivingHurtEvent` at LOWEST reports damage after every mitigation is not a sound porting assumption. NeoForge's Post event exposes the finalized sequence, whereas incoming damage is the interception point. A Post event is not cancellable.

For chore safety, preserve early cancellation as the primary protection. Do not rely only on `Mob.setTarget` events: MCA chores may manage brain memory/targets without that call. Keep ownership, name, leash, protected-role, retaliation/self-defense, and configurable hunt-abandonment semantics. A missing chore binding must not disable the basic damage veto.

For defense, preserve range/alert caps, avoid replacing an existing guard combat target, and keep player retaliation off by default. Distinguish preventing target selection from preventing actual accidental projectile/melee damage; test the configured guarantee and document its scope accurately.

For damage/death relay:

- Keep the visible guard authoritative for combat health and equipment.
- Preserve killer, direct projectile, damage type, and relevant source context.
- Use relay state scoped to the active guard/proxy pair or nested call context, restored in `finally`. A global boolean is insufficient protection against unrelated nested damage callbacks.
- Prevent proxy recursion, duplicate assault/reputation events, duplicated drops/rewards, and fake independent victims.
- Do not restore health after a proxy has actually entered a death/removal path.
- Handle shields, armor, absorption, invulnerability frames, canceled attacks, totems, and canceled deaths explicitly.
- Verify ordering for a lethal hit: a nonlethal proxy notification followed immediately by a lethal relay must not be blocked by proxy invulnerability frames or revived by the nonlethal cleanup.
- Do not equate `hurt(..., Float.MAX_VALUE)` with guaranteed successful death. Observe the actual outcome and keep links consistent until MCA's legitimate death handling is complete.
- Do not unlink/mark the pair dead merely because a cancellable death notification was observed and later canceled.

**Projectile behavior needs a separate fix.** Target `ProjectileImpactEvent` is cancellable and does not expose Forge's `ImpactResult.SKIP_ENTITY`. The inspected NeoForge arrow patch breaks out of its collision loop when impact is canceled. Therefore `event.setCanceled(true)` alone does not prove the arrow will hit the guard occupying the same space.

Inspect the selected NeoForge projectile/collision code and implement proxy transparency or a supported redirection path that preserves the actual projectile's native behavior. Test arrows, piercing/tipped arrows, tridents, thrown projectiles, and available modded projectiles. Do not replace native collision handling with a generic `guard.hurt(...)` shortcut that loses potion effects, piercing, deflection, attribution, or pickup behavior. A shot must neither hit twice nor pass harmlessly through both proxy and guard. If the no-mixin/no-AT boundary prevents a required path, report it explicitly with a minimal proposed remedy rather than claiming completion.

## 13. Port client rendering and exact proxy identification

Keep `client/ProxyRenderHandler` physically client-only through the NeoForge subscriber annotation. Common startup, commands, bindings, and data classes must remain loadable on a dedicated server.

Port against **1.21.1** rendering signatures. Do not copy the later `LivingEntityRenderState` architecture into this target.

Prove that:

- Hidden proxies render no body, clothing layers, armor, nameplate, shadow, or glow outline in the world.
- MCA, MCAConversations, and MCAQuests portraits display the correct visible guard with its own model/skin/equipment.
- Nested or canceled screen renders cannot leave a stale global screen-render flag.
- Temporary rotations, pose-stack changes, and reentrancy state restore even after an exception.
- Rendering one portrait does not alter the guard's subsequent world pose.
- Crowded guards, tracking order, travel, reconnect, and resource reload do not produce a different guard's portrait.

The current four-flag heuristic and nearest-overlapping-guard lookup are ambiguous. Prefer an explicit clientbound guard/proxy UUID association for exact pairing, with a bounded cache, entity-type checks, deferred resolution for spawn ordering, and invalidation on tracking stop/disconnect/level change. Send only the pairing/presentation data needed; do not expose the entire identity store.

If adding this synchronization, use the verified 1.21.1 payload registration/codec APIs and correct network-to-game-thread handling. Never allocate vanilla entity-data IDs on MCA or Guard Villagers classes from this addon. Reconcile server configuration affecting the pairing behavior.

Both installations are required for the proxy architecture. Test missing-client, missing-server, and incompatible-protocol cases. Do not assume metadata `side="BOTH"`, a server-list display check, or the old Forge comment alone enforces login compatibility; use a required negotiated payload/version check where needed.

## 14. Preserve diagnostics and update documentation

Port every command and its permission level 2 requirement:

```text
/mcamobcompat status
/mcamobcompat classify
/mcamobcompat classify <targets>
/mcamobcompat dump <namespace>
/mcamobcompat mca
/mcamobcompat guards status
/mcamobcompat guards inspect
/mcamobcompat guards inspect <target>
/mcamobcompat guards proxies
/mcamobcompat guards rebuild
/mcamobcompat guards purge <days>
```

Preserve console behavior, suggestions, selector handling, bounded chat output, and log output for longer dumps. Include binding and pair-resolution failures in diagnostics without making ordinary reads create new proxies or modify relationships.

Keep rebuild/purge explicitly destructive operator tools. Rebuilding a healthy proxy replaces its MCA identity; it must not be the routine fix for reconnects, reloads, or migration. Prefer diagnostics that separate healthy, pending, unloaded, conflicting, and genuinely orphaned pairs. Never auto-purge living or unresolved identities.

Update `README.md`, `DATAPACK.md`, `CLAUDE.md`, `MODMAP.md` through its generator if available, relevant design sections, configuration comments, and changelog. Preserve truthful historical release notes. Document target versions, installation on both sides, updated tag paths, actual API behavior, verified integrations, unavailable integrations, and known MCA options that cannot apply to an inert proxy.

Do not advertise automatic breeding, riding, milking, or generic animal AI changes merely because role classification works. Do not claim every companion addon works solely because a proxy is an MCA entity; their packets, UUID assumptions, event listeners, and screens require validation.

## 15. Verification and acceptance matrix

Port the existing tests rather than deleting them. The current classes are:

```text
McaEntityIdTest
NoOptionalModStaticLinkTest
RoleCacheTest
TagFileValidityTest
bridge/GuardIdentityDataMigrationTest
bridge/GuardIdentityDataTest
bridge/GuardNamesTest
event/ChoreProtectionPolicyTest
integration/guardvillagers/GuardVariantsTest
mca/McaBindingTest
```

Update paths and signatures while retaining their guarantees. Add focused tests for confirmed risks: offline hearts arithmetic and exact-once migration; return-type-aware personality binding; Guard Villagers variant resolution; module suppression through providers; spawn transaction rollback; click hook counts; malformed/future NBT; rename direction; and lifecycle/load-order transitions. Pure tests cannot prove Minecraft integration behavior.

Use NeoForge GameTests where suitable for entity lifecycle, persistence, and damage. Provide reproducible manual cases for GUIs, client packet behavior, and third-party integrations that cannot be exercised in headless tests. GameTest sources/fixtures must not leak into the distributable artifact.

| Area | Required cases | Pass condition |
|---|---|---|
| Minimal runtime | NeoForge + MCA + addon; all optional integrations absent | Client and dedicated server load; commands/classification work; no optional-mod linkage errors. |
| Classpath/artifact | Clean checkout, no local optional JARs | Build succeeds and packaged JAR passes all content gates. |
| Connection | Matching pair, addon absent on either side, incompatible protocol | Valid pair connects; unsupported combinations fail clearly before broken proxy gameplay. |
| Classification | Vanilla roles, real SWEM entity, enabled/disabled modules, dynamic provider | Correct roles and suppression with no animal-state mutations. |
| Reload | Datapack changes, config transitions, second world in one JVM | Updated roles/settings; no stale state or duplicate provider registrations. |
| Conversion | Adult/baby MCA NPC, main/offhand paths, modded tagged weapon, creative/survival | No destructive conversion while protected; intended recruitment preserves identity and items. |
| Interaction | Click guard/proxy, crouch/plain, consuming/PASS/canceled addon events | One action and correct screen; no duplicate hooks, inventory consumption, or stale follow target. |
| Proxy creation | Both load orders, immediate click, canceled spawn, duplicate saved links | At most one committed pair; failed creation does not corrupt state. |
| Persistence | Schema 1/2, offline hearts, save/restart, Forge-data fixtures | UUIDs and relationships preserved; each delta applied once. |
| Lifetime | Partial chunk unload for longer than ten seconds, reload, dimension travel, toggles | Unloaded entities survive; no unnecessary identity recreation or stale pair. |
| Movement | Follow/Stay/Move, logout, escort, active combat | Visible guard follows correct intent without duplicate pathfinding or losing native combat behavior. |
| Rendering | MCA and companion portraits, clustered guards, skins, equipment, resource reload | Correct guard portrait; no visible proxy or persistent pose corruption. |
| Chores | Owned/named/leashed/mount/pet, ordinary livestock, self-defense, binding failure | Protected damage blocked; eligible livestock still huntable; no endless protected-target attack loop. |
| Combat | Armor, shields, absorption, canceled attack, rapid hits, fire/lava, lethal damage | Correct health/attribution; no synthetic duplicate incidents or proxy-only deaths. |
| Projectiles | Arrow, piercing/tipped arrow, trident, thrown/modded projectile | Proxy is transparent or correctly redirected; exactly one native guard impact. |
| Death | Lethal hit, totem/cancellation, removal versus unload | MCA and guard death outcomes agree; one legitimate incident/reward, consistent links. |
| Companion addons | Each alone, then all supported target builds together | Quests/rewards, dialogue/portraits, crime/restraint/escort, reputation observe one intended NPC. |
| Operator tools | Console, insufficient permission, inspect, purge, rebuild | Diagnostics are accurate; destructive operations affect only their documented targets. |
| Load/performance | Representative village plus repeated joins/reloads | Bounded searches and caches; no per-tick log spam, reflection retries, or accumulating proxies. |

Test the finished JAR in both single-player and an external dedicated server with at least two clients. Source-run success is not a substitute. Record exact versions, steps, logs/screenshots, and which cases were automated, manual, failed, or unavailable.

Suggested sequence, after implementing the named tasks:

```bash
./gradlew --version
./gradlew compileJava
./gradlew test
./gradlew check
./gradlew checkJarContents verifyProductionJar
./gradlew build
./gradlew runGameTestServer
```

Use `gradlew.bat` on Windows. Run GameTests only once real registered tests exist; do not treat an empty suite as a pass. Avoid broad test repetition unless resolving a specific failure. Exercise GUI/runtime cases through the configured runs and then the packaged production JAR.

## 16. Required handoff and completion rule

Deliver:

1. The complete NeoForge port on its dedicated branch/worktree.
2. The built platform-qualified JAR and SHA-256 checksum.
3. Updated build/runtime dependency records, docs, and changelog.
4. A file-by-file summary of meaningful changes and any deliberate behavior corrections.
5. A completed acceptance matrix with exact tested versions and reproducible evidence.
6. A data-migration explanation and any limitations for existing Forge worlds.
7. A short list of external blockers or unsupported versions, with exact reasons.

Do not call the port complete merely because it compiles, starts, or can classify a cow. Completion requires preserved supported behavior, stable proxy identities, correct relationship accounting, production multiplayer operation, and verified integrations available on the target platform. Unavailable third-party target builds must remain explicit external limitations; preserve their optional support without pretending to have executed them.

## Source references

Repository links are pinned to the inspected commit where practical. Upstream branches and download listings can change; verify selected binary artifacts during implementation.

- [Addon source baseline](https://github.com/otectus/MCAMobCompatibility/tree/7062152e3f1a370a9173be89a0bc11f7ed5b2f08)
- [Repository instructions](https://github.com/otectus/MCAMobCompatibility/blob/7062152e3f1a370a9173be89a0bc11f7ed5b2f08/CLAUDE.md)
- [Addon bridge source](https://github.com/otectus/MCAMobCompatibility/tree/7062152e3f1a370a9173be89a0bc11f7ed5b2f08/src/main/java/dev/otectus/mcamobcompat/bridge)
- [Addon event handlers](https://github.com/otectus/MCAMobCompatibility/tree/7062152e3f1a370a9173be89a0bc11f7ed5b2f08/src/main/java/dev/otectus/mcamobcompat/event)
- [Official NeoForge 1.21.1 MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [NeoForge 1.21.1 getting started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [ModDevGradle documentation](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)
- [NeoForge mod metadata](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/)
- [NeoForge events](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [1.21 migration primer](https://docs.neoforged.net/primer/docs/1.21/)
- [SavedData](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [Tag resources](https://docs.neoforged.net/docs/1.21.1/resources/server/tags/)
- [Payload registration](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
- [GameTests](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
- [NeoForge LivingDamageEvent source](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/entity/living/LivingDamageEvent.java)
- [NeoForge ProjectileImpactEvent source](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/entity/ProjectileImpactEvent.java)
- [NeoForge arrow patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/entity/projectile/AbstractArrow.java.patch)
- [NeoForge player interaction patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/entity/player/Player.java.patch)
- [NeoForge entity persistence patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/entity/Entity.java.patch)
- [MCA 1.21.1 source snapshot](https://github.com/Luke100000/minecraft-comes-alive/tree/82552b8e577b7632ba36b71de8698f7e972f6298)
- [MCA extensible Personality implementation](https://github.com/Luke100000/minecraft-comes-alive/blob/82552b8e577b7632ba36b71de8698f7e972f6298/common/src/main/java/net/conczin/mca/entity/ai/relationship/Personality.java)
- [MCA releases](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn/files/all)
- [Guard Villagers 1.21.1 source snapshot](https://github.com/seymourimadeit/guardvillagers/tree/e329cd33a53e409fc5a09ead5dd107da6cd5bc61)
- [Guard Villagers releases](https://www.curseforge.com/minecraft/mc-mods/guard-villagers/files/all)
- [SWEM releases](https://www.curseforge.com/minecraft/mc-mods/swem/files/all)
- [Genetic Animals releases](https://www.curseforge.com/minecraft/mc-mods/genetic-animals/files/all)
- [DragN's Livestock Overhaul releases](https://www.curseforge.com/minecraft/mc-mods/dragns-livestock-overhaul/files/all)
- [Mojang 1.21 release notes and pack formats](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21)
