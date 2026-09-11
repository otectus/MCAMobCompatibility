# MCA: Mob Compatibility

A Minecraft Forge 1.20.1 addon that teaches **MCA Reborn** to recognise the animals, mounts,
livestock and guards that other mods add — and stops one of them destroying your villagers.

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
interaction.

**It makes guards actually defend MCA villagers.** Guard Villagers decides who to protect, and who
never to attack, by matching entity ids against lists in its own config — and both lists ship naming
`minecraft:villager`. An MCA villager is `mca:male_villager`, so out of the box it is on neither
list: guards ignore it when a zombie attacks it, and nothing stops a guard attacking it. Both gaps
are closed here.

**It sorts every mob into semantic roles** — `livestock`, `cattle`, `sheep`, `pig`, `poultry`,
`horse`, `mount`, `pet`, `guard`, `humanoid_npc` — using datapack tags, so MCA and other mods can
ask "is this thing a cow?" without anyone hardcoding a list of cows. Vanilla animals, SWEM horses,
Genetic Animals, and DragN's Livestock Overhaul O-Animals are all classified out of the box, and any
mod that follows the Forge conventional entity tags is classified without anyone doing anything.

## What it deliberately does not do

- **It never breeds, feeds or modifies another mod's animals.** Genetics, gender, pregnancy, needs,
  training and breeding belong to the mod that added the animal. There is no call to `Animal.breed`
  or `spawnChildFromBreeding` anywhere in this jar, so there is no path by which it could override
  any of that.
- **It never writes to MCA's data.** Not to the family tree, not to relationships, not to genetics.
- **It never writes to another mod's config.** Appending the MCA villager ids to the Guard Villagers
  protection lists at runtime would have been the smaller change; editing somebody else's config
  file is not this mod's business.
- **It adds no mobs, no breeding system and no genetics.**
- **It does not convert MCA villagers into guards, in either direction.** Blocking is the whole
  point; there is no "safe conversion" to offer, because MCA identity cannot be reconstructed from a
  guard.
- **No autonomous mount AI.** MCA mounts villagers only on player command, and some mounts like SWEM
  reject villager riders; this mod cannot bridge that without a mixin. Technical context is in [MCA_MOB_COMPATIBILITY.md](MCA_MOB_COMPATIBILITY.md) §11.

## Installing

Requires **MCA Reborn** (7.6 or newer) and Forge 1.20.1. Everything else is optional — the mod loads
and works with any combination of the mods below installed or absent, including none of them.

Recognised out of the box:

| Mod | Mod id | What it gets |
|---|---|---|
| Guard Villagers | `guardvillagers` | Conversion block, village defence, friendly-fire prevention, bridge to MCA guards (on by default) |
| SWEM | `swem` | `swem:swem_horse` as horse + mount, ownership recognised |
| Genetic Animals | `eanimod` | `enhanced_cow`, `enhanced_pig`, … classified as livestock |
| DragN's Livestock Overhaul | `dragnlivestock` | O-Animals classified as livestock, equines as mounts |

## Commands

`/mcamobcompat` (permission level 2):

- **`status`** — which optional mods are installed, which integrations are active, and how many
  entity types hold each role.
- **`classify`** — the roles of the entity you are looking at. `classify <targets>` takes a target
  selector instead. Bridged guards show their identity (name, gender, personality, deceased flag). For entities with role `mount`, also reports whether they are saddled, who owns them, and who is riding them.
- **`guards status`** — count of bridged guard identities stored in the world, alive and deceased.
- **`guards inspect`** — the identity of the guard you are looking at (UUID, name, gender, personality, your hearts with it). `guards inspect <target>` takes a target selector instead.
- **`guards purge <days>`** — remove deceased guard identities older than N in-game days. Does not touch living guards.
- **`mca`** — MCA binding diagnostics: the resolved package root, MCA's version, and the state of each reflective binding (ok or missing).
- **`dump <namespace>`** — every entity type a mod registered, with its roles. This is how you find
  out what a mod *actually* registered rather than what its wiki says, and it is how you would
  onboard a mod this addon has never heard of. Long dumps go to the server log in full.

## Configuration

`mcamobcompat-common.toml` chooses which integrations exist at all.
`mcamobcompat-server.toml` holds the gameplay settings, per world, because all of them are
server-authoritative.

**Guard Villagers options:**
- **`guard_villagers.blockGuardConversion`** (boolean, default: on) — While on, crouch-right-clicking an MCA villager *while holding a sword or crossbow* blocks Guard Villagers' own conversion; with `recruitMcaVillagersAsGuards` also on (the default) that gesture recruits the villager as a guard in place instead, rather than doing nothing. Either way it stops the irreversible conversion that would otherwise destroy a villager's family tree, marriage, children, genetics, personality and inventory.
- **`guard_villagers.treatGuardsAsMcaGuards`** (boolean, default: on) — When on, entities tagged `mcamobcompat:guard` (including Guard Villagers' `guardvillagers:guard`) are given MCA-style identities: a name, gender, personality and per-player hearts. These are persisted with the world and regenerated from the guard's UUID so the same guard always gets the same identity across restarts. When off, no identities are created, but existing ones in the world save file are kept and resume when this is turned back on.
- **`guard_villagers.guardMcaInteraction`** (boolean, default: on) — When on, a plain right-click on a bridged guard opens MCA's own interaction screen through a hidden proxy villager, with MCA's own options; crouch-right-click still opens Guard Villagers' inventory GUI. When off, both gestures behave exactly as Guard Villagers ships them, and no proxy is created. Requires `treatGuardsAsMcaGuards`.
- **`guard_villagers.recruitMcaVillagersAsGuards`** (boolean, default: on) — Let crouch-and-right-click with a convertible weapon recruit an MCA villager as a guard in place (profession becomes `mca:guard`, name/family/genetics/personality untouched) instead of only blocking the gesture. Requires `blockGuardConversion`.
- **`guard_villagers.protectMcaVillagers`** (boolean, default: on) — When a hostile mob attacks or targets an MCA villager, alert nearby guards. Guard Villagers matches the mobs it protects by entity id, and its default list names `minecraft:villager` — which MCA villagers are not — so without this they simply do not defend them.
- **`guard_villagers.preventGuardFriendlyFire`** (boolean, default: on) — Stop guards from targeting MCA villagers. The do-not-attack list in Guard Villagers is also matched by entity id and also names only `minecraft:villager`, so an MCA villager is not on it.
- **`guard_villagers.retaliateAgainstPlayers`** (boolean, default: off) — Also alert guards when a player attacks an MCA villager. Off by default: MCA has its own reputation and guard response for player misbehaviour, and turning a village hostile is a large consequence to add silently.
- **`guard_villagers.guardHelpRange`** (integer, default: 32, range: 1 to 128) — How far, in blocks, to look for guards to alert.
- **`guard_villagers.maxGuardsAlerted`** (integer, default: 8, range: 1 to 64) — Cap on how many guards a single attack alerts, so a large village does not turn one zombie into a full-village pathfinding storm.
- **`guard_villagers.guardShowNameTag`** (boolean, default: on) — Show a bridged guard's rolled name permanently above its head. Turning it off only stops new guards gaining one; an existing nameplate is never taken away.

**Chore safety options:**
- **`chores.protectOwnedAnimals`** (boolean, default: on) — Protect any animal that reports an owner through vanilla (tamed horses, cats, wolves, etc.). Ownership is read, never written.
- **`chores.protectedRoles`** (list of role names, default: `["mount", "pet"]`) — Roles that are never a valid hunting target. Names are the same as tag files under `data/mcamobcompat/tags/entity_types/`, matched case-insensitively. `livestock` is deliberately NOT included by default, because the hunting chore exists to produce meat; protecting livestock would break packs that replace vanilla farm animals. A pack can add one entry here.
- **`chores.protectNamedAnimals`** (boolean, default: on) — Protect any animal with a custom name. A name tag is the vanilla signal that a player cares about this particular animal.
- **`chores.protectLeashedAnimals`** (boolean, default: on) — Protect any animal on a lead. A leashed animal is being taken somewhere by somebody.
- **`chores.abandonHuntOnProtectedTarget`** (boolean, default: on) — When a hunting villager is stopped from hitting a protected animal, also abandon the chore. Without this, the hunting task keeps trying to hit an animal it can never kill, and the villager stands there swinging forever. Requires the reflective MCA binding; where that is unavailable the attack is still blocked, but the chore is not abandoned.

**SWEM options:**
- **`swem.recognizeAsMounts`** (boolean, default: on) — Treat SWEM horses as mounts. Breeding, needs, skills and tack stay entirely with SWEM — this mod only recognises them, and never writes to them.

**Genetic Animals options:**
- **`genetic_animals.recognizeLivestock`** (boolean, default: on) — Treat the animals from Genetic Animals as livestock. Genetics, gender, pregnancy and breeding all remain owned by Genetic Animals itself.

**DragN's Livestock Overhaul options:**
- **`dragn_livestock.recognizeLivestock`** (boolean, default: on) — Treat the O-Animals as livestock. Genders, breeding and genetics all remain owned by DragN's Livestock Overhaul itself.

There are no per-entity toggles and there never will be: classification is extended with datapack
tags, not config lists.

## For pack authors

Everything this mod knows is a datapack tag. See **[DATAPACK.md](DATAPACK.md)** for how to classify
a mod it has never heard of — it takes one JSON file and no code.

## For mod authors

`dev.otectus.mcamobcompat.api.MobCompatibilityAPI` is a small, read-only, static API:
`isLivestock`, `isMount`, `isGuard`, `isPet`, `getRoles(Entity)`, `getRoles(EntityType)`,
`getOwner(Entity)`, and `registerProvider(...)` for contributing per-entity roles that no tag could
express. Depend on that class and nothing else in this jar.

### Guard Villagers bridge

When Guard Villagers is installed, guards tagged `mcamobcompat:guard` are eligible for a bridged identity unless disabled in config. Two new API methods:

- **`isBridgedGuard(Entity)`** — returns true if the entity is a guard being given an MCA-style identity.
- **`getGuardIdentity(Entity)`** — returns `Optional<GuardIdentity>` with the guard's name, gender, personality, hearts per player, creation time, and deceased flag. Always empty on the client and for entities that `isBridgedGuard` rejects.

Three Forge-bus events, all in package `dev.otectus.mcamobcompat.api.event`:

- **`GuardInteractEvent(Player, Mob, InteractionHand)`** — fired for a plain, main-hand right-click on a bridged guard: ahead of forwarding the click to its hidden MCA proxy when `guardMcaInteraction` is on, or ahead of leaving the click alone when it is off. Crouch is Guard Villagers' own gesture and never posts this event. Cancelable: if cancelled, the click is never forwarded and the underlying `PlayerInteractEvent.EntityInteract` is cancelled with the event's `getCancellationResult()` (defaults to `SUCCESS`). Set a different `setCancellationResult()` to control what the client sees.
- **`GuardIdentityCreatedEvent(Mob, GuardIdentity)`** — fired server-side once per guard for the life of the world when it first receives an identity. Not cancellable; the identity already exists. Use this to seed your own per-NPC record.
- **`GuardHeartsChangedEvent(GuardIdentity, Mob, UUID player, int oldHearts, int newHearts)`** — fired server-side after a player's hearts with a guard change. Carries a nullable guard entity: hearts are persisted by UUID and can be changed while the guard is not loaded in any level, in which case no entity is handed over. Never fired for a zero delta; not cancellable (the change is already stored).

## Building

```
gradlew build
```

Requires a JDK 17 (Gradle will fetch one if you do not have it). `gradlew installMod -PmodsDir=<your
instance's mods folder>` builds and installs in one step — use it rather than copying out of
`build/libs` by hand, because the jar is only valid after `reobfJar` has run.

`libs/` is gitignored and holds optional-mod jars used only to populate a dev run's mods folder;
those mods are not redistributable, and nothing here compiles against them. Guard Villagers is added
to `runClient` automatically when present. `-PdevExtraMods` adds SWEM, Genetic Animals and DLO too,
but they need GeckoLib, PlayerAnimator and Patchouli, which this build does not supply — testing
those in a real instance via `installMod` is the easier path.

## Licence

GPL-3.0-only, matching the rest of the MCA addon suite. See [LICENSE.md](LICENSE.md).

This mod ships no assets from any other mod and references them only by registry id. SWEM, Genetic
Animals and DragN's Livestock Overhaul assets are All Rights Reserved and are not redistributed here.
