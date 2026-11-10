# SB Platform Audit and Hardening Roadmap

Audit date: 2026-11-10
Audited branch: `feature/platform-hardening`
Repository: `bindrasaggam-afk/sb-trading-bot`

## Executive summary

The repository contains a substantial Android/Kotlin trading application and a local market-data/trading backend. It is not yet a production multi-user or multi-platform platform. Do not add more broker integrations until the core platform foundation is hardened.

This is a source-code audit, not a production security certification or a successful runtime test.

## What already exists

- Android application in Kotlin.
- Kotak Neo-specific login/session and WebSocket market feed.
- Instrument/scrip-master synchronization, quote/candle persistence and market-data processing.
- Signal, risk and paper-trading components.
- SQLite database with schema creation, versioned upgrade logic, indexes and candle validation triggers.
- Local database backup/restore helpers and diagnostic/repair endpoints.
- A small set of JVM unit tests for candle aggregation, market models, repository timeframe handling, Kotak binary parsing and indicator calculations.
- GitHub Actions workflow for JVM unit tests, Android instrumentation tests and debug APK build. Run #15 completed successfully: all three test/build stages passed and the `sb-debug-apk` artifact was uploaded.

## Prioritized problem list

### P0 — Platform correctness and data safety

1. **Database migration verification is incomplete.** Existing migration code is present, but current tests do not demonstrate upgrade from every supported historical schema, preservation of representative user/trading data, rollback on failure, or backup/restore integrity.
2. **Two backend schema/version concepts need one source of truth.** `SBDatabaseHelper` and `BackendMigration` both expose version 5 while `BackendSchema` has a separate version 1. Define one documented migration path and eliminate drift.
3. **Backup/restore needs safety guarantees.** Verify WAL checkpointing, atomic restore, integrity checks, recovery after interrupted copy, and preservation of the previous database before replacing it. Never run destructive repair automatically.
4. **Test automation is now green for the current PR head.** Run #15 passed JVM unit tests, Android instrumentation tests and the debug APK build; the `sb-debug-apk` artifact was uploaded. This proves the CI path for this commit, not production readiness.

### P1 — Security and reliability

5. **No cloud identity or multi-user boundary exists.** Current database schema is device/app oriented and has no tenant/user ownership model. Do not retrofit shared users into local SQLite without an explicit data-ownership design.
6. **Embedded API server is local-only.** It binds to `127.0.0.1`; it is useful for on-device diagnostics, not a cloud API gateway. Its routes must have an explicit authorization model before any network exposure.
7. **Credential handling is broker-specific.** Kotak credentials use Android Keystore-backed encryption, but there is no platform-wide credential vault, rotation/revocation lifecycle, or server-side secret isolation.
8. **Operational resilience needs coverage.** Add tests for WebSocket disconnect/reconnect, stale feed detection, duplicate/out-of-order ticks, persistence failures, and bounded worker/thread lifecycle.

### P2 — Core trading platform completeness

9. **Broker logic is tightly coupled to Kotak Neo.** Extract a broker-neutral contract for authentication, instruments, quotes, historical data, funds, orders, trades, holdings, positions and streaming capabilities. Implement Kotak behind that contract before adding another broker.
10. **Unified trading domain is incomplete.** Define canonical instrument, order, execution, holding, position, funds, market-data and error models, including broker capability/field mapping.
11. **Trading safety needs end-to-end controls.** Define idempotent order submission, order-state reconciliation, risk limits, audit trail, user confirmations and safe handling of partial/unknown broker responses. Paper trading must remain isolated from live trading.

### P3 — Scale and multi-platform

12. **No production cloud backend is present.** Design API gateway, authentication, tenant isolation, async jobs/event streaming, database strategy, cache, observability, rate limiting and disaster recovery.
13. **No web/iOS/desktop client architecture is present.** Keep business logic behind stable APIs so clients do not duplicate broker logic.
14. **Scale is unproven.** Define load, latency, market-data fan-out, retention, backup/restore and recovery objectives; validate with load and failure tests before claiming support for large user counts.
15. **Repository hygiene needs improvement.** Historical backups and an APK are stored in the repository tree. Establish a source-of-truth policy and move bulky generated artifacts out of normal source history where safe.

## Execution order and acceptance gates

1. **Baseline and safety:** record current branch/PR state, preserve existing database files, define supported schema versions and data invariants.
2. **Database hardening:** add migration fixtures/tests for every supported old version, transactional migration checks, backup/restore tests and integrity verification. No data-destructive migration without a verified backup.
3. **CI quality gate:** run unit tests and build on every PR; add instrumentation tests where runner/device support exists. Upload APK only after tests pass.
4. **Security/reliability:** formalize local API authorization, secret lifecycle, network boundaries, logging redaction and feed recovery tests.
5. **Broker-neutral core:** define canonical models and adapter interface; move Kotak implementation behind it and test contract compliance.
6. **Cloud foundation:** only after local core and domain contracts pass, introduce multi-user identity, tenant isolation and cloud services.
7. **Additional brokers and clients:** add providers and web/iOS/desktop clients against the stable contracts.

## Immediate next implementation task

Continue P0 work with database maintenance lifecycle and backup/restore safety. The current CI gate is green for PR #4. Keep Shizuku optional and out of the critical trading-platform path. Do not add another broker or expose the embedded server to the network during this phase.


## Follow-up audit: database backup/restore lifecycle (2026-11-10)

The backup/restore call path was traced through `DatabaseBackup`, the primary singleton `com.sb.backend.core.BackendManager`, `MarketForegroundService`, and the database helper.

### Confirmed implementation risks

- `DatabaseBackup.backup()` issues `PRAGMA wal_checkpoint(FULL)` but reads only the first result column and ignores the busy/log/checkpointed counts. It then copies the main database file without coordinating all writers.
- `DatabaseBackup.restore()` copies the supplied file directly over the live database path, then deletes WAL/SHM/journal sidecars. It does not validate SQLite integrity or schema compatibility, stage a temporary file, atomically replace the live file, or retain a rollback snapshot.
- The primary `BackendManager.restore()` closes its helper and then calls `DatabaseBackup(context)`; this is not a complete quiescence protocol. The backend executor, foreground market service, local API server, and Kotak session/feed have separate lifecycles.
- `BackendManager.stop()` shuts down its executor but does not await termination and does not itself stop the foreground service/API server or Kotak session/feed. Therefore it is not currently a proven safe precondition for replacing the database file.
- The application exposes the singleton helper to repositories. There is no single database maintenance gate that blocks new reads/writes while backup/restore is in progress.
- No backup/restore instrumented tests currently prove valid restore, corrupt-file rejection, rollback, WAL consistency, or preservation of the current database on failure.

### Safe implementation gate (do not bypass)

1. Introduce an explicit maintenance lifecycle owned by the foreground service: reject new database work, stop feed/API writers, drain/await worker tasks, then close every database connection.
2. Create backup only after a successful WAL checkpoint with a non-busy result; validate the copied candidate with SQLite integrity and expected schema/version checks.
3. Restore only from an app-controlled candidate file: validate before touching the live database, copy to a same-filesystem temporary file, flush it, and verify integrity/schema again.
4. Preserve the current database as a rollback snapshot; perform replacement only while all connections are closed. On any failure, restore the previous file before reopening services.
5. Reopen the database, verify core tables and representative data, then restart services/feed only after validation succeeds.
6. Add instrumented tests for a valid round-trip, corrupt/empty/incompatible backup rejection, failed replacement rollback, and existing-data preservation. Tests must use isolated test databases and must never touch a user's installed-app database.

**Current decision:** do not patch the live restore path until the service/database maintenance lifecycle and connection ownership are implemented and tested. A superficial `close()` plus file copy would leave a data-loss race.


### Additional lifecycle findings (verified after the CI #15 success)

- The repository contains a second, instance-based `com.sb.backend.services.BackendManager` in addition to the primary singleton `com.sb.backend.core.BackendManager`. The second manager owns another `BackendDatabase`/helper and its own repositories; ownership and active call sites must be resolved before a maintenance coordinator can claim all connections are closed.
- `KotakSessionCoordinator` has asynchronous feed disconnect and historical-import executors. Its current `disconnectFeed()` schedules closure asynchronously; it is not a synchronous drain/stop barrier. The coordinator also has long-running historical import work that can write to the same database.
- `MarketForegroundService.onDestroy()` stops the embedded API server and resets its runtime flag, but does not stop the singleton backend or Kotak coordinator. Service destruction therefore cannot currently be treated as proof that all database writers have stopped.
- The main Settings screen currently has no user-facing database restore workflow. The restore method exists in the core manager, but the UI call path must be traced before enabling it for users.

### Next implementation sequence

1. Map all constructors and call sites for both backend managers, all database helpers, and every long-running database writer.
2. Add a single maintenance state/gate that prevents new DB tasks from being scheduled while maintenance is active.
3. Add synchronous stop/drain operations for market feed, historical imports, API server and backend workers; await worker termination before closing helpers.
4. Only then implement staged backup/restore with SQLite integrity/schema validation and rollback protection.
5. Add isolated instrumentation tests for backup/restore and service quiescence. Do not test against or overwrite an installed user's database.


### Database writer and ownership map (follow-up source trace, 2026-11-10)

The following call paths were confirmed by reading the current branch source:

| Component | Database access / writer path | Maintenance implication |
|---|---|---|
| `MarketForegroundService` | Initializes and starts the primary `com.sb.backend.core.BackendManager`; owns `EmbeddedApiServer` | Service is the natural lifecycle owner, but its current destroy path only stops the API server. |
| Primary `core.BackendManager` | Owns one `SBDatabaseHelper`, repositories, a single-thread startup executor and `MarketDataService` | Exposes repositories/helper to callers; `stop()` calls executor shutdown without awaiting termination. |
| `KotakSessionCoordinator` | Feed, scrip-master sync, equity/MCX historical imports use repositories from primary manager; has separate live-feed and historical executors | Feed disconnect is queued asynchronously; historical jobs have no public pause/drain barrier. |
| `MarketDataService` | Own executor; saves quotes and candles through `MarketRepository` | `shutdown()` sets shutdown flag and calls executor shutdown, but does not await in-flight tasks. |
| `EmbeddedApiServer` | Cached thread pool; API routes read DB and can start historical imports/migrations | `stop()` implementation and active-client draining must be verified; closing listener alone is not enough. |
| `com.sb.backend.services.BackendManager` | Separate instance owns `BackendDatabase` and another `SBDatabaseHelper` | No active production call site was found in the specific app entry/settings/API files inspected; repository-wide reference search is still required before classifying it as dead code. |
| `MainActivity` / `SettingsScreen` | Use the primary manager's repositories; activity intentionally does not stop persistent backend | UI lifecycle is not a safe database maintenance boundary. |
| `McxBhavcopyHistoricalService` | Writes candles through `MarketRepository`; invoked by coordinator historical executor | Must be included in the historical-import drain barrier. |

### Current safe boundary

No database maintenance lock, shared admission gate, or synchronous drain protocol exists across these components. Do not implement file replacement yet. The next code change should introduce a narrowly scoped maintenance coordinator and explicit stop/drain contracts, with tests, rather than simply calling `close()` or shutting down executors. Before implementing it, finish repository-wide call-site search for the second manager and inspect the full API server stop path and all historical import entry points.


### API server shutdown and secondary manager call-site check (2026-11-10)

- `EmbeddedApiServer.stop()` sets its running flag false and closes the listening `ServerSocket`, but it does not close accepted client sockets, wait for active `handleClient` calls, or shut down its cached thread pool. Active API handlers can therefore continue executing database reads/writes after the listener is closed.
- The server executor is currently a `val` created once per server instance. A future stop/drain implementation must account for restart semantics; shutting this executor down permanently and then reusing the same server instance would break restart.
- `MarketForegroundService.onDestroy()` calls only `stopApiServer()` and resets its runtime flag. It does not synchronously stop/drain `KotakSessionCoordinator` or the primary backend. The service is therefore not yet a complete maintenance boundary.
- The secondary `com.sb.backend.services.BackendManager` is a separate class whose constructor creates its own `BackendDatabase`; its `close()` closes that database. The inspected app entry point and Settings screen use `com.sb.backend.core.BackendManager`. GitHub code search did not return reliable repository-wide reference results, so the secondary manager is **not declared unused** until a full local-reference scan or complete source scan confirms it.
- No source files were modified in this pass; this update records verified lifecycle findings only.

### Required implementation design before touching restore

1. Make the foreground service expose a serialized maintenance operation (single owner) rather than letting UI call file restore directly.
2. Add a database-operation admission gate shared by API handlers, repository work, and historical/feed writers. A maintenance request first closes admission and waits for active operations to reach zero.
3. Give each long-running producer an explicit suspend/stop-and-await contract. A fire-and-forget disconnect method is not sufficient.
4. For the API server, track accepted sockets/active handlers, stop accepting, close or finish active clients, and await handler completion. Recreate its executor on each start if restart is supported.
5. Stop and await historical imports and live feed, then stop and await backend/market executors. Only after all gates report quiescent may every DB helper be closed.
6. Reopen and validate the database before restarting the service producers. On any restore failure, reinstate the pre-restore snapshot before reopening.
7. Add deterministic tests for admission rejection, in-flight request draining, executor timeout/failure, service restart, and no DB access after maintenance begins.

**Gate remains closed:** do not add a superficial restore patch or expose a restore UI until the above contracts and isolated tests exist.
