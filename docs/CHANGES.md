# Files changed for the NeoForge 1.21.1 port

Compared with Forge baseline `7062152e3f1a370a9173be89a0bc11f7ed5b2f08`. Paths in the Java table are relative to `src/main/java/dev/otectus/mcamobcompat/`. Public signatures and feature defaults are retained unless a target-loader type must change. See [PORTING.md](PORTING.md) for behavior and migration details and [VALIDATION.md](VALIDATION.md) for observed evidence and limitations.

## Build and resources

| File | Meaningful change |
|---|---|
| `build.gradle` | ModDevGradle, Java 21, explicit runtime-only dependencies, real GameTest source set, isolated production probe, target artifact checks and verified install task. |
| `settings.gradle` | NeoForge plugin repositories and Java toolchain resolver. |
| `gradle.properties` | Pinned Minecraft/NeoForge/MCA versions, tested metadata ranges and port version. |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.12 wrapper distribution. |
| `gradlew` | Restored executable permission. |
| `.gitignore` | Excludes local runtime/build output while retaining validation evidence. |
| `src/main/resources/META-INF/neoforge.mods.toml` | Replaces Forge metadata with required/optional NeoForge dependencies. |
| `src/main/resources/pack.mcmeta` | Target data/resource pack formats. |
| `src/main/resources/assets/mcamobcompat/lang/en_us.json` | Correct interaction/config wording. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/cattle.json` | Moves the cattle role to the 1.21.1 tag directory. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/guard.json` | Moves guard recognition to the target directory. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/horse.json` | Moves horse recognition; retains optional aliases. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/humanoid_npc.json` | Moves humanoid recognition. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/livestock.json` | Moves livestock composition without implicitly adding horses. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/mca_villagers.json` | Moves the additional MCA recognition tag. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/mount.json` | Moves mount recognition and horse composition. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/pet.json` | Moves pet recognition. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/pig.json` | Moves pig recognition. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/poultry.json` | Moves poultry recognition. |
| `src/main/resources/data/mcamobcompat/tags/entity_type/sheep.json` | Moves sheep recognition. |

The old eleven `entity_types` files and `META-INF/mods.toml` are removed.

## Production Java

| File | Meaningful change |
|---|---|
| `McaMobCompat.java` | Injected NeoForge container/event bus, configuration registration and lifecycle setup. |
| `McaMobCompatConfig.java` | NeoForge config types with existing sections/defaults. |
| `api/MobRole.java` | 1.21.1 resource identifier factory methods. |
| `api/event/GuardHeartsChangedEvent.java` | NeoForge event base class. |
| `api/event/GuardIdentityCreatedEvent.java` | NeoForge event base class. |
| `api/event/GuardInteractEvent.java` | NeoForge event and cancellable interface. |
| `bridge/GuardIdentityData.java` | Registry-aware SavedData, schema 3 heart accounting, legacy preservation, future-schema read-only mode and duplicate-owner quarantine. |
| `bridge/GuardIdentityStore.java` | Target SavedData factory and conflict-aware lookup/creation. |
| `bridge/GuardIdentityView.java` | Live MCA balances, bounded long pending deltas, exact-once flush/migration, snapshot synchronization and API event semantics. |
| `bridge/GuardInteractions.java` | Target player/item/creative fallback without duplicate interaction hooks. |
| `bridge/GuardProxy.java` | Central grace/adoption/creation path, transactional initialization, durable UUID resolution and normal bounds outside projectile handling. |
| `bridge/GuardProxySync.java` | Bidirectional names, correct MCA following-player memory, persisted follow owner, movement gating and heartbeat heart synchronization. |
| `classification/TagClassifier.java` | Target identifier parsing and event integration. |
| `client/ProxyRenderHandler.java` | Physical client isolation, exact pair resolution, complete world render cancellation and guarded portrait substitution with pose restoration. |
| `command/McaMobCompatCommand.java` | Target command registration/registries, unresolved/conflicting identity diagnostics and read-only migration guards. |
| `event/ChoreSafetyHandler.java` | Target concrete events, incoming protected-animal damage veto and chore integration. |
| `event/ClassificationReloadHandler.java` | NeoForge config/tag lifecycle invalidation. |
| `event/ConversionGuardHandler.java` | Target interaction events and recruitment item-component/creative handling. |
| `event/GuardBridgeHandler.java` | Unified server interaction routing, reach checks, once-only forwarding, selected-proxy handling and native guard routing when disabled. |
| `event/GuardDamageRelayHandler.java` | Scoped final-damage notifications, confirmed death/totem/cancellation handling, no proxy-only injury and standard drop/XP suppression. |
| `event/GuardDefenseHandler.java` | Target selection API and actual incoming friendly-fire protection. |
| `event/GuardProxyHandler.java` | Concrete entity ticks, unloaded-owner retention, dimension reacquisition, removal/death separation and target/attack redirection. |
| `event/ProxyProjectileHandler.java` | New scoped proxy collision exclusion that preserves native projectile impact/effects, with restoration and cleanup. |
| `event/ProxySpawnHandler.java` | New finalization transaction observes completed cancellation state and prevents committing rejected proxy spawns. |
| `integration/IntegrationModule.java` | NeoForge mod/config access; module policy retained. |
| `integration/guardvillagers/GuardReflection.java` | Verified runtime-object member signatures and bounded failure behavior. |
| `integration/guardvillagers/GuardVariants.java` | Target registry access. |
| `integration/guardvillagers/GuardVillagersModule.java` | Target resource identifier construction. |
| `mca/McaBinding.java` | Exact selected MCA reflection surface, namespaced personalities, heart snapshots/setters, following memory and rejected-spawn family-tree rollback. |
| `mca/McaEntities.java` | Target identifier factories while preserving MCA recognition semantics. |
| `network/ProxyPairs.java` | New required versioned payload, exact UUID/entity-ID associations, server policy synchronization and bounded per-level caches. |
| `util/ConfigAccess.java` | NeoForge config value types. |

`api/MobCompatibilityAPI.java` and `util/ModIds.java` have line-ending normalization only. Other existing production files remain unchanged; SWEM, Genetic Animals and DLO retain their optional classification/module behavior.

## Tests and reproducibility

| File | Change |
|---|---|
| `src/test/.../McaEntityIdTest.java` | Target identifier APIs; original recognition assertions retained. |
| `src/test/.../NoOptionalModStaticLinkTest.java` | Current package roots, both dotted/slash forbidden optional names and the same exact one-class MCA reflection allowlist. |
| `src/test/.../TagFileValidityTest.java` | Singular target entity-tag directory. |
| `src/test/.../bridge/GuardIdentityDataMigrationTest.java` | Target SavedData save signatures. |
| `src/test/.../bridge/GuardHeartsAccountingTest.java` | Added arithmetic, migration, future/malformed/conflicting data and bounds coverage. |
| `src/test/.../integration/guardvillagers/GuardVariantsTest.java` | Target API calls and actual variant shape. |
| `src/test/.../mca/McaBindingTest.java` | Target MCA reflection surface. |
| `src/test/.../mca/PersonalityBindingTest.java` | Added enum/namespaced personality and unknown-value handling. |
| `src/gameTest/.../PortGameTests.java` | Registered lifecycle, identity, recruitment, interaction, movement, damage and native projectile tests. |
| `src/gameTest/.../SwemGameTests.java` | Real SWEM classification, ownership and module suppression. |
| `src/gameTest/resources/META-INF/neoforge.mods.toml` | Separate development test mod. |
| `src/gameTest/resources/data/mcamobcompat/structure/empty.nbt` | Target GameTest structure fixture. |
| `src/runtimeProbe/.../RuntimeProbe.java` | Separate production-server assertions, persisted pair/heart fixtures and interaction counters. |
| `src/runtimeProbe/.../ClientProbe.java` | Real client inputs/screenshots, integrated-server and portrait checks. |
| `src/runtimeProbe/resources/META-INF/neoforge.mods.toml` | Separate opt-in validation mod; excluded from the production JAR. |
| `scripts/validation/launch_client.py` | Installer-profile production launch helper with library checksum verification. |

## Documentation

`README.md` describes installation, supported behavior, API and build commands. `DATAPACK.md` fixes extension namespaces/paths. `CLAUDE.md` and `MODMAP.md` describe the current architecture. `CHANGELOG.md` records the port without rewriting historical releases. `MCA_MOB_COMPATIBILITY.md` retains the historical record with a superseding notice. `docs/PORTING.md` explains design corrections, migration and upstream boundaries; `docs/DEPENDENCIES.json` records actual artifact metadata/hashes; `docs/VALIDATION.md` and `docs/validation/` contain the acceptance table, logs, screenshots, reports and artifact checksum. The supplied porting handoff is preserved separately.
