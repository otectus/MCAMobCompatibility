# Alex's Mobs audit (Forge 1.20.1)

This is the record behind the Alex's Mobs integration in MCA: Mob Compatibility 0.2.1. It lists every
entity type the mod registers, what each one is, and what this mod does with it. The integration is
deliberately narrow, and this table is how you can check that.

## What was inspected

| | |
|---|---|
| Artifact | `alexsmobs-1.22.9.jar` (Modrinth version `XoIASRVU`, Forge 1.20.1) |
| SHA-256 | `16bc5bcc19db9029c24f951346ec71098e6f5b0afef96e61894a2e9407aad37c` |
| Required library | Citadel 2.6.3 (`citadel-2.6.3-1.20.1.jar`, Modrinth version `lTAAe4sZ`) |
| Registry | 116 entity types, read from the string constants of `AMEntityRegistry` |

Each row was derived from the jar with `javap`. **Base** is the first vanilla class in the entity's
superclass chain. **Owned** means it extends vanilla `TamableAnimal`, so vanilla `OwnableEntity`
reports its owner. **Steering** comes from its own `getControllingPassenger` override: "player only"
means that override returns a rider only if it is a `Player`, the same pattern that makes SWEM's
horses unsteerable by a villager. **Hostile** means it implements vanilla `Enemy`. **Targets
villagers** means its class refers to vanilla `Villager` directly, so it reaches MCA villagers,
which extend `Villager`.

Runtime behaviour was checked by the `mcamobcompat_alex` GameTests with the real jar: a tamed
raccoon is a pet and an MCA villager's attack cannot hurt it, an elephant is a mount that only a
player can steer, a raccoon is not a mount, and switching the module off removes every role.

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

- **`#alexsmobs:villagers` is not extended.** That tag lists `minecraft:villager` and
  `minecraft:wandering_trader`, and Alex's Mobs predators (crocodile, caiman, anaconda and others)
  hunt what is in it. Adding MCA's villagers would make those predators hunt them too. That matches
  vanilla, but it is a gameplay change a pack should opt into. `DATAPACK.md` shows the one-line pack.
- **The ten classes that already reach MCA villagers** (marked below) are left alone. They are
  hostile creatures doing what they do to any vanilla villager, and MCA's own defence and this mod's
  guard alerts already answer an attack on a villager.

## Every registered entity type

| Id | Base | Living | Owned | Steering | Hostile | Movement | Roles from this mod | Targets villagers |
|---|---|---|---|---|---|---|---|---|
| `alexsmobs:alligator_snapping_turtle` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:anaconda` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:anaconda_part` | LivingEntity | yes |  |  |  | ground |  |  |
| `alexsmobs:anteater` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:bald_eagle` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:banana_slug` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:bison` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:blobfish` | WaterAnimal | yes |  |  |  | flying |  |  |
| `alexsmobs:blue_jay` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:bone_serpent` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:bone_serpent_part` | LivingEntity | yes |  |  |  | ground |  |  |
| `alexsmobs:bunfungus` | PathfinderMob | yes |  |  |  | ground |  |  |
| `alexsmobs:cachalot_echo` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:cachalot_whale` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:caiman` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:capuchin_monkey` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:catfish` | WaterAnimal | yes |  |  |  | flying |  |  |
| `alexsmobs:centipede_body` | Mob | yes |  |  |  | ground |  |  |
| `alexsmobs:centipede_head` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:centipede_tail` | Mob | yes |  |  |  | ground |  |  |
| `alexsmobs:cockroach` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:cockroach_egg` | ThrowableItemProjectile | no |  |  |  |  |  |  |
| `alexsmobs:comb_jelly` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:cosmaw` | TamableAnimal | yes | yes | own control |  | flying | pet when owned |  |
| `alexsmobs:cosmic_cod` | Mob | yes |  |  |  | ground |  |  |
| `alexsmobs:crimson_mosquito` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:crocodile` | TamableAnimal | yes | yes | own control |  | ground | pet when owned |  |
| `alexsmobs:crow` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:devils_hole_pupfish` | WaterAnimal | yes |  |  |  | flying |  |  |
| `alexsmobs:dropbear` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:elephant` | TamableAnimal | yes | yes | player only |  | ground | mount, player-only mount, pet when owned | yes |
| `alexsmobs:emu` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:emu_egg` | ThrowableItemProjectile | no |  |  |  |  |  |  |
| `alexsmobs:endergrade` | Animal | yes |  | player only |  | flying | player-only mount |  |
| `alexsmobs:enderiophage` | Animal | yes |  |  | yes | flying |  |  |
| `alexsmobs:enderiophage_rocket` | FireworkRocketEntity | no |  |  |  |  |  |  |
| `alexsmobs:farseer` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:fart` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:flutter` | TamableAnimal | yes | yes |  |  | flying | pet when owned |  |
| `alexsmobs:fly` | Animal | yes |  |  |  | flying |  |  |
| `alexsmobs:flying_fish` | WaterAnimal | yes |  |  |  | flying |  |  |
| `alexsmobs:frilled_shark` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:froststalker` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:gazelle` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:gelada_monkey` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:giant_squid` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:gorilla` | TamableAnimal | yes | yes | own control |  | ground | pet when owned |  |
| `alexsmobs:grizzly_bear` | TamableAnimal | yes | yes | player only |  | ground | mount, player-only mount, pet when owned |  |
| `alexsmobs:gust` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:guster` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:hammerhead_shark` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:hemolymph` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:hummingbird` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:ice_shard` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:jerboa` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:kangaroo` | TamableAnimal | yes | yes | own control |  | ground | pet when owned |  |
| `alexsmobs:komodo_dragon` | TamableAnimal | yes | yes | player only |  | ground | mount, player-only mount, pet when owned |  |
| `alexsmobs:laviathan` | Animal | yes |  | player only |  | lava | player-only mount |  |
| `alexsmobs:leafcutter_ant` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:lobster` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:maned_wolf` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:mantis_shrimp` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:mimic_octopus` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:mimicube` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:moose` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:mosquito_spit` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:mud_ball` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:mudskipper` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:mungus` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:murmur` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:murmur_head` | Monster | yes |  |  | yes | flying |  | yes |
| `alexsmobs:orca` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:platypus` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:pollen_ball` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:potoo` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:raccoon` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:rain_frog` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:rattlesnake` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:rhinoceros` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:roadrunner` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:rocky_roller` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:sand_shot` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:sea_bear` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:seagull` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:seal` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:shark_tooth_arrow` | Arrow | no |  |  |  |  |  |  |
| `alexsmobs:shoebill` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:skelewag` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:skreecher` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:skunk` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:snow_leopard` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:soul_vulture` | Monster | yes |  |  | yes | flying |  | yes |
| `alexsmobs:spectre` | Animal | yes |  |  |  | flying |  |  |
| `alexsmobs:squid_grapple` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:straddleboard` | Entity | no |  | item vehicle |  |  |  |  |
| `alexsmobs:straddler` | Monster | yes |  |  | yes | ground |  | yes |
| `alexsmobs:stradpole` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:sugar_glider` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:sunbird` | Animal | yes |  |  |  | flying |  |  |
| `alexsmobs:tarantula_hawk` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
| `alexsmobs:tasmanian_devil` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:tendon_segment` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:terrapin` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:tiger` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:tossed_item` | ThrowableItemProjectile | no |  |  |  |  |  |  |
| `alexsmobs:toucan` | Animal | yes |  |  |  | ground |  |  |
| `alexsmobs:triops` | WaterAnimal | yes |  |  |  | aquatic |  |  |
| `alexsmobs:tusklin` | Animal | yes |  | player only |  | ground | mount, player-only mount |  |
| `alexsmobs:underminer` | PathfinderMob | yes |  |  |  | ground |  |  |
| `alexsmobs:vine_lasso` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:void_portal` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:void_worm` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:void_worm_part` | LivingEntity | yes |  |  |  | ground |  |  |
| `alexsmobs:void_worm_shot` | Entity | no |  |  |  |  |  |  |
| `alexsmobs:warped_mosco` | Monster | yes |  |  | yes | ground |  |  |
| `alexsmobs:warped_toad` | TamableAnimal | yes | yes |  |  | ground | pet when owned |  |
