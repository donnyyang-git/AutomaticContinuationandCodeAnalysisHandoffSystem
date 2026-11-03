# POC-5 — Model Switch, Review, and Deterministic Reports

`spec_id: POC-5` · `spec_version: 1.0.0` · prerequisite: accepted `POC-4-final.json` and local Model A/B digests

## Goal

Switch from `qwen3:8b` to `qwen3:14b` without overwriting history, resolve conflicts through auditable CLI review, and generate byte-deterministic Markdown/JSON reports exclusively from validated database state.

## Work packets

### P5-WP1 — Model-switch revisions

Allowed: Analysis Pipeline model selector/revision logic, tests. Model identity is tag+digest. Switching model creates a new invocation and result revision for selected semantic work items; parser facts and unaffected structural index are reused. Old results remain immutable/readable. Equal normalized claims may be `CONFIRMED` as a new revision; differing material claims become `CONFLICTING` and open review.

### P5-WP2 — Review Manager and CLI

Allowed: `review/**`, review persistence, CLI adapters/tests. Implement `review list/show/accept/reject/supersede`. Each decision records operator, UTC time, original state, reason, selected revision/evidence. Critical open review holds task in `REQUIRES_REVIEW`; only a committed valid decision permits `RESUMING`. Decisions are appended/auditable, not comments.

Accept/reject/supersede MUST validate result/evidence/snapshot state. It cannot accept unsupported/stale evidence as confirmed.

### P5-WP3 — Deterministic JSON report

Allowed: `report/**`, tests, `report` adapter. Query only validated rows and render sections: task/snapshot; confirmed architecture; evidence-backed semantics; inferences; review queue; stale/conflicting history; unknowns; unfinished work; generator/report hash. Use canonical sorting and JSON rules. Include evidence path/line/hash but no absolute path or unredacted secret.

### P5-WP4 — Deterministic Markdown and full scenario

Render fixed Markdown headings from the same report model, not an LLM. Run the end-to-end sequence: baseline analysis with Model A; process kill/resume; M2 change/selective rerun; switch to Model B; force/observe at least one conflict; review it; generate both reports twice.

## Acceptance

- Old Model A invocation/result rows unchanged after Model B run.
- Every Model B result records tag+digest and revision.
- Material conflict blocks task until CLI review commit.
- Review audit fields complete; bypass attempt exits 20.
- Same post-review DB state produces byte-identical JSON and Markdown across two invocations.
- Every normal conclusion resolves to valid current-snapshot evidence.
- `CONFIRMED` sample audit has 100% file/line/hash match.
- Stale/conflicting/unknown data appears only in designated sections.
- No absolute paths/secrets; report hashes stored and verified.
- `mvn -o -q verify` and actual two-model scenario pass.

## Investment gate

POC-0…5 succeeds only if confirmed facts lost = 0, unexplained unaffected reruns = 0, evidence mismatches = 0, duplicate results = 0, all locked mutations detected, history preserved across model switch, report audit passes, and unsafe uncertainty reliably stops at `REQUIRES_REVIEW`.

## Hard stops

Model overwrite, review bypass, LLM-authored final report, nondeterministic report bytes, unsupported confirmed content, stale normal conclusion, missing operator/reason, or hidden unfinished work fails POC-5.

## Handoff

Emit `work/handoffs/POC-5-final.json` with both model digests, revision/conflict/review export, two-run report hashes, full scenario transcript, and final metric table. This accepted handoff freezes the Proposed benchmark for CONTINUUM comparison.
