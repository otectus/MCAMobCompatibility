# Changelog

## 0.2.2 (unreleased)

Bridged guards under the sibling add-ons' law. Every MCA add-on acts on the villager it can see, and
for a bridged Guard Villagers guard that is the hidden MCA proxy; three gaps in the pairing meant the
guard itself never noticed.

- **Targets travel both ways.** `GuardProxySync` mirrored the guard's target onto the proxy and
  nothing back, so an order another add-on gave the proxy (MCA: Crime's `setTarget` on the responder
  it picked to pursue a wanted player) lasted one tick and the guard never moved. Whichever side
  changed since the last agreed target now wins, the guard winning a tie; a player target is pushed up
  only under the new `[guard_villagers] followAddonTargets` (default on), and an MCA villager never
  while `preventGuardFriendlyFire` is on, so the friendly-fire handler and this cannot churn.
- **A proxy has a home village.** MCA's own `Residency.seekHome()` is asked at creation and retried
  every ten seconds while the guard is outside every village, so MCA: Crime (which charges a deed to
  the victim's home village) and MCA: Reputation name one community for an assault on a bridged guard.
  Three new binding lines (`getResidency`, `seekHome`, `getHomeVillage`) appear in the diagnostics.
- **Crime's effects reach the guard.** `mcacrime:restrained` and `mcacrime:sand_blinded`, the two
  effects MCA: Crime registers, are copied from the proxy onto the guard once a second and removed with
  it, and a restrained proxy holds its guard in place and clears its follow and patrol orders. Reached
  by registry id only; an install without MCA: Crime does nothing. Custody itself stays on the proxy.
  The server log says once at start which of the two the installed MCA: Crime registers, and warns if
  it registers only one. (An earlier draft of this release also listed a `wounded` effect, which no
  MCA: Crime build registers; it was never mirrored and is gone from the list.)
- **The MCA binding is probed against every supported MCA build.** `McaBindingProbeTest` replays
  `McaBinding` against each build in `mca_probe_versions`, each in its own class loader: on Forge the
  six-build fleet every family add-on probes, on NeoForge five 1.21.1 builds from 7.7.0 to
  7.7.36-beta.3. A member MCA renames in another supported release now fails `check` instead of
  quietly disabling a feature on a server. The family-tree members that spawn rollback reflects at call
  time (`father`, `mother`, `children`) are checked too.
- `GuardProxySyncTargetTest` pins the target-agreement rules, `ProxyEffectsContractTest` pins the
  mirrored effect ids to the ones MCA: Crime registers, a `mcamobcompat_crime` GameTest suite
  (`-PwithCrime -PcrimeJar=<path>`) checks against a real MCA: Crime jar that both effects on a proxy
  reach its guard and that `restrained` leaves with the proxy's, and the Forge/NeoForge parity
  manifest is re-recorded with the new files (`python3 scripts/parity.py --write`).

## 0.2.1+1.21.1

Recruiting that no longer makes a villager vanish, villagers that see a creeper coming, mounts that
refuse riders they cannot carry, and support for Icy's Better Horses, Alex's Mobs and Golems Kill
Creepers.

Released together with the Forge 1.20.1 build, with the same features, configuration, commands and
datapack tags on both, and one version number: this build, released as 2.0, is renumbered 0.2.1 to
follow the Forge build's 0.2.0. `scripts/parity.py` holds the two repositories to that.

### Upgrade notes

- **Install it on the server and on every client, at the same version**, as before: the required
  `mcamobcompat:proxy_pair` payload (protocol `1`) is unchanged, and a client without it is refused.
- **No save migration.** The guard identity store stays at schema version 3.
- **The version number goes down, from 2.0 to 0.2.1, and 0.2.1 is the newer build.** The jar is
  `mcamobcompat-neoforge-0.2.1+1.21.1.jar`. Remove the 2.0 jar from the mods folder.
- **Wider dependency ranges.** MCA is accepted across 7.7 (`[7.7,8)`) rather than exactly
  `7.7.36-beta.3+1.21.1`, and every optional mod at any version. 2.0 required Guard Villagers to be
  exactly 2.4.12 and refused to load beside any other release. Members an untested release lacks are
  skipped one feature at a time, with one warning each.
- **MCA's "Ride Horse" order no longer seats a villager on a SWEM horse**, nor on an Icy's Better
  Horses horse that belongs to a player. On a SWEM horse the villager could not steer, so the horse
  ignored MCA's orders. Set `[mounts] npcRidingPolicy = "ALLOW_INERT"` to put villagers back on SWEM horses anyway.
- **New config keys arrive with their defaults**: `[threats]`, `[mounts]`, `[icys_better_horses]` and
  `[alexs_mobs]` in `mcamobcompat-server.toml`, and `icysBetterHorses` and `alexsMobs` under
  `[modules]` in `mcamobcompat-common.toml`.
- **MCA's Follow order is handed to Guard Villagers exactly, and Guard Villagers decides.** Its own
  `followHero` option, on by default, lets a guard follow only a player with Hero of the Village.

### Fixed

- **Recruiting an MCA villager no longer makes it vanish for the player who recruited it.** Guard
  Villagers runs its conversion, including discarding the villager, on the client as well as the
  server. 2.0 blocked it on the server but returned early on the client before cancelling, so the
  recruiting player's own client discarded the villager while the server, and every other player,
  kept it. The block now cancels on both sides before any side check. The runtime probe reproduces
  the fault on the 2.0 jar (the villager is removed as `DISCARDED` on the recruiting client only) and
  shows 0.2.1 keeping it present and rendered on both clients (`docs/VALIDATION.md`).
- **A recruit that cannot happen says why.** A child, a villager already holding something, an
  existing guard, and a missing `mca:guard` profession each get their own message, and nothing
  changes. A recruit rebuilds the villager's MCA brain, so the new guard behaves as one at once.

### Added

- **Creeper awareness.** MCA on its own only notices a creeper lit with flint and steel. MCA villagers
  now see the mobs in the new `mcamobcompat:npc_threats` tag, which ships with `minecraft:creeper`:
  civilians run from one that is hunting them or whose fuse is burning, and MCA guards and archers
  fight one that is not about to explode. A burning fuse close by makes everyone run, guards included,
  and a charged creeper counts from twice as far. All of it goes through MCA's own panic and guard
  behaviour, by writing the brain memories MCA itself reads; no creeper, golem or gamerule is changed,
  so Golems Kill Creepers works exactly as before. New `[threats]` options: `creeperAwareness`,
  `creeperAlertRange`, `swellingFleeRange`, `guardsEngageCreepers` and `dismountToFlee`.
- **A mount policy.** A mount in the new `mcamobcompat:player_only_mounts` tag, or one an integration
  says is not a villager's to take, refuses an MCA villager, and the player who gave the order is told
  why. `[mounts] npcRidingPolicy` chooses between `REFUSE`, the default, and `ALLOW_INERT`.
- **Icy's Better Horses.** Release 2.0's fifteen breeds (`icys-better-horses:*_horse`, which extend
  vanilla `Horse`) are horses and mounts, through Icy's own `#icys-better-horses:breed_horses` tag.
  Icy's horse owners count as owners, through `MobCompatibilityAPI.getOwner` and the hunting chore's
  protection, and a horse that belongs to a player refuses a villager rider. Icy's keeps other
  players off an owned horse but does not check villagers, so this mod applies the same rule to them.
  Icy 2.0 itself lets only players ride its horses; release 1.1.6 is supported too, and there a
  hitched horse is refused as well. Nothing of Icy's is written. New option
  `[icys_better_horses] recognizeOwnership`.
- **Alex's Mobs.** Its elephant, grizzly bear, komodo dragon and tusklin are mounts, and every tamed
  animal of any mod with an owner, horses aside, is now a `pet`, so the hunting chore spares it. Its six
  player-steered mounts refuse a villager that anything tries to seat on them; MCA's own order never
  picks them. Both builds for NeoForge 1.21.1 -- Raguto's unofficial port 1.22.17 and Alex's Mobs
  Continued 2.2.2 -- were audited type by type from their jars and match the 1.20.1 audit; see
  `docs/ALEXS_MOBS_AUDIT.md`. New option `[alexs_mobs] recognizeAnimals`.
- **Diagnostics.** `/mcamobcompat mounts inspect` shows a mount's roles, owner, saddle, who steers
  it, whether a villager could, and the policy's decision. `/mcamobcompat threats inspect` shows every
  threat an MCA villager can see, its response to each, and -- with Golems Kill Creepers installed --
  that mod's two gamerules.
- **MCA's brain memories are reached by registry id** (`mca:player_following_memory`,
  `mca:nearest_guard_enemy`), through vanilla `Brain`, with no reflection at all.

### Changed: brought in from the Forge build

The Forge build moved on after the port; these now apply to both builds.

- **The proxy's collision box is nested inside its guard's.** Arrows, tridents, snowballs, melee and a
  player's pick all meet the guard first, with vanilla's own hit code, potion effects and piercing
  included. This replaces 2.0's per-projectile removal of proxy bounds.
- **A proxy that is really gone releases its link**, so an operator's `/kill` or a relayed death lets
  its guard get a new one; a guard key the store has already released no longer blocks creation.
- **A guard removed as killed without a death event is still settled**, with the last damage it took.
- **Damage that bypasses invulnerability reaches a proxy**, which is how an operator removes one.
- **One click is routed once per tick**, whichever of the two interaction packets carries it.
- **The client end of the pairing lives in `client/ClientProxyPairs`**; the payload is sent again when a
  pair's runtime ids change, and every piece of per-server state is cleared when a server stops.
- **Guards are alerted by one bounded service** shared by village defence and creeper awareness.

### Build and verification

- **GameTests**: the core suite (29 tests) runs against the real MCA 7.7 and Guard Villagers on every
  `runGameTestServer`; `-PwithSwem`, `-PwithIcy`, `-PwithAlex` and `-PwithGolems` add those mods and
  their suites, `-PwithAlexContinued` and `-PwithIcy116` swap in the other builds. The task now fails
  unless the server reports every required test passed.
- **Both jar gates** cover the new optional mods and tags.
- **The runtime probe** now recruits a villager on real clients and checks it from the recruiting
  client, the server and a second client watching. `scripts/validation/launch_client.py` serves both
  loaders' installer profiles.
- **`scripts/parity.py`** checks this repository against the Forge one.
- `docs/VALIDATION.md` records what was run and what it showed; `docs/DEPENDENCIES.json` records every
  jar tested, with its SHA-256.

## 2.0+1.21.1 — NeoForge port

- Ported the complete production source, public events, configuration and data paths to NeoForge 1.21.1/Java 21/ModDevGradle. Added required versioned pairing payloads and production JAR gates.
- Rebound the actual MCA namespaced personality API and Guard Villagers variant/movement methods. Unknown historical personalities preserve live state.
- Unified proxy creation with a loading grace period, durable UUID resolution, canceled-spawn/family rollback and conflict quarantine. Removed automatic orphan timeout deletion; maintained hiding/lifetime across module toggles and dimension transfers.
- Added schema 3 migration, separate snapshots and pending hearts, bounded arithmetic with long pending deltas, automatic flush, future-schema preservation and bidirectional names.
- Reworked interactions, safe recruitment, final-damage/death ordering and native projectile transparency. Added exact client identity/portrait association and scoped render-state cleanup.
- Expanded unit, GameTest and packaged client/server validation; documented exact dependencies and external companion API limitations. See `docs/VALIDATION.md` for evidence and unexecuted cases.

## Forge 1.20.1 development baseline (historical)

### Added

- **Chore safety.** MCA villagers' hunting chore is now restrained from killing animals you care about. Five new SERVER config options under `[chores]`: `protectOwnedAnimals`, `protectedRoles`, `protectNamedAnimals`, `protectLeashedAnimals`, and `abandonHuntOnProtectedTarget`. **`livestock` is deliberately NOT protected by default** — the hunting chore exists to produce meat, and protecting livestock would neuter it for every Genetic Animals / DragN's Livestock Overhaul pack. A pack that wants protection adds one entry to `protectedRoles`.
- **A reflective MCA binding layer.** New `mca/McaBinding` class resolves MCA's package root at runtime (trying four layouts from `forge.net.conczin.mca` down to `net.mca`) and reflectively reads `VillagerEntityMCA.getVillagerBrain()`, `VillagerBrain.getCurrentJob()` and `VillagerBrain.abandonJob()`. Both build-time guards (`NoOptionalModStaticLinkTest` and `checkJarContents`) now scan for dot-form `Class.forName` literals in addition to slash-form bytecode, with an exact-match allowlist of one file: `dev/otectus/mcamobcompat/mca/McaBinding.class`.
- **Mount diagnostics.** `/mcamobcompat classify` now reports `saddled`, `owner` and `controllingPassenger` for any entity carrying `MobRole.MOUNT`. New `/mcamobcompat mca` subcommand reports MCA's resolved package root, MCA's version and the state of each reflective binding.
- **A hidden MCA proxy for bridged guards.** Every bridged guard is now paired with a real, invisible `mca:*_villager` (profession `mca:guard`) that stands in for it: a plain right-click forwards to the proxy and opens **MCA's own interaction screen**, with every MCA option — chat, gifts, hire, marriage, adoption, procreation, home and so on — working exactly as it would for any MCA villager, except profession changes (re-asserted back to `mca:guard` within a second) and the mount/pick-up ones (neutralised because the proxy is dismounted every tick); a sneak right-click still opens Guard Villagers' own inventory GUI on the guard itself. The guard keeps its own model and combat AI; the proxy is invisible, silent, weightless and brainless, pinned to the guard's position every tick, and carries the guard's name, personality, hearts, family tree and village residency. Hits and death on the guard are relayed onto the proxy so MCA, MCACrime and MCAReputation react to it as a villager, and MCA's own movement intent (Follow/Stay/Move, including an addon's escort) is mirrored back onto the guard through reflection on Guard Villagers' own runtime class — no class of its is ever named.
- **The guard now shows its own face in MCAConversations' dialogue frame and MCAQuests' quest menu**, instead of an empty box. Both screens draw the invisible proxy through vanilla's inventory-entity-render helpers; `client/ProxyRenderHandler` now detects that case (`ScreenEvent.Render.Pre`/`Post`) and draws the linked guard in the proxy's place, found by bounding-box overlap (`GuardProxy.nearbyGuardOf`) and posed with the proxy's rotation for that one call. World rendering of the proxy is unaffected — it stays cancelled outright.
- **Full compatibility with MCAQuests, MCACrime, MCAReputation and MCAConversations.** All four resolve a bridged guard's proxy the same way they resolve any MCA villager — by its real `VillagerEntityMCA` type or by UUID — so no addon-side change is needed.
- **Crouch-and-weapon on an MCA villager can now recruit it as a guard in place**, via the new `recruitMcaVillagersAsGuards` option (default on): the villager's profession becomes `mca:guard` and one item moves from the player's hand into its own, but its name, family, genetics, personality and inventory are all untouched — replacing the plain block for that gesture.
- **Guard identity storage bumped to schema version 2**, adding the proxy link and hearts-migration bookkeeping needed above. A version-1 save loads and migrates automatically; nothing stored before this change is lost.
- **New diagnostic commands** `/mcamobcompat guards proxies` (lists every loaded proxy, its guard, and any guard still waiting for one) and `/mcamobcompat guards rebuild` (a recovery tool: discards and recreates the proxy of *every* loaded bridged guard, whether or not it already had one, so it also destroys an intact proxy's MCA memories — use it only when the link is actually broken).

### Removed

- **The emulated interaction screen shipped earlier is gone**, replaced by the hidden proxy above: `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, the whole `network` package (its Forge `SimpleChannel` and three packets), `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource`, and the `mcamobcompat:guard_gift_liked`/`guard_gift_loved` item tags.
- **Five SERVER options are gone**: `guardInteractionCooldownSeconds`, `guardSocialHearts`, `guardGiftHearts`, `guardGiftHeartsLoved`, and `guardHeartsLostPerHit` — MCA's own memories on the proxy replace the heart economy they implemented. `guardInteractionScreen` is renamed to `guardMcaInteraction` (same meaning: master switch for giving bridged guards MCA's own interaction, now through the proxy). `ForgeConfigSpec` has no migration path, so an existing `mcamobcompat-server.toml` has these keys rewritten to their new defaults the first time it loads under the new spec. `treatGuardsAsMcaGuards` and `guardShowNameTag` are unaffected.

### Corrections to the design research

Three findings from 0.2.0's research into real mod jars:

- **MCA's chores already reach modded animals.** `HuntingTask` types its target as vanilla `Animal` and excludes only `TamableAnimal`. Genetic Animals entities extend `Animal`, and SWEM and DragN's equines extend `AbstractHorse`, which in 1.20.1 extends `Animal` without being `TamableAnimal`. So a tamed, player-owned modded horse and a genetically-bred Genetic Animals animal were already legal hunting targets. Release 0.3.0 restrains the chore rather than enabling it, inverting what 0.2.0's roadmap expected.
- **Both invariant guards had a blind spot.** `NoOptionalModStaticLinkTest` previously scanned only for slash-form package roots (`forge/net/mca/…`) and `checkJarContents` matched only zip entry names, so a dot-form `Class.forName("forge.net.mca…")` literal would have passed both silently. Both now scan for dot-form names, with an exact-match allowlist of exactly one class file, matched precisely so renaming the class cannot orphan the exemption.
- **MCA villagers cannot ride SWEM horses, and it is not what the design research assumed.** MCA does mount villagers, but only on the player-issued `ridehorse` command from its own interaction screen, never autonomously. Every one of that command's filters passes for a tacked SWEM horse: `AbstractHorse implements Saddleable`, and SWEM's `setSWEMSaddled()` sets vanilla flag 4, which is exactly what `isSaddled()` reads. The villager does mount. But `SWEMHorseEntityBase.getControllingPassenger()` filters passengers to `Player` and returns null for a villager, so MCA's own `MixinHorseBaseEntity` never engages and the horse has no steering rider — it stands there. No reachable path exists from this mod to mixin-patch that without violating the project's no-mixin rule, so 0.3.0 ships a diagnostic rather than a fix. The real solution belongs in SWEM.

## 0.2.0

Guard Villagers bridge: treating guards as MCA guards for the addon suite.

### Added

- **Bridged guard identities.** Guards tagged `mcamobcompat:guard` (including Guard Villagers' `guardvillagers:guard`) are given MCA-style identities: a name, a gender, a personality and per-player hearts. All of it is persisted with the world in `SavedData` and regenerated from the guard's UUID so the same guard always gets the same identity across restarts. Names are generated from a bundled list (not read from MCA's resources, keeping this mod class-reference-free) and seeded by the guard UUID; when a guard already has a custom name it is adopted instead.
- **Two new SERVER config options** under `guard_villagers` in `mcamobcompat-server.toml`: `treatGuardsAsMcaGuards` (default `true`, toggles the bridge) and `guardHeartsLostPerHit` (default `-5`, the hearts penalty when a player hits a bridged guard).
- **Two new API methods** in `MobCompatibilityAPI`: `isBridgedGuard(Entity)` returns true for a guard that is getting an identity, and `getGuardIdentity(Entity)` returns an `Optional<GuardIdentity>` — a view of name, gender, personality, hearts per player, and deceased flag.
- **Three new Forge-bus events** in `dev.otectus.mcamobcompat.api.event`: `GuardInteractEvent` (fired on both logical sides when a player right-clicks a bridged guard, once per hand, never when sneaking; cancellable, suppresses the underlying entity interaction with the configured cancellation result); `GuardIdentityCreatedEvent` (fired server-side once per guard for the life of the world when it first gets an identity); `GuardHeartsChangedEvent` (fired server-side when a player's hearts with a guard change, carrying a nullable guard entity because hearts are persisted by UUID and can change while the entity is not loaded).
- **`/mcamobcompat guards` subcommands** for diagnosing and managing bridged guard identities: `status` counts living and deceased identities in the world; `inspect` shows a specific guard's identity (UUID, name, gender, personality, hearts per online player); `purge <days>` removes deceased entries older than N in-game days.
- **`/mcamobcompat classify` extension:** when looking at a bridged guard, the output now includes "bridged MCA guard" and the guard's stored identity summary.

### Notes

- Bridge names and gender are generated deterministically from the guard's UUID, not read from MCA's resources, so the mod remains class-reference-free for optional mods: every bridged guard always gets the same name and gender across restarts, even if the generation lists change later, because the rolled values are persisted alongside the UUID.
- The personality vocabulary is taken from MCA's canonical roster: `confident`, `peppy`, `friendly`, `flirty`, `playful`, `gloomy`, `sensitive`, `greedy`, `odd`, `crabby`, `extroverted`, `introverted`, `relaxed`, `anxious`, `peaceful`, `upbeat`. These are the same strings MCAConversations uses.
- `GuardHeartsChangedEvent` carries a nullable guard entity because hearts are keyed on UUID and can move to a different dimension or be changed while the entity is not loaded. Listeners cannot assume a mob is present; they must handle null.
- Deceased entries are kept, never auto-deleted, so a guard a player killed last week still has a name when asked about. Operators can purge old entries with the `/mcamobcompat guards purge` command.

## 0.1.0

First release. Framework, classification, and the Guard Villagers safety fix.

### Added

- **Semantic entity classification.** Ten roles — `livestock`, `cattle`, `sheep`, `pig`, `poultry`,
  `horse`, `mount`, `pet`, `guard`, `humanoid_npc` — driven entirely by datapack entity-type tags,
  cached per entity type and invalidated on `/reload`. Shipped defaults cover vanilla animals, SWEM,
  Genetic Animals and DragN's Livestock Overhaul, and reference the Forge conventional entity tags so
  that mods following those conventions are classified without anyone writing anything.
- **A block on the Guard Villagers villager-to-guard conversion for MCA villagers.** The conversion
  is reachable on a stock install and permanently destroys the villager's family tree, marriage,
  children, genetics, personality and inventory. See below.
- **Guard defence of MCA villagers.** Nearby guards are alerted when a hostile attacks or targets an
  MCA villager, and are stopped from targeting MCA villagers themselves. Guard Villagers matches both
  of those by entity id against its own config lists, and neither list names MCA's villagers.
- **A read-only public API**, `dev.otectus.mcamobcompat.api.MobCompatibilityAPI`: `isLivestock`,
  `isMount`, `isGuard`, `isPet`, `getRoles(Entity)`, `getRoles(EntityType)`, `getOwner(Entity)`, and
  `registerProvider` for contributing per-entity roles that no tag can express.
- **`/mcamobcompat`** with `status`, `classify` and `dump <namespace>`, for finding out what a mod
  actually registered and how this mod is classifying it.
- **Per-mod integration modules** for Guard Villagers, SWEM, Genetic Animals and DragN's Livestock
  Overhaul, each gated on the mod being installed and each switchable off.

### Notes

- **This mod references no type from MCA or from any optional mod.** MCA villagers are identified by
  entity registry id, guards are found through a tag and commanded through vanilla `Mob.setTarget`,
  and SWEM ownership is read through vanilla `AbstractHorse`. There is no reflective binding layer,
  no mixin and no access transformer, and a build-time test fails if a foreign type ever appears in
  the jar. MCA has renamed its Java package twice; this mod is unaffected by that and by whatever it
  does next.
- **Breeding, genetics, gender and needs stay with the mod that added the animal.** There is no call
  to `Animal.breed` or `spawnChildFromBreeding` anywhere in this jar.
- While `blockGuardConversion` is on, crouch-right-clicking an MCA villager *while holding a sword or
  crossbow* does nothing — MCA's interaction screen will not open for that one gesture either. The
  final gate on the Guard Villagers side is a check against its own config, which cannot be read
  without naming one of its classes, so the block is deliberately wider than the conversion.
- `guardArrowsHurtVillagers` lives inside the Guard Villagers config and is out of reach here.

### Corrections to the design research

Several claims in the original specification turned out to be wrong when checked against the actual
mod jars, and the implementation follows the corrected findings:

- **MCA villagers *are* vanilla `Villager` instances.** `VillagerEntityMCA extends
  net.minecraft.world.entity.npc.Villager`. The spec had this inverted, and it is the whole reason
  the Guard Villagers conversion can reach them.
- **Guard targeting cannot be steered with entity-type tags.** Guard Villagers declares exactly one
  tag and it is an item tag; it reads no entity-type tags at all. The planned tag-based approach was
  impossible and was replaced with event handlers.
- **Genetic Animals' mod id is `eanimod`, not `geneticanimals`,** and its entities are
  `eanimod:enhanced_cow` and friends. The guessed id would have produced tags that silently never
  matched.
- **SWEM ownership is the vanilla owner API.** `SWEMHorseEntityBase extends AbstractHorse` and
  overrides the vanilla owner accessors, so no NBT sniffing or reflection is needed.
- **`dragnlivestock:freight_wagon` and `dragnlivestock:o_undead_horse` are not registered entity
  types**, despite having language keys and loot tables. A test now forbids them in shipped tags.
