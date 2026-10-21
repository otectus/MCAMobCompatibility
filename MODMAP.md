# MODMAP — mcamobcompat

Current NeoForge source map. Historical Forge design is archived separately.

| Build component | Value |
|---|---|
| Addon release | see `gradle.properties` |
| Minecraft / Java | 1.21.1 / 21 |
| NeoForge compile target | 21.1.248 |
| Production runtime validation | 21.1.248 |
| Gradle / ModDevGradle | 8.12 / 2.0.141 |
| Mapping names | Official 1.21.1 |
| Mixins / access transformers | Optional hooks only (`mcamobcompat.mixins.json`, declared in `neoforge.mods.toml`) / none |
| Registered entities/items/blocks | None |

## Production packages

| Package | Java files |
|---|---:|
| `dev.otectus.mcamobcompat` | 2 |
| `dev.otectus.mcamobcompat.api` | 5 |
| `dev.otectus.mcamobcompat.api.event` | 3 |
| `dev.otectus.mcamobcompat.bridge` | 9 |
| `dev.otectus.mcamobcompat.character` | 23 |
| `dev.otectus.mcamobcompat.classification` | 6 |
| `dev.otectus.mcamobcompat.client` | 4 |
| `dev.otectus.mcamobcompat.command` | 2 |
| `dev.otectus.mcamobcompat.event` | 13 |
| `dev.otectus.mcamobcompat.hook` | 2 |
| `dev.otectus.mcamobcompat.hook.mixin` | 2 |
| `dev.otectus.mcamobcompat.integration` | 3 |
| `dev.otectus.mcamobcompat.integration.alexsmobs` | 1 |
| `dev.otectus.mcamobcompat.integration.dragnlivestock` | 1 |
| `dev.otectus.mcamobcompat.integration.geneticanimals` | 1 |
| `dev.otectus.mcamobcompat.integration.guardvillagers` | 5 |
| `dev.otectus.mcamobcompat.integration.icysbetterhorses` | 2 |
| `dev.otectus.mcamobcompat.integration.recruits` | 4 |
| `dev.otectus.mcamobcompat.integration.swem` | 1 |
| `dev.otectus.mcamobcompat.integration.workers` | 4 |
| `dev.otectus.mcamobcompat.mca` | 3 |
| `dev.otectus.mcamobcompat.mount` | 4 |
| `dev.otectus.mcamobcompat.network` | 1 |
| `dev.otectus.mcamobcompat.threat` | 3 |
| `dev.otectus.mcamobcompat.util` | 2 |

The same packages, with the same file counts, as the Forge 1.20.1 build. The `recruits` and `workers`
integrations exist here in parity and report themselves unavailable: neither mod has a NeoForge 1.21.1
build (`integration/PlatformArtifacts`).

## Resources and validation sources

- Thirteen `data/mcamobcompat/tags/entity_type` JSON files, byte-identical to the Forge build's: ten
  semantic roles, MCA recognition, `npc_threats` and `player_only_mounts`.
- `META-INF/neoforge.mods.toml` (with the `[[mixins]]` declaration), `mcamobcompat.mixins.json`,
  `pack.mcmeta`, and `assets/mcamobcompat/lang/en_us.json`.
- `src/test`: JUnit policy, migration, transition-recovery, editor-policy, hook, classification,
  binding and artifact tests. The Recruits and Workers binding probes are skipped here: there is no
  NeoForge jar to probe.
- `src/gameTest`: the `mcamobcompat_tests` GameTests, one namespace per optional mod, and their
  structure fixtures. `CharacterGameTests` is in the core namespace; the `mcamobcompat_recruits` and
  `mcamobcompat_workers` suites exist for parity and are never enabled here.
- `src/runtimeProbe`: isolated production client/server validation driver; separately packaged, opt-in.
- `scripts/validation`: production client launcher and scenario runner; `docs/validation`: observed logs/screenshots.
- `scripts/parity.py` and `parity-manifest.txt`: the check that holds this build and the Forge one together.

## Maintained decisions

Reflection-only optional integration; a five-class exact allowlist for foreign dotted names (the MCA,
Recruits and Workers bindings and the two optional hooks); optional, string-targeted hooks counted at
start and refused safely when absent; server-authoritative UUID pairing with association generations;
journaled, stamped transitions that recover forward without guessing; no unresolved-identity timeout
deletion; schema 4 records with hearts snapshots separated from queued deltas; the identity's hitbox
nested inside its actor's; one version, feature set and tag set with the Forge build. See
[PORTING.md](docs/PORTING.md) for rationale, [NATIVE_CHARACTER_COMPATIBILITY.md](docs/NATIVE_CHARACTER_COMPATIBILITY.md)
for the 0.3.0 design, and [VALIDATION.md](docs/VALIDATION.md) for test scope and external limitations.
