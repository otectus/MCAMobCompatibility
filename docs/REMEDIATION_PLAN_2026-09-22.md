# MCA: Mob Compatibility — Compatibility Remediation and Expansion Plan

**Prepared:** September 22, 2026  
**Primary target:** Minecraft 1.21.1 / NeoForge / Java 21  
**Reviewed branch:** `neoforge/1.21.1`  
**Reviewed commit:** `9e636505e7df6cf1d83a280f3ad03262273100bd`  
**Repository:** `otectus/MCAMobCompatibility`

## 1. Objective and evidence boundaries

Resolve the invisible-villager recruitment report, make SWEM riding genuinely functional, add sensible creeper awareness and coexistence with Golems Kill Creepers, and deliver explicitly scoped compatibility for Icy's Better Horses and Alex's Mobs.

This is a source-reviewed implementation specification, not a claim that the reported modpack was reproduced or that a patch has passed gameplay testing. The first report explicitly identifies NeoForge 1.21.1. The other comments do not identify their Minecraft versions. Implement and validate the primary NeoForge target first; audit corresponding behavior on the maintained Forge 1.20.1 branch and backport applicable fixes separately. Do not assume that all five reports describe the same installation.

The repository already has a NeoForge implementation, guard identity bridge, classification API, tests, and diagnostics. Extend these systems rather than starting a new port or replacing working architecture. [S01–S08]

### 1.1 Findings that shape the work

| Area | Verified observation | Implementation consequence |
|---|---|---|
| Recruitment | `ConversionGuardHandler.onEntityInteract` returns on the client before cancellation. The inspected upstream Guard Villagers 1.21.1 handler can call `villager.discard()` on the client. | Prioritize logical-side consistency and client entity lifecycle, not a blanket renderer workaround. |
| Profession split | Guard Villagers documents recruitment of nitwits and unemployed villagers. | The report's profession split strongly supports the conversion-path hypothesis; taiga and spawn eggs remain test dimensions, not established causes. |
| Proxy rendering | `GuardProxy.looksLikeProxy` delegates to exact proxy identification, and client pairing checks UUID plus runtime entity ID. | Preserve this protection. Do not pretend the current code hides every guard-profession villager. |
| SWEM | `SwemModule` enables recognition and ownership reads; its GameTest verifies roles, owner access, and module suppression, not movement. | Mount control is missing scope, not something that another horse tag will fix. |
| Creepers | `GuardDefenseHandler` reacts to targets and completed damage, primarily by alerting guards. | Add proactive danger perception and MCA movement/behavior integration. Waiting for explosion damage is insufficient. |
| Icy's Better Horses | The current project page advertises 2.0 on NeoForge 1.21.1 and describes ownership, trusted riders, multiple seats, and custom travel. | Audit the target artifact and permission model; ordinary horse assumptions are insufficient. |
| Alex's Mobs | A named unofficial NeoForge 1.21.1 port exists and requires its compatible Citadel port. | Target and document that distribution explicitly rather than saying Alex's Mobs is unavailable or assuming the original Forge binary is compatible. |

Evidence: [S02–S07, S09–S17]. Proposed class names and settings below are design suggestions, not claims that these APIs already exist.

## 2. Priority, scope, and invariants

**P0 — Guard conversion safety and visibility.** Ship a focused fix independently of animal-integration expansion.

**P1 — SWEM control and creeper response.** These address reported gameplay that does not work, not just a missing compatibility label.

**P2 — Icy's Better Horses and Alex's Mobs.** Reuse proven control and classification primitives, with per-capability verification.

Preserve the following throughout implementation:

1. **Identity remains intact.** Recruiting a real MCA villager changes that entity in place. Never discard it, spawn a replacement, reassign its UUID, rebuild its family node, or overwrite its saved identity as a visibility fix.
2. **Each mod retains its state.** MCA owns relationships and villager state; Guard Villagers owns its real guards' combat, inventory, models and animations; animal mods own genetics, breeding, needs, training, equipment, and ownership. Do not silently rewrite another mod's configuration or gamerules.
3. **Server authority is explicit.** The client may suppress invalid prediction and present feedback, but does not grant professions, transfer equipment, authorize mounts, steer server entities, or alter persistence.
4. **Optional means optional.** No hard static references to optional mod classes, no shaded third-party mods, and no loading client renderer classes on a dedicated server. Keep MCA reflection in `mca/McaBinding`. Preserve the no-mixin/no-access-transformer architecture; use a verified extension hook or an upstream cooperation path where necessary.
5. **Recognition is not capability.** Being a mount does not establish authorization, a controlling seat, steering, jumping, flight, or cart-driving support. Expose and test these separately.
6. **Do not weaken existing safety.** Preserve schema handling, exactly-once hearts accounting, quarantined identities, proxy invisibility, optional-module suppression, and animal chore protection.

These invariants continue the repository's documented behavior and build gates. [S02, S03, S06, S08]

## 3. Establish reproducible baselines

Create an issue fixture and dependency manifest before changing behavior. Record Minecraft, Java, NeoForge, MCA, this addon, Guard Villagers, TaCZ, its Guard Villagers addon, every MCA companion, renderer-support mods, resource-pack ZIP names, pack order, relevant configs, and SHA-256 hashes.

The reviewed README records MCA `7.7.36-beta.3+1.21.1`, NeoForge `21.1.248` as its minimum baseline, Guard Villagers `2.4.12`, and SWEM `1.6.12`. These are repository-recorded versions, not a substitute for the reporter's exact installation. Guard Villagers' inspected source branch declares `2.4.13`, whereas the published 1.21.1 listing identifies `2.4.12`; verify source-to-binary correspondence before declaring a binary-specific diagnosis. [S02, S09–S11]

Replace “latest as of September 20” with exact files. Preserve the original failing combination, and separately test an updated compatible combination. Do not upgrade every dependency first and lose the reproduction.

Create disposable worlds and copies of a representative existing world. Do not test migration, proxy rebuilds, or experimental integrations on the sole copy of a playing world. Record source SHA, built-JAR hash, fixture setup, expected behavior, observed behavior, and logs for each test.

## 4. P0: Invisible villagers after recruitment

### 4.1 Diagnose entity lifecycle before rendering

Trace the same UUID on the server, recruiting client, and a second observing client before the click, immediately afterward, and after tracking reload.

Capture the entity type, profession, biome variant, removal state/reason, invisibility flags, equipment, proxy marker/pair, event type, logical side, hand, item tag match, cancellation state, and recruitment result. Make logging targeted and rate-limited, not a global every-tick dump.

Use this decision table:

| Observation | Main investigation |
|---|---|
| Server retains the original MCA UUID, but recruiting client loses it | Client-side conversion/discard or tracking inconsistency. |
| Both sides retain the entity, but this addon cancels its render | Incorrect or stale explicit proxy association. |
| Entity survives, no proxy cancellation occurs, and disappearance follows resource-pack enablement | Model, layer, texture, or animation integration. |
| Server discards the original MCA UUID | Conversion protection failed; treat as identity-loss risk. |
| Only the recruited villager's clothing/model variant is broken | Profession/variant synchronization and model-resource mapping. |

The inspected source supports the first hypothesis: this addon blocks the server path but exits on the client, while Guard Villagers' conversion method performs the discard without a server-only gate. That is a concrete source-level mismatch, but the exact report still needs binary-level reproduction. [S03, S09]

### 4.2 Split conversion prevention from authoritative recruitment

Refactor `ConversionGuardHandler` into a side-safe interception phase and a server-only mutation phase.

**Interception:** Identify a real MCA villager, the crouch gesture, the main-hand item, the upstream convertible-item tag, and the effective protection policy on both logical sides. Cancel the destructive upstream route before its normal-priority handler runs. Inspect `EntityInteractSpecific` as well as `EntityInteract`; cover only actual bypasses found in the pinned runtime rather than blindly adding duplicate handlers.

**Server action:** After protection is established, require the main-hand authoritative pass and revalidate the actor, target, distance, world, age, equipment, profession availability, and applicable interaction permissions. Then recruit exactly once or return a specific refusal. The client must never call the mutation path.

**Packet correctness:** Verify that client cancellation does not prevent the intended server interaction packet. Prefer the existing vanilla interaction flow. Add a dedicated request only if a demonstrated loader-flow requirement makes it necessary; in that case validate target resolution, range, item, permissions, duplicate delivery, and rate limits. A payload is not permission to recruit arbitrary remote villagers.

**Policy agreement:** Audit whether current synchronized server config provides everything the client needs, including module activation. Reuse it where sufficient; otherwise add a compact versioned server-to-client policy. Do not let client common settings contradict the server. Before policy is known, prevent destructive local mutation without inventing a successful recruitment. Preserve explicit configuration semantics and warn clearly when an operator disables the identity-protection option.

The current weapon check intentionally follows `guardvillagers:convertible_guard_items`. Keep that tag-driven behavior, including custom pack weapons, instead of reverting to `instanceof SwordItem`. [S18]

### 4.3 Make recruitment transactional and understandable

Validate all preconditions before mutation. Keep the original MCA entity and UUID; update profession through the existing validated entry point; transfer one item, preserving components and respecting creative mode. Verify the resulting profession and equipment. Roll back only changes made by this transaction when a failure is recoverable; do not restore an indiscriminate old NBT snapshot that erases another mod's legitimate state.

Preserve name, age, gender, genetics, personality, family links, marriage, children, inventory, relationships, quests, reputation, and court references. Audit how profession changes affect job-site memory and appearance; use MCA's normal lifecycle rather than hard-resetting the Brain or copying new random clothing.

Return explicit localized results: recruited, already a guard, too young, occupied equipment slot, unsupported/missing profession, blocked by policy, or failed validation. Do not advertise success merely because protection canceled the original event.

Keep server serialization authoritative when two players click the same villager. The second attempt must not consume another weapon or create another identity. Protect main-hand/off-hand duplication and repeated events without a global debounce that prevents legitimate actions on different villagers.

### 4.4 Preserve and harden proxy rendering

Retain exact server-issued UUID association. `GuardProxy.looksLikeProxy` is already exact, and `ProxyPairs` also checks runtime IDs; audit lifecycle and negative cases rather than replacing it with a new heuristic. [S06, S19]

Test delayed pair arrival, entity-ID reuse, tracking start/stop, dimension changes, reconnects, invalid reverse links, and two overlapping guards. Unresolved or unloaded partners are not automatically orphans. Never hide a real MCA villager because its profession is `mca:guard`, because it is near a guard, or because it shares a name or skin.

Keep actual proxies invisible and inert. Do not globally call `setInvisible(false)`, disable `ProxyRenderHandler`, or expose hidden proxy bodies to fix this report. Preserve existing portrait substitution through the real guard's renderer; verify state restoration after canceled or exceptional draws. A missing portrait can use a non-destructive UI fallback, not identity replacement.

Do not recommend `/mcamobcompat guards rebuild` as routine recovery: the current command is explicitly destructive to the bridge identity. First diagnose whether the original still exists, and use ordinary re-tracking/relogin as a diagnostic for client-only disappearance. If a repair action is needed, restrict it to proven stale presentation/link state and report exactly what changes. Lost identity must be restored from a known backup, not fabricated from a replacement guard. [S02]

### 4.5 Reproduce the full reported stack without assuming blame

Start with MCA + this addon + Guard Villagers, then add the TaCZ port and Guard Villagers TaCZ addon, MCA companions, and the rendering stack in controlled groups. Finally test the exact complete stack.

Human Era documents Fresh Animations activation packs, pack-order requirements, and EMF/ETF-based mod compatibility. Record the reporter's actual variants and follow their version-specific installation requirements. Test no packs, Human Era alone, Fresh Animations plus the matching activator, then the full stack. Do not require players to permanently remove cosmetic packs unless a specific incompatibility is demonstrated. [S20]

Distinguish three actors: a recruited **MCA-native guard**, a real **Guard Villagers guard**, and its **hidden MCA proxy**. The TaCZ addon targets Guard Villagers behavior; an in-place MCA profession change does not establish firearm support for MCA's different entity implementation. Preserve existing real-guard gun behavior and explicitly label any unsupported MCA-native gun capability instead of replacing the villager to acquire it. [S03, S21]

### 4.6 P0 acceptance

Cover both MCA villager entity variants; nitwit, unemployed, employed, and existing guard; adult and baby; spawn egg, natural generation, conversion/replacement, and loaded-save provenance; taiga and at least two non-taiga variants; sword, crossbow, custom tagged item, and untagged item; survival and creative; repeated clicks, off-hand delivery, and simultaneous players.

The hard requirements are: exactly one original real MCA entity remains; UUID and identity references are unchanged; recruitment consumes at most one appropriate weapon; profession/equipment converge on server and both clients; no client-local discard occurs; no genuine villager is proxy-hidden; genuine proxies remain hidden; portraits and interactions still work; reload, reconnect, save/restart, and the original reported stack pass.

## 5. P1: Functional SWEM riding

### 5.1 Define the missing capability precisely

The existing module identifies `swem:swem_horse` and reads ownership. Its existing GameTest does not demonstrate driving. The README also explicitly excludes autonomous riding. Keep those current guarantees, but do not treat them as completion of this request. [S02, S04, S05]

Trace the real MCA Mount command end to end: command dispatch, entity search, eligibility, mount attempt, selected passenger seat, destination/task state, and actual horse navigation. Observe where motion stops. Test a vanilla horse as a control, then the exact SWEM artifact with valid taming and tack. Record whether the rider's own path is being advanced while its vehicle remains idle, whether SWEM reserves control for players, or whether required tack/permission/state rejects the attempt.

### 5.2 Prove one real route before building a framework

The first implementation spike must make one authorized adult MCA villager ride a real SWEM horse across a short clear route, stop, and turn toward a new destination using normal server movement. It must preserve ownership and animal state and display correctly on a second client.

Inspect the actual installed SWEM version's permitted public API/runtime methods and current documentation. The current project page says SWEM is no longer open source; historical public code is not proof of the shipped binary's control contract. Document what was actually inspected. Do not assume an old method name still works. [S12]

Use an ordinary supported navigation/control path when the mount genuinely supports an NPC driver. Where control is player-only, identify a proper extension point or coordinate an upstream API addition. Do not fake a player, send client keypress packets, manipulate private fields indiscriminately, teleport the horse along a path, or overwrite motion every tick in conflict with the mount's physics.

If that verified route is unavailable under the architecture constraints, implement the safe diagnostics and refusal path, keep steering explicitly incomplete, and attach the precise missing API contract and minimal upstream change required. Do not close the SWEM report or market full riding compatibility on the strength of recognition tests.

### 5.3 Add the smallest reusable mount contract

After the successful spike, extract only what SWEM and Icy's Better Horses need:

| Proposed component | Responsibility |
|---|---|
| `mount/MountAdapter` | Exact applicability, current authorization/seat checks, supported capabilities, and source-mod movement lifecycle. |
| `mount/MountDecision` | Allowed/refused result and stable localized reason, separate from classification. |
| `mount/NpcMountSession` | One real rider, one mount, one control owner, current navigation intent, lifecycle cleanup. |
| `mount/MountCoordinator` | Deterministic adapter selection, bounded ticking, movement handoff, progress/stall monitoring. |
| `integration/swem/SwemMountAdapter` | SWEM-specific implementation using the validated contract. |
| `mca/McaBinding` additions | Verified access to MCA command/task intent, without spreading MCA reflection. |

Capabilities should distinguish recognition, protection, passenger mounting, ground driving, and any separately supported jumping or specialized travel. Do not declare flight, water navigation, combat abilities, or cart driving merely because an entity can be ridden.

Evaluate ownership, taming, tack, health/state restrictions, adult/juvenile rules, occupied seats, and actor permissions at the time of use. A commanding player's right to issue a villager order is not automatically a right to use somebody else's animal. A supported delegation must be explicit and must not rewrite the horse's owner or trust list.

Use a real MCA villager as the rider. Hidden guard proxies remain inert and cannot acquire vehicles. Supporting an external real guard as a rider is a separate actor adapter, not permission to mount its proxy.

### 5.4 Navigate without fighting other AI

Map existing MCA commands into supported travel intent. A Mount command alone may mean board and remain seated; Follow, Return Home, or another actual task supplies a destination. Document this distinction rather than making mounts wander arbitrarily.

Plan against the horse's dimensions, collision constraints, speed, and loaded-world boundaries—not a villager-sized path. Ensure only one system issues movement at a time. Pause or hand off conflicting on-foot task navigation through supported hooks, and restore it when the session ends. Do not repeatedly reset the entire Brain.

Support start, turn, stop, destination change, and safe cancellation. Bound path recalculation, detect stalls, and give useful feedback for no path or rejected control. A horse moving from knockback, a teleport, or a nearby lure is not evidence of steering.

Handle dismount, death, logout, owner permission changes, entity unload, dimension travel, config changes, and reload. Do not force-load chunks, copy entities between dimensions, detach other passengers, or remount endlessly after a source mod ejects the rider. Prefer ephemeral sessions reconstructed from validated current state rather than persisting stale AI references.

### 5.5 SWEM acceptance

Retain classification/ownership/suppression tests, and add real-entity navigation fixtures: straight route, corner, obstacle requiring a detour, supported step, target relocation, stop/follow/resume, blocked route, occupied horse, wrong owner, missing tack, juvenile horse, rejected mount, and interrupted session.

In a deterministic baseline fixture, verify route completion and stopping within explicitly chosen tolerances and timeouts, positive movement along planned waypoints, and second-client agreement. Compare horse identity, owner, inventory/tack, training, and needs before and after; permit only changes attributable to ordinary SWEM gameplay. Do not suppress normal needs depletion to make a test pass.

## 6. P1: Creeper awareness and Golems Kill Creepers coexistence

### 6.1 Separate the requested behavior from the named mod

The likely referenced project, Golems Kill Creepers, controls whether iron golems attack and instakill creepers. A NeoForge 1.21.1 build is published. Its presence alone does not establish MCA villager fear or counterattack behavior. Implement MCA creeper awareness independently; add a small coexistence adapter only where real interaction requires it. Verify the user's exact project identity before closing the named compatibility request. [S13]

For 1.21.1, the documented rules are `doIronGolemsAttackCreepers` and `doIronGolemsInstakillCreepers`; newer namespaced forms are for later Minecraft releases. Never silently change these rules, grant villagers the golems' instakill behavior, or reinterpret a golem-only rule as a switch disabling villager self-preservation. [S13]

### 6.2 Add proactive, contextual threat evaluation

Introduce a small reusable `threat/ThreatPolicy` and `threat/ThreatCoordinator`, plus a vanilla-creeper provider. Suggested outcomes are `IGNORE`, `WATCH`, `FLEE`, and `DEFEND_WHEN_SAFE`.

Evaluate proximity, visibility/recent perception, target intent, fuse/ignition state, powered state where readable, current NPC health, combat capability, equipment, escape route, and nearby civilians. Detect a dangerous approaching or swelling creeper without requiring it to target an MCA villager. Do not wait for an explosion's damage callback or assume a surviving attacker will still exist afterward.

Proposed default behavior:

| Actor/state | Response |
|---|---|
| Child or unarmed civilian | Break ordinary activity, flee toward reachable safety, and request help. |
| Combat-capable MCA guard | Engage only under a supported combat policy; favor safe range and avoid civilian exposure. |
| Armed civilian | Follow MCA's applicable combat permission/ability; otherwise flee. Optional aggressive self-defense must be explicit. |
| Critically injured actor or imminent detonation | Emergency escape takes precedence over routine fighting. |
| Distant or safely separated creeper | Avoid permanent panic; reassess without aggressive global targeting. |
| Hidden MCA proxy | Never run independent perception, fleeing, or combat. |

Use conservative, configurable safety margins, not a claim of perfect explosion prediction. Do not alter creeper targeting, fuse, damage, explosion radius, block destruction, or gamerules as an implicit compatibility fix.

### 6.3 Integrate with MCA's actual behavior system

Inspect the pinned MCA Brain/task implementation. Use its effective panic, walk, and attack control points through `McaBinding` or an existing extension API. Merely adding an `AvoidEntityGoal` or calling `Mob.setTarget` is not sufficient evidence that MCA's active Brain will obey it.

Install behavior idempotently, including entity join, load, profession changes, and any Brain rebuild lifecycle. Do not accumulate duplicate tasks. Coordinate safety, combat, riding, follow, conversation, and chores so they do not issue contradictory movement. A proposed ordering is immediate survival first, then an already justified combat response, then authorized player travel orders and routine activities. Preserve the previous command for resumption after a calm-down interval; do not destroy it.

An active mount session should receive a safe escape destination only where driving is supported. Otherwise attempt a safe dismount and normal escape rather than ordering the seated villager's on-foot navigator to run.

Refactor the reusable portion of `GuardDefenseHandler.alertGuards` into a bounded service rather than duplicating it. Preserve target ownership, existing engagements, alliances, player-retaliation policy, and the current maximum-alert concept. Prevent target-change recursion: compare targets and do not call `setTarget` inside its own callback without a safe transition guard. [S07]

External Guard Villagers entities keep their own combat implementation. Integrate through supported behavior/target hooks and verify the combined result; do not wholesale replace their goal trees or TaCZ firing AI to impose MCA behavior.

### 6.4 Performance and acceptance

Stagger idle perception, reuse local candidate searches where practical, cap per-update work, and refresh a nearby imminent danger more frequently than distant idle scanning. Do not scan every entity in the level for every villager every tick. Avoid wall-penetrating omniscience and flee/reacquire oscillation.

Test idle, approaching, primed, powered, occluded, player-targeting, mod-targeting, and multiple creepers; children, civilians, guards, mounted villagers, sleepers, workers, and villagers in conversations; reachable escape and no-safe-route cases; golem mod absent and present with each gamerule combination.

Pass when MCA villagers visibly react before damage in a valid detectable-danger fixture, combat-capable actors use the configured response, primed threats trigger appropriate retreat, normal activities resume, and the golem mod's independent behavior and rules remain unchanged. Also regression-test familiar non-creeper threats so the new coordinator does not break existing fear or combat.

## 7. P2: Icy's Better Horses

### 7.1 Bind the exact supported distribution

The current project page advertises Better Horses 2.0 for NeoForge 1.21.1, with GeckoLib and Modonomicon dependencies. Older descriptions and releases differ. Pin the actual target JAR and dependency versions rather than combining an old Fabric API with the current NeoForge feature list. The public source has a `1.21.1-neo` branch, but a branch name is not proof that a particular published JAR contains every listed feature. [S14, S22]

Inspect its real registry IDs and runtime types. Determine whether each relevant feature operates on vanilla horses, custom horse types, attachments, or associated cart entities. Never invent a namespace from the display name or assume every horse-like entity is a drivable horse.

### 7.2 Implement recognition, protection, and authorized riding

Add an isolated module and runtime provider. Reuse existing role tags where sufficient. Protect owned, named, leashed, pet, and mount animals according to the established policy without breaking legitimate livestock chores elsewhere.

Implement `IcyBetterHorsesMountAdapter` using the proven shared contract. Audit source-mod ownership, trusted-rider, ejection, primary-seat, passenger-seat, and command behavior. Player trust is not automatically NPC authorization. Use only supported delegation; never change ownership, fabricate a player, disable protection globally, or remount after refusal.

Respect source-mod Follow/Stay/Wander/Home state and do not run two independent command controllers. Where the mod provides a supported temporary control handoff, restore the prior state at release. Keep gaits, bonding, genetics, tack, storage, breed abilities, and charge attacks owned by the source mod. Basic NPC ground travel does not imply support for every mounted-combat ability. [S14]

Treat carts as a separate passenger-transport capability. A villager may be a cart passenger without controlling the horse. Do not classify carts as livestock or promise autonomous ploughing, charging, or carriage operation unless separately implemented and tested.

### 7.3 Acceptance

Test vanilla horse controls, actual modded horse types, owner and stranger, supported delegation, occupied primary and secondary seats, NPC ejection, Follow/Stay conflicts, valid and missing equipment, chunk reload, and save/restart. Compare breed, genetics, bond, owner, inventory, and cart links before and after.

Test SWEM and Icy's Better Horses together when their own installations coexist. One entity must select at most one driving adapter. Specialized adapters take precedence over generic fallbacks; ambiguous matches refuse driving with diagnostics rather than enabling competing controllers. Disabling one integration must not reactivate it through a generic tamed-mount provider.

## 8. P2: Alex's Mobs

### 8.1 Identify what is supported

For NeoForge 1.21.1, use the named unofficial port maintained by Raguto and its compatible Citadel (Unofficial Port). The inspected published target is `alexsmobs-1.22.17.jar`, CurseForge file `7528193`, released January 26, 2026. Maintain any Forge 1.20.1 support against its own original distribution and dependencies. Label game version, mod version, loader, and distribution separately. [S15–S17]

Do not claim every fork exposing the same mod ID shares the same API. Use version/capability checks for reflective bindings. No presence check may force the optional classes to load before the integration is selected.

### 8.2 Build a complete registry audit, not a namespace heuristic

Generate an entity inventory from the installed artifact. For every registered type, record whether it is a living actor, its actual Java hierarchy, relevant static roles, ownership/taming support, hostility conditions, hunting suitability, rider capability, special movement, and any unresolved behavior. Nonliving helpers, projectiles, effects, and multipart components must not become independent villagers, livestock, or threats.

Assign behavior deliberately:

| Category | Integration policy |
|---|---|
| Confirmed farmable/livestock species | Use appropriate supported chore categories only when their actual interaction semantics match. |
| Passive wildlife | Do not automatically make it livestock or an allowed hunt target. |
| Tamed/owned companion | Resolve real ownership and protect it; respect sitting/following and source-mod commands. |
| Neutral creature | Become a threat through hostility context, not namespace or appearance. |
| Genuine hostile creature | Use contextual threat handling and suitable guard defense without granting new attack mechanics. |
| Ground-ridable creature | Recognize as a mount; advertise NPC steering only after an adapter-specific route test. |
| Flying, aquatic, or unusual mount | Keep unsupported movement capabilities explicitly unavailable; ground navigation is not a substitute. |

Use datapack tags for static classification and runtime providers for per-entity state. Do not cache owner, taming, anger, seat occupancy, or driving authorization in an `EntityType`-only cache. Invalidate relevant static caches on tag reload and module changes; re-evaluate dynamic properties at use time.

### 8.3 Close both interaction directions

Protect companions and mounts during MCA chores before prolonged pursuit where possible, while retaining the existing damage-stage safety net and hunt-abandon behavior. Do not rely solely on canceling final damage while leaving the villager chasing forever.

Ensure MCA responds to actual attacks by relevant modded mobs and nearby guards can assist without retaliating against allies, peaceful pets, or a whole namespace. Examine any source-mod checks restricted to vanilla villager IDs where they demonstrably omit MCA. Use narrow optional adaptations; do not make every Alex's Mobs creature hunt villagers as a side effect.

Retain breeding, taming, feeding, riding, special abilities, and unique combat state. Do not reuse a cattle tag for an unrelated interaction merely to force recognition.

### 8.4 Acceptance

Audit every registry entry, but select behavioral test representatives for each distinct mechanism: one ordinary animal, one runtime-owned companion, one neutral/aggressive transition, one hostile, one terrestrial rideable actor where applicable, and one unusual movement/multipart case. Add extra cases for every special adapter.

Test tame/untame or ownership-state transitions, module disable, tag reload, no optional mod installed, incompatible fork binding, and combined SWEM/Icy/Alex installs. Publish exactly which capabilities are verified, rather than one unqualified “Alex's Mobs supported” statement.

## 9. Cross-cutting implementation work

### 9.1 Minimal code organization

Modify the existing conversion, defense, module, binding, command, and test code first. Suggested additions are the small `mount/` and `threat/` packages, isolated SWEM/Icy/Alex adapters, and a shared bounded guard-alert service. Avoid a parallel identity store, a second interaction framework, or a large universal AI engine.

Preserve `MobCompatibilityAPI` and existing event semantics. Add new capability queries in a backward-compatible interface or separate service instead of changing what existing role predicates mean. Update the static-link and reflection-allowlist gates to cover new integrations without weakening them.

### 9.2 Configuration and reload

Keep existing configuration keys valid. Use a small number of behavior settings: functional NPC riding enabled, permission/delegation policy where supported, path/stall limits, creeper response mode, perception/retreat tuning, and debug controls. Suggested names are provisional; follow repository naming conventions.

Keep per-entity membership and static classifications in datapack tags, not hundreds of configuration booleans. Use correct 1.21.1 resource layouts such as `tags/entity_type`, optional entries for optional registries, validation for malformed values, and module gates that also suppress dynamic fallback providers.

Reload must invalidate static classifications, update effective policy, and safely suspend sessions whose adapter or permission is no longer valid. Do not discard entities or erase data when an integration is disabled. New temporary navigation state should normally not require a guard identity schema migration.

### 9.3 Diagnostics and player feedback

Extend the existing OP diagnostics rather than building a new GUI. Proposed additions:

- `/mcamobcompat inspect <entity>`: actual type, static/dynamic roles and their source, proxy status, active behavior, and selected adapter.
- `/mcamobcompat mounts inspect <entity>`: recognition, passenger/driver capability, permission decision, controlling seat, session, path progress, and refusal reason.
- `/mcamobcompat threats inspect <entity>`: threat, severity, selected response, and which system owns movement.

These are proposed commands, not commands available in the reviewed build. Preserve existing diagnostics and permissions. Report states such as absent, disabled, recognized-only, binding-unavailable, and verified driving separately. Do not report “compatible” merely because a mod ID exists.

Provide localized, actionable feedback for unavailable steering, occupied seats, no permission, missing equipment, unsafe dismount, blocked route, and deliberate refusal. Rate-limit repeated messages. An unsupported operation must not look like a successful one.

## 10. Verification strategy

### 10.1 Tests at the correct layer

**Unit tests:** conversion policy and result decisions, duplicate-event handling, exact pair matching, adapter precedence, capability/refusal rules, ownership/protection decisions, cache invalidation, threat decisions, and event re-entry guards.

**GameTests with real optional entities:** identity/equipment invariants, actual animal registry classification, real riding and waypoint progression, creeper response, session cleanup, optional-module suppression, and controlled reload/save fixtures. A mock horse is useful for logic but not evidence that SWEM accepts NPC control.

**Client/runtime tests:** actual right-click interaction, local removal regression, second-client observation, renderer layers, pack combinations, mounted interpolation, portraits, config/policy agreement, and reconnect/tracking lifecycle. A headless server test cannot establish that a villager is visibly rendered.

Keep runtime probes separate from the release artifact and use them only in disposable test worlds. The current build already has isolated unit, GameTest, and runtime-probe arrangements. [S02, S05]

### 10.2 Required configurations

Use focused and pairwise combinations rather than an uncontrolled Cartesian product: MCA + addon only; Guard Villagers; Guard Villagers with the reported TaCZ addon; the MCA companion set; Human Era/Fresh Animations rendering stack; SWEM; Icy; Alex with the matching Citadel port; golem mod with rule combinations; compatible mount-mod pairs; and the full reported guard stack.

Test both an integrated server and a dedicated server with two clients. All new optional integrations absent must remain a supported base configuration; missing a required dependency should produce the normal clear dependency failure, not a reflective crash.

### 10.3 Existing guarantees and performance

Run the existing hearts, migration, classification, binding, artifact, and static-link tests unchanged unless a documented behavior change requires a justified update. Add regressions; do not remove assertions to achieve green builds.

Benchmark a repeatable village fixture at, for example, 50 and 200 real NPCs, with controlled counts of mounts, guards, and threats. Record hardware, baseline/addon p50 and p95 tick timings, allocations, scans, and active path counts. Tune against measured results; do not promise an unmeasured millisecond budget. Verify bounded work, no forced chunk loading, no retained sessions after unload, and no event/pathfinding storms.

## 11. Delivery sequence and release gates

| Change set | Deliverable | Gate |
|---|---|---|
| 0. Reproduction and instrumentation | Pinned manifests, disposable fixtures, source/binary comparison, failing guard regression | Original failure or the exact unconfirmed boundary is documented. |
| 1. Guard hotfix | Side-consistent prevention, server-only transactional recruitment, identity/visibility tests | Recruiting and observing clients pass; original UUID/identity preserved; original stack verified. |
| 2. SWEM proof and shared contract | Working real SWEM route, minimal mount coordination, honest diagnostics | Actual control succeeds, not just boarding or classification. |
| 3. Creeper response | Contextual perception, MCA behavior integration, bounded guard alerts, golem coexistence | Detectable danger triggers appropriate behavior before damage; unrelated AI remains functional. |
| 4. Icy integration | Versioned adapter, authorization and seat handling, state-preserving ground travel | Real route and permission tests pass; no dual-controller conflict. |
| 5. Alex integration | Full registry audit, deliberate roles, dynamic ownership/threat handling | Category coverage and optional-dependency tests pass; capabilities accurately documented. |
| 6. Release hardening | Combined runtime tests, performance report, updated docs and release artifact | Artifact gates, dedicated-server tests, save/reload regressions, and support matrix pass. |

SWEM feasibility work and creeper work may proceed independently after the P0 safety work is protected. Do not hold the invisible-villager fix hostage to broad animal integration. Do not collapse separate integration states into one misleading “complete” release claim.

Update `README.md`, `DATAPACK.md`, `MODMAP.md`, `docs/DEPENDENCIES.json`, `docs/VALIDATION.md`, changelog, language strings, and relevant design notes. Describe the change from recognition-only to functional riding only after it is demonstrated. Include exact supported distributions and per-capability limitations.

The final implementation report must state what changed, the cause established for each bug, exact tested artifacts, automated and manual tests actually run, built artifact location/hash, and any remaining unverified capability with its next concrete test or required upstream hook. Compilation alone is not gameplay verification.

## 12. Definition of done

This work is complete only when the original villager survives recruitment visibly and with the same identity; a supported SWEM horse can actually be driven by an authorized real MCA villager; MCA villagers react sensibly to detectable creeper danger; Icy's Better Horses retains its ownership and animal systems while the advertised NPC capabilities work; and Alex's Mobs has an audited, accurately labeled integration rather than blanket namespace assumptions.

All supported combinations must preserve save data, optionality, source-mod ownership, multiplayer authority, and the guard proxy architecture. Recognition-only or safely refused operations are useful intermediate outcomes, but they do not close reports that specifically require functional movement or behavior.

---

## Source register

These sources were inspected on September 22, 2026. Repository references are pinned where practical. External project pages can change; resolve exact artifacts again when establishing the implementation manifest.

- **S01 — Repository branches:** `https://api.github.com/repos/otectus/MCAMobCompatibility/branches`
- **S02 — Reviewed NeoForge README:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/README.md`
- **S03 — ConversionGuardHandler:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/event/ConversionGuardHandler.java`
- **S04 — SwemModule:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/integration/swem/SwemModule.java`
- **S05 — SwemGameTests:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/gameTest/java/dev/otectus/mcamobcompat/gametest/SwemGameTests.java`
- **S06 — GuardProxy:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/bridge/GuardProxy.java`
- **S07 — GuardDefenseHandler:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/event/GuardDefenseHandler.java`
- **S08 — ProxyRenderHandler:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/client/ProxyRenderHandler.java`
- **S09 — Upstream Guard Villagers 1.21.1 HandlerEvents:** `https://github.com/seymourimadeit/guardvillagers/blob/e329cd33a53e409fc5a09ead5dd107da6cd5bc61/src/main/java/tallestegg/guardvillagers/HandlerEvents.java`
- **S10 — Upstream source version declaration:** `https://github.com/seymourimadeit/guardvillagers/blob/e329cd33a53e409fc5a09ead5dd107da6cd5bc61/gradle.properties`
- **S11 — Guard Villagers release listing:** `https://www.curseforge.com/minecraft/mc-mods/guard-villagers`
- **S12 — SWEM publisher page:** `https://www.curseforge.com/minecraft/mc-mods/swem`
- **S13 — Golems Kill Creepers description and target release:** `https://modrinth.com/mod/golemskillcreepers` and `https://modrinth.com/mod/golemskillcreepers/version/1.0.0%2B1.21.1`
- **S14 — Icy's Better Horses current project description:** `https://modrinth.com/mod/icys-better-horses`
- **S15 — Alex's Mobs unofficial target distribution:** `https://modrinth.com/mod/alexs-mobs(1.21.1)`
- **S16 — Alex's Mobs 1.22.17 artifact:** `https://www.curseforge.com/minecraft/mc-mods/alexs-mobs-1-21-1-port/files/7528193`
- **S17 — Alex's Mobs 1.22.17 dependency declaration:** `https://modrinth.com/mod/alexs-mobs%281.21.1%29/version/1.22.17`
- **S18 — GuardVillagersModule / convertible-item tag:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/integration/guardvillagers/GuardVillagersModule.java`
- **S19 — ProxyPairs protocol:** `https://github.com/otectus/MCAMobCompatibility/blob/9e636505e7df6cf1d83a280f3ad03262273100bd/src/main/java/dev/otectus/mcamobcompat/network/ProxyPairs.java`
- **S20 — Human Era installation and Fresh Animations guidance:** `https://modrinth.com/resourcepack/human-era-villagers-illagers`
- **S21 — Guard Villagers TaCZ addon description:** `https://modrinth.com/mod/guard-villagers-tacz-support`
- **S22 — Icy's Better Horses available source branches:** `https://api.github.com/repos/Icywhenhot/Icy-s-Better-Horses/branches`
