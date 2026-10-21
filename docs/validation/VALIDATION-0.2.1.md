# MCA: Mob Compatibility 0.2.1 (NeoForge 1.21.1): validation record (archived)

The record for 0.2.1, kept beside its evidence. The current record is [../VALIDATION.md](../VALIDATION.md).

What was run to check 0.2.1 on NeoForge 1.21.1, what it showed, and what was not run. The Forge 1.20.1
build of the same release keeps its own record in its repository's `docs/VALIDATION.md`; the two
builds share their unit tests, GameTests, runtime probe and scenario runner. Every third-party jar
involved, with its SHA-256, is in [DEPENDENCIES.json](../DEPENDENCIES.json). The logs and screenshots
are in [validation/](./).

Recorded 2026-09-22 on Linux, Java 21, NeoForge 21.1.248, while the release was numbered 2.1. On
2026-09-23 it was renumbered 0.2.1 and rebuilt, and the unit tests and jar gates ran again on the
0.2.1 jar; the GameTests and production runs below were not repeated, because the two jars differ
only in their version metadata and one config comment (see [Renumbering to 0.2.1](#renumbering-to-021)).
Where this record says 2.1 it means that build.

## Summary

| Check | Result | Evidence |
|---|---|---|
| Unit tests (`build` runs `test`), 0.2.1 | 68 tests in 15 classes, 0 failures | [unit-tests.txt](unit-tests.txt) |
| Jar gates in `build`, 0.2.1 | `checkJarContents` clean; `verifyProductionJar` passed | [build.log](build.log) |
| Lang keys | All 10 keys the code uses are defined, and every defined key is used; `en_us.json` is byte-identical to the Forge build's | [check_mod.txt](check_mod.txt) |
| GameTests, MCA 7.7.36-beta.3 + Guard Villagers 2.4.12 | All 29 required tests passed | [gametest-core.log](gametest-core.log) |
| GameTests, core + SWEM, Icy's 2.0, Alex's (Raguto port), Golems Kill Creepers | All 35 required tests passed; 4 of 5 repeats passed, one hit a known intermittent failure (below) | [gametest-all-mods.log](gametest-all-mods.log) |
| GameTests, core + SWEM 1.6.12 alone | All 32 required tests passed | [gametest-swem-only.log](gametest-swem-only.log) |
| GameTests, core + Alex's Mobs Continued | All 30 required tests passed | [gametest-alex-continued.log](gametest-alex-continued.log) |
| GameTests, core + Icy's Better Horses 1.1.6 | All 30 required tests passed | [gametest-icy116.log](gametest-icy116.log) |
| Production: dedicated server, two clients | Pairing, MCA's screen, portrait, movement and the recruit scenario pass on both clients | [runtime/two/](runtime/two/) |
| Production: single player | The same checks pass on an integrated server | [runtime/solo/](runtime/solo/) |
| Production: Guard Villagers absent | Server and client load and play | [runtime/minimal/](runtime/minimal/) |
| Production: client without this mod | Refused at login: the `mcamobcompat:proxy_pair` channel is missing on the client | [runtime/missing/](runtime/missing/) |
| The 2.0 recruit fault, reproduced on the 2.0 jar | The recruiting client discards the villager; the server and the other client keep it | [runtime/regression-2.0/](runtime/regression-2.0/) |
| Parity with the Forge build | In step: 117 files, 69 loader-neutral, 48 reviewed pairs | `python3 scripts/parity.py` |

`check_mod.py` itself reads Forge 1.20.1 projects only. Run here it reports 33 errors, every one a
Forge 1.20.1 expectation (Java 17, `mods.toml`, `tags/entity_types`, `pack_format` 15,
`net.minecraftforge` imports) and none about models, textures or lang keys; its output is kept in
the same file, after the lang-key check that replaces it.

**The artifact:** `build/libs/mcamobcompat-neoforge-0.2.1+1.21.1.jar`, 197,880 bytes, 84 classes.

```
SHA-256 944996607101340486157f778625ba869605b1be99de998bdef47ad62f1c83ed
```

The runtime scenarios loaded the 2.1 jar, `mcamobcompat-neoforge-2.1+1.21.1.jar`, 197,876 bytes
(SHA-256 `a8f8456034f0ee5af2c42c75b804ca6eb12d655f1de4b91a502ff8a2cdcf6e00`). The validation driver
beside it,
`mcamobcompat-neoforge-2.1+1.21.1-runtime-probe.jar` (SHA-256
`5aa28e204f39ed2af2c1ec458dd572b09e5bcf63a02ddd398118373437f770ce`), is built only by
`runtimeProbeJar` and is never shipped.

## Commands

Gradle ran through the workspace's quiet wrapper, which writes the full log to disk.

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithAlexContinued
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithIcy116
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runtimeProbeJar
python3 scripts/validation/run_scenario.py <runtime-dir> two|solo|minimal|missing
python3 scripts/parity.py
```

The 0.2.1 rebuild ran `gradlew-quiet.sh <project> clean build`.

`runGameTestServer` passes only if `run-gametest/logs/latest.log` contains "All N required tests
passed". The server exits cleanly even when mods fail to load, so its exit code proves nothing.

## GameTests

Each run starts a real dedicated server with the real mods, fetched by the pins in
`gradle.properties`. `-P` flags choose which suites run. The suites and test names are the Forge
build's:

| Suite (namespace) | Runs in | Tests |
|---|---|---|
| Proxy bridge (`mcamobcompat`) | every run | 22: classificationsAndMcaPersonality, stableLinksAndTwoWayNames, unloadedOwnerNeverCausesProxyDeletion, rejectedSpawnDoesNotCommitLinks, cancelledInitializationNeverCommits, protectedAnimalsAndFriendlyFire, proxyHitboxIsNestedInsideTheGuard, arrowsUseNativeGuardCollision, tippedArrowsKeepNativePotionEffects, tridentsUseOneNativeGuardImpact, snowballsConsumeAtTheGuard, damageRelaysTheFinalAmountAndHonoursCancellation, totemSurvivalKeepsIdentity, lateCancelledDeathNeverKillsIdentity, confirmedDeathRelaysOnce, killingTheProxyReleasesTheLink, rebuildReplacesTheProxyAndRelinks, aStaleGuardKeyDoesNotBlockCreation, recruitmentPreservesIdentityAndItemData, dimensionTravelPreservesBothUuids, graceAndModuleToggleKeepThePair, disabledBridgeRoutesASelectedProxyToTheGuard |
| Threats (`mcamobcompat`) | every run | 5: aVillagerRunsFromACreeperHuntingIt, anMcaGuardTakesTheCreeperOn, aBurningFuseSendsEvenAGuardRunning, aHiddenProxyNeverReacts, aCreeperIsAThreatByTag |
| Mounts (`mcamobcompat`) | every run | 2: aVanillaHorseStillTakesAnMcaVillager, aPlayerIsNeverRefusedByTheVillagerPolicy |
| SWEM (`mcamobcompat_swem`) | `-PwithSwem` | 3: anOwnedSwemHorseIsRecognisedAndRespectsSuppression, aVillagerCannotSteerASwemHorseButCanAVanillaOne, aSwemHorseRefusesAnMcaVillager |
| Icy's Better Horses (`mcamobcompat_icy`) | `-PwithIcy`, `-PwithIcy116` | 1: icysOwnerAndHitchDecideWhoRides |
| Alex's Mobs (`mcamobcompat_alex`) | `-PwithAlex`, `-PwithAlexContinued` | 1: aTamedRaccoonIsAProtectedPetAndAnElephantAMount |
| Golems Kill Creepers (`mcamobcompat_golems`) | `-PwithGolems` | 1: golemGamerulesAreLeftAlone |

Where 1.21.1 differs, the tests follow the mod, not the other way round:

- **Icy's Better Horses 2.0** lets only players ride any horse, its own breeds and vanilla ones alike.
  The mount tests therefore check the mount policy's decision directly and seat a villager only on a
  horse that accepts a non-player rider at all (checked with a zombie first). The Icy test runs on
  Icy 2.0's `icys-better-horses:arabian_horse`, and on a vanilla horse under 1.1.6, which registers
  no breeds; its hitch check runs only where Icy has hitching (1.1.6).
- **The SWEM observation** behind the mount policy is `aVillagerCannotSteerASwemHorseButCanAVanillaOne`:
  the same MCA villager rides a vanilla horse as its controlling passenger, and the horse, sent
  somewhere, walks more than two blocks with it aboard, while a SWEM 1.6.12 horse names no controlling
  passenger. (Icy's Better Horses 2.0 lets only players ride any horse, so with it installed only
  the SWEM half is checked.) An earlier form of this test drove the SWEM horse's own navigation and
  checked that it moved less than half a block; with every optional mod loaded it sometimes walked,
  because with a villager aboard a SWEM horse is under its own control. See [UPSTREAM.md](../UPSTREAM.md).
- **Both Alex's Mobs builds** pass the same test; [ALEXS_MOBS_AUDIT.md](../ALEXS_MOBS_AUDIT.md) compares
  them type by type.

The core run reported every MCA binding as ok at start-up, with root `net.conczin.mca` on
7.7.36-beta.3.

### Repeated runs and one intermittent failure

After the final build, the all-mods configuration ran five more times
([gametest-all-mods-repeats.txt](gametest-all-mods-repeats.txt)). Four passed. One failed
`aBurningFuseSendsEvenAGuardRunning`: the MCA guard had not moved a block away from a creeper with a
lit fuse within the test's 26 ticks ([gametest-all-mods-intermittent.log](gametest-all-mods-intermittent.log)).
Across the 49 NeoForge GameTest runs of this work that test failed twice, both times with every
optional mod loaded, and passed in every other run; it never failed in 21 Forge runs. Its cause is
not found. This is recorded as a known intermittent failure, not a pass.

## Production runtime

A NeoForge 21.1.248 server and client, each installed by NeoForge's own installer, in disposable
directories, with a new flat world. Mods on both sides: MCA 7.7.36-beta.3+1.21.1, Guard Villagers
2.4.12, the release jar above and the runtime-probe jar. Clients ran headless under
`gamescope --backend headless` through `scripts/validation/launch_client.py`, as offline players
against a server bound to 127.0.0.1. `scripts/validation/run_scenario.py` ran each scenario. The
probe drives a real client: it clicks, crouches, opens screens and takes screenshots with
Minecraft's own screenshot code, and it logs each check as a `RUNTIME_PROBE` line.

**Two clients on a dedicated server** (`ProbeOne`, `ProbeTwo`):

- The server gave each player a guard and its proxy, added 7 hearts through the API and read them
  back from MCA's own villager (`PAIR ... hearts=7`). It then set MCA's Follow, Stay and Move on the
  proxy and saw Guard Villagers follow MCA's authorised player, patrol and stop
  (`MOVEMENT_VERIFIED`).
- Each client learned its pair from the `mcamobcompat:proxy_pair` payload (`CLIENT_PAIR ...
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
  camera.

**Single player.** A copy of the server's world opened on the client's integrated server. Every
check above passed for `ProbeSolo`. MCA's first-join Destiny screen pauses a single-player game and
reopens itself each tick until its flow ends, so the probe ends it the way MCA's own story pages do
(`DestinyManager.allowClosing()`) instead of choosing a starting village.

**Guard Villagers absent.** Server and client loaded MCA, this mod and the probe only; the client
joined and played (`MINIMAL_CLIENT_READY`, `MINIMAL_PLAYER_JOIN`).

**Client without this mod.** A client with MCA and Guard Villagers but not this mod was refused
during configuration: "Channel [mcamobcompat:proxy_pair] failed to connect ... This channel is
missing on the client side".

**The 2.0 fault, reproduced.** The same two-client scenario with the released 2.0 jar
(`mcamobcompat-neoforge-2.0+1.21.1.jar`, SHA-256
`b1af7d916a5edc03b6272317a2aa323a9f64621dcfbc7f892344d5530ca599de`) in place of 2.1. On each
recruiting client the villager was gone (`RECRUIT_CLIENT FAIL ... present=false removed=DISCARDED`).
The server still held it as a guard with the sword (`RECRUIT_SERVER PASS`), and the other client
still saw and rendered it (`RECRUIT_OBSERVER ... removed=false:rendered=90`). 2.0 blocked Guard
Villagers' conversion on the server but returned early on the client before cancelling, so Guard
Villagers' client-side conversion discarded the villager. 2.1 cancels on both sides first.

## Renumbering to 0.2.1

The release was renumbered from 2.1 to 0.2.1 after validation, so that both loaders follow the Forge
line's 0.2.0; the port was released as 2.0. `mod_version` changed, and so did the comment on
`[mounts] npcRidingPolicy`, which names the version whose behaviour `ALLOW_INERT` restores. A clean
`build` of 0.2.1 passed the 68 unit tests and both jar gates ([build.log](build.log),
[unit-tests.txt](unit-tests.txt)). Comparing it entry by entry with the 2.1 jar above: the
same 84 classes and the same entry names, and two entries changed.

| Entry | Change |
|---|---|
| `META-INF/neoforge.mods.toml` | `version="2.1+1.21.1"` becomes `version="0.2.1+1.21.1"` |
| `dev/otectus/mcamobcompat/McaMobCompatConfig$Server.class` | the config comment string "the behaviour before 2.1." becomes "the behaviour before 0.2.1." |

## Not run

- The four sibling addons with 2.1 or 0.2.1 (their 1.21.1 builds ran with 2.0; see below), and any run
  with MCA: Conversations or MCA: Quests screens.
- Chat or gifts changing hearts on a real client, and a Guard Villagers inventory opened by a crouch
  click (the probe's crouch click is the recruit).
- A mount refusal message reaching the player who gave the order, outside GameTests.
- A server restart with saved pairs under 2.1 or 0.2.1 (2.0's restart checks are below; the saved-data schema
  is unchanged at version 3).
- Any run with Genetic Animals or DragN's Livestock Overhaul, for which no NeoForge 1.21.1 build was
  available.
- Internet authentication; every client was an offline player on a loopback server.

## 2.0 record (historical)

2.0+1.21.1 was validated on 2026-09-14 with 53 JUnit tests, 19 GameTests and production runs on
NeoForge 21.1.248 and 21.1.249, including all four sibling addons. Its evidence stays in
[validation/](./) under its original names. What follows is that record as it was written;
where it describes the projectile bounds exclusion, 2.1 replaced it with the nested proxy hitbox
([PORTING.md](../PORTING.md)).

Recorded 2026-09-14. Tests use Java 21 and actual published MCA/Guard Villagers/SWEM JARs. Production checks use installer-created NeoForge runtimes (`forgeclient`/`forgeserver`), not development launch targets. Two separate Minecraft clients connected simultaneously to a localhost-only dedicated server; a separate client ran an integrated server on a disposable world copy. A separately packaged validation driver performed reproducible input/assertions and screenshots; it is not part of the production addon.

### Version 2.0 rebuild

The release is now **2.0+1.21.1**. `./gradlew clean build` passed all **53 JUnit tests** and both artifact gates. Comparing the rebuilt JAR with the previously runtime-tested artifact found exactly one changed entry: `META-INF/neoforge.mods.toml`, containing only the addon version update. All class and gameplay resource bytes are identical. See the [rebuild log](version-2.0-build.log), [artifact comparison](version-2.0-build.json), and [current checksum](production-jar.sha256).

The GameTest and production runtime evidence below was recorded with the preceding `0.3.0+1.21.1` build; those runtime runs were not repeated for this metadata-only update. Its original checksum is preserved in [runtime-tested-0.3.0.sha256](runtime-tested-0.3.0.sha256).

### Original port validation results

- **53 JUnit tests passed**, retaining the original tests and adding migration, pending-heart arithmetic, future/malformed/conflicting save preservation and personality-shape coverage.
- **19 registered GameTests passed** against NeoForge 21.1.248, MCA, Guard Villagers and SWEM. These exercise real entities, events, native projectiles and SavedData.
- Production JAR loaded on dedicated server and client with **all optional integrations absent**.
- Production JAR passed **single-player and two-client dedicated-server** interaction/pairing/render checks, with all four locally available MCA companion builds installed on NeoForge 21.1.249.
- Both saved guard/proxy UUID pairs survived repeated server restarts. Verified hearts progressed `7 → 14 → 21 → 28` through deliberate `+7` API changes, with the prior balance preserved at each restart and no extra heartbeat increment.
- A client missing the addon was rejected during configuration: `mcamobcompat:proxy_pair` was reported missing on the client and required on the server.
- Build/resource/class-link gates passed. The main JAR contains no fixture classes, nested dependency JARs, old Forge metadata/Java references or legacy entity-tag directory.

The original 0.3.0 build in the delivered folder also passed all 53 tests and both artifact gates. Every ZIP entry name and uncompressed byte matched its runtime-tested JAR; only ZIP container metadata differed. This is historical evidence for that build; the current 2.0 artifact and checksum are recorded above. See [destination build log](destination-build.log) and [comparison record](destination-build.json).

Exact production artifact hash: [production-jar.sha256](production-jar.sha256). Exact dependency hashes/metadata: [DEPENDENCIES.json](../DEPENDENCIES.json).

### Runtime versions

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 (build/GameTests/initial production); 21.1.249 (final production/companions) |
| MCA | 7.7.36-beta.3+1.21.1 |
| Guard Villagers | 2.4.12, Modrinth `JyXugpy2` |
| SWEM | 1.6.12, CurseForge file 8557159 |
| GeckoLib | 4.7.5.1 |
| Player Animator | 2.0.4+1.21.1, CurseForge file 7389814 |
| MCAQuests | 1.6.4 |
| MCAConversations | 1.6.3+1.21.1 |
| MCACrime | 0.7.2 |
| MCAReputation | 0.5.0 |

MCAReputation 0.5.0 declares NeoForge 21.1.249 minimum. It exposes API v2, while these Quests/Crime builds require API v1. Both log the incompatibility and use their own stores. That companion-to-companion integration is **not** claimed working; this port does not modify their API contracts. Conversations' dialogue and Quests' guard menu/portrait were exercised despite that mismatch.

### Acceptance coverage

| Area | Executed evidence | Result / boundary |
|---|---|---|
| Minimal runtime | Installer-created server and client; MCA + addon + isolated test driver, every optional mod absent | Pass; client reaches play state, common/client classes load |
| Artifact/classpath | `build`, 53 JUnit tests, both JAR gates; no local optional `libs/` needed | Pass |
| Connection | Two matching clients; client missing addon | Matching clients pass; missing addon rejected before play. Deliberately altered protocol version and server-missing-addon permutations not separately run |
| Classification | Vanilla cow/livestock/horse tags; real owned/tamed SWEM horse; module suppression | Pass; ownership remains unchanged |
| Reload/toggles | Cache unit tests; real module-off/restore; existing proxy stays hidden/linked; disabled-proxy selection delegates to native guard | Pass for executed cases; custom datapack editing and second world in one client JVM remain manual cases |
| Conversion | Main/offhand protection, survival weapon transfer with custom item name, repeated recruitment/occupied equipment | Pass; creative/baby/modded-weapon branches implemented but not each exercised with a client |
| Interaction | Actual MCA screen from both clients and single-player; one specific and one generic forwarded server hook per guard click | Pass; all third-party cancellation-order combinations and every inventory gesture are not exhaustively covered |
| Proxy creation | Grace period, idempotence, canceled initialization, rejected insertion, family-tree snapshot rollback | Pass; no leaked nodes or committed seed/link on rejection |
| Persistence | Schema 1/2/3 fixtures; offline deltas; int-range crossings; future/malformed/conflicting records; actual production restarts | Pass; see migration ambiguity notes in PORTING.md |
| Lifetime | Unloaded owner retained beyond 200 ticks; exact UUID recovery; dimension transfer; module-off pair retention | Pass. Dimension test holds a destination chunk and waits for entity visibility; arbitrary portal destinations/cold-load ordering are not exhaustively covered |
| Movement | Actual MCA Follow/Stay/Move state with real server players; incorrect last-click UUID deliberately supplied | Pass: guard uses MCA's authorized player. Full escort path completion and logout/resume behavior remain manual cases |
| Rendering | Exact UUID pairs on two clients; MCA interaction; Quests portrait; Conversations dialogue portrait; standalone inventory-helper portrait; world render cancellation counters | Pass; screenshots below. All armor/skin/resource-reload combinations not exhaustively covered |
| Chores | Policy unit tests; real named animal incoming damage veto; runtime binding resolution | Pass; long-running hunting-job behavior remains a manual case |
| Combat | Final damage after armor/absorption; incoming cancellation; no independent proxy injury | Pass for tested cases; all fire/lava/shield/rapid-hit combinations remain manual cases |
| Projectiles | Piercing arrow, tipped poison arrow, trident, snowball, proxy bounds restoration and single native guard impact | Pass. No additional modded-projectile implementation was exercised |
| Death | Confirmed death exactly once, totem survival, late death cancellation | Pass. Cancellation of the synthetic MCA death itself has an upstream limitation described below |
| Companions | All four together: actual Quests screen/offers/hearts/portrait; Conversations dialogue/portrait; guard classification and identity | Pass for these paths. Individual-isolation combinations, quest completion/rewards, crime restraint/escort and full reputation incidents remain manual cases; API v1/v2 incompatibility is external |
| Operator tools | Dedicated console `status`, `mca`, `guards status`, `guards proxies`; source permission gate; conflict/future preservation unit tests | Executed diagnostics pass. Destructive commands were not applied to valuable worlds |
| Load/performance | Repeated production joins/restarts, bounded caches and scoped cleanup | No accumulating proxy links observed; representative large-village load benchmark not run |

The unexecuted cases above are follow-up coverage, not additional claimed passes. Unavailable target mods and upstream API/behavior issues remain explicit external limitations.

### Important external limits

- No actual NeoForge 1.21.1 Genetic Animals or DLO build was available in the verified dependency matrix. Their optional entity/tag support remains; their genetics/breeding/mount behavior is not claimed tested.
- MCA's selected `die` override performs relationship callbacks after `super.die()` even if another NeoForge listener canceled that death. The bridge delays until the visible guard truly dies, never retries a rejected synthetic death, and preserves the link for diagnosis. Fully rolling back a canceled MCA death requires an upstream MCA fix or a broader integration surface.
- The actual companion versions have the MCAReputation API mismatch above. Do not read a successful combined startup as proof that Quests/Crime's disabled reputation integration works.
- Narrator `flite` warnings, optional Townstead mixin targets, third-party refmap warnings and SWEM animation-directory warnings occurred without preventing the tested paths. The isolated server used offline authentication bound to loopback; Internet authentication was not part of these tests.

### Evidence

- [Build and all 19 GameTests](mcamobcompat-final-verification.log), [JUnit XML](junit/).
- [Final dedicated server](production-final-server.log), [client one](production-companions-client-one.log), [client two](production-companions-client-two.log).
- [Single-player](production-singleplayer.log), [missing-addon rejection](production-missing-client.log).
- [Minimal server](production-minimal-final-server.log), [minimal client](production-minimal-client.log).
- [Quests portrait/menu](final-one-probe-quests.png), [Conversations dialogue](final-solo-probe-conversations.png), [guard portrait](final-one-probe-portrait.png), [world view](final-one-probe-world-final.png).

## Reproduction

```sh
./gradlew build
./gradlew runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems
./gradlew runtimeProbeJar
```

Use installer-created NeoForge profiles for production tests. Install the main addon jar and the exact
runtime mods in both client and server `mods` folders; add the separate `*-runtime-probe.jar` only in
disposable validation instances. `scripts/validation/run_scenario.py` describes the runtime directory
it expects and runs one scenario at a time. `scripts/validation/launch_client.py RUNTIME_ROOT GAME_DIR
PLAYER_NAME [--solo WORLD_NAME]` launches an installer-created Linux client profile, of either
loader. It looks for Java and the asset cache where this host keeps them; set `MCAMOB_PROBE_JAVA` and
`MCAMOB_PROBE_ASSETS` on another machine. Do not use the probe in a world you want to keep.
