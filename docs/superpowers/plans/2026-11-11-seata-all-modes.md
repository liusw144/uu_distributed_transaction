# Seata All Modes Implementation Plan

> **For agentic workers:** Use executing-plans for the shared implementation, and dispatching-parallel-agents for the
> independent TCC and Saga modules.

**Goal:** Add four genuine Seata implementations under seata/.

**Architecture:** Four isolated Spring Boot apps share the existing common DTO/JDBC configuration and two dedicated
databases. AT/XA/TCC connect to the existing local Seata TC; Saga uses Seata's persistent state-machine engine.

**Tech Stack:** Java 17, Spring Boot 3.3.13, Seata 2.2.0, MySQL 8, PostgreSQL 16.

**Spec:** docs/superpowers/specs/2026-11-11-seata-all-modes-design.md

## Global Constraints

- Preserve old demos and pre-existing changes. No ps1 scripts. New modules only use real Seata integrations.
- New ports 8101-8104; separate resource prefixes. Incremental SQL must not reset resources.
- Real database/framework tests are enabled by DEMO_SEATA_TESTS=true, with DEMO_DB_TESTS=true for existing tests.
- No new external infrastructure required. Do not stop existing services or reset databases.

## Review Focus

- Transaction proxies must wrap every database operation and rollback both databases on failure.
- TCC second-phase duplicates and early cancellation must never leak frozen resources.
- Saga compensation failures must remain visible and be recoverable after engine recreation.
- Duplicate business identifiers with different payloads must not silently affect another transaction.
- Build/test gating and README commands must work from the project root with Java 17.

### Task 1: Infrastructure and AT/XA

- [x] Add Maven aggregation and four module POMs; reuse existing TC, install parent/common.
- [x] Write framework integration tests for success, stage failures, shortage, duplicate IDs.
- [x] Implement real AT/XA proxies, separate local transaction boundaries and global coordinator.
- [x] Run against MySQL/PostgreSQL/TC and verify rollback with both raw databases.

### Task 2: Seata TCC

- [x] Write failing tests using Seata TC and persistent branch state.
- [x] Implement LocalTCC actions, reservations, confirm/cancel, idempotence and empty rollback/fencing.
- [x] Add SQL, module README, requests.http and run tests.

### Task 3: Seata Saga

- [x] Add official Saga state machine dependencies and real engine tests.
- [x] Implement JSON orchestration, local committed actions, compensation and durable engine logs.
- [x] Verify failed compensation can resume using a new engine/application instance.
- [x] Add SQL, README, requests.http and run tests.

### Task 4: Integration and review

- [x] Validate all four HTTP files, run the full Maven verification with actual test gates.
- [x] Review new code independently, fix material findings with regression tests.
- [x] Update root/module navigation and record verification evidence.
- [x] Stop only processes started for this work and report the new entry points.
