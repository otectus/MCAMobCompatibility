# Native characters: compatibility, capabilities and design (0.3.0)

> These are the real guards, recruits and workers from their own mods, with a persistent MCA person
> behind each of them.

From 0.3.0, a Guard Villagers guard is an MCA character on NeoForge 1.21.1; on Forge 1.20.1 so are a
Villager Recruits recruit and a Villager Workers worker, which have no NeoForge 1.21.1 build. The native entity, the **actor**, is the body: it moves, fights, works, takes damage
and answers its own mod's screens and orders, exactly as that mod ships it. A real, hidden MCA
villager, the **identity**, is the person: name, face, genes, clothes, personality, memories, hearts,
family tree and home. Clients draw the actor as its person, MCA's editor edits the person, and an MCA
adult can take a native role and come home again as the same person.

This document is the compatibility matrix, the capability table, the hook contracts and the design
decisions behind them. The player-facing guide is [PLAYER_GUIDE.md](PLAYER_GUIDE.md); what was run, and
what it showed, is in [VALIDATION.md](VALIDATION.md).

## Tested artifacts

NeoForge 21.1.248, Java 21. Every third-party jar, with its source and size, is also listed in
[DEPENDENCIES.json](DEPENDENCIES.json).

| Mod | Build | SHA-256 | Tested by |
|---|---|---|---|
| MCA Reborn | 7.7.36-beta.3+1.21.1 | `de4763d34a41cb84ffa392b87cdb23191beddda2323b56552a1a2fcd7c436fc3` | unit probes, GameTests, production |
| MCA Reborn | 7.7.33+1.21.1 | `8838b1ffd0265429373db584fd4f3f482ef73316a3f4092ef33e87fee5f686d7` | unit probes: binding and editor-hook target |
| MCA Reborn | 7.7.22+1.21.1 | `c5c76d7994a206dace8a3e5b52228af377f31ede33eb1e1b7d2915ffa6db2544` | unit probes |
| MCA Reborn | 7.7.13+1.21.1 | `1db57d75cf6df7407a2eaa80c72ef4b8a53ce933bf4d863b47e841fb7fa74c5f` | unit probes |
| MCA Reborn | 7.7.0+1.21.1 (Modrinth `EGdYukBI`) | `f2951627a4c678f7cd09e95ada4f6d709c3fd144f919dd3ab36a9ee17887fbff` | unit probes |
| Guard Villagers | 2.4.12 (Modrinth `JyXugpy2`) | `aab03d59216bd931a05434b2527e0d5b7641793bd0178a9958100579c4f11407` | GameTests, production |
| Villager Recruits | **no NeoForge 1.21.1 build** (Modrinth, checked 2026-10-20) | — | adapter reports itself unavailable |
| Villager Workers | **no NeoForge 1.21.1 build** (Modrinth, checked 2026-10-20) | — | adapter reports itself unavailable |

The Forge 1.20.1 build is the same mod, with the same configuration keys; its tested builds, Villager
Recruits 1.15.2 and Villager Workers 2.0.3 among them, are listed in that repository's copy of this
document. Here the Recruits and Workers adapters, their hooks and their transitions are **unavailable**:
`PlatformArtifacts` lists only Guard Villagers for this loader, so even a mod installed under one of
their ids is not claimed, and both say so at startup and in `/mcamobcompat characters adapters`.

## Adapters

One adapter per native mod, chosen per entity type and cached, in this order: **Villager Workers,
then Villager Recruits, then Guard Villagers**. A worker is a Recruits recruit by inheritance, so the
Workers adapter is asked first, and a worker has one adapter and one identity.

| Adapter | Claims | Never claims |
|---|---|---|
| `mcamobcompat:workers` | `workers:` farmer, lumberjack, miner, builder, merchant, fisherman, animal_farmer, courier, cook | its work-area markers and fishing bobber |
| `mcamobcompat:recruits` | every `recruits:` recruit: recruit, recruit_shieldman, bowman, crossbowman, nomad, horseman, messenger, scout, patrol_leader, captain, siege_engineer, villager_noble | anything that is not a Recruits recruit |
| `mcamobcompat:guardvillagers` | entities in the `mcamobcompat:guard` tag, `guardvillagers:guard` among them | MCA villagers |

Tags classify entities. A tag alone never makes an entity a character, since only an adapter knows
its mod's orders, screens and lifecycle.

### Capabilities

What each adapter does, with every option at its default. The Recruits and Workers columns describe
the Forge 1.20.1 build; on NeoForge 1.21.1 both are unavailable. "Native" means through the
mod's own code: its own screen, its own orders, its own hiring, its own payment.

| Capability | Guard Villagers | Villager Recruits | Villager Workers |
|---|---|---|---|
| Persistent MCA identity (adopted actors) | yes | yes | yes |
| Plain click opens MCA's screen; crouch-click opens the mod's own screen | yes | yes | yes |
| Drawn as the MCA person, in the actor's own pose and equipment | yes | yes | yes |
| MCA's editor on the actor (appearance, genes, name) | yes | yes | yes |
| MCA's Follow / Stay / Move carried out as native orders | follow, patrol, stop | follow (1), hold (2), wander (0), owner only | follow, hold, back to work (6), owner only |
| Enlist an MCA adult | recruit gesture, or `/mcamobcompat character enlist guardvillagers:guard` | a noble's hire, or `character enlist recruits:<role>` | a noble's hire, or `character enlist workers:<job>` |
| Return an enlisted person to civilian life | yes | yes, through Recruits' own dismissal | yes, work and home areas released |
| Promotion keeps the character (host replacement) | not applicable | yes | not applicable |
| An addon's target on the identity reaches the actor (MCA: Crime) | yes (`followAddonTargets`) | only with `[recruits] lawEnforcement` | no |
| Law enforcement to MCA: Crime / Reputation (`mca:guard` profession) | yes | only with `[recruits] lawEnforcement` (off) | never |
| Recruits' conversions of MCA villagers refused | — | profession conversion and nobles: always; noble hire: enlists the same person or is refused (`villagerConversion`) | through Recruits' hire |

On NeoForge 1.21.1 the Guard Villagers column applies as written; the other two are **unavailable**.

## How a character works

- **One actor, one person.** The identity is a real `mca:*_villager`, invisible, without AI, silent,
  weightless and unable to pick anything up. It is pinned to its actor every tick, its hitbox is
  nested inside the actor's, and it is drawn by nobody. The association is persistent data on both
  entities plus a store record. There is never a second physical AI.
- **The client knows exactly.** Protocol 2 of `mcamobcompat:proxy_pair` sends each association with
  the character UUID, both entities' UUIDs and runtime ids, the dimension, the association generation,
  the appearance revision and the adapter's capabilities. A message from an older generation never
  replaces a newer one. No family tree, hearts or native data is ever sent.
- **Drawing.** For an actor whose character is drawn as MCA, the client draws a detached MCA villager,
  the *render view*, with MCA's own renderer: MCA's model, skin, face, hair, clothes, dyes, custom skin
  and genetic proportions. It is posed in the actor's walk, swing, arm poses, held items, armour and
  mount. The view copies the identity's synced data, is cached per character (512 at most) and is
  rebuilt when the appearance revision changes. Shadow, fire and leash stay the native renderer's, so
  each appears once. If MCA's renderer fails for an actor type, that type is drawn natively from then
  on, and the failure is logged once. A portrait of the hidden identity (MCA: Conversations, MCA:
  Quests) draws the person.
- **Editing.** MCA's villager editor used on an actor opens MCA's own editor for its identity, gated
  by `[characters] editorPermission`. A save goes through MCA's own request, bracketed by the
  editor-save hook. The session is checked: same player, still in range, same association generation,
  no newer save by someone else, store writable. The payload is then rebuilt as the identity's current
  state plus the fields this player may change. Cosmetic fields are name, skin, clothes, hair, colours,
  dyes and genes. Age, traits, personality, mood, memories, infection and family links need
  `editorAdminPermissionLevel`. A non-finite or out-of-range value, an oversized payload, and a change
  that would make a child are refused before MCA applies anything. A conflicting save is refused, and
  the editor reopens on the latest state.
- **Orders.** MCA's movement state on the identity is consumed once per change and handed to the
  adapter as a native order. A refused order is reverted to MCA's Move, and the player is told why.
  The order last applied is remembered on the identity, so a restart does not apply it again over an
  order given through the native mod's own screen.

## Transitions, and what recovery guarantees

A character changes body only through a journaled transition: `ENLIST`, `RETURN_TO_CIVILIAN` or
`HOST_REPLACEMENT`. Its stages are `PREPARED`, then `ACTOR_READY`, then `ASSOCIATION_COMMITTED`, then
`CLEANED`, or `ROLLED_BACK` from any point before the commit. Each record keeps its last 16
transitions.

- **Enlist.** The native mod creates the actor through its own initialisation and, for Recruits and
  Workers, its own `handleRecruiting`, which checks and takes the payment, sets the owner and group and
  posts its own `Hired` event. What was taken is measured into the transition's receipt. A guard is
  initialised the way Guard Villagers' own conversion does it: the weapon, a variant, persistence,
  full drop chances, and the enlisting player's reputation with it (minor positive gossip at Guard
  Villagers' configured inventory requirement, read by key, default 15), so they can open its
  inventory; the summon trigger and Guard Villagers' `guards_made` statistic follow the commit. The person is
  then linked and hidden, their job site is released and their civilian profession and flags are
  recorded, and the commit is stamped on both entities. A failure before the commit removes the actor,
  refunds exactly the receipt, and reveals the person unchanged.
- **Return to civilian life.** The fit of the actor's possessions into the person's inventory is
  simulated first; if anything would not fit, nothing happens. Otherwise the native state is released
  through the native mod (Recruits' dismissal; Workers' work and home areas), the possessions move
  once, the person is revealed where the actor stood with the same health fraction, civilian
  profession, flags and MCA order, and goes home. The actor leaves as a role change: no death, no
  loot, no experience.
- **Promotion.** Recruits' `Promoted` event opens a bracket. Recruits' discard of the old recruit is
  snapshotted, and the first recruit to join at the same spot with the same owner receives the record,
  with the generation bumped. If no replacement joins by the end of the tick, the old actor is
  restored from its snapshot and Recruits' group is pointed back at it.
- **Discard.** An enlisted person whose actor is discarded outside a transition goes home, keeping
  their own inventory; what the actor carried leaves with it. If the person is not loaded at that
  moment, they stay hidden and linked, the record waits for its actor, and an operator repairs it.

**The guarantee.** A transition runs within one server tick, and Minecraft never saves in the middle of
a tick. But the store, each entity's chunk and each player's inventory are written at different times,
so after an unclean stop they may each come from either side of a transition. So the transaction id and
generation are stamped on both entities as well as the store. At startup, every open transition settles
forward if it was committed and back if it was not. When entities load, an actor whose identity names
it is relinked, rolled forward and never re-rolled. A stale actor (an older generation) is left
unbridged, and a conflict is quarantined for an operator.

**Its limits.** Recovery never restores inventories, and never refunds or charges by itself: after an
unclean stop, a payment's fate cannot be known from the world, and a guess would duplicate or delete
items. Nothing is ever deleted on a timer, because an unloaded partner is not a dead one.
`/mcamobcompat characters repair <target> <relink|clear-stale-link|restore-civilian|settle>` is the
operator's tool for what recovery deliberately leaves alone.

## Physical size policy

The actor keeps its own collision box, navigation dimensions, reach, armour, speed and damage. MCA
genetics decide how the person looks and what children inherit: a tall person is drawn tall, but walks
and fights in the native actor's box. There is no matching-hitbox mode. One would need per-adapter
bounds, server-authoritative resizing and navigation tests, and it is not needed to deliver inherited
appearance. A client never resizes an actor by scaling its model.

## Hook contracts

**Architecture decision: optional, string-targeted Mixin hooks, as a narrow exception to "no
mixins".**

- *Context.* Three of Recruits' conversions create their NPC and `discard()` the villager in one
  method, with no event between. For an MCA villager that destroys a family member. MCA applies an
  editor save to whatever entity the client names, with no check and no event first.
- *Decision.* Hook exactly those methods, at their head, and nothing else. Every hook is `@Pseudo`,
  string-targeted, `remap = false` and `require = 0`, in an optional config (`"required": false`, no
  refmap). Only the exact classes on the build's dotted-name allowlist name the targets.
  `hook.HookPlugin` skips a target the owning jar does not contain, and counts the injected call sites
  in the transformed class. A hook is **armed** only when every handler it needs landed, and every
  feature that depends on a hook asks whether it is armed.
- *Consequences.* A reshaped upstream build gets no injection, a warning, and a safe refusal instead
  of an unchecked path. The jar still names no foreign type: `checkJarContents` and
  `NoOptionalModStaticLinkTest` enforce a five-class allowlist for the dotted names.

| Hook | Target | Method | Audited against | Original runs | When missing |
|---|---|---|---|---|---|
| `mca.editor_save` | `<mca root>.network.c2s.VillagerEditorSyncRequest` | `receive(ServerPlayer)` on Forge, `handleServer(ServerPlayer)` on NeoForge; head and every return | MCA 7.6.20 to 7.7.1-beta.2 (Forge), 7.7.0 to 7.7.36-beta.3 (NeoForge) | yes, with the payload rebuilt; skipped when refused | bridged characters cannot be opened in MCA's editor |
| `recruits.create_recruit` | Recruits' `VillagerEvents` | `createRecruit(Villager, EntityType)`, head | Recruits 1.15.2 | for vanilla villagers; never for MCA villagers | MCA villagers unprotected from profession conversion; warned at start |
| `recruits.create_noble` | Recruits' `VillagerEvents` | `createNobleVillager(Villager)`, head | Recruits 1.15.2 | for vanilla villagers; never for MCA villagers | MCA villagers unprotected from becoming nobles; warned at start |
| `recruits.hire_from_villager` | Recruits' `VillagerEvents` | `createHiredRecruitFromVillager(ServerLevel, Villager, EntityType, Player, RecruitsGroup)`, head | Recruits 1.15.2 | for vanilla villagers; replaced by the enlistment for MCA villagers | noble-hire enlistment unavailable and unprotected; logged as an error at start |

`/mcamobcompat characters adapters` prints each hook's state: armed or not, its call sites and how
often it ran.

## Unknown builds

- An MCA build whose editor request has another shape: the editor hook is not armed, and MCA's editor
  is refused on bridged characters. Ordinary villagers are unaffected.
- A Recruits or Workers build whose members do not resolve: the adapter reports which binding failed.
  Without `handleRecruiting` and an armed hire hook, enlistment is unavailable. Without the disband
  method, the return to civilian life is unavailable.
- A native NPC from a mod with no adapter is classified by its tags and nothing more.

## Not supported

- Guard Villagers' own guards cannot be promoted, so no host replacement applies to them. A Workers
  worker cannot be promoted either.
- A person with a family action that needs MCA's own movement or brain tasks (courting, for example)
  does not run it while employed, because the identity has no AI. Inheritance through MCA's own
  breeding of two characters' persons was not exercised.
- Villager Recruits' and Villager Workers' NeoForge 1.21.1 builds do not exist; their adapters are
  unavailable there.
- Native combat and work are the native mod's own; this mod adds nothing to them and changes nothing in
  them. Comparing them against unbridged control actors in production was not done (see
  [VALIDATION.md](VALIDATION.md)).
