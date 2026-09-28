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
- **Crime's effects reach the guard.** `mcacrime:restrained`, `mcacrime:sand_blinded` and
  `mcacrime:wounded` are copied from the proxy onto the guard once a second and removed with it, and a
  restrained proxy holds its guard in place and clears its follow and patrol orders. Reached by
  registry id only; an install without MCA: Crime does nothing. Custody itself stays on the proxy.
- `GuardProxySyncTargetTest` pins the target-agreement rules, and the Forge/NeoForge parity manifest is
  re-recorded with the new files (`python3 scripts/parity.py --write`).

## 0.2.1

Guards that are real MCA villagers, villagers that see a creeper coming, mounts that refuse riders
they cannot carry, and support for Icy's Better Horses, Alex's Mobs and MCA 7.7.

Released for Forge 1.20.1 and NeoForge 1.21.1 together, with the same features, configuration,
commands and datapack tags on both, and one version number: the Forge build goes from 0.2.0 to
0.2.1, and the NeoForge build, released as 2.0, is renumbered 0.2.1 to join it. `scripts/parity.py`
now holds the two repositories to that.

### Upgrade notes

- **Install it on the server and on every client, at the same version.** The client learns which
  villager is a guard's hidden proxy from a new network channel, `mcamobcompat:proxy_pair`, and Forge
  refuses a connection whose two sides disagree about it.
- **The guard identity store moves to schema version 3**, migrated automatically from version 1 or 2
  on first load. The version-2 record of each guard is kept verbatim under `LegacyV2`. A world saved
  by a *newer* version of this mod loads read-only and is never overwritten.
- **The jar is now `mcamobcompat-forge-0.2.1+1.20.1.jar`.** Remove the old `mcamobcompat-0.2.0.jar`
  from the mods folder; `gradlew installMod` warns if it finds one, since Forge refuses two copies.
- **Worlds from a pre-release build of this version (then numbered 0.3.0) counted some hearts
  twice.** A change made while a guard's MCA villager was unreachable was added to the stored hearts
  *and* queued for MCA. Migration subtracts the duplicate once; nothing else needs doing.
- **MCA's "Ride Horse" order no longer seats a villager on a SWEM horse**, nor on an Icy's Better
  Horses horse that belongs to a player or is hitched. On a SWEM horse the villager could not steer,
  so the horse ignored MCA's orders; on an owned Icy's horse a villager could ride off with somebody else's horse. Set
  `[mounts] npcRidingPolicy = "ALLOW_INERT"` to put villagers back on SWEM horses anyway.
- **New config keys arrive with their defaults**: `[threats]`, `[mounts]`, `[icys_better_horses]` and
  `[alexs_mobs]` in `mcamobcompat-server.toml`, and `icysBetterHorses` and `alexsMobs` under
  `[modules]` in `mcamobcompat-common.toml`.
- **Five SERVER options are gone**: `guardInteractionCooldownSeconds`, `guardSocialHearts`,
  `guardGiftHearts`, `guardGiftHeartsLoved` and `guardHeartsLostPerHit` — MCA's own memories on the
  proxy replace the heart economy they implemented. `guardInteractionScreen` is renamed to
  `guardMcaInteraction`, with the same meaning. `ForgeConfigSpec` has no migration path, so an
  existing `mcamobcompat-server.toml` has these keys rewritten to their new defaults the first time it
  loads.
- **A click routed to a guard now uses the player's own entity reach**, plus one block of slack, in
  place of a fixed eight blocks, as the NeoForge build always has.
- **MCA's Follow order is handed to Guard Villagers exactly, and Guard Villagers decides.** Its own
  `followHero` option, on by default, lets a guard follow only a player with Hero of the Village.

### Added: Guard Villagers guards as real MCA villagers

- **A hidden MCA villager for every bridged guard.** Each guard is paired with a real, invisible
  `mca:*_villager` with profession `mca:guard`. A plain right-click opens **MCA's own interaction
  screen** through it, with every MCA option — chat, gifts, hire, marriage, adoption, procreation,
  home and so on — working exactly as for any MCA villager. The exceptions are a profession change,
  which is put back to `mca:guard` within a second, and the mount and pick-up options, which the proxy
  cannot act on because it is kept dismounted. A crouch right-click still opens Guard Villagers' own
  inventory. The guard keeps its own model and combat AI; the proxy is invisible, silent, weightless
  and brainless, pinned to the guard every tick, and carries its name, personality, memories, hearts,
  family tree and village residency.
- **Damage and death reach MCA.** The guard's final damage, after armour and every other mod's
  changes, is relayed onto the proxy, so MCA, MCA: Crime and MCA: Reputation react as they would to
  any villager. A death is relayed only once it is final at the end of the tick, so a totem of
  undying, or a mod that cancels the death late, leaves both halves alive and linked. The proxy drops
  no loot and no experience.
- **MCA's orders reach the guard.** Follow, Stay and Move, including an addon's escort, are mirrored
  onto the guard through Guard Villagers' own methods, looked up by name. Follow reads MCA's own
  following memory, so the villager follows whoever MCA says it follows, across restarts too. Names
  travel both ways: a rename in MCA reaches the guard, and a name tag on the guard reaches MCA.
- **Full compatibility with MCA: Quests, Crime, Reputation and Conversations.** All four resolve a
  bridged guard's proxy the same way they resolve any MCA villager — by its real MCA villager class,
  or by UUID — so none of them needs a change.
- **The guard shows its own face in MCA: Conversations' dialogue frame and MCA: Quests' quest
  menu**, instead of an empty box, drawn with the proxy's pose.
- **Crouch-and-weapon on an MCA villager recruits it as a guard in place**, through the new
  `recruitMcaVillagersAsGuards` option, on by default. Its profession becomes `mca:guard` and one item
  moves from the player's hand to its own; its name, family, genetics, personality and inventory are
  untouched. A child, a villager already holding something, and an existing guard are refused with a
  message.
- **`/mcamobcompat guards proxies`** lists every loaded proxy with its guard, and every guard still
  waiting for one. **`/mcamobcompat guards rebuild`** replaces every loaded guard's proxy with a new
  one: a destructive repair for a link that is actually broken.
- **`GuardIdentity.proxyUuid()`** gives an addon the hidden villager's UUID, so it can use its own
  MCA code path on the real entity.

### Added: everything else

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
- **Icy's Better Horses.** Its horse owners count as owners, through `MobCompatibilityAPI.getOwner`
  and the hunting chore's protection. A horse that belongs to a player, or that is hitched, refuses a
  villager rider. Icy's keeps other players off an owned horse but does not check villagers, so this
  mod applies the same rule to them. Nothing of Icy's is written. New option `[icys_better_horses] recognizeOwnership`.
- **Alex's Mobs.** Its elephant, grizzly bear, komodo dragon and tusklin are mounts, and every tamed
  animal of any mod with an owner, horses aside, is now a `pet`, so the hunting chore spares it. Its six player-steered
  mounts refuse a villager that anything tries to seat on them; MCA's own order never picks them. Every registered entity type was audited from the
  jar; the result is in `docs/ALEXS_MOBS_AUDIT.md`. New option `[alexs_mobs] recognizeAnimals`.
- **MCA 7.7.** MCA's 7.7 line, whose personalities are registry objects rather than an enum, is
  supported alongside 7.6. `/mcamobcompat mca` reports which shape it found.
- **Chore safety.** MCA villagers' hunting chore leaves alone animals you care about. Five SERVER
  options under `[chores]`: `protectOwnedAnimals`, `protectedRoles`, `protectNamedAnimals`,
  `protectLeashedAnimals` and `abandonHuntOnProtectedTarget`. **`livestock` is deliberately NOT
  protected by default** — the hunting chore exists to produce meat, and protecting livestock would
  neuter it for every Genetic Animals / DragN's Livestock Overhaul pack. A pack that wants protection
  adds one entry to `protectedRoles`.
- **Diagnostics.** `/mcamobcompat mounts inspect` shows a mount's roles, owner, saddle, who steers
  it, whether a villager could, and the policy's decision. `/mcamobcompat threats inspect` shows every
  threat an MCA villager can see, its response to each, and — with Golems Kill Creepers installed —
  that mod's two gamerules. `/mcamobcompat mca` reports MCA's package root, version, personality shape
  and the state of each reflective binding. `/mcamobcompat classify` reports a mount's saddle, owner
  and rider.
- **A reflective MCA binding layer**, `mca/McaBinding`, which resolves MCA's package root at runtime
  and reaches MCA's villager brain, hearts, personality and family tree by MCA's own member names.
  Both build-time guards scan for dot-form `Class.forName` literals as well as slash-form bytecode,
  with an exact-match allowlist of one file, `dev/otectus/mcamobcompat/mca/McaBinding.class`. MCA's
  brain memories are looked up by registry id instead, which needs no reflection at all.

### Fixed

- **Guard Villagers' conversion is blocked on the client as well as the server.** Guard Villagers
  runs its conversion, including discarding the villager, on both sides, so a block on the server
  alone left the recruiting player looking at an invisible villager. The block cancels before any
  client-side early return. Two real clients on a dedicated server now confirm it: the recruited
  villager stays present and rendered for the player who recruited it and for another player
  watching (`docs/VALIDATION.md`).
- **Guards no longer hurt MCA villagers with stray arrows or blows.** With
  `preventGuardFriendlyFire` on, any damage a guard deals to an MCA villager is cancelled, whatever
  Guard Villagers' own `guardArrowsHurtVillagers` option says.

### Changed: brought in from the NeoForge build

The NeoForge port of this mod refined a few things on its own; they now apply to both builds.

- **Queued hearts reach MCA before its screen opens.** A click forwarded to a guard's MCA villager
  first applies any queued hearts change, rather than leaving it for the next heartbeat.
- **The hearts snapshot follows every relayed hit, and a confirmed death.** A penalty MCA applies for
  a hit is on record at once, and the last balance is kept before a dead guard's link is released.
- **Guard Villagers' members are bound by their whole signature** -- name, parameter types and return
  type -- so a same-named method that means something else is refused. The variant getter is found
  under either of its two names, `getGuardVariant` (1.20.1) or `getVariant` (1.21.1).
- **A throwing classification provider is reported once**, not once for every entity it is asked
  about.
- **Classification and chore settings are re-read when a config loads or unloads**, not only when it
  reloads.
- **Both invariant guards also refuse an optional mod's dotted package name** anywhere in the jar,
  McaBinding included: such a name is how a `Class.forName` target is stored, and nothing here may
  hold one.
- **An MCA personality is read only from a `getId()` that returns an id.**

### How the proxy stays correct

These describe behaviour, and were each made to hold under a GameTest:

- A proxy is created only from its guard's own tick, after a two-second grace period, and never
  while the guard's saved proxy might only be unloaded. A proxy found alone near its guard is adopted
  rather than duplicated. Creation goes through Forge's spawn event: if another mod cancels it, the
  proxy and MCA's family-tree entry for it are rolled back and nothing is linked.
- A proxy whose guard is not loaded is kept, never deleted on a timer. Conflicting saved links are
  quarantined and reported by `guards status`, never relinked.
- The proxy's collision box is narrower than its guard's and nested inside it, so arrows, tridents,
  snowballs and melee hit the guard with vanilla's own hit code, potion effects and piercing
  included.
- The proxy follows its guard through portals, keeping both UUIDs.
- The client is told exactly which villager is which guard's proxy, rather than guessing from its
  flags, so a real villager made invisible by a potion is never hidden, and two guards standing
  together never swap portraits.
- A hearts change made while MCA's villager is unreachable is queued once and applied once, and
  `GuardHeartsChangedEvent` fires once for it.
- All state held between ticks is cleared when the server stops.

### Removed

- **The emulated interaction screen shipped earlier is gone**, replaced by MCA's own screen through
  the proxy: `client/GuardInteractionScreen`, `client/ClientGuardInteractions`, its three network
  packets, `bridge/GuardAction`, `bridge/GuardResponses`, `bridge/McaNameSource`, and the
  `mcamobcompat:guard_gift_liked`/`guard_gift_loved` item tags. The `network` package now holds only
  the proxy-pairing message.

### Build and verification

- **GameTests** run on a real server against the real MCA and Guard Villagers:
  `runGameTestServer`, or `runGameTestServer -PmcaDev77` for MCA 7.7. `-PwithSwem`, `-PwithIcy`,
  `-PwithAlex` and `-PwithGolems` add those mods and their own suites. The task fails unless the server
  reports every required test passed, because a server that fails to load mods still exits cleanly.
- **`remapDevMods`** writes development copies of third-party mods with their SRG member names
  rewritten to official names, mixin annotations and refmaps included. Without it MCA, which ships
  its mixins without a refmap, cannot start in a dev run.
- Optional mods for dev runs are pinned by Modrinth version id in `gradle.properties`.
- **`verifyProductionJar`** checks the finished jar the way an instance will see it: metadata expanded,
  every shipped tag and resource present, Java 17 classes, nothing from the test or probe source sets.
  The NeoForge build runs the same checks against its own layout.
- **`runtimeProbeJar`** packages an opt-in validation driver for disposable test worlds, reobfuscated
  like the release jar. On a real server and real clients it checks pairing, MCA's screen, portraits,
  movement, hearts and the crouch-and-weapon recruit, and takes screenshots.
  `scripts/validation/launch_client.py` launches an installer-created client profile for it; the same
  script serves both loaders.
- **`scripts/parity.py`** checks this repository against the NeoForge one: the same files on both
  sides, the loader-neutral ones byte-identical, and no loader-specific file changed on one side only.
- `docs/VALIDATION.md` records what was run and what it showed; `docs/DEPENDENCIES.json` records every
  jar tested, with its SHA-256.

### Corrections to the design research

- **MCA's chores already reach modded animals.** `HuntingTask` types its target as vanilla `Animal` and excludes only `TamableAnimal`. Genetic Animals entities extend `Animal`, and SWEM and DragN's equines extend `AbstractHorse`, which in 1.20.1 extends `Animal` without being `TamableAnimal`. So a tamed, player-owned modded horse and a genetically-bred Genetic Animals animal were already legal hunting targets. Release 0.2.1 restrains the chore rather than enabling it, inverting what 0.2.0's roadmap expected.
- **Both invariant guards had a blind spot.** `NoOptionalModStaticLinkTest` previously scanned only for slash-form package roots (`forge/net/mca/…`) and `checkJarContents` matched only zip entry names, so a dot-form `Class.forName("forge.net.mca…")` literal would have passed both silently. Both now scan for dot-form names, with an exact-match allowlist of exactly one class file, matched precisely so renaming the class cannot orphan the exemption.
- **Why a villager on a SWEM horse goes nowhere it is sent.** An earlier version of this entry blamed MCA's `MixinHorseBaseEntity` never engaging; that was wrong, and so was the next one, which said the horse could never move. MCA mounts villagers only on the player-issued `ridehorse` command, every one of whose filters a tacked SWEM horse passes, so the villager does mount. A mount is steered by its controlling passenger, which on a vanilla horse is the villager and on which MCA's riding support keys. `SWEMHorseEntityBase.getControllingPassenger()` names only a player, and its `isVehicle()` counts only a player as a rider, so the horse ignores the villager and goes on under its own AI as if unridden. A GameTest confirms it: a villager-ridden vanilla horse names the villager its controlling passenger and walks where it is sent, and a villager-ridden SWEM horse has no controlling passenger. No mixin-free route exists from here, so 0.2.1 refuses the ride instead. The change SWEM would need is in `docs/UPSTREAM.md`.
- **Guard Villagers' villager-behaviour mixin cannot reach MCA villagers.** MCA builds its villagers' brains from its own task lists, never from vanilla's `VillagerGoalPackages`, which is what Guard Villagers patches.
- **Icy's Better Horses steers its horses itself.** Its follow, stay, return-home and wander commands run only while a horse is owned and unridden, and a horse's default command is follow. They pause while the horse carries a rider, so a villager's ride never competes with a command, and no rule about commands was needed.
- **Guard Villagers runs its conversion on the client too**, as described under Fixed.

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
