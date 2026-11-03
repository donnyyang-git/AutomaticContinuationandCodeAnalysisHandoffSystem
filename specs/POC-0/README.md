# POC-0 — Snapshot and Data Model

`spec_id: POC-0` · `spec_version: 1.0.0` · `contract_version: 1.0.0` · `corpus_version: 1.0.0`

## Goal

Build the minimal Java 21/Maven CLI foundation, create the canonical SQLite schema, and prove that one immutable source snapshot of the fixed JPetStore corpus is reproducible and validated. No parser, LLM call, resume logic, or report generation is implemented here.

## Fixed inputs

The execution bundle MUST contain the canonical contracts, `jpetstore-corpus-v1.0.0.zip`, `corpus-manifest.json`, `ground-truth.json`, and a READY environment manifest. Hash mismatch stops with exit 10. Repository initially contains no implementation, or only an accepted predecessor-free scaffold declared in the handoff.

## Required outputs

- Maven project and package layout from the canonical contract.
- Executable shaded JAR `target/automatic-continuation-poc.jar` with main class `io.handoff.cli.HandoffCommand`.
- Idempotent schema bootstrap using the exact `schema.sql`; schema version `1.0.0`.
- `create-task`, `status`, and `validate` commands.
- Snapshot Manager that hashes only corpus-manifest paths, rejects symlinks/path escape/missing/extra mismatches, and stores project-relative paths.
- Schema-valid handoff manifest.

## Work packets

### P0-WP1 — Build and CLI shell

Allowed: `pom.xml`, Maven wrapper/config, `src/main/java/io/handoff/cli/**`, test counterparts. Implement root Picocli command, global `--db`/`--json`, version output, deterministic error envelope, and shaded JAR. Use only pinned dependencies.

Completion:

```text
mvn -o -q test
java -jar target/automatic-continuation-poc.jar --help
java -jar target/automatic-continuation-poc.jar --json status 00000000-0000-7000-8000-000000000000
```

First two exit 0; unknown task exits 10 with `E_PRECONDITION`. Handoff lists exact files and hashes.

### P0-WP2 — SQLite bootstrap

Requires accepted P0-WP1. Allowed: `persistence/**`, `src/main/resources/db/migration/**`, persistence tests, Picocli wiring. Implement one connection factory with required PRAGMAs and an idempotent bootstrap that executes the canonical DDL transactionally. Reject schema versions other than 1.0.0.

Permanent tests MUST verify all tables, foreign keys, strict-table constraints, second bootstrap idempotence, rollback on malformed migration, and configured PRAGMAs. Do not test SQL source text.

Completion: `mvn -o -q -Dtest='*Persistence*' test` and actual CLI startup against a new database both exit 0.

### P0-WP3 — Immutable snapshot and task creation

Requires accepted P0-WP2. Allowed: `snapshot/**`, `runtime/**` only for Task creation, `create-task` adapter, tests. Implement canonical path normalization, byte hashing, manifest comparison, canonical snapshot JSON, snapshot ID, and atomic insertion of snapshot/files/task state. Initial task state is `CREATED`, version 0.

Smoke:

```text
java -jar target/automatic-continuation-poc.jar --db work/p0/handoff.db create-task work/corpus --corpus-manifest specs/inputs/corpus-manifest.json --json
java -jar target/automatic-continuation-poc.jar --db work/p0/handoff.db status <task-id> --json
```

Creating the same project again may create a new task but MUST reuse the identical snapshot ID. Modifying one byte then creating a task MUST exit 10 `E_CORPUS_HASH` and insert nothing.

### P0-WP4 — Integrity validation

Requires accepted P0-WP3. Allowed: validation logic, `validate` adapter, tests. Validate schema version, all snapshot file hashes against the immutable copied snapshot, foreign keys, task/snapshot linkage, and path safety. `validate <task-id>` returns 0 only when all checks pass and emits deterministic JSON.

Corrupt one stored snapshot hash in a throwaway database: validation exits 30 and names `E_EVIDENCE_INVALID`/integrity finding without continuing.

## Acceptance

All work packets accepted; clean `mvn -o -q verify` passes; create-task/status/validate smoke observed; two clean runs produce the same snapshot ID; corpus mutation is rejected; no absolute source path appears in database JSON or stdout. Database contains exactly one schema version and every corpus file row.

## Hard stops

Any half-created task, nondeterministic snapshot ID, accepted corpus mismatch, non-pinned dependency, network access during execution, source-project modification, missing validation command, or unverified claimed success fails POC-0.

## Handoff

Emit `work/handoffs/POC-0-final.json` against `execution.schema.json`. Artifacts include JAR, database, canonical snapshot manifest, command outputs, and their SHA-256 values. Next precondition: POC-1 receives this accepted repository and handoff.
