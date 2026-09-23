# Teaching MCA: Mob Compatibility about a new mod

Everything this mod knows about which mob is a cow lives in datapack tags. Adding a mod it has never
heard of takes one JSON file and no code.

## The roles

| Role | Tag | Meaning |
|---|---|---|
| `livestock` | `mcamobcompat:livestock` | Any farmed animal. The umbrella role. |
| `cattle` | `mcamobcompat:cattle` | Cows, mooshrooms and equivalents. |
| `sheep` | `mcamobcompat:sheep` | Sheep and equivalents. |
| `pig` | `mcamobcompat:pig` | Pigs and equivalents. |
| `poultry` | `mcamobcompat:poultry` | Chickens and other farmed birds. |
| `horse` | `mcamobcompat:horse` | Horses, donkeys, mules, modded equines. |
| `mount` | `mcamobcompat:mount` | Anything a villager could ride. Superset of `horse`. |
| `pet` | `mcamobcompat:pet` | Owned companion animals. |
| `guard` | `mcamobcompat:guard` | Village defenders that are NPCs. Any entity type in this tag is eligible for bridging to an MCA guard (given a name, gender, personality and per-player hearts) when Guard Villagers is installed and the bridge option is on. |
| `humanoid_npc` | `mcamobcompat:humanoid_npc` | Humanoid village inhabitants. |

`livestock` is composed from the four species tags rather than repeating their contents, so putting
a mob in `cattle` automatically makes it livestock. Horses are deliberately **not** inside
`livestock`: a player's prize horse is not a farm animal for chore purposes. If you want them there,
add `#mcamobcompat:horse` to your own `livestock.json` — it is one line.

## Behaviour tags

Two more tags are not roles: they change what MCA villagers do.

| Tag | Ships with | Meaning |
|---|---|---|
| `mcamobcompat:npc_threats` | `minecraft:creeper` | Mobs an MCA villager reacts to before it is hurt. Civilians run from one that is hunting them or anyone beside them, or that comes within half the alert range; MCA guards and archers fight it. A creeper's burning fuse makes everyone run, guards included. |
| `mcamobcompat:player_only_mounts` | `swem:swem_horse` and six Alex's Mobs mounts | Mounts that accept a rider but only ever let a player steer. With `[mounts] npcRidingPolicy = REFUSE`, the default, an MCA villager ordered onto one is refused and the player is told why. |

Adding a mob to `npc_threats` gives it the same treatment as a creeper that is not swelling: only a
creeper has a fuse to read. Use it for mobs worth running from on sight, not every hostile mob —
MCA already reacts to a mob that actually attacks.

Add a mount to `player_only_mounts` when a villager put on it cannot steer it, so the mount ignores
MCA's orders. Check
first with `/mcamobcompat mounts inspect`, which reports who the mount lets steer it. This tag is a
fact about the mob, so it applies even when that mod's module is switched off.

## Adding a mod

Create `data/mcamobcompat/tags/entity_types/<role>.json` in your datapack. The `mcamobcompat`
namespace is what makes it extend this mod's tag: a file under your own pack's namespace would
define a separate tag of the same name, which nothing reads.

```json
{
  "replace": false,
  "values": [
    { "id": "some_mod:highland_cow", "required": false }
  ]
}
```

Do that for each role the mob should hold. A highland cow wants `cattle.json` — `livestock` follows
automatically.

Three rules worth following:

- **Always `"replace": false`.** `true` throws away the shipped defaults and everything every other
  pack added.
- **Always `"required": false`** for entries from a mod that might not be installed. Without it,
  Minecraft logs a datapack error on every world load for anyone who does not have that mod. The
  shipped tags follow this rule for every non-vanilla entry, and a build-time test enforces it.
- **Check the id actually exists.** A wrong entity id does not error — it silently never matches. Use
  `/mcamobcompat dump <namespace>` to list what a mod really registered.

## Finding the right ids

```
/mcamobcompat dump swem
```

lists every entity type that mod registered along with the roles it currently holds. This is the
reliable way, and it is worth using rather than trusting a wiki. Two real examples from building
this mod:

- Genetic Animals' mod id is **`eanimod`**, not `geneticanimals`, and its cow is
  `eanimod:enhanced_cow`.
- DragN's Livestock Overhaul has language keys and loot tables for `dragnlivestock:freight_wagon` and
  `dragnlivestock:o_undead_horse`, but **neither is a registered entity type**. Tagging them does
  nothing at all.
- Icy's Better Horses registers its release 2.0 breeds under **`icys-better-horses`** -- with
  hyphens -- although its mod id is `icys_better_horses`. The shipped `horse` tag lists them through
  Icy's own `#icys-better-horses:breed_horses`.
- Alex's Mobs registers well over a hundred entity types, including body parts and projectiles, and
  several of its rideable animals can only be steered by a player. What each one is, and why only
  four are shipped as mounts, is in [docs/ALEXS_MOBS_AUDIT.md](docs/ALEXS_MOBS_AUDIT.md).

`/mcamobcompat classify` gives the roles of whatever you are looking at, which is the quickest way to
check your pack worked. Both need permission level 2, and both update on `/reload`.

## Forge conventional tags

The shipped tags reference the Forge conventional entity tags (`#forge:cow`, `#forge:pig`,
`#forge:sheep`, `#forge:chicken`, `#forge:horses`, and so on) wherever one exists. Any mod that
registers its animals into those is classified with no work from anyone — which is how DragN's
Livestock Overhaul is largely covered, since it populates them itself.

If you are a mod author adding farm animals, putting them in the Forge conventional tags is the
single most useful thing you can do: it makes them work with this mod, and with everything else that
reads those tags, for free. A datapack for Minecraft 1.20.1 declares `pack_format` 15.

## Alex's Mobs predators and MCA villagers

Alex's Mobs has its own tag, `alexsmobs:villagers`, which its predators use to choose villagers as
prey. It names only `minecraft:villager` and `minecraft:wandering_trader`, so those predators leave
MCA villagers alone. This mod deliberately does not change that: it would make MCA villagers prey. A
pack that wants MCA villagers treated like vanilla ones adds
`data/alexsmobs/tags/entity_types/villagers.json`:

```json
{
  "replace": false,
  "values": [
    { "id": "mca:male_villager", "required": false },
    { "id": "mca:female_villager", "required": false }
  ]
}
```

A few Alex's Mobs creatures pick their prey by class rather than by that tag, and already reach MCA
villagers without it. The audit lists them.

## Turning a mod off instead

If two mods are fighting over the same integration, `mcamobcompat-common.toml` has a per-mod switch
under `[modules]`. Turning one off makes this addon completely blind to that mod's entities — they
stop being classified at all, and a tamed one is no longer a `pet` — which is a blunter instrument
than editing a tag, but sometimes the right one. Two things still apply: the hunting chore spares an
animal that has an owner whatever its roles, and `player_only_mounts` still refuses villagers.

One exception worth knowing: turning off the `guardVillagers` module also turns off the block on the
villager-to-guard conversion, which permanently destroys MCA villager data. See the README.

## Guard names

A bridged guard's name and personality are rolled from its own UUID (a small bundled list; see
`bridge/GuardNames`) the moment it is first seen. That roll is only ever the *seed*: once
`guardMcaInteraction` gives the guard its hidden MCA proxy villager, MCA's own spawn initialisation
names the proxy itself and this mod's roll is pushed onto it exactly once. From then on the guard's
displayed name and personality are whatever MCA (or an addon, or a player) sets on the proxy — the
bundled list is the fallback used only before a proxy exists or while MCA is unreachable.

## Operator note: hidden proxies

While `guardMcaInteraction` is on, every bridged guard is paired with a real, invisible MCA villager
(the "proxy") that carries its MCA state. Every proxy carries the scoreboard tag
`mcamobcompat_proxy`, so operators can select them:

```
/mcamobcompat guards proxies
/kill @e[tag=mcamobcompat_proxy]
/mcamobcompat guards rebuild
```

`guards proxies` lists every loaded proxy with its guard. A proxy whose guard is not loaded is
reported as such and kept: its guard may only be in a chunk that is not loaded, so nothing deletes a
proxy automatically.

Killing a proxy by hand releases its link, and a living guard is given a new proxy within a second
or two. The new proxy is a new villager to MCA, so the old one's memories, hearts and family-tree
node are gone. Remove a proxy by hand only when its guard is known to be gone for good.

`guards rebuild` replaces the proxy of every loaded bridged guard, whether or not it already had
one. It is destructive for the same reason, so use it only after confirming with `guards proxies`
that a link is actually broken.
