# Changelog

## Unreleased

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
