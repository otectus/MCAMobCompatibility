# MCA: Mob Compatibility — NeoForge 1.21.1

Semantic mob classification, protection for animals during MCA chores, and a persistent MCA identity bridge for Guard Villagers.

## Install

Install the same addon JAR on the server and every client. This port requires Minecraft **1.21.1**, Java **21**, NeoForge **21.1.248 or newer within 21.1.x**, and **MCA 7.7.36-beta.3+1.21.1**. Required network protocol `1` prevents an unsupported client/server combination from silently losing proxy pairing.

| Optional integration | Tested artifact | Behavior |
|---|---|---|
| Guard Villagers | 2.4.12, Modrinth version `JyXugpy2` | Conversion protection, recruitment in place, defense, MCA proxy identities |
| SWEM | 1.6.12 for NeoForge 1.21.1 | Horse/mount classification and ownership reads |
| Genetic Animals (`eanimod`) | No target NeoForge build verified | Optional legacy entity tags retained |
| DragN's Livestock Overhaul (`dragnlivestock`) | No target NeoForge build verified | Optional legacy entity tags retained |

The tested SWEM runtime also includes GeckoLib 4.7.5.1 and Player Animator 2.0.4+1.21.1. No third-party mod is bundled or required to compile this addon. See [dependency records](docs/DEPENDENCIES.json) and [validation results](docs/VALIDATION.md) for exact versions, hashes, companion combinations and limitations.

## Behavior

A bridged guard keeps its own model, combat AI and inventory. A real, hidden MCA villager carries its relationships, family, name, personality and hearts. Plain right-click opens MCA; crouch-right-click invokes Guard Villagers' inventory interaction, subject to that mod's permission checks. MCA and companion portraits substitute the exact paired guard.

Crouch-clicking an adult MCA villager with a tagged recruitment weapon changes its profession to `mca:guard` in place. Its UUID and MCA identity remain intact. Existing equipment, babies and repeated recruitment are protected; survival transfers one weapon, and creative copies one with its item components. Disabling recruitment retains the conversion block when `blockGuardConversion` remains enabled.

Classification uses ten semantic role tags plus the MCA recognition tag. Horses imply mounts, while cattle, sheep, pigs and poultry imply livestock. Protected ownership/name/leash/role checks stop MCA chore damage; ordinary livestock remains huntable by default. Animal breeding, genetics, pregnancy, tack and ownership are left to their original mods.

The bridge intentionally writes its own SavedData and MCA state through verified public APIs. It does not edit other mods' configuration, register new mobs/items/blocks, or use mixins/access transformers. It does not add autonomous riding; proxy mount/pick-up actions are neutralized so the identity stays with its guard.

## Commands

All `/mcamobcompat` commands require permission level 2. Console use supports commands that do not require looking at an entity.

- `status`, `classify [targets]`, `dump <namespace>`: integration status and actual registered entity roles.
- `mca`: resolved runtime class, personality API and binding diagnostics.
- `guards status`, `guards inspect [target]`, `guards proxies`: identity, hearts and link diagnostics. An unresolved UUID can mean an unloaded entity; it is not proof of an orphan.
- `guards purge <days>`: remove sufficiently old deceased records; living and quarantined records remain.
- `guards rebuild`: explicitly destructive replacement of resolved proxies for loaded guards. It detaches their existing MCA identity. Pending/unloaded/conflicting links are not replaced. Use backups and diagnosis before this operator repair; it is not a migration or reconnect step.

## Save migration

Schema 1/2 identity records migrate to schema 3. UUIDs, names, seed flags, namespaced/historical personalities and migration markers persist. Schema 2 offline writes duplicated deltas into both ledgers: migration subtracts the pending value from the snapshot and retains the original under `LegacyV2`. Manually edited or already damaged schema 2 balances can be ambiguous; see [migration notes](docs/PORTING.md).

Queued changes are separate from MCA snapshots and flush once when both the player and proxy are available. Pending deltas use a long so balances can cross the whole signed-int range without wrapping. Unknown future schemas remain read-only; duplicate saved owners are quarantined and preserved. Unloaded guards/proxies are never deleted by a timeout.

Back up a world before changing Minecraft/MCA versions. This addon migrates its own identity records; MCA and the other entity mods remain responsible for their own world/entity formats.

## Configuration

`mcamobcompat-common.toml` chooses which integrations exist at all.
`mcamobcompat-server.toml` holds the gameplay settings, per world, because all of them are
server-authoritative.

**Guard Villagers options:**
- **`guard_villagers.blockGuardConversion`** (boolean, default: on) — While on, crouch-right-clicking an MCA villager *while holding a sword or crossbow* blocks Guard Villagers' own conversion; with `recruitMcaVillagersAsGuards` also on (the default) that gesture recruits the villager as a guard in place instead, rather than doing nothing. Either way it stops the irreversible conversion that would otherwise destroy a villager's family tree, marriage, children, genetics, personality and inventory.
- **`guard_villagers.treatGuardsAsMcaGuards`** (boolean, default: on) — When on, entities tagged `mcamobcompat:guard` (including Guard Villagers' `guardvillagers:guard`) are given MCA-style identities: a name, gender, personality and per-player hearts. These are seeded from the guard's UUID once and persisted with the world. When off, no identities are created, but existing ones in the world save file are kept and resume when this is turned back on.
- **`guard_villagers.guardMcaInteraction`** (boolean, default: on) — When on, a plain right-click on a bridged guard opens MCA's own interaction screen through a hidden proxy villager, with MCA's own options; crouch-right-click still opens Guard Villagers' inventory GUI. When off, both gestures behave exactly as Guard Villagers ships them, and new proxy creation stops. Existing pairs stay hidden, linked and synchronized. Requires `treatGuardsAsMcaGuards`.
- **`guard_villagers.recruitMcaVillagersAsGuards`** (boolean, default: on) — Let crouch-and-right-click with a convertible weapon recruit an MCA villager as a guard in place (profession becomes `mca:guard`, name/family/genetics/personality untouched) instead of only blocking the gesture. Requires `blockGuardConversion`.
- **`guard_villagers.protectMcaVillagers`** (boolean, default: on) — When a hostile mob attacks or targets an MCA villager, alert nearby guards. Guard Villagers matches the mobs it protects by entity id, and its default list names `minecraft:villager` — which MCA villagers are not — so without this they simply do not defend them.
- **`guard_villagers.preventGuardFriendlyFire`** (boolean, default: on) — Stop guards from targeting or damaging MCA villagers. The do-not-attack list in Guard Villagers is also matched by entity id and also names only `minecraft:villager`, so an MCA villager is not on it.
- **`guard_villagers.retaliateAgainstPlayers`** (boolean, default: off) — Also alert guards when a player attacks an MCA villager. Off by default: MCA has its own reputation and guard response for player misbehaviour, and turning a village hostile is a large consequence to add silently.
- **`guard_villagers.guardHelpRange`** (integer, default: 32, range: 1 to 128) — How far, in blocks, to look for guards to alert.
- **`guard_villagers.maxGuardsAlerted`** (integer, default: 8, range: 1 to 64) — Cap on how many guards a single attack alerts, so a large village does not turn one zombie into a full-village pathfinding storm.
- **`guard_villagers.guardShowNameTag`** (boolean, default: on) — Show a bridged guard's rolled name permanently above its head. Turning it off only stops new guards gaining one; an existing nameplate is never taken away.

**Chore safety options:**
- **`chores.protectOwnedAnimals`** (boolean, default: on) — Protect any animal that reports an owner through vanilla (tamed horses, cats, wolves, etc.). Ownership is read, never written.
- **`chores.protectedRoles`** (list of role names, default: `["mount", "pet"]`) — Roles that are never a valid hunting target. Names are the same as tag files under `data/mcamobcompat/tags/entity_type/`, matched case-insensitively. `livestock` is deliberately NOT included by default, because the hunting chore exists to produce meat; protecting livestock would break packs that replace vanilla farm animals. A pack can add one entry here.
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

`dev.otectus.mcamobcompat.api.MobCompatibilityAPI` is a small static API:
`isLivestock`, `isMount`, `isGuard`, `isPet`, `getRoles(Entity)`, `getRoles(EntityType)`,
`getOwner(Entity)`, and `registerProvider(...)` for contributing per-entity roles that no tag could
express. Depend on that class and nothing else in this jar.

### Guard Villagers bridge

When Guard Villagers is installed, guards tagged `mcamobcompat:guard` are eligible for a bridged identity unless disabled in config. Two new API methods:

- **`isBridgedGuard(Entity)`** — returns true if the entity is a guard being given an MCA-style identity.
- **`getGuardIdentity(Entity)`** — returns `Optional<GuardIdentity>` with the guard's name, gender, personality, hearts per player, creation time, and deceased flag. Always empty on the client and for entities that `isBridgedGuard` rejects.

Three NeoForge-bus events, all in package `dev.otectus.mcamobcompat.api.event`:

- **`GuardInteractEvent(Player, Mob, InteractionHand)`** — fired for a plain, main-hand right-click on a bridged guard: ahead of forwarding the click to its hidden MCA proxy when `guardMcaInteraction` is on, or ahead of leaving the click alone when it is off. Crouch is Guard Villagers' own gesture and never posts this event. Cancelable: if cancelled, the click is never forwarded and the underlying `PlayerInteractEvent.EntityInteract` is cancelled with the event's `getCancellationResult()` (defaults to `SUCCESS`). Set a different `setCancellationResult()` to control what the client sees.
- **`GuardIdentityCreatedEvent(Mob, GuardIdentity)`** — fired server-side once per guard for the life of the world when it first receives an identity. Not cancellable; the identity already exists. Use this to seed your own per-NPC record.
- **`GuardHeartsChangedEvent(GuardIdentity, Mob, UUID player, int oldHearts, int newHearts)`** — fired server-side after a nonzero `GuardIdentity.modHearts` call. MCA-originated gifts/dialogue changes update snapshots without emitting this API event. Carries a nullable guard entity: hearts are persisted by UUID and can be changed while the guard is not loaded in any level, in which case no entity is handed over. Never fired for a zero delta; not cancellable (the change is already stored).

## Build and test

```sh
./gradlew build
./gradlew runGameTestServer -PwithGuards
./gradlew runGameTestServer -PwithGuards -PwithSwem
./gradlew runClient -PwithGuards
./gradlew installMod -PmodsDir="/path/to/instance/mods"
```

Use `gradlew.bat` on Windows. Gradle 8.12 provisions Java 21 through its configured toolchain. The build uses ModDevGradle 2.0.141 and official 1.21.1 names. It produces `build/libs/mcamobcompat-neoforge-2.0+1.21.1.jar`; no reobfuscation step is needed.

`build` and `installMod` require both artifact gates: Java 21 bytecode, expanded NeoForge metadata, all tag/resource paths, no legacy Forge Java references, no shaded optional mod code, and the exact single-class MCA reflection allowlist. Unit and GameTest sources are separate from production. `runtimeProbeJar` creates a separate, opt-in driver for isolated production tests; never install that driver in a playing world.

## License

GPL-3.0-only; see [LICENSE.md](LICENSE.md). Third-party mod JARs and their assets are not redistributed. Screenshots under `docs/validation` document validation sessions.
