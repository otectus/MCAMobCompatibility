# MCA: Mob Compatibility 0.3.0 (NeoForge 1.21.1): validation record

What was run to check 0.3.0 on NeoForge 1.21.1, what it showed, and what was not run. The Forge 1.20.1
build of the same release keeps its own record in its repository's `docs/VALIDATION.md`; the two builds
share their unit tests, GameTests, runtime probe and scenario runner. Every third-party jar involved,
with its SHA-256, is in [DEPENDENCIES.json](DEPENDENCIES.json); the per-capability status is in
[NATIVE_CHARACTER_COMPATIBILITY.md](NATIVE_CHARACTER_COMPATIBILITY.md). The logs and screenshots are
in [validation/0.3.0/](validation/0.3.0/). The 0.2.1 record is archived at
[validation/VALIDATION-0.2.1.md](validation/VALIDATION-0.2.1.md).

Recorded 2026-10-20 on Linux, Java 21, NeoForge 21.1.248.

## Summary

| Check | Result | Evidence |
|---|---|---|
| Unit tests (`build` runs `test`) | 106 tests in 24 classes, 0 failures; 2 skipped: the Villager Recruits and Villager Workers binding probes, which have no NeoForge jar to probe | [unit-tests.txt](validation/0.3.0/unit-tests.txt) |
| Jar gates in `build` | `checkJarContents` clean against the 5-class dotted-name allowlist; `verifyProductionJar` passed (Java 21 bytecode, the `[[mixins]]` declaration, hook config optional with no refmap) | [build.log](validation/0.3.0/build.log) |
| Lang keys | `en_us.json` byte-identical to the Forge build's; all 67 keys the code uses are defined, and every defined key is used | the Forge build's `check_mod.txt` |
| Binding probes | MCA binding and editor-hook target (`handleServer`) in all 5 NeoForge MCA builds of the fleet, 7.7.0 to 7.7.36-beta.3 | unit tests above |
| GameTests, MCA 7.7.36-beta.3 + Guard Villagers 2.4.12 | All 39 required tests passed | [gametest-core.log](validation/0.3.0/gametest-core.log) |
| GameTests, core + SWEM, Icy's 2.0, Alex's (Raguto port), Golems Kill Creepers | All 45 required tests passed | [gametest-all-mods.log](validation/0.3.0/gametest-all-mods.log) |
| GameTests, core + SWEM 1.6.12 alone | All 42 required tests passed | [gametest-swem-only.log](validation/0.3.0/gametest-swem-only.log) |
| GameTests, core + Alex's Mobs Continued | All 40 required tests passed | [gametest-alex-continued.log](validation/0.3.0/gametest-alex-continued.log) |
| GameTests, core + Icy's Better Horses 1.1.6 + MCA: Crime 0.8.0 | All 42 required tests passed | [gametest-icy116-crime.log](validation/0.3.0/gametest-icy116-crime.log) |
| Production: dedicated server, two clients | Every check passed on both clients and the server | [runtime/two/](validation/0.3.0/runtime/two/) |
| Production: single player | Every check passed on the integrated server | [runtime/solo/](validation/0.3.0/runtime/solo/) |
| Production: MCA and this mod only | Server and client load and play | [runtime/minimal/](validation/0.3.0/runtime/minimal/) |
| Production: client without this mod | Refused at login: "Channel [mcamobcompat:proxy_pair] failed to connect" | [runtime/missing/](validation/0.3.0/runtime/missing/) |
| Villager Recruits and Villager Workers | **Unavailable**: no NeoForge 1.21.1 build exists (Modrinth, checked 2026-10-20). Both adapters report it at startup and in `characters adapters`; `theNativeAdaptersReportThisPlatformsAvailability` pins it; `-PwithRecruits`/`-PwithWorkers` are refused by the build | [gametest-core.log](validation/0.3.0/gametest-core.log) |
| Parity with the Forge build | In step: 176 files, 114 loader-neutral, 62 reviewed pairs | `python3 scripts/parity.py` |

**The artifact:** `build/libs/mcamobcompat-neoforge-0.3.0+1.21.1.jar`, 382,868 bytes, 150 classes.

```
SHA-256 4ecad2bd9d79b0b090e941025edc2042351f0a269b4b03795d52ed59d441ec36
```

The production runs loaded exactly that jar, and the validation driver beside it,
`mcamobcompat-neoforge-0.3.0+1.21.1-runtime-probe.jar` (SHA-256
`c68c9ecbc633cb9eec0719980a21f4017e755c63a912e095e69f8d11b7929562`), which is built only by
`runtimeProbeJar` and is never shipped.

## Commands

Gradle ran through the workspace's quiet wrapper, which writes the full log to disk.

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem -PwithIcy -PwithAlex -PwithGolems
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithSwem
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithAlexContinued
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runGameTestServer -PwithIcy116 -PwithCrime -PcrimeJar=<MCACrime_1.21.1>/build/libs/mcacrime-0.8.0.jar
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> runtimeProbeJar
python3 scripts/validation/run_scenario.py <runtime-dir> two|minimal|missing|solo
python3 scripts/parity.py
```

`runGameTestServer` passes only if `run-gametest/logs/latest.log` contains "All N required tests
passed". The server exits cleanly even when mods fail to load, so its exit code proves nothing.

MCA: Crime's NeoForge builds require GeckoLib `[4.8.3,5)`, so the dev pin moved from 4.7.5.1 to 4.8.3,
and `-PwithCrime` now brings GeckoLib along. SWEM and Icy's 1.1.6 passed with it.

## GameTests

The suites are the Forge build's; see its record for what each pins. Here the core namespace runs
39 tests: the 29 of 0.2.1 plus the 10 character tests, among them
`theNativeAdaptersReportThisPlatformsAvailability`, which expects Guard Villagers available and
Villager Recruits and Villager Workers reported unavailable and inactive. The editor test calls MCA's
own NeoForge payload handler, `handleServer`. The `mcamobcompat_recruits` and `mcamobcompat_workers`
suites exist here for parity and are never enabled.

Every run in the summary is on the final code. An earlier run of this work, superseded, failed five
character tests on a bug in the enlistment's statistic credit, fixed before the runs above (see the
Forge record).

## Production runtime

A NeoForge 21.1.248 server and client, each installed by NeoForge's own installer (`--install-server`,
`--install-client`; SHA-1 as published), in disposable directories, with a new flat world. The vanilla
1.21.1 version file came from Mojang's manifest and matched its SHA-1, as did the installed client jar.
Mods on both sides: MCA 7.7.36-beta.3, Guard Villagers 2.4.12, the release jar above and the
runtime-probe jar. Clients ran headless under `gamescope --backend headless` through
`scripts/validation/launch_client.py`, as offline players against a server bound to 127.0.0.1. The
probe is the Forge build's, with NeoForge's events; it skips the Recruits and Workers steps, which have
nothing to act on here.

**Two clients on a dedicated server** (`ProbeOne`, `ProbeTwo`):

- **Pairing and orders.** Each player got a guard and its identity, with 7 hearts added through the API
  and read back from MCA's own villager (`PAIR ... hearts=7`), and Guard Villagers followed, patrolled
  and stopped on MCA's Follow, Stay and Move after the pair's first heartbeat (`MOVEMENT_VERIFIED`).
- **Drawn as MCA people, by MCA 7.7.36's own renderer.** Each client learned its association over
  protocol 2 (`CLIENT_PAIR ... routed=true mcaAppearance=true generation=1`), and its guard was drawn
  as its MCA person in place of Guard Villagers' model (`WORLD_DRAW ... asPerson=60 nativeCancelled=60
  native=0`).
- **MCA's screen and the portrait.** A plain click opened MCA's own interaction screen; in the probe's
  portrait every cancelled render of the hidden identity drew the person (`RENDER_COUNTS
  hiddenPortrait=90 personPortrait=90 guardPortrait=0`).
- **MCA's editor across two clients.** MCA's own editor opened on each guard's person, and the save went
  through MCA's NeoForge payload (`handleServer`), bracketed by the hook. The server applied the gene
  with the person still hidden (`EDITOR_SERVER ... size=0.65000004 revision=1 invisible=true
  noAi=true`), and **each client saw both characters change** (`APPEARANCE ... character=830eb677…
  ... to=1/0.65000004` on both clients).
- **Native controls, enlistment and the way home.** A crouching click opened Guard Villagers'
  `GuardInventoryScreen`. The recruit gesture enlisted a plain MCA adult as a real guard holding the
  sword, hidden and drawn as the person (`RECRUIT_SERVER`, `RECRUIT_CLIENT`, `RECRUIT_DRAWN` PASS).
  A second crouching click, after walking up to the guard, opened its own inventory and enlisted nobody
  (`ALREADY_ENLISTED PASS`). `/mcamobcompat character release`, looking at the guard standing beside
  the player, brought the same person back visible with the guard's sword in their inventory
  (`RELEASE_SERVER`, `RELEASE_CLIENT` PASS). The Recruits steps found nothing to act on, as expected.

**Single player.** A copy of the server's world opened on the client's integrated server, and every
check above passed for `ProbeSolo`.

**MCA and this mod only.** Server and client loaded MCA, this mod and the probe; the client joined and
played (`MINIMAL_CLIENT_READY`, `MINIMAL_PLAYER_JOIN`).

**Client without this mod.** A client with MCA and Guard Villagers but not this mod was refused at
login: "Channel [mcamobcompat:proxy_pair] failed to connect", then disconnected as an incompatible
client.

The probe's own fixes in these runs (an approach before the second click and a clear look for the
release, because an enlisted guard wanders and 1.21.1's entity-interaction reach is short) are
described in the Forge record; none was a fault in this mod. Earlier, superseded runs here showed both
problems before those fixes.

## Not run

- The items in the Forge record's "Not run" list that apply here: performance profiling; native combat
  against unbridged controls; one continuous acceptance journey; two clients editing one character at
  once; MCA breeding between two characters' persons; resource and renderer reloads; dimension travel
  by an enlisted person; the sibling addons with a client; Genetic Animals, DragN's Livestock Overhaul
  and internet authentication.

## Unavailable

- **Villager Recruits and Villager Workers.** No NeoForge 1.21.1 build of either exists, so their
  adapters, hooks, transitions and GameTest suites are unavailable on this platform. Their code and
  configuration keys exist here unchanged, in parity with the Forge build, and report the
  unavailability instead of claiming support.
