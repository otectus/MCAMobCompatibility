# Alex's Mobs audit (NeoForge 1.21.1)

The record behind the Alex's Mobs integration in the NeoForge build of MCA: Mob Compatibility 0.2.1.
Alex's Mobs has no official 1.21.1 release; two independent NeoForge 1.21.1 builds exist, both with
mod id `alexsmobs`, and both were audited. The Forge 1.20.1 build's audit of the original mod is in
that repository's `docs/ALEXS_MOBS_AUDIT.md`; the result here is the same.

## What was inspected

| | Raguto's unofficial port | Alex's Mobs Continued |
|---|---|---|
| Artifact | `alexsmobs-1.22.17.jar` (Modrinth project `EmNhnNnt`, version `KSgki4uc`) | `alexsmobs-2.2.2-neoforge+1.21.1.jar` (Modrinth project `kYIaHHfw`, version `ThZbQpga`) |
| SHA-256 | `6e502855f79e4c9f2a11d560a9b88a3ab295aa378c44b0f0a0dd95f95d0301a6` | `240a96f8b098892e5374bdfedaced89f5fb8f7c9b79527534d397c5b7b1673d5` |
| Required library | Citadel (Unofficial Port) `citadel-1.21.1-2.7.6.jar` (`mIylVpkN`, SHA-256 `9e12468c49e5a95b7adbf22b3b4d05bc55565989b89c40b985cd73bdfe63c3c2`) | CodxLib `codxlib-1.6.1-neoforge+1.21.1.jar` (`MMcDASmX`, SHA-256 `15d9052888ad8b0db4719eb1aa4a409b892a4f9314e68169ad1848bbe1e1db52`) |
| Registry | 116 entity types | 116 entity types |

Each row was derived from both jars with `javap`: each registry name was followed from
`AMEntityRegistry`'s static initialiser to the constructor its entity factory calls. **Base** is the
first vanilla class in the entity's superclass chain. **Owned** means it extends vanilla
`TamableAnimal`, so vanilla `OwnableEntity` reports its owner. **Steering** comes from its own
`getControllingPassenger` override: "player only" means that override returns a rider only if it is a
`Player`, the same pattern that makes SWEM's horses unsteerable by a villager. **Hostile** means it
implements vanilla `Enemy`. **Targets villagers** means its class refers to vanilla `Villager`
directly, so it reaches MCA villagers, which extend `Villager`.

**The two builds agree with each other, and with the 1.20.1 audit, on every entity id and on every
column that decides what this mod does** -- ownership, steering, hostility and villager targeting.
The only difference between them is the base class of the non-living `enderiophage_rocket`
(`Projectile` in the Raguto port, `FireworkRocketEntity` in Continued), which no tag uses. Movement
was checked for the six species the tags name: the elephant and tusklin use Alex's ground pathfinder,
the grizzly bear and komodo dragon vanilla ground navigation, the endergrade flies (its own direct
navigator, no gravity) and the laviathan is lava-bound -- as in 1.20.1.

Runtime behaviour was checked by the `mcamobcompat_alex` GameTests against each build in turn
(`-PwithAlex`, `-PwithAlexContinued`): a tamed raccoon is a pet and an MCA villager's attack cannot
hurt it, an elephant is a mount that only a player can steer, a raccoon is not a mount, and
switching the module off removes every role.

## What this mod does with Alex's Mobs

- **Mounts.** Only the four ground species a player steers are in the `mount` tag: elephant,
  grizzly bear, Komodo dragon and tusklin. The flying endergrade, the lava-swimming laviathan and
  the straddleboard (a vehicle, not an animal) are left out. Species that carry passengers under
  their own control are not mounts either: cosmaw, crocodile, gorilla and kangaroo.
- **Player-only mounts.** All six player-steered species are in `player_only_mounts`. MCA's "Ride
  Horse" order only picks saddled horses, so it never chooses them. If anything else seats a
  villager on one, the mount policy refuses it rather than leaving the villager stuck.
- **Pets.** The 20 tamable species are pets once a player owns them, through vanilla ownership
  (`OwnedAnimalProvider`). That also puts them under the hunting chore's protection. A wild one is
  not a pet.
- **Nothing else.** No species is livestock, a guard, a humanoid NPC or an `npc_threats` threat by
  default. Hostile species are MCA's and the guards' ordinary business. Taming, breeding, riding,
  abilities and combat stay entirely Alex's Mobs'. Nothing is written to any of its entities.

## Deliberately not done

- **`#alexsmobs:villagers` is not extended.** Alex's Mobs predators hunt what is in that tag, and it
  names only vanilla villagers. Adding MCA's villagers would make those predators hunt them too. That
  matches vanilla, but it is a gameplay change a pack should opt into. `DATAPACK.md` shows the
  one-line pack.
- **The classes that already reach MCA villagers** (marked below) are left alone. They are hostile
  creatures doing what they do to any vanilla villager, and MCA's own defence and this mod's guard
  alerts already answer an attack on a villager.

## Every registered entity type

| Id | Base | Living | Owned | Steering | Hostile | Roles from this mod | Targets villagers |
|---|---|---|---|---|---|---|---|
| `alexsmobs:alligator_snapping_turtle` | Animal | yes |  |  |  |  |  |
| `alexsmobs:anaconda` | Animal | yes |  |  |  |  |  |
| `alexsmobs:anaconda_part` | LivingEntity | yes |  |  |  |  |  |
| `alexsmobs:anteater` | Animal | yes |  |  |  |  |  |
| `alexsmobs:bald_eagle` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:banana_slug` | Animal | yes |  |  |  |  |  |
| `alexsmobs:bison` | Animal | yes |  |  |  |  |  |
| `alexsmobs:blobfish` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:blue_jay` | Animal | yes |  |  |  |  |  |
| `alexsmobs:bone_serpent` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:bone_serpent_part` | LivingEntity | yes |  |  |  |  |  |
| `alexsmobs:bunfungus` | PathfinderMob | yes |  |  |  |  |  |
| `alexsmobs:cachalot_echo` | Entity | no |  |  |  |  |  |
| `alexsmobs:cachalot_whale` | Animal | yes |  |  |  |  |  |
| `alexsmobs:caiman` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:capuchin_monkey` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:catfish` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:centipede_body` | Mob | yes |  |  |  |  |  |
| `alexsmobs:centipede_head` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:centipede_tail` | Mob | yes |  |  |  |  |  |
| `alexsmobs:cockroach` | Animal | yes |  |  |  |  |  |
| `alexsmobs:cockroach_egg` | ThrowableItemProjectile | no |  |  |  |  |  |
| `alexsmobs:comb_jelly` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:cosmaw` | TamableAnimal | yes | yes | own control |  | pet when owned |  |
| `alexsmobs:cosmic_cod` | Mob | yes |  |  |  |  |  |
| `alexsmobs:crimson_mosquito` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:crocodile` | TamableAnimal | yes | yes | own control |  | pet when owned |  |
| `alexsmobs:crow` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:devils_hole_pupfish` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:dropbear` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:elephant` | TamableAnimal | yes | yes | player only |  | mount, player-only mount, pet when owned | yes |
| `alexsmobs:emu` | Animal | yes |  |  |  |  |  |
| `alexsmobs:emu_egg` | ThrowableItemProjectile | no |  |  |  |  |  |
| `alexsmobs:endergrade` | Animal | yes |  | player only |  | player-only mount |  |
| `alexsmobs:enderiophage` | Animal | yes |  |  | yes |  |  |
| `alexsmobs:enderiophage_rocket` | Projectile / FireworkRocketEntity | no |  |  |  |  |  |
| `alexsmobs:farseer` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:fart` | Entity | no |  |  |  |  |  |
| `alexsmobs:flutter` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:fly` | Animal | yes |  |  |  |  |  |
| `alexsmobs:flying_fish` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:frilled_shark` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:froststalker` | Animal | yes |  |  |  |  |  |
| `alexsmobs:gazelle` | Animal | yes |  |  |  |  |  |
| `alexsmobs:gelada_monkey` | Animal | yes |  |  |  |  |  |
| `alexsmobs:giant_squid` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:gorilla` | TamableAnimal | yes | yes | own control |  | pet when owned |  |
| `alexsmobs:grizzly_bear` | TamableAnimal | yes | yes | player only |  | mount, player-only mount, pet when owned |  |
| `alexsmobs:gust` | Entity | no |  |  |  |  |  |
| `alexsmobs:guster` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:hammerhead_shark` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:hemolymph` | Entity | no |  |  |  |  |  |
| `alexsmobs:hummingbird` | Animal | yes |  |  |  |  |  |
| `alexsmobs:ice_shard` | Entity | no |  |  |  |  |  |
| `alexsmobs:jerboa` | Animal | yes |  |  |  |  |  |
| `alexsmobs:kangaroo` | TamableAnimal | yes | yes | own control |  | pet when owned |  |
| `alexsmobs:komodo_dragon` | TamableAnimal | yes | yes | player only |  | mount, player-only mount, pet when owned |  |
| `alexsmobs:laviathan` | Animal | yes |  | player only |  | player-only mount |  |
| `alexsmobs:leafcutter_ant` | Animal | yes |  |  |  |  |  |
| `alexsmobs:lobster` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:maned_wolf` | Animal | yes |  |  |  |  |  |
| `alexsmobs:mantis_shrimp` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:mimic_octopus` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:mimicube` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:moose` | Animal | yes |  |  |  |  |  |
| `alexsmobs:mosquito_spit` | Entity | no |  |  |  |  |  |
| `alexsmobs:mud_ball` | Entity | no |  |  |  |  |  |
| `alexsmobs:mudskipper` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:mungus` | Animal | yes |  |  |  |  |  |
| `alexsmobs:murmur` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:murmur_head` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:orca` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:platypus` | Animal | yes |  |  |  |  |  |
| `alexsmobs:pollen_ball` | Entity | no |  |  |  |  |  |
| `alexsmobs:potoo` | Animal | yes |  |  |  |  |  |
| `alexsmobs:raccoon` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:rain_frog` | Animal | yes |  |  |  |  |  |
| `alexsmobs:rattlesnake` | Animal | yes |  |  |  |  |  |
| `alexsmobs:rhinoceros` | Animal | yes |  |  |  |  |  |
| `alexsmobs:roadrunner` | Animal | yes |  |  |  |  |  |
| `alexsmobs:rocky_roller` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:sand_shot` | Entity | no |  |  |  |  |  |
| `alexsmobs:sea_bear` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:seagull` | Animal | yes |  |  |  |  |  |
| `alexsmobs:seal` | Animal | yes |  |  |  |  |  |
| `alexsmobs:shark_tooth_arrow` | Arrow | no |  |  |  |  |  |
| `alexsmobs:shoebill` | Animal | yes |  |  |  |  |  |
| `alexsmobs:skelewag` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:skreecher` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:skunk` | Animal | yes |  |  |  |  |  |
| `alexsmobs:snow_leopard` | Animal | yes |  |  |  |  |  |
| `alexsmobs:soul_vulture` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:spectre` | Animal | yes |  |  |  |  |  |
| `alexsmobs:squid_grapple` | Entity | no |  |  |  |  |  |
| `alexsmobs:straddleboard` | Entity | no |  | item vehicle |  |  |  |
| `alexsmobs:straddler` | Monster | yes |  |  | yes |  | yes |
| `alexsmobs:stradpole` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:sugar_glider` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:sunbird` | Animal | yes |  |  |  |  |  |
| `alexsmobs:tarantula_hawk` | TamableAnimal | yes | yes |  |  | pet when owned |  |
| `alexsmobs:tasmanian_devil` | Animal | yes |  |  |  |  |  |
| `alexsmobs:tendon_segment` | Entity | no |  |  |  |  |  |
| `alexsmobs:terrapin` | Animal | yes |  |  |  |  |  |
| `alexsmobs:tiger` | Animal | yes |  |  |  |  |  |
| `alexsmobs:tossed_item` | ThrowableItemProjectile | no |  |  |  |  |  |
| `alexsmobs:toucan` | Animal | yes |  |  |  |  |  |
| `alexsmobs:triops` | WaterAnimal | yes |  |  |  |  |  |
| `alexsmobs:tusklin` | Animal | yes |  | player only |  | mount, player-only mount |  |
| `alexsmobs:underminer` | PathfinderMob | yes |  |  |  |  |  |
| `alexsmobs:vine_lasso` | Entity | no |  |  |  |  |  |
| `alexsmobs:void_portal` | Entity | no |  |  |  |  |  |
| `alexsmobs:void_worm` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:void_worm_part` | LivingEntity | yes |  |  |  |  |  |
| `alexsmobs:void_worm_shot` | Entity | no |  |  |  |  |  |
| `alexsmobs:warped_mosco` | Monster | yes |  |  | yes |  |  |
| `alexsmobs:warped_toad` | TamableAnimal | yes | yes |  |  | pet when owned |  |
