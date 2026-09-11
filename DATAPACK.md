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

## Adding a mod

Create `data/<your_pack>/tags/entity_types/<role>.json` in your datapack:

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

`/mcamobcompat classify` gives the roles of whatever you are looking at, which is the quickest way to
check your pack worked. Both need permission level 2, and both update on `/reload`.

## Forge conventional tags

The shipped tags reference the Forge conventional entity tags (`#forge:cow`, `#forge:pig`,
`#forge:sheep`, `#forge:chicken`, `#forge:horses`, and so on) wherever one exists. Any mod that
registers its animals into those is classified with no work from anyone — which is how DragN's
Livestock Overhaul is largely covered, since it populates them itself.

If you are a mod author adding farm animals, putting them in the Forge conventional tags is the
single most useful thing you can do: it makes them work with this mod, and with everything else that
reads those tags, for free.

## Turning a mod off instead

If two mods are fighting over the same integration, `mcamobcompat-common.toml` has a per-mod switch
under `[modules]`. Turning one off makes this addon completely blind to that mod's entities — they
stop being classified at all — which is a blunter instrument than editing a tag, but sometimes the
right one.

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
`mcamobcompat_proxy`, so a stuck or duplicated one can be removed by hand:

```
/kill @e[tag=mcamobcompat_proxy]
/mcamobcompat guards rebuild
```

`guards rebuild` discards and recreates the proxy of every loaded bridged guard, whether or not it
already had one. It is destructive — a rebuilt proxy is a new villager to MCA, so an intact proxy's
family-tree node and memories are thrown away along with an orphan's — so use it only after
confirming with `/mcamobcompat guards proxies` that something is actually broken.
