# POC-1 — Crash, Restart, Resume

`spec_id: POC-1` · `spec_version: 1.0.0` · prerequisite: accepted `POC-0-final.json`

## Goal

Prove durable task lifecycle, atomic work-item commit, stable `operation_id`, pause, hard interruption, restart, and resume from the last committed checkpoint. Analysis payloads are deterministic local facts; JavaParser and Ollama remain out of scope.

## Fixed contract summary

One task, one worker, one SQLite writer. A work item commits evidence, result, event, cursor, and checkpoint in one `BEGIN IMMEDIATE` transaction. Committed operations are reused; absent operations execute; contradictory state enters `REQUIRES_REVIEW`. Legal task transitions and exit codes are exactly those in Canonical Contracts v1.0.0.

## Work packets

### P1-WP1 — State machine and sequential scheduler

Allowed: `runtime/**`, task-state persistence adapter, tests, `run/pause/resume` CLI wiring. Implement explicit transition validation, deterministic work ordering by `(kind, subject, operation_id)`, optimistic task version checks, and pause only at transaction boundaries.

Tests exercise every legal transition and representative illegal transitions through the Runtime interface. CLI smoke creates three deterministic work items, pauses after one, and observes one `COMMITTED` plus two `PENDING`.

### P1-WP2 — Atomic commit and idempotency

Allowed: Checkpoint Manager, Evidence/Event adapters, deterministic analysis fixture, transaction coordinator, tests. Implement canonical checkpoint content/hash and stable operation identity. Commit every work-item artifact in one transaction.

Inject `PROCESS_KILL_BEFORE_COMMIT`: after restart, the operation is absent/pending and may execute once. Inject `PROCESS_KILL_AFTER_RESULT` before the transaction commit point: no partial result/evidence/event/checkpoint may remain. Re-running a committed `operation_id` MUST leave row counts and result IDs unchanged.

### P1-WP3 — Event chain and checkpoint validation

Allowed: Event Logger, Checkpoint validation, tests. Implement sequence allocation and exact hash formula. Resume first validates schema, event chain, latest checkpoint hash, snapshot, and evidence references.

Tamper one payload byte and one checkpoint byte in separate throwaway databases. Both resume attempts exit 30, record a recovery review transaction, and never execute pending work.

### P1-WP4 — End-to-end hard-process recovery

Allowed: failure injection adapter, subprocess acceptance harness, tests. `inject-failure` arms one-shot failures. The acceptance harness MUST launch the shaded JAR as a child process, terminate it at the armed point, launch a fresh JVM, invoke `resume`, and inspect durable state out of process.

The harness is not allowed to emulate process death with an exception in the same JVM.

## Acceptance

Scenario: create five deterministic work items; commit two; kill process during item three; start a fresh JVM; resume; complete all five. Assertions:

- five unique committed operations/results;
- zero duplicate result revisions;
- first two IDs/hashes unchanged;
- item three follows the before/after-commit rule;
- latest valid checkpoint cursor is item five;
- event chain validates;
- task ends `COMPLETED`;
- pause/resume and corrupt-checkpoint branches have observed exit codes.

Run `mvn -o -q verify` plus the actual subprocess harness command documented by the implementation. Preserve stdout/stderr/DB/JAR hashes.

## Hard stops

Confirmed data loss, partial committed work item, duplicate committed operation, automatic continuation after corruption, exception-only crash simulation, illegal transition acceptance, or failure to launch a fresh JVM fails POC-1.

## Handoff

Emit `work/handoffs/POC-1-final.json`; include the pre-crash and post-resume database hashes/copies, subprocess transcript hashes, and count assertions. Next: POC-2.
