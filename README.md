# MCA: Mob Compatibility

A Minecraft Forge 1.20.1 addon that teaches **MCA Reborn** to recognise the animals, mounts,
livestock and guards that other mods add, gives Guard Villagers' guards, Villager Recruits' recruits
and Villager Workers' workers a persistent MCA person, and stops those mods destroying your villagers.

This is the Forge 1.20.1 build. The NeoForge 1.21.1 build is the same mod with the same version
number, features, configuration, commands and datapack tags; the two are kept in step by a check
that runs against both repositories (see [Building](#building)).

Part of the MCA addon suite, alongside MCA: Quests, MCA: Crime, MCA: Reputation and
MCA: Conversations.

## What it does

**Guards, recruits and workers are MCA people.** These are the real guards, recruits and workers from
their own mods, with a persistent MCA person behind each of them. The native NPC is the body: it
moves, fights, works and answers its own mod's screens and orders exactly as that mod ships it. A real,
hidden MCA villager is the person: name, face, genes, clothes, personality, memories, hearts, family
tree and home. Clients draw the NPC as its person, in its own pose, armour and weapons. A plain click
opens MCA's screen, a crouching click opens the mod's own. MCA's editor edits the person, and MCA's
Follow, Stay and Move become the mod's own orders, under its own ownership rules. An adult MCA villager
can take a native role (enlist) and come home again as the same person, possessions and all, and a
promoted recruit keeps its person. How to play with it: [docs/PLAYER_GUIDE.md](docs/PLAYER_GUIDE.md).
The design, the capability table and the tested builds:
[docs/NATIVE_CHARACTER_COMPATIBILITY.md](docs/NATIVE_CHARACTER_COMPATIBILITY.md).

**It stops Guard Villagers destroying MCA villagers.** This is the headline, and it is not a
hypothetical. Guard Villagers converts a villager into a guard when you crouch and right-click it
holding a sword or crossbow, and the only check it makes is `instanceof Villager` — which MCA
villagers pass, because `VillagerEntityMCA` extends the vanilla villager class. Its default list of
convertible professions includes `none`, so **any unemployed MCA villager is convertible in a stock
install**. The conversion carries over the position, custom name and held item, then deletes the
villager. Family tree, marriage, children, genetics, personality and inventory all go with it, and
none of it can be recovered, because a guard has nowhere to keep any of it. This mod blocks that
interaction on both the server and the client — the client half matters, because Guard Villagers
runs its conversion on the client too — and by default turns the same gesture into a safe
enlistment: the villager becomes a real Guard Villagers guard holding that weapon and stays the same
MCA person behind it, ready to come home with `/mcamobcompat character release`. (The 0.2 behaviour,
where the villager takes MCA's own guard profession in place, is the `guardRecruitmentMode` option.)

**It stops Villager Recruits destroying MCA villagers.** Recruits turns a villager into a recruit when
it takes a recruit profession, turns jobless villagers into nobles, and turns a villager a player hires
at a noble into a recruit or worker. Each of those discards the villager. None of them ever runs on an
MCA villager: the first two are refused (the profession is put back), and a hire enlists the same
person through Recruits' own payment and ownership instead, or is refused unpaid
(`villagerConversion`).

**It makes Guard Villagers' guards real MCA guards.** Each guard is paired with a hidden, real MCA
villager that carries its MCA identity: name, personality, memories, hearts, family tree and home. A
plain right-click opens MCA's own screen for it, with MCA's own options, and MCA: Quests, Crime,
Reputation and Conversations all treat it as an MCA villager, because it is one. Crouch-right-click
still opens Guard Villagers' own inventory. The guard keeps its own combat AI, hitbox and inventory and
is drawn as its MCA person (`guardAppearance`); damage and death reach MCA, and MCA's Follow, Stay and
Move orders reach the guard. (Guard Villagers itself lets
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
Genetic Animals, DragN's Livestock Overhaul O-Animals and Alex's Mobs' mounts are all classified out
of the box, a tamed animal of any mod is a pet (a tamed horse is a mount), and any mod that follows
the Forge conventional entity tags is classified without anyone doing anything.

## What it deliberately does not do

- **It never breeds, feeds or modifies another mod's animals.** Genetics, gender, pregnancy, needs,
  training and breeding belong to the mod that added the animal. There is no call to `Animal.breed`
  or `spawnChildFromBreeding` anywhere in this jar, so there is no path by which it could override
  any of that.
- **It never writes to another mod's config or gamerules.** Appending the MCA villager ids to the
  Guard Villagers protection lists at runtime would have been the smaller change; editing somebody
  else's config file is not this mod's business.
- **It adds no mobs, no breeding system and no genetics.**
- **It never imitates MCA.** A guard, recruit or worker gets MCA behaviour through a real MCA villager,
  never through a lookalike screen, and looks like its person through MCA's own renderer. Enlisting
  replaces nobody: the same MCA villager stands behind the native body and comes back.
- **It never imitates the native mods either.** Combat, formations, work, hiring, payment, upkeep,
  promotion and every screen belong to Guard Villagers, Recruits and Workers. An NPC keeps its own
  hitbox, reach and stats whatever its MCA genes say.
- **It does not make villagers steer mounts that only a player can steer.** SWEM's horses, and a few
  of Alex's Mobs' mounts, hand control to a player rider only; a villager aboard is carried along
  with no say in where the animal goes, and that cannot be changed from outside those mods without a
  mixin. Such mounts refuse villagers instead. The change SWEM would need is written up in
  [docs/UPSTREAM.md](docs/UPSTREAM.md).

The full design, with the evidence for every claim, is in
[docs/MCA_MOB_COMPATIBILITY.md](docs/MCA_MOB_COMPATIBILITY.md).

## Installing

Requires **MCA Reborn** 7.6 or 7.7 for 1.20.1, and Forge 1.20.1. Install it on the server and on
every client, at the same version: the client learns which villager is an NPC's hidden person over
the `mcamobcompat:proxy_pair` channel (protocol 2 since 0.3.0), and a client without it, or with 0.2,
is refused at login. Everything else
is optional — the mod loads and works with any combination of the mods below installed or absent,
including none of them.

| Mod | Mod id | What it gets |
|---|---|---|
| Guard Villagers | `guardvillagers` | Conversion block and enlistment, village defence, friendly-fire prevention, guards as MCA people |
| Villager Recruits | `recruits` | Recruits, companions and nobles as MCA people; enlistment through a noble's hire; promotions keep the person; its conversions never take an MCA villager |
| Villager Workers | `workers` | Workers as MCA people; MCA's Move sends a worker back to work; enlistment through a noble's hire |
| SWEM | `swem` | `swem:swem_horse` as horse + mount, ownership recognised, refuses villager riders |
| Genetic Animals | `eanimod` | `enhanced_cow`, `enhanced_pig`, … classified as livestock |
| DragN's Livestock Overhaul | `dragnlivestock` | O-Animals classified as livestock, equines as mounts |
| Icy's Better Horses | `icys_better_horses` | Its horse owners recognised; owned or hitched horses refuse villager riders |
| Alex's Mobs | `alexsmobs` | Four ground mounts recognised; tamed animals protected as pets. See [docs/ALEXS_MOBS_AUDIT.md](docs/ALEXS_MOBS_AUDIT.md) |
| Golems Kill Creepers | `golemskillcreepers` | Nothing needs integrating: its gamerules are left alone and shown by `threats inspect` |

The exact versions tested are in [docs/DEPENDENCIES.json](docs/DEPENDENCIES.json), and what was tested,
and how, in [docs/VALIDATION.md](docs/VALIDATION.md).

## Commands

`/mcamobcompat character` is for every player; everything else needs permission level 2.

- **`character info`** — the name, native role, owner and current activity of the guard, recruit or
  worker you are looking at.
- **`character controls`** — opens its own mod's screen, as a crouching click does.
- **`character enlist <role>`** — gives the MCA adult you are looking at a native role, for example
  `guardvillagers:guard`, `recruits:bowman` or `workers:farmer`, through that mod's own hiring and
  costs (`playerEnlistment`).
- **`character release`** — sends the enlisted person you are looking at home: the same MCA villager
  comes back with everything the NPC carried (`playerCivilianReturn`). Whoever enlisted the person, a
  recruit's or worker's owner, or an operator.

Operators:

- **`characters status`** — records by adapter, state and origin, open transitions, quarantined and
  unreadable records, a census of the loaded actors and their persons, and every hook's state.
- **`characters inspect`** — everything about the character you are looking at: its record, its
  identity, its native role and which capabilities it has and lacks. `characters inspect <target>`
  takes a target selector instead.
- **`characters adapters`** — each adapter, its mod's version or platform availability, its options,
  its bindings and whether enlistment, the way home and promotions are available; and each hook.
- **`characters transitions`** — every open transition and every character's last one.
- **`characters repair <target> [relink|clear-stale-link|restore-civilian|settle]`** — the
  non-destructive repairs recovery deliberately leaves to an operator. Without an action it shows
  what it would do.

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

Character records live in the world save (`mcamobcompat_guard_identities`, schema 4). Schema 1, 2
and 3 records migrate automatically on first load, as guard records of origin `MIGRATED_GUARD_BRIDGE`:
UUIDs, proxy UUIDs, names, seed flags, personalities, hearts and migration markers are kept, and no
identity is re-rolled. Schema 4 adds each record's adapter, origin, lifecycle state, association
generation, appearance revision, civilian life and a journal of its last 16 transitions. A schema 2 world written while a guard's MCA villager was unreachable counted that
change twice, in both ledgers; migration subtracts it once and keeps the original record under
`LegacyV2`. Hand-edited or already-damaged schema 2 balances can be ambiguous, so back a world up first.

Queued hearts changes are kept apart from MCA's own balances and applied once, when both the player
and the MCA villager are available; they are stored as a long, so a balance can cross the whole
signed-int range without wrapping. A world saved by a newer version of this mod loads read-only and is
never overwritten, conflicting links are quarantined and reported, and an unloaded guard or MCA
villager is never deleted on a timer. This mod migrates only its own records; MCA and every other mod
remain responsible for their own world and entity formats.

## Configuration

`mcamobcompat-common.toml` chooses which integrations exist at all (`[modules]`, including `recruits`
and `workers`) and turns on debug logging. `mcamobcompat-server.toml` holds the gameplay settings,
per world, because all of them are server-authoritative. `mcamobcompat-client.toml` holds one
client preference: `rendering.mcaAppearance` (default: on), whether this client draws guards,
recruits and workers as their MCA persons where the server allows it. Turning it off never hides
anything; it only falls back to each mod's own renderer.

**Guard Villagers options:**
- **`guard_villagers.blockGuardConversion`** (boolean, default: on) — While on, crouch-right-clicking an MCA villager *while holding a sword or crossbow* blocks Guard Villagers' own conversion; with `recruitMcaVillagersAsGuards` also on (the default) that gesture recruits the villager instead, as `guardRecruitmentMode` says, rather than doing nothing. Either way it stops the irreversible conversion that would otherwise destroy a villager's family tree, marriage, children, genetics, personality and inventory.
- **`guard_villagers.recruitMcaVillagersAsGuards`** (boolean, default: on) — Let crouch-and-right-click with a weapon recruit an MCA villager instead of only blocking the gesture. A child and someone already serving are refused with a message. Requires `blockGuardConversion`.
- **`guard_villagers.guardRecruitmentMode`** (`NATIVE_GUARD` or `MCA_PROFESSION`, default: `NATIVE_GUARD`) — What the recruit gesture does. `NATIVE_GUARD`: the villager becomes a real Guard Villagers guard holding the weapon (Guard Villagers' own combat, patrol, food and inventory) and stays the same MCA person behind it, who can come home with `/mcamobcompat character release`; it needs `treatGuardsAsMcaGuards` and `guardMcaInteraction`, and falls back to `MCA_PROFESSION` without them. `MCA_PROFESSION`: the villager takes MCA's own `mca:guard` profession in place, keeps MCA's guard AI and holds the weapon, as in 0.2. Existing guards are never converted by changing this.
- **`guard_villagers.guardAppearance`** (`MCA` or `NATIVE`, default: `MCA`) — How clients draw a bridged guard: as its MCA person in Guard Villagers' own pose and equipment, or with Guard Villagers' own model. Collision, reach and combat are always Guard Villagers'.
- **`guard_villagers.treatGuardsAsMcaGuards`** (boolean, default: on) — When on, entities tagged `mcamobcompat:guard` (including Guard Villagers' `guardvillagers:guard`) are given MCA identities: a name, gender, personality and per-player hearts, persisted with the world. When off, no identities are created, but existing ones in the world save file are kept and resume when this is turned back on.
- **`guard_villagers.guardMcaInteraction`** (boolean, default: on) — When on, each bridged guard is paired with a hidden MCA villager, and a plain right-click opens MCA's own interaction screen through it; crouch-right-click still opens Guard Villagers' inventory GUI. When off, both gestures behave exactly as Guard Villagers ships them and no new proxy is created; a proxy that already exists stays hidden and keeps its MCA state. Requires `treatGuardsAsMcaGuards`.
- **`guard_villagers.protectMcaVillagers`** (boolean, default: on) — When a hostile mob attacks or targets an MCA villager, alert nearby guards. Guard Villagers matches the mobs it protects by entity id, and its default list names `minecraft:villager` — which MCA villagers are not — so without this they simply do not defend them.
- **`guard_villagers.preventGuardFriendlyFire`** (boolean, default: on) — Stop guards from targeting MCA villagers, and cancel any damage a guard deals to one, arrows included. The do-not-attack list in Guard Villagers is also matched by entity id and also names only `minecraft:villager`, so an MCA villager is not on it.
- **`guard_villagers.retaliateAgainstPlayers`** (boolean, default: off) — Also alert guards when a player attacks an MCA villager. Off by default: MCA has its own reputation and guard response for player misbehaviour, and turning a village hostile is a large consequence to add silently.
- **`guard_villagers.guardHelpRange`** (integer, default: 32, range: 1 to 128) — How far, in blocks, to look for guards to alert.
- **`guard_villagers.maxGuardsAlerted`** (integer, default: 8, range: 1 to 64) — Cap on how many guards a single attack alerts, so a large village does not turn one zombie into a full-village pathfinding storm.
- **`guard_villagers.followAddonTargets`** (boolean, default: on) — Push an attack target that another MCA addon set on a guard's hidden stand-in up to the guard itself, so MCA: Crime's lawful pursuit of a wanted player actually engages the guard. Off, such a target is reverted to the guard's own on the next tick and bridged guards never enforce Crime's law. Player targets only; other targets are always pushed up, except an MCA villager while `preventGuardFriendlyFire` is on.
- **`guard_villagers.guardShowNameTag`** (boolean, default: on) — Show a bridged guard's rolled name permanently above its head. Turning it off only stops new guards gaining one; an existing nameplate is never taken away.

**Villager Recruits options** (`[recruits]`):
- **`recruits.mcaCharacters`** (boolean, default: on) — Give recruits a persistent MCA person, created once and kept through promotions, restarts and portals. Off: recruits stay as Recruits ships them; persons already made are kept, hidden, and resume when it is turned back on.
- **`recruits.mcaInteraction`** (boolean, default: on) — A plain right-click on a recruit opens MCA's screen for its person; crouch-right-click opens Recruits' own screen (inventory and orders for the owner, the hire screen for an unhired recruit). Off: Recruits' own gestures only.
- **`recruits.appearance`** (`MCA` or `NATIVE`, default: `MCA`) — How clients draw a recruit: as its MCA person, in Recruits' pose and equipment, or with Recruits' own model.
- **`recruits.villagerConversion`** (`ENLIST` or `BLOCK`, default: `ENLIST`) — What happens when a noble's hire picks an MCA villager. `ENLIST`: the same person is hired through Recruits' own payment and ownership and becomes the recruit's (or worker's) MCA person. `BLOCK`: the hire is refused, unpaid. Recruits' automatic conversions (a villager taking a recruit profession, a jobless villager becoming a noble) are always refused for MCA villagers.
- **`recruits.lawEnforcement`** (boolean, default: off) — Give recruits' persons MCA's `mca:guard` profession, which MCA: Crime and MCA: Reputation treat as law enforcement, and let an addon's target on the person reach the recruit. Off by default: a soldier is not a constable, and an enemy faction's recruits must not police your village.

**Villager Workers options** (`[workers]`):
- **`workers.mcaCharacters`** (boolean, default: on) — Give workers a persistent MCA person, as for recruits.
- **`workers.mcaInteraction`** (boolean, default: on) — A plain right-click opens MCA's screen for the worker's person; crouch opens Workers' own screen.
- **`workers.appearance`** (`MCA` or `NATIVE`, default: `MCA`) — How clients draw a worker.

**Character options** (`[characters]`, every adapter):
- **`characters.editorPermission`** (`ANYONE`, `OWNER_OR_UNOWNED` or `OPERATORS`, default: `OWNER_OR_UNOWNED`) — Who may open MCA's villager editor on a guard, recruit or worker: anyone holding the item (MCA's own rule); the native owner of an owned NPC, anyone for an unowned one, and operators; or operators only.
- **`characters.editorAdminPermissionLevel`** (integer, default: 2, range: 0 to 4) — The permission level that may change a character's age, traits, personality, mood, memories and family links in MCA's editor. Such changes by anyone else are not applied.
- **`characters.editorSessionMinutes`** (integer, default: 10, range: 1 to 120) — How long an editor opened on a native NPC may keep saving. A later save, or one after somebody else saved the same character, is refused and the editor reopens on the latest version.
- **`characters.playerEnlistment`** (boolean, default: on) — Let players give an MCA adult a native role with `/mcamobcompat character enlist`, through the native mod's own hiring, payment and ownership.
- **`characters.playerCivilianReturn`** (boolean, default: on) — Let an NPC's owner, or whoever enlisted it, send an enlisted MCA person home with `/mcamobcompat character release`. Operators always may.
- **`characters.forwardMcaCommands`** (boolean, default: on) — Carry out MCA's Follow, Stay and Move buttons through the native mod's own orders and ownership rules. Off: those buttons do nothing to the NPC.
- **`characters.socialPause`** (boolean, default: off) — Hold a recruit or worker still for up to 30 seconds while a player talks to it through MCA's screen. Released by distance, damage, a target or the time limit; its native order resumes afterwards.

**Chore safety options:**
- **`chores.protectOwnedAnimals`** (boolean, default: on) — Protect any animal that reports an owner through vanilla (tamed horses, cats, wolves, Alex's Mobs' tamed animals, and so on), or through Icy's Better Horses. Ownership is read, never written.
- **`chores.protectedRoles`** (list of role names, default: `["mount", "pet"]`) — Roles that are never a valid hunting target. Names are the same as tag files under `data/mcamobcompat/tags/entity_types/`, matched case-insensitively. `livestock` is deliberately NOT included by default, because the hunting chore exists to produce meat; protecting livestock would break packs that replace vanilla farm animals. A pack can add one entry here.
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

### Native characters

Four server-side methods, for a sibling addon that wants to reach the right body or the right person:

- **`isCharacterActor(Entity)`** — true for a guard, recruit or worker this mod gives an MCA person.
  Wider than `isBridgedGuard`, which stays true for guards only. Works on both sides.
- **`characterIdentity(Entity actor)`** — the hidden MCA villager that is the actor's person, when
  loaded: the real villager to talk to, instead of assuming the visible body is one.
- **`characterActor(Entity identity)`** — the native actor whose person this hidden villager is: the
  body to aim speech, escorts or restraints at.
- **`characterUuid(Entity actorOrIdentity)`** — the character's canonical UUID: its MCA identity's
  own UUID, which never changes across promotions, restarts or portals.

### Guard Villagers bridge

When Guard Villagers is installed, guards tagged `mcamobcompat:guard` are eligible for a bridged identity unless disabled in config. Two API methods:

- **`isBridgedGuard(Entity)`** — returns true if the entity is a guard being given an MCA identity.
- **`getGuardIdentity(Entity)`** — returns `Optional<GuardIdentity>` with the guard's name, gender, personality, hearts per player, the UUID of its hidden MCA villager, creation time, and deceased flag. Hearts prefer the live MCA villager. Always empty on the client and for entities that `isBridgedGuard` rejects.

Three Forge-bus events, all in package `dev.otectus.mcamobcompat.api.event`:

- **`GuardInteractEvent(Player, Mob, InteractionHand)`** — fired for a plain, main-hand right-click on a bridged guard: ahead of forwarding the click to its hidden MCA villager when `guardMcaInteraction` is on, or ahead of leaving the click alone when it is off. Crouch is Guard Villagers' own gesture and never posts this event. Cancelable: if cancelled, the click is never forwarded and the underlying interaction event is cancelled with the event's `getCancellationResult()` (defaults to `SUCCESS`). Set a different `setCancellationResult()` to control what the client sees.
- **`GuardIdentityCreatedEvent(Mob, GuardIdentity)`** — fired server-side once per guard for the life of the world when it first receives an identity. Not cancellable; the identity already exists. Use this to seed your own per-NPC record.
- **`GuardHeartsChangedEvent(GuardIdentity, Mob, UUID player, int oldHearts, int newHearts)`** — fired server-side after `GuardIdentity.modHearts` changes a player's hearts. Carries a nullable guard entity: hearts are persisted by UUID and can be changed while the guard is not loaded in any level, in which case no entity is handed over. Each change is counted exactly once, even when it is made while MCA's villager is unreachable and is applied later. Never fired for a zero delta; not cancellable (the change is already stored).

## Building

```
gradlew build
```

Requires a JDK 17 (Gradle will fetch one if you do not have it). The jar is
`build/libs/mcamobcompat-forge-<version>+1.20.1.jar`. `build` runs the unit tests and three checks on
the finished jar: that it neither contains nor names a class from MCA or any optional mod, that it is
reobfuscated, and that it carries every shipped tag and resource and nothing from the test or probe
source sets. `gradlew installMod -PmodsDir=<your instance's mods folder>` builds and installs in one
step — use it rather than copying out of `build/libs` by hand, because the jar is only valid after
`reobfJar` has run.

The GameTests run on a real server against the real mods:

```
gradlew runGameTestServer                                          # MCA 7.6 and Guard Villagers
gradlew runGameTestServer -PmcaDev77                               # the same against MCA 7.7
gradlew runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems
gradlew runGameTestServer -PwithRecruits -PwithWorkers               # Villager Recruits and Workers
```

The task fails unless the server reports that every required test passed. Each `-Pwith*` flag adds
that mod to the run, with its libraries, and enables its own test suite; `-PwithWorkers` brings
Villager Recruits along. MCA, Architectury and the optional mods other than SWEM are fetched at
versions pinned in `gradle.properties`. The unit tests also probe the audited Villager Recruits and
Villager Workers jars (`RecruitsBindingProbeTest`, `WorkersBindingProbeTest`): every member the
bindings use, and every hook target, must exist in them exactly.

The native-character hooks are this mod's only Mixin use: one optional config,
`mcamobcompat.mixins.json`, with string-targeted, `require = 0` injections whose landing is counted at
start. Why, and their contracts, are in
[docs/NATIVE_CHARACTER_COMPATIBILITY.md](docs/NATIVE_CHARACTER_COMPATIBILITY.md#hook-contracts).

`libs/` is gitignored and holds the optional-mod jars that cannot be fetched: Guard Villagers, added
to every dev run when present, and SWEM for `-PwithSwem`. Those mods are not redistributable, and
nothing here compiles against them. `-PdevExtraMods` adds Genetic Animals and DLO from `libs/` too, but
testing those in a real instance via `installMod` is the easier path.

Third-party mods reach a dev run through `remapDevMods`, which writes development copies with their
SRG member names rewritten to the official names a dev run uses. MCA ships its mixins without a
refmap, so it cannot start in a dev run without this. The copies live in `build/devmods` and are never
shipped.

`gradlew runtimeProbeJar` packages an opt-in validation driver, `src/runtimeProbe`, for disposable
test worlds only: on a real server and real clients it checks pairing, MCA's screen, portraits,
movement, hearts, guards, recruits and workers drawn as their MCA persons, MCA's editor saved on one
client and seen on the other, the native screens, enlistment and the way home, and takes screenshots. It is never part of the
release jar. `scripts/validation/launch_client.py` launches an installer-created client profile for
it, and `scripts/validation/run_scenario.py` runs one production scenario with it. What was run, and
what it showed, is in [docs/VALIDATION.md](docs/VALIDATION.md).

`python3 scripts/parity.py` checks this repository against the NeoForge one: the same files on both
sides, the loader-neutral ones byte-identical, and no loader-specific file changed on one side only.
After porting a change to both, `python3 scripts/parity.py --write` records it.

## Licence

GPL-3.0-only, matching the rest of the MCA addon suite. See [LICENSE.md](LICENSE.md).

This mod ships no assets from any other mod and references them only by registry id. SWEM, Genetic
Animals, DragN's Livestock Overhaul and Alex's Mobs assets are not redistributed here.
