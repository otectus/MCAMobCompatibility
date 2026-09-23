# MODMAP — mcamobcompat

Current NeoForge source map. Historical Forge design is archived separately.

| Build component | Value |
|---|---|
| Addon release | 0.2.1+1.21.1 |
| Minecraft / Java | 1.21.1 / 21 |
| NeoForge compile target | 21.1.248 |
| Production runtime validation | 21.1.248 |
| Gradle / ModDevGradle | 8.12 / 2.0.141 |
| Mapping names | Official 1.21.1 |
| Mixins / access transformers | None |
| Registered entities/items/blocks | None |

## Production packages

| Package | Java files |
|---|---:|
| `dev.otectus.mcamobcompat` | 2 |
| `dev.otectus.mcamobcompat.api` | 5 |
| `dev.otectus.mcamobcompat.api.event` | 3 |
| `dev.otectus.mcamobcompat.bridge` | 8 |
| `dev.otectus.mcamobcompat.classification` | 6 |
| `dev.otectus.mcamobcompat.client` | 2 |
| `dev.otectus.mcamobcompat.command` | 1 |
| `dev.otectus.mcamobcompat.event` | 13 |
| `dev.otectus.mcamobcompat.integration` | 2 |
| `dev.otectus.mcamobcompat.integration.alexsmobs` | 1 |
| `dev.otectus.mcamobcompat.integration.dragnlivestock` | 1 |
| `dev.otectus.mcamobcompat.integration.geneticanimals` | 1 |
| `dev.otectus.mcamobcompat.integration.guardvillagers` | 3 |
| `dev.otectus.mcamobcompat.integration.icysbetterhorses` | 2 |
| `dev.otectus.mcamobcompat.integration.swem` | 1 |
| `dev.otectus.mcamobcompat.mca` | 3 |
| `dev.otectus.mcamobcompat.mount` | 4 |
| `dev.otectus.mcamobcompat.network` | 1 |
| `dev.otectus.mcamobcompat.threat` | 3 |
| `dev.otectus.mcamobcompat.util` | 2 |

The same packages, with the same file counts, as the Forge 1.20.1 build.

## Resources and validation sources

- Thirteen `data/mcamobcompat/tags/entity_type` JSON files, byte-identical to the Forge build's: ten
  semantic roles, MCA recognition, `npc_threats` and `player_only_mounts`.
- `META-INF/neoforge.mods.toml`, `pack.mcmeta`, and `assets/mcamobcompat/lang/en_us.json`.
- `src/test`: JUnit policy, migration, classification, binding and artifact tests.
- `src/gameTest`: the `mcamobcompat_tests` GameTests, one namespace per optional mod, and their structure fixtures.
- `src/runtimeProbe`: isolated production client/server validation driver; separately packaged, opt-in.
- `scripts/validation`: production client launcher and scenario runner; `docs/validation`: observed logs/screenshots.
- `scripts/parity.py` and `parity-manifest.txt`: the check that holds this build and the Forge one together.

## Maintained decisions

Reflection-only optional integration; one exact MCA class-name allowlist entry; server-authoritative UUID pairing; transactional proxy creation; no unresolved-identity timeout deletion; schema 3 snapshots separated from queued deltas; the proxy's hitbox nested inside its guard's; one version, feature set and tag set with the Forge build. See [PORTING.md](docs/PORTING.md) for rationale and [VALIDATION.md](docs/VALIDATION.md) for test scope and external limitations.
