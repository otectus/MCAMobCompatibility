# MCA: Mob Compatibility — MCA characters with native Guard Villagers, Recruits, and Workers behavior

Implementation instructions for a coding agent. Prepared 20 October 2026.

Repository: <https://github.com/otectus/MCAMobCompatibility>

## 1. Mission and intended result

Implement the feature requested by this feedback: players want Guard Villagers, Villager Recruits, and Villager Workers to look and feel like individually editable MCA characters, with real MCA genetics, while retaining the source mods’ combat, command, and work systems.

**Use native entities as the physical actors and real MCA villagers as their persistent character identities. Extend the existing hidden-villager bridge; render MCA appearance on the native actors.** Do not transplant collections of AI goals into MCA villagers. A source mod’s behavior depends on its entity class, navigation, inventory, tracked state, packets, managers, and lifecycle—not just its goals.

The player-facing result must support all of these journeys:

1. Encounter an existing Guard Villagers guard, recruit, or worker. It has a persistent MCA appearance and can be opened in MCA’s actual character editor.
2. Create or edit an MCA villager, give that person a native guard/recruit/worker role, and retain the same character: face, genes, name, personality, relationships, and family identity.
3. Use the source mod’s inventory, hiring, orders, formations, promotions, work areas, and other supported controls normally.
4. Edit the character again; changes appear correctly in the world and portraits for other clients, including after reconnecting.
5. Promote a recruit, change a supported worker role, or return a character to civilian MCA life without losing identity or duplicating possessions.
6. Preserve MCA’s inheritance data and family operations. Children created through supported MCA family mechanics inherit from the actual MCA parents; employment is not an inherited gene.
7. Upgrade an existing Mob Compatibility world without recreating its guard identities or resetting hearts.

This is a full feature implementation, not a skin pack or a prototype. Deliver the code, migration, tests, documentation, production validation, and both supported Mob Compatibility platform branches. Where an upstream mod has no verified build for a platform, report that platform’s adapter as unavailable; do not manufacture compatibility.

## 2. Source-grounded starting point

The following findings were verified in source. The rest of this document specifies proposed behavior, not features that already exist.

| Area | Verified starting point | Implementation consequence |
| --- | --- | --- |
| Main branch | Commit `09efcacf7cadd60805b11f31ba2faeace1b9c7f0`; `gradle.properties` declares Mob Compatibility **0.2.2**, Minecraft **1.20.1**, Forge **47.4.10**, Java **17**. Some design/validation documents still describe 0.2.1. [S1] | Read current code and metadata before editing. Do not choose a release version from stale prose. |
| NeoForge branch | `neoforge/1.21.1`, inspected at `c0ab21e889ea0db989c827407711a0c9a3991176`; Mob Compatibility 0.2.2 and NeoForge 21.1.248. [S2] | Preserve the project’s parity workflow. Use the branch’s own dependencies and Java toolchain. |
| Existing bridge | `GuardProxy`, `GuardProxySync`, `GuardIdentityData`, and related handlers already pair a native guard with a real hidden MCA villager. The guard retains its original renderer and AI. Identity storage is schema 3. [S3] | Generalize this foundation; do not create a competing identity store or a second proxy per actor. |
| Existing recruitment | `ConversionGuardHandler` blocks destructive Guard Villagers conversion and normally assigns the original MCA villager MCA’s own guard profession. [S4] | That safe behavior does **not** give the villager Guard Villagers AI. Add a distinct native-role conversion mode. |
| Existing rendering | `ProxyRenderHandler` suppresses hidden proxies and substitutes the native guard in screen portraits. It uses a screen-stack check. [S5] | The new renderer must handle world, portrait, and editor-preview contexts separately. Merely reversing this substitution would be incorrect. |
| MCA editor | The inspected MCA 1.20.1 editor creates detached MCA preview villagers and sends data against a target UUID. Its sync handler can write appearance, age, profession, and family fields. [S6] | Use the actual editor, preserve its preview instances, and enforce bridge-specific permissions and data boundaries on the server. |
| MCA genetics | MCA owns tracked genes, gender, appearance resources, serialization, and parent-gene combination. [S7] | Reuse those mechanisms; do not implement a second genome or inheritance algorithm. |
| Recruits | Inspected at `44f60bdb4a2b4676a31674f31c081f195873e79c`. Native conversion paths create a recruit and discard the villager. Promotion can replace the recruit entity. Source `mods.toml` declares 1.15.2, while `gradle.properties` contains an older number. [S8] | Bind to an exact release artifact and audit every replacement path. Do not derive compatibility solely from repository properties. |
| Workers | Inspected at `29d26e1df6475fc8d043dc5d455f67b2fd1e9982`. Source `mods.toml` declares 2.0.3 and requires Recruits `[1.15.0,)`. `AbstractWorkerEntity` inherits through Recruits classes; current hiring uses Recruits hire trades. [S9] | Choose Workers before the broader Recruits adapter. Treat Workers 2.x as its own supported integration, not an assumed match for older profession-table implementations. |

This handoff is based on source review, not a compiled or runtime-tested integration. Upstream branch heads are evidence for design and inspection targets; they are not proof that every published jar contains identical methods.

### Required initial audit

- Read the repository’s current `AGENTS.md`, if present, `CLAUDE.md`, `MODMAP.md`, `README.md`, `DATAPACK.md`, `docs/DEPENDENCIES.json`, `docs/VALIDATION.md`, build checks, and parity manifest.
- Recheck the source snapshots above against the working branch. Preserve unrelated work.
- Produce `docs/NATIVE_CHARACTER_COMPATIBILITY.md` with an exact artifact matrix: Minecraft, loader, MCA release and package layout, native-mod release, artifact source/checksum, supported entity IDs, adapter revision, and verified capabilities.
- For Forge, start with the project’s current MCA probe fleet, its recorded Guard Villagers 1.6.19 baseline, and verified release artifacts for Recruits 1.15.2 plus Workers 2.0.3. Resolve dependencies from the actual jars. Add newer tuples only after inspection and tests.
- For NeoForge, use the branch’s MCA probe fleet and verified Guard Villagers artifact. The research here did not establish a Recruits/Workers NeoForge 1.21.1 tuple. Keep shared interfaces and configuration in parity, with an explicit availability result when no valid upstream dependency exists.
- Do not claim older Workers 1.7.x compatibility from the 2.0.3 adapter. If supporting it, give it a separately audited adapter and regression fixture.
- Run the current baseline checks before changes and record pre-existing failures separately from regressions.

## 3. Architecture: one character, one active physical actor

Use these terms consistently:

- **Character identity:** a real MCA villager and its existing UUID, containing MCA-owned personal data.
- **Native actor:** the actual Guard Villagers, Recruits, or Workers entity that moves, fights, works, takes physical damage, and is addressed by native commands.
- **Bridge record:** durable association between that identity and its current actor, including lifecycle state and revisions.
- **Render view:** a client-only MCA appearance representation. It is never added to a world, ticked as an NPC, or saved as another character.

For an MCA villager entering a native role, keep the **original MCA entity and original MCA UUID** as the identity. Hide and constrain it after the transition commits. A newly spawned native actor gets a distinct UUID. Never assign the same UUID to two live entities.

For a native actor that already exists, keep its UUID, native state, equipment, owner, groups, and tasks. Create or resolve its MCA identity through the existing bridge lifecycle. For an already bridged guard, reuse the existing proxy exactly.

### Authority boundaries

| State or action | Authoritative owner | Bridge responsibility |
| --- | --- | --- |
| Face, skin, hair, clothing, genes, MCA traits and personality | MCA identity | Initialize once, expose the editor, synchronize public appearance |
| Hearts, family tree, marriage, personal memories | MCA identity | Resolve the same person across role changes; preserve existing accounting |
| Combat, targeting, navigation, formations, native work | Native actor/source mod | Translate only supported explicit commands; do not run a second controller |
| Native owner, faction, group, contract, payment, morale and progression | Source mod | Preserve native checks and managers; never infer ownership from hearts |
| Health, physical damage, equipment wear, hunger/upkeep, loot and XP drops | Native actor/source mod | Mirror relevant consequences to MCA without executing them twice |
| MCA relationship timers and family bookkeeping | MCA | Keep required lifecycle updates; suppress conflicting physical side effects |
| Connection, migration, recovery and transition provenance | Bridge record | Maintain unique links and recover deterministically |
| Motion and item-use animation | Native actor | Adapt pose to the MCA render view |
| Appearance preview before save | MCA editor preview instance | Render the preview’s current data, not the last saved actor appearance |

### Non-negotiable invariants

1. One active native actor per character, one MCA identity per character, and no identity shared by unrelated actors.
2. There is only one physical AI/navigation owner while employed.
3. No new family node is created when an existing MCA person changes roles.
4. A native actor’s successful registration is required before hiding its original MCA villager or consuming transition costs.
5. Existing identity data is never rerolled during tracking, resource reload, restart, profession change, or promotion.
6. Items, equipment, payments, health consequences, death notifications, drops, and rewards have explicit single ownership.
7. An unresolved UUID means **unavailable**, not proven dead. Do not repair it by creating a replacement after a timeout.
8. Native diplomacy and ownership remain authoritative. A soldier is not automatically friendly to every entity that has an MCA identity.
9. Missing bindings leave a visible, usable native entity or an unchanged civilian, with an actionable diagnostic.
10. Tags classify entities; tags alone do not certify that arbitrary mobs have a working character/AI adapter.

### Generalize without breaking the existing API

Introduce a neutral internal character service and adapter registry. Suggested names are design labels, not existing APIs:

```java
interface NativeCharacterAdapter {
    ResourceLocation id();
    boolean supports(Mob actor);
    AdapterCapabilities capabilities();
    NativeRoleState inspect(Mob actor);
    CommandResult applyCommand(CommandContext context, Mob actor, CharacterCommand command);
    InteractionResult openNativeInterface(ServerPlayer player, Mob actor, InteractionHand hand);
    TransitionSupport transitions();
}
```

Use Minecraft and project-owned types at the common boundary. Keep authorization context in commands; a bare enum plus actor is insufficient for ownership checks. Avoid exposing arbitrary reflection handles or writable native NBT through the public API.

Recommended responsibilities:

| Proposed component | Responsibility |
| --- | --- |
| `CharacterBridge` / `CharacterResolver` | Resolve actor, identity, and canonical character UUID in either direction |
| `CharacterIdentityStore` | Extend the existing store and indexes, preserving its compatibility facade |
| `CharacterTransitionService` | Adoption, enlistment, host replacement, civilian restoration, rollback and recovery |
| `NativeCharacterAdapters` | One adapter selection per actor; Workers before Recruits; explicit Guard Villagers adapter |
| `CharacterInteractionRouter` | Item-aware routing, reach, ownership, permissions and duplicate-click protection |
| `CharacterCommandRouter` | Apply authorized intent through the correct native controller |
| `McaAppearanceBinding` | Version-tested access to MCA appearance/genetics serialization |
| `McaEditorBridge` | Open actual editor; validate and route saves for bridged identities |
| `CharacterRenderHandler` / `NativePoseAdapter` | MCA visuals plus native animation, with scoped render context |
| `CharacterSync` | Tracking snapshots, identity revisions, host replacement and cache invalidation |

Retain existing `GuardIdentity`, `MobCompatibilityAPI` methods, `GuardInteractEvent`, and hearts events as compatible guard-specific views. Do not make worker-only interactions emit guard events. Add neutral events only where a real consumer needs them.

## 4. Integration hooks and the existing no-mixins convention

The repository currently forbids mixins/access transformers and static references to optional mods. Preserve that isolation wherever public APIs, loader events, and the current reflective boundary are sufficient.

The new scope exposes paths that ordinary click cancellation cannot cover: direct recruitment methods, promotion replacements, native editor saves, and some render/animation internals. **Do not use an after-the-fact proximity scan to recover a villager that upstream code already discarded.** A cancellation after data loss is too late, and two nearby NPCs cannot be paired safely by name or position.

Implement in this order:

1. Use a supported upstream cancellable event/API when it supplies all required entities and transaction context.
2. Use existing loader events and narrowly bound native methods for fully observable paths.
3. Where a verified path has no adequate hook, add the smallest version-gated optional mixin/hook needed to bracket that operation. Record the specific exception in an architecture decision and update `CLAUDE.md` and the build rules accordingly.

This exception is limited to completing the requested native character integration. Do not remove `NoOptionalModStaticLinkTest`, globally relax jar checks, copy native AI, or make optional mods mandatory. Prefer string targets and Minecraft/project-owned signatures; isolate any unavoidable typed bridge in a separately gated adapter boundary. Test absent-mod class loading on a dedicated server.

Maintain a hook contract table with target class, complete method descriptor, supported artifact, logical side, whether original behavior runs, expected injection count, and failure behavior. Do not silently use `require = 0` to claim support when an essential hook missed. An unsupported artifact must not activate a transition mode whose interception is unproven.

Known audit targets:

- Guard Villagers 1.20.1 `HandlerEvents` conversion and `Guard` lifecycle. [S10]
- Recruits `VillagerEvents.createRecruit`, `createNobleVillager`, both `createHiredRecruitFromVillager` forms, and other native spawning paths. Some conversion logic runs from a living-tick listener rather than a player click. [S8]
- Recruits `RecruitEvents.promoteRecruit`: the inspected implementation discards the old actor and adds a new one, then updates group membership. A pre-promotion event by itself does not identify the eventual new actor. [S8]
- Workers’ Recruits-based hire-trade paths and registered worker types; distinguish new hire spawns from conversion of a pre-existing MCA person. [S9]
- MCA editor item, GUI-open request, `VillagerEditorSyncRequest`, and any version-specific path that replaces a character when editing age/gender/type. [S6]

MCA source uses different mappings/package layouts from shipped jars. Probe the actual 7.6 and 7.7 artifacts across the package roots already handled by `McaBinding`. A source method name is not a production descriptor.

## 5. Persistence, migration and lifecycle

### Record design

Extend schema 3 to the next schema only after checking the current head. Retain the existing storage identity or provide a transactional migration; do not leave two independently writable stores.

Store at least:

- Canonical character UUID, equal to the MCA identity UUID once one exists.
- Current native actor UUID, native entity type, adapter ID, and association generation.
- MCA identity UUID/type and last known dimension/location for diagnostics.
- Origin: existing MCA person, newly adopted native actor, or migrated guard bridge.
- Lifecycle state, appearance revision, and transition journal reference.
- Original civilian profession and owned civilian state needed for restoration, without treating an old snapshot as the current person.
- Migration provenance, existing seed flags, hearts migration markers and pending deltas.
- Last confirmed death/transition identifiers needed to prevent duplicate application.

Keep the existing hearts rule: the MCA snapshot and unapplied deltas are separate ledgers. Migration must not add a delta to both. Preserve `LegacyV2`, unreadable records, quarantine behavior, and read-only handling of a newer unknown schema.

Migrate existing bridged guards using their **existing proxy UUID** as character identity. Do not replace that proxy to obtain a better appearance. For records that never acquired a proxy, retain their pending identity state and initialize only through the controlled lifecycle.

### Lifecycle states

Use explicit states equivalent to `ACTIVE`, `AWAITING_ACTOR`, `AWAITING_IDENTITY`, `TRANSITIONING`, `SUSPENDED`, `DECEASED`, and `QUARANTINED`. Keep read-only schema handling distinct from gameplay state.

- Reconcile through UUIDs and association generations, not nearest entities.
- Resolve existing entities before considering creation. Preserve the current load grace period and create-from-controlled-tick behavior.
- Revalidate on chunk load/unload, entity replacement, portal travel, login tracking, server restart, and configuration changes.
- An actor’s removal during promotion or dimension transfer is not a death or abandonment.
- Existing proxies remain hidden and safe when a feature is disabled. Suspend integration; do not expose or delete identities.
- Do not force-load chunks to locate a missing partner. Reattach when normal loading makes both available.
- Do not let the proxy acquire independent chunk tickets. If Workers/Recruits deliberately keeps an actor loaded under its own configuration, document and respect that native behavior without adding another loader.

### Recovery, including interrupted saves

Model each structural transition as a journaled state machine: `PREPARED → ACTOR_READY → ASSOCIATION_COMMITTED → CLEANED`, with an explicit rollback path. A transaction ID travels in the bridge record and relevant entity persistent data.

Minecraft does not atomically save the player inventory, two entities, native managers, and this mod’s SavedData. Calling `setDirty()` is not a durable transaction. Implement a tested recovery protocol using persisted transition intent, idempotent completion, and an explicit inventory/payment receipt for the affected operation.

- Persist enough evidence to distinguish an uncommitted actor from a legitimate active actor.
- Recover before permitting another transition for that character.
- Never auto-refund a payment if the evidence does not establish whether native payment completed.
- Never restore stale full inventory snapshots over inventories that have resumed gameplay.
- Quarantine ambiguous records and provide a targeted repair report; preserve all data.
- Fault-inject interruption at each journal stage and at entity/manager save boundaries. If guaranteed power-loss atomicity is not implemented, state the exact recovery guarantee and limitations; do not advertise it as atomic across the whole world save.

## 6. Safe enlistment, role changes and civilian restoration

### Existing MCA villager → native role

Implement a source-specific transaction:

1. Validate that the MCA person is loaded, alive, adult, not already bridged/transitioning, and eligible for the source mod’s requested role. Validate the initiating player and any native contract/cost requirements.
2. Validate that all required hooks, native initialization methods, inventory mappings, and rollback operations are available **before** changing anything.
3. Record intent and exact affected state. Reserve the operation against duplicate clicks/packets.
4. Create and initialize the real native actor using the source mod’s supported initialization and economy/ownership operations. Do not copy source-mod AI or manually replace native hiring checks with a guessed cost.
5. Transfer only explicitly owned physical state: position, orientation, health fraction, equipment, relevant cargo, and necessary native ownership/team information. Do not load the MCA entity’s entire NBT into the actor.
6. Require successful world registration and successful required native-manager registration. Honor spawn cancellation.
7. Bind the **original MCA villager** as identity. Preserve its UUID, current genes, relations, family record, personality, home association, quests and addon data. Do not call random initialization on it.
8. Put the identity into constrained proxy mode and synchronize the association to tracking clients. Commit costs exactly once according to the transaction’s payment receipt.
9. Complete transfer/cleanup without dropping or duplicating items. Suppress the source conversion’s destructive discard and inappropriate POI/family cleanup for this MCA path.
10. On failure, keep or restore the same visible civilian and undo only mutations owned by this transaction.

The adapter may intercept the original conversion and orchestrate the MCA-specific path using native helpers, or bracket the native routine while replacing its destructive steps. Choose by verified upstream behavior. Never execute both the original conversion and a second bridge conversion.

For Guards, preserve the old safe MCA-profession recruitment mode as an option. Add a clearly named native-AI recruitment mode, and use that mode for the new experience. On upgrade, preserve explicitly configured legacy behavior; document how to select native conversion. Existing MCA guards are not silently replaced en masse.

For Recruits/Workers, support normal native acquisition and a discoverable **Assign native role** action for an existing MCA adult. This action must invoke the adapter’s real eligibility, hiring, group and cost rules. Admin conversion may bypass gameplay costs only through a separate permission-checked path.

### Existing native actor → MCA character

- Adopt the actor in place; do not despawn and recreate it just to give it a face.
- Preserve its custom name when meaningful; generate missing MCA appearance/personality once through MCA initialization.
- Preserve contracts, equipment, native XP, owner/group/faction, work areas, passengers, cooldowns and scheduled activity.
- Persist the generated result. A deterministic seed alone is insufficient because a changed hair pool could otherwise change the face after an update.
- Import existing Mob Compatibility identities before generating anything new.

### Promotion and native host replacement

Bracket every supported replacement with a transition token containing the exact source and destination actor UUIDs. Move the association to the new native actor, keep the same MCA identity, preserve native progression, and let native managers update their own membership using their expected actor UUIDs.

Invalidate old client actor IDs and bump the association generation. Do not interpret the outgoing actor’s `DISCARDED` removal as character death. If a replacement spawn fails, restore the old valid actor/association without granting a second promotion or charging twice.

### Return to civilian

Provide a deliberate, reversible player action when the source mod’s ownership/contract rules permit it. Native dismissal is not automatically civilian conversion: some mods dismiss an owner while retaining the same native entity. Keep these actions distinct.

- Close active native menus, validate owner/authority, and safely stop ongoing work or commands.
- Release native group membership, owned work assignments and chunk tickets through the source mod’s cleanup paths.
- Transfer possessions exactly once. Abort before committing if they cannot fit; offer a visible overflow-transfer step instead of deleting cargo.
- Unhide the same MCA identity, restore its appropriate civilian profession/AI flags and physical state, and reestablish valid residency/POI state through supported MCA/vanilla operations.
- Restore original flags only where proxy mode changed them. Do not erase unrelated tags, addon data, or an independently applied effect.
- Keep all edits and relationships acquired while employed. Do not load the pre-enlistment full snapshot over the current MCA person.
- Remove the native actor as a role transition, without death effects, loot, XP, reputation penalties or a second family node.

## 7. MCA appearance and native animation

### Rendering approach

Render the native actor through an MCA-compatible client appearance view. Reuse MCA’s model, layer/resource selection, dye handling, custom-skin behavior and genetic scaling. A client-only MCA entity instance created through registered entity types is an acceptable render view; it must never be added to `ClientLevel` or run normal entity ticks.

Keep MCA-specific client binding in an isolated client boundary. Cache render views by canonical identity and revision. Prefer the public appearance subset to full proxy NBT. Do not serialize full entities each frame.

Preserve and test:

- Skin, face, hairstyle, hair dye, clothes, gender presentation, age appearance, genetic body proportions, and supported MCA traits.
- Resource-pack replacements and MCA clothing/hair pools.
- Custom player skins and their asynchronous texture loading, using MCA’s existing mechanism.
- Native held items, armor, enchanted overlays, shields, bows/crossbows, item-use animation, attack swing, hurt/death animation, and mounted poses.
- Native role markers such as team colors, banners and worker clothing where supported. Adapt these layers explicitly; do not invoke a renderer layer with the wrong entity/model type.

Create a pose adapter that reads native actor state and builds an immutable frame snapshot: interpolated position/rotation, walk animation, hand-use state, attack phase, pose, equipment, mounting and relevant native flags. Apply this snapshot to the render view only. If a field needs a version-specific accessor, contain it there; do not start fake item use or mount a real entity during rendering.

MCA rendering can rely on MCA-only methods and layers. Simply casting a recruit to `VillagerEntityMCA`, or assigning a human texture to the old villager model, does not implement this feature.

### Three distinct render contexts

| Context | Required appearance source | Required pose source |
| --- | --- | --- |
| World native actor | Latest synchronized MCA character appearance | Native actor animation |
| Dialogue, quest or native-management portrait | Character appearance | Portrait-controlled rotation with appropriate displayed equipment |
| MCA editor preview and hair/clothing gallery | The editor’s actual detached preview instance, including unsaved changes | Editor’s own preview pose |

The current guard portrait substitution must not replace MCA’s detached editor previews with the last saved actor. The inspected editor creates separate `villager` and `villagerVisualization` instances; neither is another persisted identity.

Use explicit, scoped render contexts and reentrancy guards, restored in `finally`. Avoid making a whole open screen or a global boolean mean “render every invisible MCA villager.” Replace the current stack-walking approach for the hot path where practical; any fallback must be bounded and must not run for every visible entity.

Ensure exactly one body, shadow, nameplate, leash and applicable overlay. Check native renderer overrides that do work outside their superclass render call; canceling a living-render event alone may not suppress everything. Make renderer ownership explicit when GuardsSkins/RecruitsSkins or another renderer replacement is present.

If a supported render binding fails, log once and use the original native renderer. No invisible NPCs, endless exceptions, recursive rendering, or stale transforms on the next entity.

### Physical size policy

Default to native collision/navigation dimensions and native combat attributes. MCA genetics controls character appearance and inheritance; it does not silently rewrite reach, armor, speed, damage, or pathfinding dimensions.

Document this distinction. An optional matching-hitbox mode may be added only with per-adapter bounds, server-authoritative dimension updates, obstruction checks and navigation regression tests. It is not required to deliver inherited visual genetics. Never resize the actor merely by changing a client model scale.

## 8. Actual MCA editor and interaction routing

### Use MCA’s editor

An MCA editor item used on a supported native actor must open the real MCA character editor for the linked identity. The standard editor item must not disappear into the native inventory GUI or generic chat interception.

Route all editor modes supported by the pinned MCA build: appearance, genes, names, supported traits/personality, and administrative identity fields. Preserve MCA’s existing categories and appearance selectors. Do not replace them with a lookalike menu that supports fewer controls.

The bridge must refresh public appearance after edits made through MCA’s editor, MCA items, commands, or supported addons. Detect authoritative changes through known write hooks/dirty revisions, with a cheap bounded reconciliation fallback—not a full NBT comparison every tick.

### Server validation

Opening a screen does not authorize every subsequent packet. For bridged targets, enforce:

- Same dimension, actual entity reach, live actor, current identity association and editor permissions.
- A valid, short-lived editor session bound to player, character and association generation.
- Per-field validation and allowed resource IDs; finite, bounded gene/dye values and bounded payload sizes.
- An explicit editable-field allowlist. Reject entity IDs/UUIDs, health, equipment, native inventory, owner, faction, transition flags, proxy state and arbitrary entity NBT.
- Separation of cosmetic editing from administrative age/traits/family editing. Owning a soldier does not grant unrestricted family-tree editing.
- Profession edits that could trigger another mod’s conversion must go through the transition service, or be disabled with a clear explanation while employed.
- Adult-only role eligibility. If an authorized admin edits age below adult, require a safe civilian transition first; do not leave a child in a paid combat/work role.

Inspect the actual editor-save handler for each admitted MCA artifact. The reviewed older handler accepts broad custom NBT; do not assume that simply using that packet provides bridge-specific validation. Intercept saves for bridged targets before mutation and leave ordinary unrelated MCA behavior unchanged. A bridge-owned packet is acceptable only if all corresponding vanilla-MCA editor save paths for that bridged session are also controlled.

Use an appearance revision or edit lock to prevent two clients silently overwriting each other. On conflict, reload the latest character with a clear message. Respect MCA’s actual save semantics; do not label a button “Cancel” if earlier changes were already committed.

### Default interaction precedence

| Player action | Result |
| --- | --- |
| MCA editor item | MCA editor for the linked character |
| A recognized native command/work tool | Native mod interaction, with its normal checks |
| Recognized gift, ring, dye or other MCA interaction item | Appropriate MCA identity interaction, once |
| Ordinary social interaction | MCA’s actual interaction screen |
| Crouch interaction / configured native-access gesture | Native inventory/management interface |
| Role assignment or civilian restoration | Validated transition action with a clear resulting role |

Preserve existing configured gesture choices. Add a discoverable help hint and native-controls entry where possible; do not rely on players guessing a crouch gesture. Check both hands and `EntityInteract`/`EntityInteractSpecific` sequencing. Prevent double execution without suppressing unrelated later interactions.

Never make hostile enemy recruits socially controllable through MCA. Respect native hostility, contract ownership and access restrictions, while allowing appropriate MCA social interactions independently of military command rights.

## 9. Adapter-specific requirements

### Guard Villagers

- Keep the real guard’s melee, ranged, shield, food/healing, patrol, follow and other supported native behavior.
- Preserve the source mod’s Hero of the Village/follow restrictions unless an explicit new gameplay configuration changes them. MCA hearts alone must not bypass them.
- Translate MCA Follow/Stay/Move only through validated native control operations. Respect the existing guard defense, friendly-fire, conversion protection and Crime integration contracts.
- Preserve guard-specific variant/equipment data when adopting existing guards. MCA appearance becomes the visible character layer; native variant data remains intact for fallback rendering.
- Test both vanilla-villager-style and human-style native renderer configurations.
- Make the recruitment choice visible: **MCA guard profession** and **Guard Villagers AI** are different modes with different controllers.

### Villager Recruits

Enumerate actual entity types and supported capabilities from the pinned artifact. Cover its combatants and humanoid companions, not just the basic recruit. The inspected source includes melee recruits, shieldmen, bowmen, crossbowmen, horsemen, nomads, leaders and specialist companions; nobles and other noncombat roles need their own capability restrictions.

Preserve native:

- Hiring costs, owner UUID, teams, diplomacy, groups and formations.
- Following, holding, patrolling, attack/protection commands and formation movement.
- Food/upkeep, morale, leveling, inventory and equipment.
- Ranged weapons, shields, mounts, native weapon-compatibility behavior and dismount orders.
- Promotions, leader/specialist data, command UI, noble hire/trade screens and group-manager registration.
- Source-mod death/corpse behavior where supported; add no second corpse or second loot inventory.

Do not blanket-set all recruit identities to `mca:guard`. A guard profession can make MCA-family addons treat the character as law enforcement. Separate native combat role from MCA social profession and legal authority. Preserve the originating civilian profession as restoration data and expose native role through the bridge’s own role metadata.

If administrative integration deliberately grants a recruit law-enforcement authority, make that an explicit policy and test it with Crime/Reputation. Enemy factions must remain enemies even when both sides have MCA faces.

### Villager Workers

Select the Workers adapter before the Recruits adapter because workers inherit Recruits behavior. Never create both a soldier proxy and a worker proxy.

For the reviewed 2.0.3 source, cover these registered worker entities: `farmer`, `lumberjack`, `miner`, `builder`, `merchant`, `fisherman`, `animal_farmer`, `courier`, and `cook` in the `workers` namespace. Reverify the exact IDs in the release jar. Work-area marker entities and fishing bobbers are not characters.

Preserve native:

- Hiring through the actual Recruits trade flow, owner/group references and economy checks.
- Job-specific AI and navigation, work-area assignments, home assignments, storage rules and tool selection.
- Farming/replanting, mining, building, logging, fishing, animal work, cooking, courier routes, merchant trades and deposit/retrieval operations where present in the tested release.
- Night/home behavior, work interruption/resumption, permissions and claim protection.
- Native chunk-loading policy without introducing proxy-driven tickets.

The inspected worker `isWorking()` reads a native follow-state value; do not hard-code that numeric value into shared code. Put verified state translation inside the versioned worker adapter.

MCA Follow/Stay/Return-to-work must preserve the underlying job and assignments. A conversation can pause movement briefly only if the native controller supports a safe pause; it must not erase a courier route, cancel a build permanently, or start competing MCA chores.

Workers’ own animal handling remains owned by Workers. Existing Mob Compatibility livestock/hunting protections and animal genetics integrations must not be reinterpreted as permission to replace another mod’s breeding system.

## 10. Command arbitration and social/physical side effects

The current guard synchronizer can replay proxy navigation onto the guard. Do not apply that behavior indiscriminately to Recruits or Workers: it would fight formations and job navigation.

Implement explicit command routing with source, initiator, capability, permission, sequence, and completion/result. Let the source mod retain its internal priority rules. A practical bridge policy is:

1. Enforce actual death, disabled state, restraints and equivalent blocking conditions.
2. Respect the native controller’s combat/emergency behavior.
3. Apply valid owner/native management commands and explicit permitted MCA commands through the same adapter.
4. Allow an optional social pause only at a safe point, with automatic release on distance, damage, disconnect or screen close.
5. Resume the underlying native order when a temporary override ends, unless a newer valid command superseded it.

Do not set navigation, targets or follow flags every tick merely because an old MCA memory still exists. Consume an intent once; maintain enough state to distinguish a new request from a stale mirrored value.

### Single ownership of possessions and physical effects

Define an ownership map for every inventory exposed by an adapter. Native equipment and work cargo belong to the native actor. MCA gifts must either enter an explicitly separate personal inventory or be transferred through a validated native insert operation. No stack may exist as a usable item in both inventories.

- Do not mirror equipment to the server proxy as real consumable inventory just for rendering.
- Disable proxy item pickup, independent feeding/healing, collision, pressure-plate interaction, vehicle control, unsolicited drops and physical attacks.
- A hidden proxy may still execute MCA lifecycle code despite `NoAI`. The reviewed MCA tick calls residency, relationships, inventory updates and MCA brain logic. Audit those paths; setting `NoAI` is not proof that all duplicate effects stopped. [S11]
- Keep required MCA social/family updates exactly once. Do not call them manually if the entity is already running them.
- Mirror authoritative health for MCA displays without letting proxy regeneration heal the actor for free.
- Route explicit healing, gifts and effects through a documented adapter operation where they are meant to affect the body.
- Retain the existing Crime restraint/blindness effect contract and define which additional effects are mirrored and in which direction. Avoid reflecting the same effect back indefinitely.

### Damage, death and replacement

Physical damage resolves on the native actor. After cancellations, shields, armor, totems and late event changes, relay the resulting MCA social consequences once with the correct attacker/projectile attribution.

Confirmed native death should produce one native loot/XP/corpse outcome and one MCA identity death/family outcome. Proxy death must not generate a second inventory drop. Differentiate true death, natural/native despawn, capture, dimension change, role transition, administrative deletion and unresolved unloading.

For native despawn of a bridged character, specify an explicit policy that preserves the identity record and does not silently create a homeless duplicate. Do not change every source mod’s despawn behavior globally.

Handle MCA infection/zombification/cure and supported capture/release paths explicitly. Preserve character identity and define whether the native role is suspended, restored or converted to civilian. If a third-party capture mechanism lacks a safe hook, refuse that operation for bridged actors with a reason; do not advertise compatibility or silently lose half the character.

## 11. Genetics, relationships and MCA-family compatibility

### Real inherited genetics

Store and use MCA’s full supported gene set and traits, including fields not exposed by the current GUI. Preserve unknown supported data through MCA serialization instead of hard-coding only skin and hair.

- New identities use MCA’s normal initialization once.
- Existing MCA people retain their exact current genes and appearance resource references.
- Parent identity resolution must return the actual MCA identities for native actors. Use MCA’s own baby/relationship/inheritance operations; do not independently average genes or infer genetics from a texture.
- Children remain ordinary MCA children. Do not create child soldiers/workers or inherit a contract, faction command role, work area or native progression.
- Enlistment at adulthood is an explicit eligible action; existing family links remain unchanged.
- An editor gender change must preserve character identity. If a specific MCA version requires entity replacement, treat it as an identity-preserving transaction with no simultaneous duplicate UUID and update tracking safely.
- Editing a parent after a child exists follows MCA’s normal behavior; it does not retroactively rewrite the child’s genome.

Demonstrate MCA-supported family interactions for a bridged parent and an ordinary MCA parent, and for two bridged identities where MCA permits that relationship path. Verify inheritance using MCA’s combination routine and a controlled seed, not by expecting the child to look identical to either parent.

Do not promise autonomous courtship or the same NPC birth rate merely because genetic inheritance works. Any family action normally dependent on an MCA movement/brain task needs a mapped, tested path or a clearly unavailable action. Do not wake a second physical MCA AI to make that task appear to work. Required player-invoked supported family actions and identity continuity must work before release.

### Sibling addons and settlement data

Test the installed, supported family tuple of MCA: Conversations, Quests, Reputation and Crime. Preserve their existing interactions through the real identity and add explicit identity-resolution hooks where a sibling currently assumes its target is also the visible body.

- Dialogue portraits show the same character; speech originates from the visible actor’s location and is not played twice.
- Quest giver/participant UUIDs remain the original character UUID across enlistment and promotion. Deliveries resolve to the intended owned inventory.
- Escort/follow quests use the actor’s movement through the adapter.
- Hearts, reputation and crimes are credited once to the correct identity/community.
- Arrest/restraint/escort affects the body; workers and ordinary soldiers do not automatically become law enforcement.
- Village population and family-tree views count one resident. Do not double-count host plus proxy.
- A member of an opposing Recruits faction must not become globally protected merely because their identity is an MCA villager.
- Settlement residency can remain associated with the original community while a soldier travels; do not rewrite home every time the actor walks near another village.

Use Townstead/Capitals and Ultima integrations only within their existing supported contracts. Do not claim that an invisible proxy automatically makes every third-party scheduler compatible. Document tested combinations, avoid duplicate work controllers, and keep current read-only contracts intact.

## 12. Networking and client lifecycle

Extend the existing `proxy_pair` mechanism or introduce a versioned successor with a clean compatibility decision. Client persistent entity data is not an automatic synchronization channel.

Send a bounded tracking snapshot containing character UUID, actor UUID/runtime ID, identity UUID/runtime ID where applicable, dimension, association generation, public appearance revision, adapter capabilities and rendering mode.

- Synchronize on start tracking, committed adoption/conversion, role replacement, editor save and authoritative appearance changes.
- Order association and appearance messages safely when entity-spawn packets have not arrived yet. Queue by UUID/generation with a bound, then resolve; do not match by closest mob.
- Handle out-of-order revisions, late actor spawn, late identity spawn, entity-ID reuse, reconnect, dimension change and chunk re-tracking.
- Remove caches on stop tracking, level unload and disconnect; invalidate render resources on resource-pack reload.
- Use the native actor’s existing synchronization for its normal equipment/motion. Bridge only the state required to render MCA appearance or specialized poses.
- Send no full family tree, per-player hearts ledger, arbitrary native NBT or private contract data in an appearance broadcast.
- Validate every client request on the server. A client may request an authorized action; it cannot select an arbitrary proxy UUID and rewrite it.
- Require compatible client/server bridge protocol. An unsupported client must not see floating proxy parts or operate against mismatched identities.

When appearance is temporarily unavailable, show the native actor until a complete valid snapshot arrives. Never hide the only usable body while waiting for appearance data.

## 13. Configuration, diagnostics and user experience

Use the existing COMMON module toggles and SERVER gameplay configuration structure. A client preference may choose rendering fallback; it must not change server identity, collision, permissions or controller behavior.

Provide well-documented options equivalent to:

- Enable Guard Villagers, Recruits and Workers character adapters independently, subject to real upstream dependencies.
- MCA appearance mode per adapter, with native-renderer fallback.
- MCA/social versus native default interaction gesture, while retaining editor/tool precedence.
- Guard recruitment mode: existing MCA-profession behavior or native Guard Villagers actor.
- Eligibility for player-driven enlistment/civilian return and the server’s editor permission policy.
- Optional social pause, command forwarding and explicitly permitted law-enforcement integration.
- Native collision dimensions as the default; any tested alternative clearly labeled.

Keep defaults and old explicit settings migration-safe. Do not automatically enlist every existing MCA villager or grant control of already hired NPCs. Adopt native actor appearance without resetting native state.

Extend diagnostics with commands equivalent to:

```text
/mcamobcompat characters status
/mcamobcompat characters inspect <target>
/mcamobcompat characters adapters
/mcamobcompat characters transitions
/mcamobcompat characters repair <target> <specific-action>
```

Use the existing permission model for operator commands. Report native mod/version, chosen adapter, current mode, character/actor/identity UUIDs, association generation, lifecycle state, available/missing capabilities, controller, appearance revision, and reason for refusal. Preserve existing guard commands as aliases/facades where appropriate.

Repair must be targeted and previewable. Reconnecting a verified pair must not regenerate a character. Keep destructive identity reset separate, explicit, and documented; do not recommend the old broad `guards rebuild` as routine migration or troubleshooting.

Use localized, concrete messages: “This recruit is owned by another player,” “Return this character to civilian life before changing their age,” “Waiting for this character’s MCA identity to load,” or “Native recruitment is unavailable for this mod version.” Avoid exposing reflection jargon during normal play.

A compact role line can make the feature understandable: character name, native role, owner where appropriate, and current activity. Reuse native state for activity labels. Do not invent a parallel job-status simulator.

## 14. Suggested implementation sequence

Each stage must leave the existing guard bridge and unrelated mob compatibility functional. These are sequencing milestones, not permission to ship an incomplete final feature.

1. **Baseline and contracts:** artifact matrix, source audit, identity/authority decisions, hook contracts, fixtures from existing schema 1–3 worlds.
2. **Generalized identity service:** neutral resolution and adapter selection; legacy guard API facade; migrate without changing appearance or controller behavior yet.
3. **Guard appearance and editor:** full MCA world render, poses, correct editor previews, server-validated saves and tracking synchronization.
4. **Native guard enlistment and civilian return:** retain the original MCA person; inventory ownership, journaled transitions and failure handling.
5. **Recruits integration:** adopt existing actors, native acquisition, explicit MCA-person enlistment, commands, ownership, promotion and group continuity.
6. **Workers integration:** select most-specific adapter, cover all supported jobs, native hiring, work assignment and pause/resume without competing MCA chores.
7. **Lifecycle and social integration:** relationships/genetics, damage/death, family-addon consumers, portals, capture policy, state changes and targeted recovery.
8. **Platform parity and release validation:** both branches, actual supported upstream tuples, production jars, dedicated server and two-client scenarios, docs and changelog.

Suggested touchpoints in the current project:

| Existing area | Required work |
| --- | --- |
| `bridge/GuardProxy*`, `GuardIdentity*` | Generalize lifecycle and identity resolution while preserving existing migrations and public guard behavior |
| `event/ConversionGuardHandler` | Native enlistment mode and item-aware conversion protection on both sides |
| `event/GuardBridgeHandler`, `GuardProxyHandler` | Generalized routing, lifecycle, transition-aware removal and correct adapter selection |
| `event/GuardDamageRelayHandler`, `bridge/ProxyEffects` | Single physical outcome and single social consequence; capability-aware policy |
| `client/ClientProxyPairs`, `ProxyRenderHandler` | Revised tracking, MCA world appearance, context-safe portraits/editor rendering |
| `mca/McaBinding` | Version-probed appearance, editor, relationship and lifecycle operations; client-only bindings separated |
| `integration/guardvillagers` | Extend rather than bypass existing native behavior adapters |
| New `integration/recruits`, `integration/workers` | Source-specific native state/control/transition adapters |
| `network/ProxyPairs` | Versioned association, capabilities and appearance synchronization |
| `api`, config, command and localization | Neutral resolution surface, compatible guard facade, diagnostics and discoverability |
| Build guards, GameTests, runtime probes, parity script | Narrow hook exceptions where necessary, actual dependency probes, production validation |

Prefer small reviewable changes. Do not combine this feature with unrelated livestock, mounting or threat-system redesigns.

## 15. Validation and acceptance matrix

Use meaningful automated tests for identity, transactions, permissions and lifecycle. Use actual native mods for behavior tests; mocks cannot prove that a recruit formation or worker job still functions. Compare against an unbridged native control actor to distinguish source-mod behavior from bridge regressions.

| Scenario | Required proof |
| --- | --- |
| MCA + Mob Compatibility only | Launches on client and dedicated server; no optional-class linkage; existing animal/threat features work |
| Guard Villagers only added | Existing bridge works, guards look like MCA people, editor works, combat/patrol/follow restrictions remain native |
| Recruits added | Supported combat/companion types, native hiring/ownership/group UIs and commands work |
| Recruits + Workers added | One identity per worker, correct adapter priority, all supported worker jobs operate |
| All three native mods | No adapter ambiguity, duplicate bridge or competing controls |
| Existing schema 1/2/3 saves | Same proxy UUIDs and family nodes; correct hearts/pending deltas; migration is idempotent |
| MCA person enlists | Exact genes/appearance/relations preserved; original MCA UUID unchanged; native AI actually executes |
| Native actor adopted | UUID, owner, equipment, progression and assignments preserved; appearance initialized only once |
| Editor save | All supported appearance/genetic fields persist; detached previews and galleries show live edits |
| Two editor clients | Conflict policy works; stale edits cannot overwrite new state silently |
| Malformed/unauthorized editor requests | Rejected before mutation; cannot rewrite identity, items, owner or proxy flags |
| Promotion/role replacement | Same character/family identity, correct new actor association and native manager membership |
| Civilian restoration | Same MCA person returns; current possessions/relationships retained; no loot, XP or duplicate resident |
| Full inventories/failed spawn/hire rejection | No item loss, duplicate payment, stranded hidden civilian or extra proxy |
| Save/restart interruption | Journal recovery reconciles tested partial states without speculative duplication/refunds |
| Chunk unload/late load | Missing partner never triggers destructive timeout repair |
| Portal/dimension travel | Single association follows correctly; no cross-dimension UI access or orphan clone |
| Native combat | Melee, shield use, bows/crossbows, mounted combat and relevant native weapon integration match controls |
| Native work | Resource collection, deposit, work area, courier/merchant activity and safe resumption match controls |
| Damage/totem/cancelled death | Physical result once; no premature family death or duplicate penalty |
| Confirmed death | One loot/XP/corpse outcome and one MCA death outcome |
| Genetics/family action | Actual MCA inheritance path used; parent IDs correct; child is an ordinary MCA child |
| MCA addons | Quests, portraits, dialogue/speech, reputation and Crime effects address the correct person/body once |
| Faction conflict | Native diplomacy survives; enemy MCA-faced recruits are not made universally friendly |
| Resource/renderer reload | Appearance restored, no stale textures/poses/duplicate layers; native fallback remains usable |
| Module disabled/re-enabled | Identities retained, proxies remain safe, native controls behave as documented |
| Unknown upstream build | Explicit unsupported capability; no unsafe conversion or false “fully supported” status |

Additional required checks:

- Test every admitted MCA package-layout family with binding probes. Add actual runtime coverage for any version whose rendering/editor behavior differs; server reflection probes alone do not verify a client renderer.
- Validate an ordinary invisible MCA villager remains distinct from a registered hidden identity; no heuristic hiding by entity flags.
- Test native actor runtime-ID reuse and delayed pairing packets with multiple adjacent characters.
- Verify no extra item consumption, regeneration, pressure-plate triggers, projectile collisions, native chunk tickets, family records, or population counts from proxies.
- Check supported config changes and `/reload` do not reroll names, traits, genes or clothing.
- Preserve existing mount refusal, hunting protection, creeper-awareness and guard defense tests.
- Profile representative populations, for example 20, 100 and 250 active actors. Record hardware, server tick cost, client frame cost, packet bytes and cache sizes against native-only and existing-bridge baselines. Choose a measured budget; do not invent a universal millisecond guarantee.
- Inspect for growth in caches after repeated tracking, portals, disconnects and resource reloads. No per-frame reflection lookups, unbounded world scans or full-entity NBT serialization in the hot path.

### Build and production gates

Follow current repository tasks, including `compileJava`, `check`, `build`, production jar verification, binding probes, GameTests and runtime probes. Preserve its requirement to inspect GameTest results, not just a clean process exit. Add dev-only Recruits/Workers artifacts through reproducible documented configuration; do not bundle their jars.

Run equivalent checks on NeoForge and `scripts/parity.py`. Update `parity-manifest.txt` only after both branches’ intended changes are present and reviewed. A platform-specific adapter marked unavailable is honest only when the corresponding upstream platform artifact is unavailable/unverified; it is not a substitute for implementing a verified target.

Use at least one real dedicated server with two clients to verify editor changes, packets, interaction routing, inventory consistency and native behavior. A successful compile or a single-player screenshot is insufficient evidence.

## 16. Required deliverables and completion standard

Deliver:

1. Working integrations in MCA Mob Compatibility, including the actual MCA editor and genetics with native AI.
2. A compatibility matrix with exact tested artifacts and per-capability status.
3. Migration and interrupted-transition recovery with fixtures and recorded results.
4. Automated regression tests and manual/production scenario results, clearly separating passed, failed, unrun and unavailable cases.
5. Updated README, configuration documentation, command help, datapack/API documentation, localization, architecture decision, validation record and parity manifest.
6. A player-facing guide: how to edit a native NPC, hire/enlist an existing MCA person, access native controls, preserve identity through promotion, and return to civilian life.
7. A concise changelog and implementation report listing remaining concrete limitations. Do not label a missing required target capability as complete.

Acceptance demonstration: start with a named MCA adult with deliberately edited genes, clothing, inventory, hearts and a family link. Enlist that person into a native role. Demonstrate the native behavior and native management interface. Edit their appearance while employed and observe it on a second client. Exercise a supported promotion/role change, unload/reload, restart, and return the person to civilian life. Verify the original MCA UUID, genes, family link, hearts and current possessions throughout. Repeat the relevant journey for Guard Villagers, Recruits and Workers, plus adoption of pre-existing native NPCs.

The finished feature should make the original request straightforward to explain: **“These are the real guards, recruits and workers from their own mods, with a persistent MCA person behind each of them.”**

## Source references

These are primary-source inspection links. Recheck current source and exact release jars before implementation; the commits below record what informed this handoff.

- **[S1] Mob Compatibility baseline:** [repository at inspected commit](https://github.com/otectus/MCAMobCompatibility/tree/09efcacf7cadd60805b11f31ba2faeace1b9c7f0), [gradle.properties](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/gradle.properties), [CLAUDE.md](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/CLAUDE.md).
- **[S2] NeoForge baseline:** [gradle.properties](https://github.com/otectus/MCAMobCompatibility/blob/c0ab21e889ea0db989c827407711a0c9a3991176/gradle.properties).
- **[S3] Existing identity architecture:** [GuardProxy.java](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/src/main/java/dev/otectus/mcamobcompat/bridge/GuardProxy.java), [GuardProxySync.java](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/src/main/java/dev/otectus/mcamobcompat/bridge/GuardProxySync.java), [GuardIdentityData.java](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/src/main/java/dev/otectus/mcamobcompat/bridge/GuardIdentityData.java).
- **[S4] Existing conversion protection:** [ConversionGuardHandler.java](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/src/main/java/dev/otectus/mcamobcompat/event/ConversionGuardHandler.java).
- **[S5] Existing proxy rendering:** [ProxyRenderHandler.java](https://github.com/otectus/MCAMobCompatibility/blob/09efcacf7cadd60805b11f31ba2faeace1b9c7f0/src/main/java/dev/otectus/mcamobcompat/client/ProxyRenderHandler.java).
- **[S6] MCA 1.20.1 editor source:** [VillagerEditorScreen.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/client/gui/VillagerEditorScreen.java), [VillagerEditorItem.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/item/VillagerEditorItem.java), [VillagerEditorSyncRequest.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/network/c2s/VillagerEditorSyncRequest.java).
- **[S7] MCA genetics and rendering:** [Genetics.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/entity/ai/Genetics.java), [VillagerLike.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/entity/VillagerLike.java), [VillagerEntityMCARenderer.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/client/render/VillagerEntityMCARenderer.java).
- **[S8] Recruits source:** [VillagerEvents.java](https://github.com/talhanation/recruits/blob/44f60bdb4a2b4676a31674f31c081f195873e79c/src/main/java/com/talhanation/recruits/VillagerEvents.java), [RecruitEvents.java](https://github.com/talhanation/recruits/blob/44f60bdb4a2b4676a31674f31c081f195873e79c/src/main/java/com/talhanation/recruits/RecruitEvents.java), [AbstractRecruitEntity.java](https://github.com/talhanation/recruits/blob/44f60bdb4a2b4676a31674f31c081f195873e79c/src/main/java/com/talhanation/recruits/entities/AbstractRecruitEntity.java), [mods.toml](https://github.com/talhanation/recruits/blob/44f60bdb4a2b4676a31674f31c081f195873e79c/src/main/resources/META-INF/mods.toml).
- **[S9] Workers source and release:** [mods.toml](https://github.com/talhanation/workers/blob/29d26e1df6475fc8d043dc5d455f67b2fd1e9982/src/main/resources/META-INF/mods.toml), [AbstractWorkerEntity.java](https://github.com/talhanation/workers/blob/29d26e1df6475fc8d043dc5d455f67b2fd1e9982/src/main/java/com/talhanation/workers/entities/AbstractWorkerEntity.java), [VillagerEvents.java](https://github.com/talhanation/workers/blob/29d26e1df6475fc8d043dc5d455f67b2fd1e9982/src/main/java/com/talhanation/workers/VillagerEvents.java), [ModEntityTypes.java](https://github.com/talhanation/workers/blob/29d26e1df6475fc8d043dc5d455f67b2fd1e9982/src/main/java/com/talhanation/workers/init/ModEntityTypes.java), [Workers 2.0.3 release](https://modrinth.com/mod/villager-workers/version/2.0.3).
- **[S10] Guard Villagers 1.20.1 source:** [HandlerEvents.java](https://github.com/seymourimadeit/guardvillagers/blob/b067d5340fdae162e933fd77bec0a2bc330eef05/src/main/java/tallestegg/guardvillagers/HandlerEvents.java), [Guard.java](https://github.com/seymourimadeit/guardvillagers/blob/b067d5340fdae162e933fd77bec0a2bc330eef05/src/main/java/tallestegg/guardvillagers/entities/Guard.java), [GuardRenderer.java](https://github.com/seymourimadeit/guardvillagers/blob/b067d5340fdae162e933fd77bec0a2bc330eef05/src/main/java/tallestegg/guardvillagers/client/renderer/GuardRenderer.java).
- **[S11] MCA lifecycle:** [VillagerEntityMCA.java](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/entity/VillagerEntityMCA.java).

Keep upstream mods/assets as dependencies. Public source visibility does not authorize bundling or copying their implementations. This design relies on native behavior and narrowly scoped interoperability code, not redistributed AI implementations.
