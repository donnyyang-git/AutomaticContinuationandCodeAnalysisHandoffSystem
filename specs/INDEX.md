# Locked POC Specification Set

Contract version: `1.0.0`  
Corpus version: `1.0.0`  
Execution order is normative.

## Gate

The package may be executed only when `SPEC-MANIFEST.json` is `LOCKED`, all listed SHA-256 values match, and `environment-manifest.json` validates with status `READY`. The current workstation observation is `NOT_READY`; specification work can finish, implementation cannot start.

## Sequence

| Order | Spec | Goal | Entry gate |
|---:|---|---|---|
| 0 | POC-0 | Snapshot and durable data model | Ready environment and fixed corpus |
| 1 | POC-1 | Crash/restart/resume | POC-0 accepted handoff |
| 2 | POC-2 | JavaParser/CodeGraph | POC-1 accepted handoff |
| 3 | POC-3 | Structured claims/evidence | POC-2 accepted handoff; Model A local |
| 4 | POC-4 | Change invalidation/selective reanalysis | POC-3 accepted handoff |
| 5 | POC-5 | Model switch/review/report | POC-4 accepted handoff; Models A/B local |
| 6 | CONTINUUM | External comparison | POC-0…5 benchmark locked |

## Executor protocol

1. Start a fresh session with one execution bundle and the accepted predecessor handoff.
2. Verify bundle/spec/contract/corpus hashes before any mutation.
3. Execute exactly one work packet.
4. Run its compile, test, CLI smoke, and durable-state assertions.
5. Emit schema-valid `handoff.json` or `blocker.json`; never amend the specification.
6. A separate integration owner accepts the handoff before the next packet.

Canonical contracts: `contracts/CANONICAL-CONTRACTS.md`, `contracts/schema.sql`, `contracts/schemas/`, `contracts/ERROR-CATALOG.md`. Corpus contract and artifacts: `inputs/`.
