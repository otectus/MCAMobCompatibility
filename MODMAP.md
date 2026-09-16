# MODMAP — mcamobcompat

Current NeoForge source map. Historical Forge design is archived separately.

| Build component | Value |
|---|---|
| Addon release | 2.0+1.21.1 |
| Minecraft / Java | 1.21.1 / 21 |
| NeoForge compile target | 21.1.248 |
| Production runtime validation | 21.1.248 and 21.1.249 |
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
| `dev.otectus.mcamobcompat.classification` | 5 |
| `dev.otectus.mcamobcompat.client` | 1 |
| `dev.otectus.mcamobcompat.command` | 1 |
| `dev.otectus.mcamobcompat.event` | 10 |
| `dev.otectus.mcamobcompat.integration` | 2 |
| `dev.otectus.mcamobcompat.integration.dragnlivestock` | 1 |
| `dev.otectus.mcamobcompat.integration.geneticanimals` | 1 |
| `dev.otectus.mcamobcompat.integration.guardvillagers` | 3 |
| `dev.otectus.mcamobcompat.integration.swem` | 1 |
| `dev.otectus.mcamobcompat.mca` | 2 |
| `dev.otectus.mcamobcompat.network` | 1 |
| `dev.otectus.mcamobcompat.util` | 2 |

## Resources and validation sources

- Eleven `data/mcamobcompat/tags/entity_type` JSON files: ten semantic roles plus MCA recognition.
- `META-INF/neoforge.mods.toml`, `pack.mcmeta`, and `assets/mcamobcompat/lang/en_us.json`.
- `src/test`: JUnit policy, migration, classification, binding and artifact tests.
- `src/gameTest`: registered NeoForge entity/lifecycle/combat tests and their structure fixture.
- `src/runtimeProbe`: isolated production client/server validation driver; separately packaged, opt-in.
- `scripts/validation`: production launcher helper; `docs/validation`: observed logs/screenshots.

## Maintained decisions

Reflection-only optional integration; one exact MCA class-name allowlist entry; server-authoritative UUID pairing; transactional proxy creation; no unresolved-identity timeout deletion; schema 3 snapshots separated from queued deltas. See [PORTING.md](docs/PORTING.md) for rationale and [VALIDATION.md](docs/VALIDATION.md) for test scope and external limitations.
