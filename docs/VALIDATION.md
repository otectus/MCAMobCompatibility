# MCA: Mob Compatibility 0.2.1 (Forge 1.20.1): validation record

What was run to check 0.2.1 on Forge 1.20.1, what it showed, and what was not run. The NeoForge
1.21.1 build of the same release keeps its own record in its repository's `docs/VALIDATION.md`;
the two builds share their unit tests, GameTests, runtime probe and scenario runner. Every
third-party jar involved, with its SHA-256, is in [DEPENDENCIES.json](DEPENDENCIES.json). The logs
and screenshots are in [validation/](validation/).

Recorded 2026-09-22 on Linux, Java 17, Forge 1.20.1-47.4.10, while the release was numbered 2.1.
On 2026-09-23 it was renumbered 0.2.1 and rebuilt, and the unit tests and jar gates ran again on the
0.2.1 jar; the GameTests and production runs below were not repeated, because the two jars differ
only in their version metadata and one config comment (see [Renumbering to 0.2.1](#renumbering-to-021)).
Where this record says 2.1 it means that build.

## Summary

| Check | Result | Evidence |
|---|---|---|
| Unit tests (`build` runs `test`), 0.2.1 | 68 tests in 15 classes, 0 failures | [unit-tests.txt](validation/unit-tests.txt) |
| Jar gates in `build`, 0.2.1 | `checkJarContents` clean; `verifyProductionJar` passed; 37 classes carry SRG names | [build.log](validation/build.log) |
| Resource check (`check_mod.py`) | 0 errors, 0 warnings, 0 notes; all 10 lang keys the code uses are defined | [check_mod.txt](validation/check_mod.txt) |
| GameTests, MCA 7.6.20 + Guard Villagers | All 29 required tests passed | [gametest-core-mca76.log](validation/gametest-core-mca76.log) |
| GameTests, MCA 7.7.1-alpha.2 + Guard Villagers | All 29 required tests passed | [gametest-core-mca77.log](validation/gametest-core-mca77.log) |
| GameTests, core + SWEM, Icy's, Alex's, Golems Kill Creepers | All 35 required tests passed, and in 5 repeats | [gametest-all-mods.log](validation/gametest-all-mods.log) |
| Production: dedicated server, two clients | Pairing, MCA's screen, portrait, movement and the recruit scenario pass on both clients | [runtime/two/](validation/runtime/two/) |
| Production: single player | The same checks pass on an integrated server | [runtime/solo/](validation/runtime/solo/) |
| Production: Guard Villagers absent | Server and client load and play | [runtime/minimal/](validation/runtime/minimal/) |
| Production: client without this mod | Refused at login: the `mcamobcompat:proxy_pair` channel is missing on the client | [runtime/missing/](validation/runtime/missing/) |
| Parity with the NeoForge build | In step: 117 files, 69 loader-neutral, 48 reviewed pairs | `python3 scripts/parity.py` |

**The artifact:** `build/libs/mcamobcompat-forge-0.2.1+1.20.1.jar`, 200,511 bytes, 84 classes.

```
SHA-256 1e74fe84121de90f3df524cb502fe7ebaaddbe9d8fc9343a8f9b8ed3811db21f
```

The runtime scenarios loaded the 2.1 jar, `mcamobcompat-forge-2.1+1.20.1.jar`, 200,506 bytes (SHA-256
`ba356dfb40e458310a4e0de5830218b431d21bf0a1a16c3382c4f1ae4d6cb8d2`). The validation driver beside it,
`mcamobcompat-forge-2.1+1.20.1-runtime-probe.jar` (SHA-256
`dcd7312c5f2b8a283884fb5f288eb6c36741ead47fcdf3eaffdeb4a5499770a1`), is built only by
`runtimeProbeJar`, reobfuscated like the release jar, and is never shipped.

## Commands

Gradle ran through the workspace's quiet wrapper, which writes the full log to disk.

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> build -Dnet.minecraftforge.gradle.check.certs=false
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py <project>
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PmcaDev77 -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems -Dnet.minecraftforge.gradle.check.certs=false
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runtimeProbeJar -Dnet.minecraftforge.gradle.check.certs=false
python3 scripts/validation/run_scenario.py <runtime-dir> two|solo|minimal|missing
python3 scripts/parity.py
```

`-Dnet.minecraftforge.gradle.check.certs=false` skips only ForgeGradle's start-up probe of
`maven.minecraftforge.net` and `libraries.minecraft.net`, which hung in an SSL read with no timeout
during 0.3.0's validation. It does not change what is built or tested.

The 0.2.1 rebuild ran `gradlew-quiet.sh <project> clean build`, without the certificate flag.

`runGameTestServer` passes only if `run-gametest/logs/latest.log` contains "All N required tests
passed". The server exits cleanly even when mods fail to load, so its exit code proves nothing.

## GameTests

Each run starts a real dedicated server in `run-gametest/` with the real mods, remapped for the dev
runtime by `remapDevMods`. `-P` flags choose which suites run. The NeoForge build runs the same
suites.

| Suite (namespace) | Runs in | Tests |
|---|---|---|
| Proxy bridge (`mcamobcompat`) | every run | 22: classificationsAndMcaPersonality, stableLinksAndTwoWayNames, unloadedOwnerNeverCausesProxyDeletion, rejectedSpawnDoesNotCommitLinks, cancelledInitializationNeverCommits, protectedAnimalsAndFriendlyFire, proxyHitboxIsNestedInsideTheGuard, arrowsUseNativeGuardCollision, tippedArrowsKeepNativePotionEffects, tridentsUseOneNativeGuardImpact, snowballsConsumeAtTheGuard, damageRelaysTheFinalAmountAndHonoursCancellation, totemSurvivalKeepsIdentity, lateCancelledDeathNeverKillsIdentity, confirmedDeathRelaysOnce, killingTheProxyReleasesTheLink, rebuildReplacesTheProxyAndRelinks, aStaleGuardKeyDoesNotBlockCreation, recruitmentPreservesIdentityAndItemData, dimensionTravelPreservesBothUuids, graceAndModuleToggleKeepThePair, disabledBridgeRoutesASelectedProxyToTheGuard |
| Threats (`mcamobcompat`) | every run | 5: aVillagerRunsFromACreeperHuntingIt, anMcaGuardTakesTheCreeperOn, aBurningFuseSendsEvenAGuardRunning, aHiddenProxyNeverReacts, aCreeperIsAThreatByTag |
| Mounts (`mcamobcompat`) | every run | 2: aVanillaHorseStillTakesAnMcaVillager, aPlayerIsNeverRefusedByTheVillagerPolicy |
| SWEM (`mcamobcompat_swem`) | `-PwithSwem` | 3: anOwnedSwemHorseIsRecognisedAndRespectsSuppression, aVillagerCannotSteerASwemHorseButCanAVanillaOne, aSwemHorseRefusesAnMcaVillager |
| Icy's Better Horses (`mcamobcompat_icy`) | `-PwithIcy` | 1: icysOwnerAndHitchDecideWhoRides |
| Alex's Mobs (`mcamobcompat_alex`) | `-PwithAlex` | 1: aTamedRaccoonIsAProtectedPetAndAnElephantAMount |
| Golems Kill Creepers (`mcamobcompat_golems`) | `-PwithGolems` | 1: golemGamerulesAreLeftAlone |

Both MCA lines reported every binding as ok at start-up: `forge.net.mca` with the enum personality
shape on 7.6.20, and `forge.net.conczin.mca` with the registry shape on 7.7.1-alpha.2.

The SWEM observation behind the mount policy is `aVillagerCannotSteerASwemHorseButCanAVanillaOne`:
under `ALLOW_INERT`, the same MCA villager rides a vanilla horse as its controlling passenger, and the
horse, sent somewhere, walks more than two blocks with it aboard, while a SWEM 1.6.6 horse names no
controlling passenger. See [UPSTREAM.md](UPSTREAM.md).

### Repeated runs

After the final build, the all-mods configuration ran five more times, and all five passed
([gametest-all-mods-repeats.txt](validation/gametest-all-mods-repeats.txt)). Across the 21 Forge
GameTest runs of this work, `aBurningFuseSendsEvenAGuardRunning` never failed; on NeoForge it failed
2 times in 49 runs, both with every optional mod loaded. Its cause is not found; the NeoForge record
has the failing log.

## Production runtime

A Forge 1.20.1-47.4.10 server and client, each installed by Forge's own installer
(`--installServer`, `--installClient`), in disposable directories, with a new flat world. Mods on
both sides: MCA 7.6.20, Architectury 9.2.14, Guard Villagers 1.20.1-1.6.19, the release jar above and
the runtime-probe jar. Clients ran headless under `gamescope --backend headless` through
`scripts/validation/launch_client.py`, as offline players against a server bound to 127.0.0.1.
`scripts/validation/run_scenario.py` ran each scenario. The probe drives a real client: it clicks,
crouches, opens screens and takes screenshots with Minecraft's own screenshot code, and it logs each
check as a `RUNTIME_PROBE` line.

**Two clients on a dedicated server** (`ProbeOne`, `ProbeTwo`):

- The server gave each player a guard and its proxy, added 7 hearts through the API and read them
  back from MCA's own villager (`PAIR ... hearts=7`). It then set MCA's Follow, Stay and Move on the
  proxy and saw Guard Villagers follow MCA's authorised player, patrol and stop
  (`MOVEMENT_VERIFIED`). Guard Villagers 1.20.1 lets a guard follow only a player with Hero of the
  Village while its `followHero` option is on (its default), so the probe gives the player that
  effect first.
- Each client learned its pair from the `mcamobcompat:proxy_pair` channel (`CLIENT_PAIR ...
  routed=true`). A plain click on the guard opened MCA's own `InteractScreen`, and the probe's
  portrait screen drew the guard in the proxy's place (`RENDER_COUNTS`: the proxy's world renders
  were cancelled, and in the portrait every cancelled proxy render drew the guard instead, so
  `hiddenPortrait` equals `guardPortrait`).
- **Recruit.** Each player, crouching with a named iron sword, clicked a plain adult MCA villager.
  Server: the same villager, now `mca:guard`, holding that sword, with the player's hand empty
  (`RECRUIT_SERVER PASS`). Recruiting client: the same UUID still present, not removed, rendered and
  showing the new profession (`RECRUIT_CLIENT PASS`). A second click with a second sword changed
  nothing (`ALREADY_GUARD PASS`). Each client also saw both recruited villagers still present
  and not removed (`RECRUIT_OBSERVER`); the other player's was also rendered, facing the
  camera. 2.0 for NeoForge failed exactly this check (see the NeoForge record).

**Single player.** A copy of the server's world opened on the client's integrated server. Every
check above passed for `ProbeSolo`. MCA's first-join Destiny screen pauses a single-player game and
reopens itself each tick until its flow ends, so the probe ends it the way MCA's own story pages do
(`DestinyManager.allowClosing()`) instead of choosing a starting village.

**Guard Villagers absent.** Server and client loaded MCA, Architectury, this mod and the probe
only; the client joined and played (`MINIMAL_CLIENT_READY`, `MINIMAL_PLAYER_JOIN`).

**Client without this mod.** A client with MCA, Architectury and Guard Villagers but not this mod
was refused at login: "Channels [mcamobcompat:proxy_pair] rejected their client side version number",
then "Terminating connection with server, mismatched mod list".

## Renumbering to 0.2.1

The release was renumbered from 2.1 to 0.2.1 after validation, so that it follows the Forge line's
0.2.0. `mod_version` changed, and so did the comment on `[mounts] npcRidingPolicy`, which names the
version whose behaviour `ALLOW_INERT` restores. A clean `build` of 0.2.1 passed the 68 unit tests and
all three jar gates ([build.log](validation/build.log), [unit-tests.txt](validation/unit-tests.txt)).
Comparing it entry by entry with the 2.1 jar above: the same 84 classes and the same entry names, and
three entries changed.

| Entry | Change |
|---|---|
| `META-INF/mods.toml` | `version="2.1+1.20.1"` becomes `version="0.2.1+1.20.1"` |
| `META-INF/MANIFEST.MF` | `Implementation-Version: 2.1+1.20.1` becomes `Implementation-Version: 0.2.1+1.20.1` |
| `dev/otectus/mcamobcompat/McaMobCompatConfig$Server.class` | the config comment string "the behaviour before 2.1." becomes "the behaviour before 0.2.1." |

## 0.3.0 pre-release production server

Before the release was numbered 2.1, the 0.3.0 jar (SHA-256
`99e5e10c70463d52f60b123b16f2857ecb7b6d2e54c3ba6f7168909b9abbf7c0`) ran on a Forge dedicated
server with MCA: Quests 1.6.6, MCA: Crime 0.7.5, MCA: Reputation 0.6.0 and MCA: Conversations 1.8.0,
driven from the console with no player online ([production-server.log](validation/production-server.log)).
All eight mods loaded; proxies were created, rebuilt, replaced after `/kill`, and guard deaths were
relayed once each. Neither 2.1 nor 0.2.1 was re-run with the sibling addons.

## Not run

- The four sibling addons with 2.1 or 0.2.1, and any of them with a client: MCA: Conversations' dialogue frame,
  MCA: Quests' quest menu, and their reactions to a guard's death.
- Chat or gifts changing hearts on a real client, and a Guard Villagers inventory opened by a crouch
  click (the probe's crouch click is the recruit).
- A mount refusal message reaching the player who gave the order, outside GameTests.
- Any run with Genetic Animals or DragN's Livestock Overhaul installed.
- Internet authentication; every client was an offline player on a loopback server.
