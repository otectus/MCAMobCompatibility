# MCA: Mob Compatibility - NeoForge 1.21.1 Mod

## Orientation
Registration inventory and package layout live in `MODMAP.md` (hand-maintained here:
`.mcmod-tools/modmap.py` understands only Forge build files and reports this project's loader and
mappings as unknown). Read that instead of crawling `src/`.
`docs/PORTING.md` records how the NeoForge build differs from the Forge one, and why.

This mod registers **no** blocks, items, or entities. It is a classification/compat layer that
teaches MCA Reborn to recognise modded animals, mounts, livestock and guards, and makes Guard
Villagers' guards MCA characters: the native NPC is the body (the *actor*), a real hidden MCA
villager is the person (the *identity*). The Villager Recruits and Villager Workers adapters exist
here in parity with the Forge build but report themselves unavailable: neither mod has a NeoForge
1.21.1 build. Design and contracts: `docs/NATIVE_CHARACTER_COMPATIBILITY.md`.

## Quick Reference
- **Mod ID**: `mcamobcompat`
- **Package**: `dev.otectus.mcamobcompat`
- **MC / Loader / Java**: see `gradle.properties`; Java 21 toolchain and ModDevGradle, set in `build.gradle`
- **Mappings**: official

Version numbers live only in `gradle.properties`; `processResources` expands them into
`neoforge.mods.toml`. Never hard-code one elsewhere, this file included.

## Build
- `./gradlew compileJava` - compile only, the normal iteration loop
- `./gradlew build` - full build; runs `checkJarContents` and `verifyProductionJar` (no reobfuscation on
  NeoForge), output `build/libs/mcamobcompat-neoforge-<version>+1.21.1.jar`
- `./gradlew check` - JUnit tests, including `NoOptionalModStaticLinkTest`
- `./gradlew installMod -PmodsDir=<dir>` - copy the verified jar into a mods folder
  (falls back to `$MCAMOBCOMPAT_MODS_DIR`)
- `./gradlew runGameTestServer` - GameTests on a real server with MCA 7.7 and Guard Villagers;
  `-PwithSwem`, `-PwithIcy`, `-PwithAlex`, `-PwithGolems` add those mods and their suites, and
  `-PwithAlexContinued` / `-PwithIcy116` swap in Alex's Mobs Continued / Icy's Better Horses 1.1.6.
  `-PwithCrime -PcrimeJar=<path>` loads a built NeoForge MCA: Crime jar and runs the
  `mcamobcompat_crime` suite (MCA: Crime needs GeckoLib, which `-PwithCrime` adds). `-PwithRecruits`
  and `-PwithWorkers` are refused: those suites run on the Forge build only. Fails unless
  `run-gametest/logs/latest.log` reports every required test passed, because the server exits
  cleanly even when mod loading fails.
- `./gradlew runtimeProbeJar` - the opt-in production validation driver, never shipped
- `python3 scripts/parity.py` - checks this repository against the Forge one (see below)

## Structure
```
.api            public extension surface: MobCompatibilityAPI, ClassificationProvider, MobRole, GuardIdentity
.api.event      NeoForge-bus events for the bridge: GuardInteractEvent, GuardIdentityCreatedEvent, GuardHeartsChangedEvent
.bridge         character store and identity lifecycle: GuardIdentityData (schema 4 records, journal), GuardIdentityStore,
                GuardIdentityView, GuardNames, GuardInteractions (the crouch gesture), GuardProxy (hidden MCA villager
                lifecycle, hide/reveal), GuardProxySync (per-tick mirroring), ProxyEffects
.character      the adapter layer, loader-neutral: NativeCharacterAdapter(s) (selection order Workers, Recruits,
                Guard Villagers), CharacterTransitions (enlist, civilian return, promotion bracket, recovery),
                TransitionRecovery (pure), CharacterCommandRouter (MCA orders to native orders), CharacterSync,
                McaEditorBridge + EditorPatchPolicy (editor sessions and payload rebuild), RecruitsConversions,
                the state/origin/stage/capability enums and the config enums
.classification caches, provider registry, tag-driven classifier, TamedMountProvider, OwnedAnimalProvider
.client         Dist.CLIENT only: ClientProxyPairs (the client's exact associations), CharacterRenderHandler
                (draws an actor as its MCA person; cancels a hidden identity's render; portraits),
                CharacterRenderViews (detached MCA render views, bounded cache), NativePoseAdapter (actor pose onto a view)
.command        /mcamobcompat command; CharacterCommands (`characters` for operators, `character` for players)
.hook           Hooks (contracts, armed state), HookPlugin (skips absent targets, counts injected call sites)
.hook.mixin     the optional hooks: McaEditorSaveHook, RecruitsConversionHook
.event          NeoForge-bus handlers: reload, conversion guard and recruit, guard defense and GuardAlerts, chore safety,
                GuardBridgeHandler (the interaction router), GuardProxyHandler (proxy lifecycle and redirects),
                GuardDamageRelayHandler (hurt/death relay), ProxySpawnHandler, ProxyShapeHandler, MountPolicyHandler,
                ServerLifecycleHandler (clears static state on server stop)
.integration    one IntegrationModule subclass per optional mod, in a per-mod subpackage; guardvillagers also holds
                GuardReflection, GuardVariants and GuardVillagersAdapter, icysbetterhorses holds IcyReflection,
                recruits holds RecruitsBinding, RecruitsAdapter and RecruitsEvents, workers holds WorkersBinding,
                WorkersAdapter and WorkersChunkTickets (a no-op here); PlatformArtifacts says which native mods this
                loader verifies (here: Guard Villagers only)
.mca            McaEntities (knows MCA entity ids), McaBinding (reflective layer), McaMemories (MCA memories by registry id)
.mount          MountPolicy and its decision, refusal and policy types
.network        ProxyPairs: the mcamobcompat:proxy_pair payload (registrar version "2") and its one message
.threat         ThreatPolicy (pure decision), ThreatAssessor, ThreatHandler (creeper awareness)
.util           ConfigAccess, ModIds
```
The `client` package controls rendering only: hidden identities, and actors drawn as their MCA
persons. Its one option is the CLIENT spec's `[rendering] mcaAppearance`. GameTests live in a separate
`gameTest` source set and test mod, `mcamobcompat_tests`, one `@GameTestHolder` namespace per optional
mod (`CharacterGameTests` is in the core namespace; the `mcamobcompat_recruits` and
`mcamobcompat_workers` suites exist here for parity and are never enabled).

## Key Dependencies
- MCA Reborn and every optional mod - **runtime only**, for dev runs and GameTests. Never compile
  deps. Pinned in `gradle.properties` (Modrinth version ids; CurseForge file ids for SWEM and Player
  Animator) and fetched on demand; no local `libs/` is needed and nothing is remapped, since NeoForge
  1.21.1 runs on official names.

## Conventions
- No `DeferredRegister`. Mod bus is used only for `FMLCommonSetupEvent`, the three `ModConfigEvent`s
  and `RegisterPayloadHandlersEvent`, via manual `addListener` calls in `McaMobCompat`'s injected
  constructor `(IEventBus, ModContainer)`.
- NeoForge-bus handlers are `@EventBusSubscriber` classes with static `@SubscribeEvent` methods.
- Config is `ModConfigSpec` with three specs in `McaMobCompatConfig`: COMMON (module toggles, now
  including `recruits` and `workers`), SERVER (all gameplay tuning, so it is world-owned) and CLIENT
  (`[rendering] mcaAppearance` only; it may only ever fall back to a native renderer). Add gameplay
  options to SERVER. Current `[guard_villagers]` options: `blockGuardConversion`, `protectMcaVillagers`,
  `preventGuardFriendlyFire`, `retaliateAgainstPlayers`, `guardHelpRange`, `maxGuardsAlerted`,
  `treatGuardsAsMcaGuards` (bridges a guard to an MCA identity at all), `guardMcaInteraction`
  (plain click opens MCA's own screen through the hidden proxy; crouch opens Guard Villagers'
  inventory; off means stock gestures and no proxy), `recruitMcaVillagersAsGuards` (crouch-click
  with a weapon recruits an MCA villager instead of blocking the conversion), `guardRecruitmentMode`
  (`NATIVE_GUARD`: enlist as a real guard, the default; `MCA_PROFESSION`: the 0.2 profession in place),
  `guardAppearance`, `guardShowNameTag`, `followAddonTargets` (an attack target an addon set on the
  hidden proxy is pushed up to the guard; how MCA: Crime's pursuit reaches a bridged guard).
  `[recruits]`: `mcaCharacters`, `mcaInteraction`, `appearance`, `villagerConversion`,
  `lawEnforcement`. `[workers]`: `mcaCharacters`, `mcaInteraction`, `appearance` (both sections exist
  for parity and do nothing here). `[characters]`: `editorPermission`, `editorAdminPermissionLevel`,
  `editorSessionMinutes`, `playerEnlistment`, `playerCivilianReturn`, `forwardMcaCommands`,
  `socialPause`. Other sections: `[chores]` (hunting restraint, five options), `[threats]` (creeper
  awareness, five options), `[mounts] npcRidingPolicy`, and the per-mod recognitions `[swem]`,
  `[genetic_animals]`, `[dragn_livestock]`, `[icys_better_horses]`, `[alexs_mobs]`.
- GameTests never call `ModConfigSpec.ConfigValue.set()`: it writes the file, and the file
  watcher's reload races later reads. They use `ConfigAccess.override(value, replacement, body)`,
  a thread-local override every `ConfigAccess` read honours.
- Classification data is datapack tags under `data/mcamobcompat/tags/entity_type/` (singular on
  1.21.1); see `DATAPACK.md`. The tag files are byte-identical to the Forge build's.
  Reload is vanilla `TagsUpdatedEvent`, consumed by `ClassificationReloadHandler` to invalidate caches.
  There is no custom reload listener.
- This mod is required on both sides: the `mcamobcompat:proxy_pair` payload is registered as
  required, so a client without it is refused at login.
- Character records are stored in `SavedData` in the overworld's `DimensionDataStorage` under
  the key `mcamobcompat_guard_identities` (schema version 4, migrated automatically from 1, 2 and 3 as
  `MIGRATED_GUARD_BRIDGE` records, with each version-2 record kept under `LegacyV2`). Records are keyed
  by actor UUID and indexed by identity UUID; the identity's UUID is the canonical character UUID.
  Every change of body is a journaled transition (`PREPARED` → `ACTOR_READY` →
  `ASSOCIATION_COMMITTED` → `CLEANED`, or `ROLLED_BACK`), stamped on both entities
  (`mcamobcompat:tx`, `mcamobcompat:generation`); recovery rolls forward, never re-rolls, and never
  refunds or restores inventories on its own. Hearts are two ledgers: `Hearts`, the last MCA
  snapshot, and `PendingHearts`, deltas not yet applied to MCA; never add a delta to both. A newer
  schema loads read-only; conflicting links are quarantined, never relinked.
  The store is world-scoped (one per world, not per dimension) because a guard UUID is global and
  must survive portal travel.
- **Hidden-proxy architecture.** Every bridged actor whose adapter opens MCA's screen is paired
  with a real, hidden `mca:*_villager` (profession `mca:guard` for a guard, adopted or enlisted):
  invisible, `NoAI`, silent, weightless, unable to pick up loot, and pinned to the actor's position
  every tick by `GuardProxySync`. The actor keeps its own combat AI, hitbox and inventory; the proxy carries MCA's name, personality,
  memories/hearts, family tree and residency, and receives every MCA interaction. The link is
  persistent-data on both sides — `mcamobcompat:proxy` on the guard, `mcamobcompat:guard` on the
  proxy — plus a scoreboard tag `mcamobcompat_proxy` on every proxy for operator selectors. Proxies
  are created only through `GuardProxy.create`, from the guard's own tick (with a grace period),
  never on `EntityJoinLevelEvent`, to avoid spawning a duplicate on chunk load. Creation goes
  through `EventHooks.finalizeMobSpawn` and is rolled back, family-tree node included, if
  anything rejects it. **Nothing deletes a proxy on a timer**: an unresolved partner may only be
  unloaded. The proxy's hitbox is nested inside the guard's (`ProxyShapeHandler`, which carries the eye height over
  explicitly, since on 1.21.1 it is part of `EntityDimensions`), so hits land on the guard natively; guard death is relayed only once confirmed at the end of the server tick.
- MCA's brain memories are reached by registry id through vanilla `Brain` (`McaMemories`:
  `mca:player_following_memory`, `mca:nearest_guard_enemy`), never by reflection. Check
  `McaMemories.registers` before any access: `Brain.getMemory` throws for an unregistered memory.
- Creeper awareness writes only memories MCA already reads (`NEAREST_HOSTILE`, `WALK_TARGET`,
  `mca:nearest_guard_enemy`); it adds no goal or task, and never touches golems, creepers or gamerules.
- Mount refusals go through `MountPolicy`: the `player_only_mounts` tag plus `MountRefusal`s that
  integrations register. `MountPolicyHandler` cancels `EntityMountEvent` under `REFUSE`.
- Guard Villagers has no API. Its guard is driven only by reflection **by method name** on the
  guard's runtime class, in `GuardReflection` (`setFollowing`, `setPatrolling`, `setPatrolPos`,
  `setOwnerId`, and `getGuardVariant` on 1.20.1 or `getVariant` on 1.21.1, each bound by its whole
  signature) — never by naming its class, so both `checkJarContents` and
  `NoOptionalModStaticLinkTest` stay green. This is safe only because those are Guard Villagers' own
  member names, not ones inherited from Minecraft (which are SRG-mangled on Forge).
- The client knows exactly which entity is a proxy: the server sends each pair over the
  `mcamobcompat:proxy_pair` channel (`network/ProxyPairs`) on start tracking, on link and removal,
  and when runtime ids change. `ClientProxyPairs` keeps a bounded per-level map keyed by UUID and
  runtime id. On the client `GuardProxy.isProxy` and `nearbyGuardOf` answer from that map; there is
  no flag heuristic. Protocol 2 also carries the character UUID, dimension, association generation
  (an older message never replaces a newer one), appearance revision and capabilities.
  `CharacterRenderHandler` cancels a paired proxy's render always; between the level stages
  `AFTER_SKY` and `AFTER_ENTITIES` an actor drawn as MCA is drawn through MCA's own renderer on a
  detached render view in the actor's pose, and outside the world, with a screen open, a portrait of
  the identity draws the person (MCAConversations' dialogue frame, MCAQuests' quest menu). A failing
  MCA renderer falls back to the native one per actor type, logged once. `RenderFrameEvent.Pre/Post`
  reset the in-world flag each frame.
- Guard Villagers' conversion runs on **both** logical sides, so `ConversionGuardHandler` must cancel
  before any client-side early return. Moving the client return above the cancel makes the
  recruiting player's villager invisible -- which is exactly what 2.0 shipped, and what the runtime
  probe's recruit scenario reproduces.
- Forge-to-NeoForge event mapping used throughout: `LivingTickEvent` -> `EntityTickEvent.Pre`;
  `LivingAttackEvent` -> `LivingIncomingDamageEvent`; Forge's `LivingDamageEvent` -> `LivingDamageEvent.Post`
  to observe the final amount, `.Pre` to set it; `LivingHurtEvent` -> `LivingDamageEvent.Pre`;
  `ServerTickEvent` END -> `ServerTickEvent.Post`; `ProjectileImpactEvent` `SKIP_ENTITY` -> `setCanceled(true)`.
- **No access transformers, and Mixin only for the optional hooks** in `hook.mixin`: `@Pseudo`,
  string-targeted, `remap = false`, `require = 0`, in `mcamobcompat.mixins.json` (`"required": false`,
  no refmap, `JAVA_21`), declared in `neoforge.mods.toml` under `[[mixins]]`. `HookPlugin` skips a
  target the owning jar lacks (through NeoForge's `LoadingModList`) and counts injected call sites;
  every feature that depends on a hook checks `Hooks.armed` and refuses safely when it is not. MCA's
  NeoForge editor request is a record handled by `handleServer(ServerPlayer)`; the same hook class
  names that and Forge's `receive`, so it stays byte-identical. NeoForge loads MCA's payload classes
  late, so setup loads that one class (`McaBinding.loadEditorRequestClass`) before reporting hooks.
- **Never import a class from MCA or an optional mod.** Detect with `ModList.get().isLoaded(...)`
  during `FMLCommonSetupEvent.enqueueWork`; refer to their content by registry-id string or `TagKey`.
  `checkJarContents` and `NoOptionalModStaticLinkTest` fail the build if this rule is broken.
  **Exception:** five classes may name a foreign root as dot-form string literals, for reflection or as
  a Mixin target, each for its own roots only: `mca/McaBinding` and `hook/mixin/McaEditorSaveHook`
  (MCA's three roots), `integration/recruits/RecruitsBinding` and `hook/mixin/RecruitsConversionHook`
  (`com.talhanation.recruits`), and `integration/workers/WorkersBinding` (`com.talhanation.workers`).
  Both build-time guards hold the same allowlist, matched exactly by class file name so it cannot
  widen silently, and fail on an allowlisted class that disappears. The no-static-import rule itself
  is unchanged.
  The bridge never references a Guard Villagers class; it detects the module and config option via
  `ModIntegrations` and reaches guard-only state through `GuardReflection` by method name. Icy's
  Better Horses is reached the same way, through `IcyReflection` and its own `bh_*` member names.
  Alex's Mobs needs no reflection at all.

## Parity with the Forge build
This mod ships from two repositories: this NeoForge 1.21.1 one and the Forge 1.20.1 original at
`../../MCAMobCompatibility` (branch `main`). They are one mod: same
version, features, config keys and defaults, commands, tags, lang keys, API and tests, differing only
where the loader or Minecraft version forces it.
- Make every behaviour change on **both** sides, then run `python3 scripts/parity.py` (identical in
  both repositories). It fails when a file exists on one side only, when a loader-neutral file is no
  longer byte-identical, or when a loader-specific file changed on one side only. After porting a
  change to both, `python3 scripts/parity.py --write` records it in `parity-manifest.txt` (identical
  in both).
- Keep files loader-neutral wherever possible: vanilla `BuiltInRegistries`/`Registries` rather than
  loader registries, `McaMobCompat.location(ns, path)` and `McaMobCompat.id(path)` rather than a
  `ResourceLocation` constructor, and `McaMobCompat.post(event)` to post this mod's events.
- `src/runtimeProbe` is an opt-in production validation driver (`runtimeProbeJar`), never in the
  release jar; `scripts/validation/launch_client.py` launches an installer-created client for it and
  `scripts/validation/run_scenario.py` runs one production scenario (two clients, Guard Villagers
  only, single player, minimal, missing mod); both are the same scripts on both loaders.
- `integration/PlatformArtifacts` is deliberately a pair: it lists the native mods each loader has an
  audited build of. Villager Recruits and Villager Workers have no NeoForge 1.21.1 build, so there
  their adapters report themselves unavailable; their code, config keys and tests still exist on both
  sides, and `-PwithRecruits`/`-PwithWorkers` are refused on NeoForge.

## Terminology
- `MobRole` is the semantic role enum; named to avoid colliding with vanilla `MobCategory`
  (the spawn-group enum). Its constants double as the datapack tag names.
