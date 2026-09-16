# NeoForge 1.21.1 validation

Recorded 2026-09-14. Tests use Java 21 and actual published MCA/Guard Villagers/SWEM JARs. Production checks use installer-created NeoForge runtimes (`forgeclient`/`forgeserver`), not development launch targets. Two separate Minecraft clients connected simultaneously to a localhost-only dedicated server; a separate client ran an integrated server on a disposable world copy. A separately packaged validation driver performed reproducible input/assertions and screenshots; it is not part of the production addon.

## Version 2.0 rebuild

The release is now **2.0+1.21.1**. `./gradlew clean build` passed all **53 JUnit tests** and both artifact gates. Comparing the rebuilt JAR with the previously runtime-tested artifact found exactly one changed entry: `META-INF/neoforge.mods.toml`, containing only the addon version update. All class and gameplay resource bytes are identical. See the [rebuild log](validation/version-2.0-build.log), [artifact comparison](validation/version-2.0-build.json), and [current checksum](validation/production-jar.sha256).

The GameTest and production runtime evidence below was recorded with the preceding `0.3.0+1.21.1` build; those runtime runs were not repeated for this metadata-only update. Its original checksum is preserved in [runtime-tested-0.3.0.sha256](validation/runtime-tested-0.3.0.sha256).

## Original port validation results

- **53 JUnit tests passed**, retaining the original tests and adding migration, pending-heart arithmetic, future/malformed/conflicting save preservation and personality-shape coverage.
- **19 registered GameTests passed** against NeoForge 21.1.248, MCA, Guard Villagers and SWEM. These exercise real entities, events, native projectiles and SavedData.
- Production JAR loaded on dedicated server and client with **all optional integrations absent**.
- Production JAR passed **single-player and two-client dedicated-server** interaction/pairing/render checks, with all four locally available MCA companion builds installed on NeoForge 21.1.249.
- Both saved guard/proxy UUID pairs survived repeated server restarts. Verified hearts progressed `7 → 14 → 21 → 28` through deliberate `+7` API changes, with the prior balance preserved at each restart and no extra heartbeat increment.
- A client missing the addon was rejected during configuration: `mcamobcompat:proxy_pair` was reported missing on the client and required on the server.
- Build/resource/class-link gates passed. The main JAR contains no fixture classes, nested dependency JARs, old Forge metadata/Java references or legacy entity-tag directory.

The original 0.3.0 build in the delivered folder also passed all 53 tests and both artifact gates. Every ZIP entry name and uncompressed byte matched its runtime-tested JAR; only ZIP container metadata differed. This is historical evidence for that build; the current 2.0 artifact and checksum are recorded above. See [destination build log](validation/destination-build.log) and [comparison record](validation/destination-build.json).

Exact production artifact hash: [production-jar.sha256](validation/production-jar.sha256). Exact dependency hashes/metadata: [DEPENDENCIES.json](DEPENDENCIES.json).

## Runtime versions

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

## Acceptance coverage

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

## Important external limits

- No actual NeoForge 1.21.1 Genetic Animals or DLO build was available in the verified dependency matrix. Their optional entity/tag support remains; their genetics/breeding/mount behavior is not claimed tested.
- MCA's selected `die` override performs relationship callbacks after `super.die()` even if another NeoForge listener canceled that death. The bridge delays until the visible guard truly dies, never retries a rejected synthetic death, and preserves the link for diagnosis. Fully rolling back a canceled MCA death requires an upstream MCA fix or a broader integration surface.
- The actual companion versions have the MCAReputation API mismatch above. Do not read a successful combined startup as proof that Quests/Crime's disabled reputation integration works.
- Narrator `flite` warnings, optional Townstead mixin targets, third-party refmap warnings and SWEM animation-directory warnings occurred without preventing the tested paths. The isolated server used offline authentication bound to loopback; Internet authentication was not part of these tests.

## Evidence

- [Build and all 19 GameTests](validation/mcamobcompat-final-verification.log), [JUnit XML](validation/junit/).
- [Final dedicated server](validation/production-final-server.log), [client one](validation/production-companions-client-one.log), [client two](validation/production-companions-client-two.log).
- [Single-player](validation/production-singleplayer.log), [missing-addon rejection](validation/production-missing-client.log).
- [Minimal server](validation/production-minimal-final-server.log), [minimal client](validation/production-minimal-client.log).
- [Quests portrait/menu](validation/final-one-probe-quests.png), [Conversations dialogue](validation/final-solo-probe-conversations.png), [guard portrait](validation/final-one-probe-portrait.png), [world view](validation/final-one-probe-world-final.png).

## Reproduction

```sh
./gradlew build
./gradlew runGameTestServer -PwithGuards -PwithSwem
./gradlew runtimeProbeJar
```

Use installer-created NeoForge profiles for production tests. Install the main addon JAR and the exact runtime mods in both client/server `mods` folders; add the separate `*-runtime-probe.jar` only in disposable validation instances. The driver spawns its test guard near the player, checks live hearts and Follow/Stay/Move, opens MCA/Quests/Conversations, captures screenshots and exits the client. Its `probe-pairs.properties` fixture (`PlayerName=guardUUID proxyUUID expectedHearts`) enables restart assertions. Do not use this driver in a playing world.

`scripts/validation/launch_client.py RUNTIME_ROOT GAME_DIR PLAYER_NAME` launches an installer-created Linux profile using verified manifest libraries and cached assets. `MCAMOB_PROBE_NEOFORGE` selects 21.1.248/249; `MCAMOB_PROBE_SERVER` selects the loopback server; `--solo WORLD_NAME` opens an existing disposable single-player world. The helper records this host's JDK/cache paths; adapt those paths on another machine. Headless graphics were provided by `gamescope --backend headless`; screenshots came from Minecraft's own screenshot API.

Manual follow-ups should retain the same pair UUID through each operation: disable/re-enable modules, `/reload` a role datapack, leave/re-enter tracking range, travel through portals, reconnect, issue Follow/Stay/Move, use Guard Villagers inventory, gift/rename through MCA, complete a quest and observe one reward/incident. Run with backups and a compatible companion API matrix.
