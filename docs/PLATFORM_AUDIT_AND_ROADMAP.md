# SB Platform Audit and Hardening Roadmap

Audit date: 2026-11-10
Audited branch: `feature/shizuku-integration`
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
- GitHub Actions workflow that currently builds a debug APK.

## Prioritized problem list

### P0 — Platform correctness and data safety

1. **Database migration verification is incomplete.** Existing migration code is present, but current tests do not demonstrate upgrade from every supported historical schema, preservation of representative user/trading data, rollback on failure, or backup/restore integrity.
2. **Two backend schema/version concepts need one source of truth.** `SBDatabaseHelper` and `BackendMigration` both expose version 5 while `BackendSchema` has a separate version 1. Define one documented migration path and eliminate drift.
3. **Backup/restore needs safety guarantees.** Verify WAL checkpointing, atomic restore, integrity checks, recovery after interrupted copy, and preservation of the previous database before replacing it. Never run destructive repair automatically.
4. **Test automation is incomplete.** Current CI runs `:app:assembleDebug` and uploads an APK; it does not run JVM unit tests or Android instrumentation tests.

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

Start with P0 items 1–4: migration/data-preservation tests and CI test gates. Keep Shizuku optional and out of the critical trading-platform path. Do not add another broker or expose the embedded server to the network during this phase.
