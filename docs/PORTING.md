# NeoForge 1.21.1 port notes

## Baseline and scope

Ported from Forge source commit `7062152e3f1a370a9173be89a0bc11f7ed5b2f08` on an isolated `port/neoforge-1.21.1` branch. The original Forge working tree and its unrelated files were preserved. The mod ID remains `mcamobcompat`, Java package remains `dev.otectus.mcamobcompat`, and the license remains GPL-3.0-only.

The port preserves classification, all four optional integration modules, animal chore protection, conversion protection/recruitment, village defense, the public identity/events API, proxy-based MCA interactions, movement/appearance synchronization, damage/death relays, commands and config defaults. It adds no registered gameplay content, mixins or access transformers.

The file-by-file handoff is in [CHANGES.md](CHANGES.md).

## Build and runtime API changes

ForgeGradle/reobfuscation was replaced with ModDevGradle, Java 21 and official 1.21.1 names. Configuration uses `ModConfigSpec` and constructor-injected `ModContainer`; NeoForge's supported event types/buses replace Forge handlers. SavedData supplies a registry-aware save method and factory. Registry lookups use built-in registries with explicit missing-entry handling. Resource identifiers use factory methods. All eleven tags use the singular `entity_type` directory.

MCA is deliberately runtime-only. The exact selected JAR places its villager at `net.conczin.mca.entity.VillagerEntityMCA` and its brain at `entity.ai.brain.VillagerBrain`. Personality is an extensible namespaced identity with `getId()`/`get(ResourceLocation)` rather than an enum. The binding handles verified enum and namespaced shapes, checks parameter/return types, preserves custom identifiers, and refuses unknown setters without overwriting live values.

Guard Villagers 2.4.12 uses `getVariant()`, `setFollowing(boolean)`, `setPatrolling(boolean)`, `setPatrolPos(BlockPos)` and `setOwnerId(UUID)`. Access is restricted to the verified namespace/runtime class and exact signatures. Missing features warn once and degrade locally.

## Identity transactions and lifecycle

The central creation path checks both persistent entity keys and SavedData, allows a two-second loading grace, adopts only an unambiguous matching orphan link, and never creates while a durable proxy UUID is unresolved. MCA initializes the real proxy through NeoForge's spawn hook. A canceled initialization or insertion rolls back new family-tree nodes and leaves seed/link bookkeeping uncommitted. Seeded identities are not rerolled by ordinary reloads.

Both entity keys and the SavedData link are recovered when possible. Duplicate saved guard/proxy ownership is quarantined, preserved and reported rather than arbitrarily reassigned. Unknown future schemas are preserved read-only. Existing pairs continue to hide and synchronize when gameplay modules are turned off. Unresolved/unloaded identities are retained indefinitely; no ten-second deletion timer remains. `guards proxies` distinguishes unresolved partners from proven removal. Rebuild is an explicit destructive operator action, not automatic migration/reconnect repair.

Names synchronize in both directions; a changed guard name wins simultaneous edits, otherwise an MCA-side rename reaches the visible guard. Appearance/profession and relationship snapshots update on a one-second heartbeat. Position/rotation/hiding run each entity tick. MCA's actual registered following-player memory supplies the authorized follow target, with its UUID persisted for restart; merely opening a screen does not retarget Follow.

## SavedData migration and hearts

- Schema 1 fields remain valid. Missing proxy/seed/migration fields retain their absent/default meanings.
- The baseline schema 2 writer added offline changes to both `Hearts` and `PendingHearts`. Schema 3 subtracts pending deltas from the stored snapshot on migration and retains each original v2 record under `LegacyV2` for recovery/audit.
- Manually edited schema 2 saves, stale historical MCA snapshots, or balances already duplicated by the old implementation are intrinsically ambiguous. The migration follows the inspected baseline writer; it cannot reconstruct lost external history. Backups and the preserved original record are the recovery source.
- Schema 3 `Hearts` holds the last MCA balance (or an unmigrated legacy base); `PendingHearts` holds unapplied deltas, using a long NBT value while accepting old int values on read. This handles crossing the complete signed-int balance range without wrapping or truncating a queued change.
- API reads prefer live MCA state and account for outstanding deltas once. API writes queue one delta and emit one API event. The heartbeat flushes after player/proxy availability, marks migration and clears pending only after a successful MCA setter, then updates snapshots. No extra earned delta is required after reconnect.
- MCA-originated gifts/dialogue update snapshots; they do not spuriously emit another `GuardHeartsChangedEvent`. That event reports changes requested through the bridge API.

This only migrates the addon's data. Full world conversion between Minecraft/MCA/entity-mod versions remains subject to those mods' own save compatibility.

## Interaction, rendering and combat

Specific and generic click paths converge on one router with a reentrancy guard. It checks server reach/level/alive state, offers each relevant hook once, obeys cancellation, and executes the normal item fallback without reposting its hook. Selected proxies route to the same visible guard. With the bridge disabled, existing proxies delegate to the guard's stock gesture instead of accidentally opening MCA. Recruitment keeps the MCA entity and item components, refuses occupied equipment/babies/repeats, and respects creative consumption.

Protocol `1` carries exact guard/proxy UUIDs and runtime entity IDs plus server interaction policy. Clients verify both identifiers and use bounded per-level caches, clearing state on disconnect. The physically client-only renderer cancels all proxy world layers and substitutes the paired guard inside screen rendering, restoring its pose even on failure. It does not infer identity from invisibility or proximity.

Combat damage belongs to the visible guard. Final health damage generates a scoped MCA notification; cancellation, armor and absorption are respected. Confirmed death is deferred until the guard's actual death/totem outcome is known. Standard proxy loot and XP are suppressed; MCA's separate inventory gifts retain MCA's one-time drop behavior. If another mod cancels the synthetic MCA death, the identity link is retained and the notification is not retried: the selected MCA override runs relationship callbacks after `super.die()` even when a NeoForge death listener canceled it. Safely undoing that upstream behavior would require an MCA fix or an explicitly broader integration surface.

During a server projectile tick, paired proxy bounds are temporarily excluded from the projectile's swept collision area and restored afterward, with tick-end/shutdown cleanup. The projectile's own native code hits the visible guard, preserving piercing, potion effects, trident behavior and thrown-projectile consumption. Proxies retain normal bounds outside that tick for companion searches/interactions.

## Boundaries and evidence

See [VALIDATION.md](VALIDATION.md) for actual tests and limitations, and [DEPENDENCIES.json](DEPENDENCIES.json) for exact artifacts/hashes. No NeoForge Genetic Animals or DLO runtime is claimed tested. Optional legacy tags remain useful if a compatible entity implementation supplies those IDs.
