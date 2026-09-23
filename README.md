# MCA: Mob Compatibility

A Minecraft NeoForge 1.21.1 addon that teaches **MCA Reborn** to recognise the animals, mounts,
livestock and guards that other mods add — and stops one of them destroying your villagers.

This is the NeoForge 1.21.1 build. The Forge 1.20.1 build is the same mod with the same version
number, features, configuration, commands and datapack tags; the two are kept in step by a check
that runs against both repositories (see [Building](#building)). Worlds do not move between them:
Minecraft, MCA and every animal mod have their own formats on each version.

Part of the MCA addon suite, alongside MCA: Quests, MCA: Crime, MCA: Reputation and
MCA: Conversations.

## What it does

**It stops Guard Villagers destroying MCA villagers.** This is the headline, and it is not a
hypothetical. Guard Villagers converts a villager into a guard when you crouch and right-click it
holding a sword or crossbow, and the only check it makes is `instanceof Villager` — which MCA
villagers pass, because `VillagerEntityMCA` extends the vanilla villager class. Its default list of
convertible professions includes `none`, so **any unemployed MCA villager is convertible in a stock
install**. The conversion carries over the position, custom name and held item, then deletes the
villager. Family tree, marriage, children, genetics, personality and inventory all go with it, and
none of it can be recovered, because a guard has nowhere to keep any of it. This mod blocks that
interaction on both the server and the client — the client half matters, because Guard Villagers
runs its conversion on the client too — and by default turns the same gesture into a safe recruit:
the villager takes MCA's own guard profession in place and keeps everything.

**It makes Guard Villagers' guards real MCA guards.** Each guard is paired with a hidden, real MCA
villager that carries its MCA identity: name, personality, memories, hearts, family tree and home. A
plain right-click opens MCA's own screen for it, with MCA's own options, and MCA: Quests, Crime,
Reputation and Conversations all treat it as an MCA villager, because it is one. Crouch-right-click
still opens Guard Villagers' own inventory. The guard keeps its own model and combat AI; damage and
death reach MCA, and MCA's Follow, Stay and Move orders reach the guard. (Guard Villagers itself lets
a guard follow only a player with Hero of the Village while its own `followHero` option is on, which
is its default; MCA's Follow order is handed to Guard Villagers exactly, and that rule then applies.)

**It makes guards actually defend MCA villagers, and never hurt them.** Guard Villagers decides who
to protect, and who never to attack, by matching entity ids against lists in its own config — and
both lists ship naming `minecraft:villager`. An MCA villager is `mca:male_villager`, so out of the
box it is on neither list: guards ignore it when a zombie attacks it, and nothing stops a guard
attacking it. Both gaps are closed here, including a guard's stray arrows.

**It lets MCA villagers see a creeper coming.** MCA on its own only notices a creeper lit with flint
and steel. Villagers now run from a creeper that is hunting them or whose fuse is burning, and MCA's
own guards and archers fight one that is not about to go off — all through MCA's own panic and guard
behaviour. No creeper, golem or gamerule is changed.

**It stops chores and orders that go wrong on modded animals.** MCA's hunting chore leaves owned,
named, leashed, pet and mount animals alone. A villager is no longer seated on a mount that only a
player can steer: the mount refuses it, and the player who gave the order is told why.

**It sorts every mob into semantic roles** — `livestock`, `cattle`, `sheep`, `pig`, `poultry`,
`horse`, `mount`, `pet`, `guard`, `humanoid_npc` — using datapack tags, so MCA and other mods can
ask "is this thing a cow?" without anyone hardcoding a list of cows. Vanilla animals, SWEM horses,
Genetic Animals, DragN's Livestock Overhaul O-Animals, Icy's Better Horses' breeds and Alex's Mobs'
mounts are all classified out of the box, a tamed animal of any mod is a pet (a tamed horse is a
mount), and the conventional entity tags the shipped role tags reference are picked up without
anyone doing anything.

## What it deliberately does not do

- **It never breeds, feeds or modifies another mod's animals.** Genetics, gender, pregnancy, needs,
  training and breeding belong to the mod that added the animal. There is no call to `Animal.breed`
  or `spawnChildFromBreeding` anywhere in this jar, so there is no path by which it could override
  any of that.
- **It never writes to another mod's config or gamerules.** Appending the MCA villager ids to the
  Guard Villagers protection lists at runtime would have been the smaller change; editing somebody
  else's config file is not this mod's business.
- **It adds no mobs, no breeding system and no genetics.**
- **It never imitates MCA.** A guard gets MCA behaviour through a real MCA villager, never through a
  lookalike screen. Recruiting changes an MCA villager's profession in place and replaces nothing.
- **It does not make villagers steer mounts that only a player can steer.** SWEM's horses, and a few
  of Alex's Mobs' mounts, hand control to a player rider only; a villager aboard is carried along
  with no say in where the animal goes, and that cannot be changed from outside those mods without a
  mixin. Such mounts refuse villagers instead. The change SWEM would need is written up in
  [docs/UPSTREAM.md](docs/UPSTREAM.md).

The original design research, written for the Forge build, is archived in
[MCA_MOB_COMPATIBILITY.md](MCA_MOB_COMPATIBILITY.md); how the NeoForge port differs is in
[docs/PORTING.md](docs/PORTING.md).

## Installing

Requires **MCA Reborn** 7.7 for 1.21.1, and NeoForge 21.1.248 or newer within 21.1 (Java 21).
Install it on the server and on
every client, at the same version: the client learns which villager is a guard's hidden proxy over
the `mcamobcompat:proxy_pair` channel, and a client without it is refused at login. Everything else
is optional — the mod loads and works with any combination of the mods below installed or absent,
including none of them.

| Mod | Mod id | What it gets |
|---|---|---|
| Guard Villagers | `guardvillagers` | Conversion block and safe recruit, village defence, friendly-fire prevention, guards as real MCA guards |
| SWEM | `swem` | `swem:swem_horse` as horse + mount, ownership recognised, refuses villager riders |
| Genetic Animals | `eanimod` | `enhanced_cow`, `enhanced_pig`, … classified as livestock (no NeoForge 1.21.1 build has been tested) |
| DragN's Livestock Overhaul | `dragnlivestock` | O-Animals classified as livestock, equines as mounts (no NeoForge 1.21.1 build has been tested) |
| Icy's Better Horses | `icys_better_horses` | Release 2.0's fifteen breeds as horses + mounts; its horse owners recognised; owned horses refuse villager riders. Release 1.1.6 works too, with its hitched horses refused as well |
| Alex's Mobs | `alexsmobs` | Four ground mounts recognised; tamed animals protected as pets. Both 1.21.1 builds — Raguto's unofficial port and Alex's Mobs Continued — were audited; see [docs/ALEXS_MOBS_AUDIT.md](docs/ALEXS_MOBS_AUDIT.md) |
| Golems Kill Creepers | `golemskillcreepers` | Nothing needs integrating: its gamerules are left alone and shown by `threats inspect` |

Icy's Better Horses 2.0 lets only players ride its horses (a mob rides along only as cart cargo
behind one), so while it is installed MCA's Ride Horse order seats no villager on any horse; this
mod's own refusal of owned horses is what applies with release 1.1.6.

The exact versions tested are in [docs/DEPENDENCIES.json](docs/DEPENDENCIES.json), and what was tested,
and how, in [docs/VALIDATION.md](docs/VALIDATION.md).

## Commands

`/mcamobcompat` (permission level 2):

- **`status`** — which optional mods are installed, which integrations are active, and how many
  entity types hold each role.
- **`classify`** — the roles of the entity you are looking at. `classify <targets>` takes a target
  selector instead. Bridged guards show their identity; mounts show their saddle, owner and rider.
- **`mounts inspect`** — for the mount you are looking at: its roles, owner and saddle, who is
  steering it, whether a villager could, and what the mount policy decides and why.
  `mounts inspect <target>` takes a target selector instead.
- **`threats inspect`** — for the MCA villager you are looking at: every threat it can see, what it
  makes of each, and which of MCA's memories hold its reaction. With Golems Kill Creepers installed,
  its two gamerules are shown too. `threats inspect <target>` takes a target selector instead.
- **`guards status`** — the bridge's state, stored identities alive and deceased, and a census of
  hidden proxies. Identity records that could not be read, or that conflict, are counted here.
- **`guards inspect`** — the identity of the guard you are looking at: UUID, name, gender,
  personality, its proxy, the live MCA name and profession, and your hearts with it.
  `guards inspect <target>` takes a target selector instead.
- **`guards proxies`** — every loaded proxy with its guard, and every guard still waiting for one.
- **`guards rebuild`** — a destructive repair: replaces the proxy of every loaded guard with a new MCA
  villager. The old proxies' memories and family-tree nodes are lost. Use it only when a link is
  actually broken.
- **`guards purge <days>`** — remove deceased guard identities older than N in-game days. Does not
  touch living guards.
- **`mca`** — MCA binding diagnostics: MCA's version, its package root, which personality shape it
  uses, and the state of each reflective binding (ok or missing).
- **`dump <namespace>`** — every entity type a mod registered, with its roles. This is how you find
  out what a mod *actually* registered rather than what its wiki says, and it is how you would
  onboard a mod this addon has never heard of. Long dumps go to the server log in full.

## Save migration

Guard identities live in the world save (`mcamobcompat_guard_identities`, schema 3). Schema 1 and 2
records migrate automatically on first load: UUIDs, names, seed flags, personalities and migration
markers are kept. A schema 2 world written while a guard's MCA villager was unreachable counted that
change twice, in both ledgers; migration subtracts it once and keeps the original record under
`LegacyV2`. Hand-edited or already-damaged schema 2 balances can be ambiguous, so back a world up first.

Queued hearts changes are kept apart from MCA's own balances and applied once, when both the player
and the MCA villager are available; they are stored as a long, so a balance can cross the whole
signed-int range without wrapping. A world saved by a newer version of this mod loads read-only and is
never overwritten, conflicting links are quarantined and reported, and an unloaded guard or MCA
villager is never deleted on a timer. This mod migrates only its own records; MCA and every other mod
remain responsible for their own world and entity formats.

## Configuration

`mcamobcompat-common.toml` chooses which integrations exist at all (`[modules]`) and turns on debug
logging. `mcamobcompat-server.toml` holds the gameplay settings, per world, because all of them are
server-authoritative.

**Guard Villagers options:**
- **`guard_villagers.blockGuardConversion`** (boolean, default: on) — While on, crouch-right-clicking an MCA villager *while holding a sword or crossbow* blocks Guard Villagers' own conversion; with `recruitMcaVillagersAsGuards` also on (the default) that gesture recruits the villager as a guard in place instead, rather than doing nothing. Either way it stops the irreversible conversion that would otherwise destroy a villager's family tree, marriage, children, genetics, personality and inventory.
- **`guard_villagers.recruitMcaVillagersAsGuards`** (boolean, default: on) — Let crouch-and-right-click with a convertible weapon recruit an MCA villager as a guard in place (profession becomes `mca:guard`, name/family/genetics/personality untouched) instead of only blocking the gesture. A child, a villager already holding something in its main hand, and an existing guard are refused with a message. Requires `blockGuardConversion`.
- **`guard_villagers.treatGuardsAsMcaGuards`** (boolean, default: on) — When on, entities tagged `mcamobcompat:guard` (including Guard Villagers' `guardvillagers:guard`) are given MCA identities: a name, gender, personality and per-player hearts, persisted with the world. When off, no identities are created, but existing ones in the world save file are kept and resume when this is turned back on.
- **`guard_villagers.guardMcaInteraction`** (boolean, default: on) — When on, each bridged guard is paired with a hidden MCA villager, and a plain right-click opens MCA's own interaction screen through it; crouch-right-click still opens Guard Villagers' inventory GUI. When off, both gestures behave exactly as Guard Villagers ships them and no new proxy is created; a proxy that already exists stays hidden and keeps its MCA state. Requires `treatGuardsAsMcaGuards`.
- **`guard_villagers.protectMcaVillagers`** (boolean, default: on) — When a hostile mob attacks or targets an MCA villager, alert nearby guards. Guard Villagers matches the mobs it protects by entity id, and its default list names `minecraft:villager` — which MCA villagers are not — so without this they simply do not defend them.
- **`guard_villagers.preventGuardFriendlyFire`** (boolean, default: on) — Stop guards from targeting MCA villagers, and cancel any damage a guard deals to one, arrows included. The do-not-attack list in Guard Villagers is also matched by entity id and also names only `minecraft:villager`, so an MCA villager is not on it.
- **`guard_villagers.retaliateAgainstPlayers`** (boolean, default: off) — Also alert guards when a player attacks an MCA villager. Off by default: MCA has its own reputation and guard response for player misbehaviour, and turning a village hostile is a large consequence to add silently.
- **`guard_villagers.guardHelpRange`** (integer, default: 32, range: 1 to 128) — How far, in blocks, to look for guards to alert.
- **`guard_villagers.maxGuardsAlerted`** (integer, default: 8, range: 1 to 64) — Cap on how many guards a single attack alerts, so a large village does not turn one zombie into a full-village pathfinding storm.
- **`guard_villagers.guardShowNameTag`** (boolean, default: on) — Show a bridged guard's rolled name permanently above its head. Turning it off only stops new guards gaining one; an existing nameplate is never taken away.

**Chore safety options:**
- **`chores.protectOwnedAnimals`** (boolean, default: on) — Protect any animal that reports an owner through vanilla (tamed horses, cats, wolves, Alex's Mobs' tamed animals, and so on), or through Icy's Better Horses. Ownership is read, never written.
- **`chores.protectedRoles`** (list of role names, default: `["mount", "pet"]`) — Roles that are never a valid hunting target. Names are the same as tag files under `data/mcamobcompat/tags/entity_type/`, matched case-insensitively. `livestock` is deliberately NOT included by default, because the hunting chore exists to produce meat; protecting livestock would break packs that replace vanilla farm animals. A pack can add one entry here.
- **`chores.protectNamedAnimals`** (boolean, default: on) — Protect any animal with a custom name. A name tag is the vanilla signal that a player cares about this particular animal.
- **`chores.protectLeashedAnimals`** (boolean, default: on) — Protect any animal on a lead. A leashed animal is being taken somewhere by somebody.
- **`chores.abandonHuntOnProtectedTarget`** (boolean, default: on) — When a hunting villager is stopped from hitting a protected animal, also abandon the chore. Without this, the hunting task keeps trying to hit an animal it can never kill, and the villager stands there swinging forever. Requires the reflective MCA binding; where that is unavailable the attack is still blocked, but the chore is not abandoned.

**Threat options:**
- **`threats.creeperAwareness`** (boolean, default: on) — Let MCA villagers notice and react to the mobs in the `mcamobcompat:npc_threats` tag, which ships with `minecraft:creeper`, before they are hurt. Civilians run through MCA's own panic behaviour; guards fight through MCA's own guard combat.
- **`threats.creeperAlertRange`** (integer, default: 8, range: 2 to 32) — How far, in blocks, a villager notices a threat it can see.
- **`threats.swellingFleeRange`** (integer, default: 4, range: 1 to 16) — Within this many blocks of a creeper whose fuse is burning, everyone runs, guards included. Doubled for a charged creeper, as its explosion is.
- **`threats.guardsEngageCreepers`** (boolean, default: on) — Let MCA guards and archers fight a creeper that is not about to explode, instead of running from it. Guard Villagers' own guards are alerted when a villager runs from a creeper, but by default their own config forbids them to attack creepers, and this mod does not change that.
- **`threats.dismountToFlee`** (boolean, default: on) — A mounted villager dismounts to run from a threat. Nothing here can steer a villager's mount, so staying seated means staying put.

**Mount options:**
- **`mounts.npcRidingPolicy`** (`REFUSE` or `ALLOW_INERT`, default: `REFUSE`) — What happens when an MCA villager is put on a mount it cannot steer, or that an integration says is not its to take. `REFUSE`: the mount refuses the villager, and the player who gave the order is told why. `ALLOW_INERT`: the villager mounts anyway, as a passenger that cannot steer, which is the behaviour before 0.2.1. Mounts that only a player can steer are listed in the `mcamobcompat:player_only_mounts` tag.

**SWEM options:**
- **`swem.recognizeAsMounts`** (boolean, default: on) — Treat SWEM horses as mounts. Breeding, needs, skills and tack stay entirely with SWEM — this mod only recognises them, and never writes to them.

**Genetic Animals options:**
- **`genetic_animals.recognizeLivestock`** (boolean, default: on) — Treat the animals from Genetic Animals as livestock. Genetics, gender, pregnancy and breeding all remain owned by Genetic Animals itself.

**DragN's Livestock Overhaul options:**
- **`dragn_livestock.recognizeLivestock`** (boolean, default: on) — Treat the O-Animals as livestock. Genders, breeding and genetics all remain owned by DragN's Livestock Overhaul itself.

**Icy's Better Horses options:**
- **`icys_better_horses.recognizeOwnership`** (boolean, default: on) — Treat a horse that Icy's Better Horses says belongs to a player as owned, so the hunting chore leaves it alone and no villager rides off on it. Icy's keeps other players off an owned horse, but does not check villagers. A hitched horse refuses a villager whatever this says. Read only.

**Alex's Mobs options:**
- **`alexs_mobs.recognizeAnimals`** (boolean, default: on) — Classify Alex's Mobs' animals: ground-rideable ones as mounts, tamed ones as pets. When off, they get no roles at all, though a tamed one is still spared by the hunting chore because it has an owner. Taming, riding and every creature's abilities stay entirely with Alex's Mobs.

There are no per-entity toggles and there never will be: classification is extended with datapack
tags, not config lists.

## For pack authors

Everything this mod knows is a datapack tag. See **[DATAPACK.md](DATAPACK.md)** for how to classify
a mod it has never heard of, add a threat, or mark a mount as player-only — each takes one JSON file
and no code.

## For mod authors

`dev.otectus.mcamobcompat.api.MobCompatibilityAPI` is a small, read-only, static API:
`isLivestock`, `isMount`, `isGuard`, `isPet`, `getRoles(Entity)`, `getRoles(EntityType)`,
`getOwner(Entity)`, and `registerProvider(...)` for contributing per-entity roles that no tag could
express. Depend on that class and nothing else in this jar. `getOwner` reads vanilla ownership, and
falls back to Icy's Better Horses' owner when that mod is present.

### Guard Villagers bridge

When Guard Villagers is installed, guards tagged `mcamobcompat:guard` are eligible for a bridged identity unless disabled in config. Two API methods:

- **`isBridgedGuard(Entity)`** — returns true if the entity is a guard being given an MCA identity.
- **`getGuardIdentity(Entity)`** — returns `Optional<GuardIdentity>` with the guard's name, gender, personality, hearts per player, the UUID of its hidden MCA villager, creation time, and deceased flag. Hearts prefer the live MCA villager. Always empty on the client and for entities that `isBridgedGuard` rejects.

Three NeoForge-bus events, all in package `dev.otectus.mcamobcompat.api.event`:

- **`GuardInteractEvent(Player, Mob, InteractionHand)`** — fired for a plain, main-hand right-click on a bridged guard: ahead of forwarding the click to its hidden MCA villager when `guardMcaInteraction` is on, or ahead of leaving the click alone when it is off. Crouch is Guard Villagers' own gesture and never posts this event. Cancelable: if cancelled, the click is never forwarded and the underlying interaction event is cancelled with the event's `getCancellationResult()` (defaults to `SUCCESS`). Set a different `setCancellationResult()` to control what the client sees.
- **`GuardIdentityCreatedEvent(Mob, GuardIdentity)`** — fired server-side once per guard for the life of the world when it first receives an identity. Not cancellable; the identity already exists. Use this to seed your own per-NPC record.
- **`GuardHeartsChangedEvent(GuardIdentity, Mob, UUID player, int oldHearts, int newHearts)`** — fired server-side after `GuardIdentity.modHearts` changes a player's hearts. Carries a nullable guard entity: hearts are persisted by UUID and can be changed while the guard is not loaded in any level, in which case no entity is handed over. Each change is counted exactly once, even when it is made while MCA's villager is unreachable and is applied later. Never fired for a zero delta; not cancellable (the change is already stored).

## Building

```
gradlew build
```

Requires a JDK 21 (Gradle will fetch one if you do not have it); the build uses ModDevGradle and
official 1.21.1 names, so there is no reobfuscation step. The jar is
`build/libs/mcamobcompat-neoforge-<version>+1.21.1.jar`. `build` runs the unit tests and two checks on
the finished jar: that it neither contains nor names a class from MCA or any optional mod, and that it
carries every shipped tag and resource, Java 21 bytecode and nothing from the test or probe source
sets. `gradlew installMod -PmodsDir=<your instance's mods folder>` builds, checks and installs in one
step.

The GameTests run on a real server against the real mods:

```
gradlew runGameTestServer                                          # MCA 7.7 and Guard Villagers
gradlew runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems
gradlew runGameTestServer -PwithAlexContinued                      # Alex's Mobs Continued instead
gradlew runGameTestServer -PwithIcy116                             # Icy's Better Horses 1.1.6 instead
```

The task fails unless the server reports that every required test passed. Each `-Pwith*` flag adds
that mod to the run, with its libraries, and enables its own test suite. MCA, Guard Villagers and the
optional mods are fetched at versions pinned in `gradle.properties` (a Modrinth version id, a
CurseForge file id for SWEM and Player Animator, or a maven version for MCA and GeckoLib); none is a
compile dependency, and no local `libs/` folder is needed.

`gradlew runtimeProbeJar` packages an opt-in validation driver, `src/runtimeProbe`, for disposable
test worlds only: on a real server and real clients it checks pairing, MCA's screen, portraits,
movement, hearts and the crouch-and-weapon recruit, and takes screenshots. It is never part of the
release jar. `scripts/validation/launch_client.py` launches an installer-created client profile for
it, and `scripts/validation/run_scenario.py` runs one production scenario with it. What was run, and
what it showed, is in [docs/VALIDATION.md](docs/VALIDATION.md).

`python3 scripts/parity.py` checks this repository against the Forge one: the same files on both
sides, the loader-neutral ones byte-identical, and no loader-specific file changed on one side only.
After porting a change to both, `python3 scripts/parity.py --write` records it.

## Licence

GPL-3.0-only, matching the rest of the MCA addon suite. See [LICENSE.md](LICENSE.md).

This mod ships no assets from any other mod and references them only by registry id. SWEM, Genetic
Animals, DragN's Livestock Overhaul, Icy's Better Horses and Alex's Mobs assets are not redistributed
here.
