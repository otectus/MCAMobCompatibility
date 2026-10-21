# MCA: Mob Compatibility 0.3.0 (Forge 1.20.1): validation record

What was run to check 0.3.0 on Forge 1.20.1, what it showed, and what was not run. The NeoForge
1.21.1 build of the same release keeps its own record in its repository's `docs/VALIDATION.md`; the
two builds share their unit tests, GameTests, runtime probe and scenario runner. Every third-party jar
involved, with its SHA-256, is in [DEPENDENCIES.json](DEPENDENCIES.json); the per-capability status
is in [NATIVE_CHARACTER_COMPATIBILITY.md](NATIVE_CHARACTER_COMPATIBILITY.md). The logs and
screenshots are in [validation/0.3.0/](validation/0.3.0/). The 0.2.1 record is archived at
[validation/VALIDATION-0.2.1.md](validation/VALIDATION-0.2.1.md).

Recorded 2026-10-20 on Linux, Java 17, Forge 1.20.1-47.4.10.

## Summary

| Check | Result | Evidence |
|---|---|---|
| Unit tests (`build` runs `test`) | 106 tests in 24 classes, 0 failures, 0 skipped | [unit-tests.txt](validation/0.3.0/unit-tests.txt) |
| Jar gates in `build` | `checkJarContents` clean against the 5-class dotted-name allowlist; `verifyProductionJar` passed (hook config, manifest `MixinConfigs`, no refmap); 59 classes carry SRG names | [build.log](validation/0.3.0/build.log) |
| Resource check (`check_mod.py`) | 0 errors, 0 warnings, 0 notes; all 67 lang keys used and defined | [check_mod.txt](validation/0.3.0/check_mod.txt) |
| Binding probes | MCA binding and editor-hook target in all 6 MCA builds of the fleet; every Recruits and Workers member and hook target in the audited jars | unit tests above |
| GameTests, MCA 7.6.20 + Guard Villagers | All 39 required tests passed | [gametest-core-mca76.log](validation/0.3.0/gametest-core-mca76.log) |
| GameTests, MCA 7.6.20 + Recruits + Workers | All 48 required tests passed | [gametest-recruits-workers-mca76.log](validation/0.3.0/gametest-recruits-workers-mca76.log) |
| GameTests, MCA 7.7.1-alpha.2 + Recruits + Workers | All 48 required tests passed | [gametest-recruits-workers-mca77.log](validation/0.3.0/gametest-recruits-workers-mca77.log) |
| GameTests, core + SWEM, Icy's, Alex's, Golems Kill Creepers, Recruits, Workers | All 54 required tests passed | [gametest-all-mods.log](validation/0.3.0/gametest-all-mods.log) |
| GameTests, core + MCA: Crime 0.8.0 | All 41 required tests passed | [gametest-crime.log](validation/0.3.0/gametest-crime.log) |
| Production: dedicated server, two clients, Guard Villagers + Recruits + Workers | Every check passed on both clients and the server | [runtime/two/](validation/0.3.0/runtime/two/) |
| Production: Guard Villagers only | Every check passed | [runtime/guards/](validation/0.3.0/runtime/guards/) |
| Production: single player | Every check passed on the integrated server | [runtime/solo/](validation/0.3.0/runtime/solo/) |
| Production: MCA and this mod only | Server and client load and play | [runtime/minimal/](validation/0.3.0/runtime/minimal/) |
| Production: client without this mod | Refused at login: `mcamobcompat:proxy_pair` rejected the client's version | [runtime/missing/](validation/0.3.0/runtime/missing/) |
| Parity with the NeoForge build | In step: 176 files, 114 loader-neutral, 62 reviewed pairs | `python3 scripts/parity.py` |

**The artifact:** `build/libs/mcamobcompat-forge-0.3.0+1.20.1.jar`, 386,363 bytes, 150 classes.

```
SHA-256 81fb147703f536d96abaa0cae6e154a682c32c13a6ef745c67b2eed64b69a905
```

The production runs loaded exactly that jar, and the validation driver beside it,
`mcamobcompat-forge-0.3.0+1.20.1-runtime-probe.jar` (SHA-256
`ea43286e2aaaad7263b56d40049870e22c28acc03b23b018496684db5408716b`), which is built only by
`runtimeProbeJar`, is reobfuscated like the release jar, and is never shipped.

## Commands

Gradle ran through the workspace's quiet wrapper, which writes the full log to disk.

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> build -Dnet.minecraftforge.gradle.check.certs=false
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py <project>
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithRecruits -PwithWorkers -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PmcaDev77 -PwithRecruits -PwithWorkers -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems -PwithRecruits -PwithWorkers -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithCrime -PcrimeJar=<MCACrime>/build/libs/mcacrime-0.8.0.jar -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runtimeProbeJar -Dnet.minecraftforge.gradle.check.certs=false
python3 scripts/validation/run_scenario.py <runtime-dir> two|guards|minimal|missing|solo
python3 scripts/parity.py
```

`-Dnet.minecraftforge.gradle.check.certs=false` skips only ForgeGradle's start-up probe of
`maven.minecraftforge.net` and `libraries.minecraft.net`. It does not change what is built or tested.

`runGameTestServer` passes only if `run-gametest/logs/latest.log` contains "All N required tests
passed". The server exits cleanly even when mods fail to load, so its exit code proves nothing.

## GameTests

Each run starts a real dedicated server in `run-gametest/` with the real mods, remapped for the dev
runtime by `remapDevMods`. `-P` flags choose which suites run.

| Suite (namespace) | Runs in | Tests |
|---|---|---|
| Proxy bridge, threats, mounts (`mcamobcompat`) | every run | the 29 of 0.2.1, with `recruitmentPreservesIdentityAndItemData` now under `guardRecruitmentMode = MCA_PROFESSION` |
| Characters (`mcamobcompat`) | every run | 10: nativeRecruitmentKeepsThePerson, civilianReturnKeepsThePersonAndTheirPossessions, aFullInventoryRefusesTheReturnAndChangesNothing, aRefusedGuardRollsTheEnlistmentBack, aDiscardedGuardSendsThePersonHome, aLostRecordIsRolledForwardFromTheStamps, anInvisibleVillagerIsNeverTakenForAnIdentity, editorSavesAreSessionBoundAndRebuilt, theNativeAdaptersReportThisPlatformsAvailability, aPersistedMcaOrderIsNotAppliedAgainAfterARestart |
| Villager Recruits (`mcamobcompat_recruits`) | `-PwithRecruits` or `-PwithWorkers` | 6: aRecruitIsAdoptedInPlace, aRecruitProfessionNeverConvertsAnMcaVillager, aVanillaVillagerStillConverts, aJoblessMcaVillagerNeverBecomesANoble, aNobleHireEnlistsTheSamePersonAndPromotionKeepsThem, aBlockedHireTakesNothing |
| Villager Workers (`mcamobcompat_workers`) | `-PwithWorkers` | 3: everyWorkerIsClaimedOnceAndNothingElseIs, mcasMoveSendsAWorkerBackToWork, aNobleHireMakesTheSamePersonAWorker |
| SWEM, Icy's, Alex's, Golems, MCA: Crime | their flags | as in 0.2.1 and 0.2.2: 3, 1, 1, 1 and 2 |

What the character suites pin, in short. An enlisted MCA villager keeps their UUID, name and record,
is hidden behind a real guard holding the gesture's weapon exactly once, gives the enlisting player
the reputation Guard Villagers' own conversion grants, and comes home with that guard's possessions. A full inventory refuses the return and changes nothing. A refused spawn rolls the
enlistment back. A discarded guard sends its person home. A record lost to an unclean stop is rolled
forward from the entities' stamps. An operator-invisible villager is never taken for an identity. An
editor save outside a session, or by a second player after a newer save, is refused, and an accepted
one is rebuilt (a gene changed, the identity's own `NoAI` and an attempted inventory kept
as they were). Recruits' profession and noble conversions never take an MCA villager, while a vanilla
villager still converts. A noble's hire enlists the same person through Recruits' own payment and
ownership, a promotion moves the person to the captain with the generation bumped and the replacement
journaled, a stranger's order is refused and the owner's obeyed. Every worker type is claimed once by
the Workers adapter and no work-area marker or bobber is, and MCA's Move sends a worker back to work
(Workers' follow state 6). A persisted MCA order is not applied again after a restart over a native
order given since.

### Intermittent and fixed failures

Every run in the summary is on the final code and passed. Earlier runs of this work, all superseded by
those, failed in three ways:

- `aBurningFuseSendsEvenAGuardRunning` (a 0.2.1 threat test) failed twice, with "The guard has not moved
  away from a burning fuse". Once was in a Crime run whose two immediate re-runs passed; the other was
  in a run already failing on the bug below. It was recorded as intermittent on NeoForge in 0.2.1
  (2 failures in 49 runs); these are its first recorded failures on Forge. Its cause is not found.
  Nothing in 0.3.0 touches creeper awareness or MCA's own guard movement.
- `mcasMoveSendsAWorkerBackToWork` failed in three runs, because a new test reset the command router's
  state for every actor while GameTests in the same batch ran beside it. The test now resets only its
  own guard.
- Five character tests failed in every run after the enlisting player's Guard Villagers statistic was
  first awarded with an equal copy of its id: a statistic is found by identity, so it had no name and
  threw. It now awards the registered instance.

## Production runtime

A Forge 1.20.1-47.4.10 server and client, each installed by Forge's own installer (`--installServer`,
`--installClient`; SHA-1 as published), in disposable directories, with a new flat world. The vanilla
1.20.1 version file came from Mojang's manifest and matched its SHA-1, as did the installed client jar.
Mods on both sides: MCA 7.6.20, Architectury 9.2.14, Guard Villagers 1.20.1-1.6.19, Villager Recruits
1.15.2, Villager Workers 2.0.3, the release jar above and the runtime-probe jar. Clients ran headless
under `gamescope --backend headless` through `scripts/validation/launch_client.py`, as offline players
against a server bound to 127.0.0.1. `scripts/validation/run_scenario.py` ran each scenario. The probe
drives a real client: it clicks, crouches, opens and saves screens and takes screenshots with
Minecraft's own screenshot code, and it logs each check as a `RUNTIME_PROBE` line. The worlds render
dark in the screenshots, as in 0.2.1: the headless clients do not light the freshly placed platform.

**Two clients on a dedicated server** (`ProbeOne`, `ProbeTwo`):

- **Pairing and orders.** The server gave each player a guard and its identity, added 7 hearts through
  the API and read them back from MCA's own villager (`PAIR ... hearts=7`). After the pair's first
  heartbeat, it set MCA's Follow, Stay and Move and saw Guard Villagers follow MCA's authorised player,
  patrol and stop (`MOVEMENT_VERIFIED`). It also placed an unowned Recruits bowman and a Workers farmer
  beside each guard, adopted in place.
- **Drawn as MCA people.** Each client learned every association over protocol 2 (`CLIENT_PAIR ...
  routed=true mcaAppearance=true generation=1`). Every guard, bowman and farmer in its view was drawn by
  MCA's renderer in place of its own model (`WORLD_DRAW ... asPerson=113 nativeCancelled=117 native=0`
  for the player's own guard). An actor whose person had not reached the client yet was drawn by its
  own mod until it did, which is the designed fallback (`native=28` for the other player's farmer,
  whose person arrived later; `asPerson=81` after that). The screenshots `*-probe-world.png` show MCA people where
  0.2.1's showed Guard Villagers' own model.
- **MCA's screen and the portrait.** A plain click opened MCA's own interaction screen. In the probe's
  portrait screen, every cancelled render of the hidden identity drew the person instead
  (`RENDER_COUNTS hiddenPortrait=90 personPortrait=90 guardPortrait=0`).
- **MCA's editor across two clients.** Each player held MCA's villager editor and right-clicked their
  guard. MCA's own `VillagerEditorScreen` opened on the guard's person (`EDITOR_SAVED ...
  isGuardsPerson=true`). The probe changed the SIZE gene on MCA's own preview villager and saved
  through MCA's own `syncVillagerData()`. The server applied it, with the person still hidden
  (`EDITOR_SERVER ... size=0.65000004 revision=1 invisible=true noAi=true`). **Each client saw both
  characters change**: its own and the other player's, gene and appearance revision together
  (`APPEARANCE ... character=fd30e135… from=0/0.33657703 to=1/0.65000004` on both clients).
- **Native controls.** A crouching click opened Guard Villagers' own `GuardInventoryScreen` on the
  guard, and Recruits' own `RecruitHireScreen` on the unhired bowman (`NATIVE_SCREEN`).
- **Enlistment.** Each player, crouching with a named iron sword, clicked a plain adult MCA villager.
  Server: the same person, alive, hidden behind exactly one new Guard Villagers guard holding that
  sword, professed `mca:guard` while serving, with the player's hand empty (`RECRUIT_SERVER PASS`).
  Client: the same entity, now a hidden identity paired with that guard (`RECRUIT_CLIENT PASS`), which
  is drawn as the person (`RECRUIT_DRAWN PASS`). A second crouching click, on the guard with a second
  sword after walking up to it, opened the guard's own inventory and enlisted nobody
  (`ALREADY_ENLISTED PASS`, one actor).
- **The way home.** With their guard standing beside them, each player looked at it and ran
  `/mcamobcompat character release`. Server:
  the same UUID, visible, no longer an identity, profession `minecraft:none` again, with the guard's
  sword in their own inventory, and the guard gone (`RELEASE_SERVER PASS`). Client: the person present,
  visible and rendered again (`RELEASE_CLIENT PASS`).

**Guard Villagers only.** The same checks with Recruits and Workers absent all passed (`ProbeGuards`);
no bowman or farmer was placed, and the Recruits screen check found nothing to click, as expected.

**Single player.** A copy of the server's world opened on the client's integrated server. Every check
above passed for `ProbeSolo`.

**MCA and this mod only.** Server and client loaded MCA, Architectury, this mod and the probe; the
client joined and played (`MINIMAL_CLIENT_READY`, `MINIMAL_PLAYER_JOIN`).

**Client without this mod.** A client with MCA, Architectury, Guard Villagers, Recruits and Workers but
not this mod was refused at login: "Channels [mcamobcompat:proxy_pair] rejected their client side
version number", then "Terminating connection with server, mismatched mod list".

Problems in the probe itself were found and fixed during these runs; none was a fault in this mod.
Guard Villagers opens a guard's inventory only for a guard with AI, so the probe enables AI on its
standing guard for the native click. An enlisted guard has its own AI and wanders, so the probe walks
the player up to it just before the second click (`approach`), and for the look-targeted release has it
stand still beside the player, clear of the other NPCs (`present`); without that, a look could land on a
neighbouring NPC or on nothing. NeoForge 1.21.1's shorter entity-interaction reach made this show
there first.

## Not run

- **Performance.** No profiling of 20, 100 or 250 actors; no measured tick, frame, packet or cache
  budget beyond the bounded caches in code.
- **Native combat and work against unbridged controls.** Recruits' formations and ranged and mounted
  combat, and Workers' jobs (collection, deposit, couriers, merchants), were not exercised in
  production. GameTests check orders and states, not job output.
- **One continuous acceptance journey.** A named adult with edited genes, clothing, inventory, hearts
  and a family link, enlisted, edited while employed, promoted, unloaded and reloaded, restarted and
  sent home in one world was not run as a single scenario. Its steps are covered separately: GameTests
  for enlistment, return, promotion and recovery; production for enlistment, the editor across clients
  and the return.
- **Two clients editing the same character at once.** The conflict rule is pinned by GameTests only.
- **MCA breeding between two characters' persons**, and family actions that need MCA's own movement.
- **Resource or renderer reloads** with characters drawn as MCA, and dimension travel by an enlisted
  person (0.2.1's dimension GameTest covers a bridged guard).
- **The sibling addons with a client.** MCA: Crime's effect mirror ran in GameTests; MCA: Quests,
  Conversations and Reputation were not loaded in production for 0.3.0.
- **Faction conflict** between Recruits teams with MCA-faced recruits.
- Genetic Animals and DragN's Livestock Overhaul, and internet authentication, as in 0.2.1.

## Unavailable

- Nothing on this platform. On NeoForge 1.21.1, Villager Recruits and Villager Workers have no build;
  see that repository's record.
