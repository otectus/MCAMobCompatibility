# MCA: Mob Compatibility — NeoForge 1.21.1

Read [MODMAP.md](MODMAP.md), [docs/PORTING.md](docs/PORTING.md), and [docs/VALIDATION.md](docs/VALIDATION.md) before changing the port. The original Forge research is archived in `MCA_MOB_COMPATIBILITY.md`; its loader/runtime claims are superseded.

## Build

- Java 21, Gradle 8.12, ModDevGradle 2.0.141, NeoForge 21.1.248; exact dependency settings in `gradle.properties`.
- `./gradlew build` runs unit tests and both production artifact gates. `./gradlew runGameTestServer -PwithGuards -PwithSwem` runs registered integration tests with the actual optional mods.
- Third-party dependencies stay runtime-only. No local `libs/` JAR is needed to compile.
- `runtimeProbeJar` is a separate opt-in driver for disposable test worlds; it does not belong in a playing instance or the main JAR.

## Invariants

- Keep mod ID/package/public API stable. No new registered entities/items/blocks, mixins, access transformers, shaded optional mod classes, or edits to another mod's config.
- Only `mca/McaBinding.class` may contain dotted MCA class names. Never widen the exact allowlist to nested classes/packages. No optional mod class may be statically linked. Guard Villagers access uses exact methods of its verified runtime guard, after checking the entity namespace.
- Tags use `data/mcamobcompat/tags/entity_type/`. Optional `forge:` aliases are data identifiers; do not blindly rewrite them to nonexistent conventional species tags.
- COMMON module toggles and SERVER gameplay defaults are preserved. Config/tag reload invalidates caches; dynamic providers cannot bypass a disabled module.
- Guard UUID and proxy UUID are different, durable identities. Use central resolve/adopt/create with its grace period; never replace an unresolved stored UUID. Never destroy an identity merely because its owner is unloaded.
- The guard owns combat health and inventory; its real MCA proxy owns relationships and MCA interaction state. Initialization/link commit is transactional, including family-tree rollback after rejection. Quarantined saved links cannot be replaced automatically.
- Client proxy identity comes from the explicit UUID/ID pairing payload, not flag heuristics or nearest-entity searches. Keep client rendering code physically client-only and restore poses in `finally`.
- Final damage relays are scoped to a single pair. Confirm death after cancellation/totem handling; do not revive dead proxies or repeat rejected synthetic deaths. Native projectile collision remains responsible for effects/attribution/pickup.
- Follow uses MCA's registered player-following memory, with a saved UUID for restart; opening another player's screen must not steal an existing follow order.
- Schema 3 keeps MCA snapshots and pending deltas separate. Pending deltas need `long` even though balances are bounded `int`. Apply/clear only on successful MCA writes; synchronize on the heartbeat. Preserve legacy/future/malformed records as documented.

## Verification

Keep validation claims narrower than the evidence. Reproduce a failure with the actual target JAR, and record any upstream incompatibility separately. In particular, installed Quests/Crime versions may reject MCAReputation API v2. Preserve isolated logs/screenshots under `docs/validation`; do not commit third-party runtimes or generated worlds.
