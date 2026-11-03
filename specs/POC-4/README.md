# POC-4 — Source Change and Selective Reanalysis

`spec_id: POC-4` · `spec_version: 1.0.0` · prerequisite: accepted `POC-3-final.json`

## Goal

Detect fixed corpus mutations, preserve historical snapshots/results, mark directly and transitively affected data stale, and rerun only impacted work. Prove zero unexplained rework of unaffected committed items.

## Fixed invalidation algorithm

1. Build a new immutable snapshot from a baseline copy with exactly one locked mutation patch.
2. Diff sorted `{path, sha256}` manifests.
3. Mark evidence in changed/deleted files `STALE`.
4. Mark every result linked to stale evidence `STALE` without overwriting it.
5. Traverse reverse `code_relation` edges from changed symbols/config relations.
6. Mark corresponding work items `STALE`/new-snapshot `PENDING`.
7. Append new result revisions after reanalysis.

The traversal MUST record a reason path for every invalidated work item. Conservative invalidation is allowed only with an explicit relation path; unrelated whole-project reruns fail.

## Work packets

### P4-WP1 — Snapshot diff

Allowed: snapshot diff module, persistence adapter, tests, resume preflight wiring. Apply no mutations internally; consume prepared project directories. Produce deterministic added/changed/deleted/unchanged sets. Same snapshot yields empty diff. Missing Evidence lines are detected before analysis.

### P4-WP2 — Direct staleness and revision preservation

Allowed: Evidence/Result stale transitions through their interfaces, tests. Mark old rows; never delete or update claim text/evidence identity. Create a new task snapshot association/reanalysis generation according to canonical schema constraints. Historical reports remain addressable by hash.

### P4-WP3 — Reverse impact and selective scheduling

Allowed: CodeGraph impact query, Runtime scheduler integration, tests. Persist invalidation reason chains. Changed configuration relations participate with `CONFIG` authority; unresolved dynamic relations create review instead of guessed impact.

### P4-WP4 — Locked mutation acceptance

For each patch M1, M2, M3, start from a clean baseline copy, verify patch SHA-256, apply it, create a new snapshot, resume, and record operation sets before/after.

Expected invariants:

- M1: Catalog service and its Account/Catalog callers plus dependent catalog/account claims may rerun; order persistence and unrelated mapper work do not.
- M2: `OrderMapper.xml`, order persistence/data-flow relations and dependent order claims rerun; account/catalog/cart work does not.
- M3: Account controller view relation and dependent account entry claims rerun; order/catalog/cart do not.

Exact operation IDs are implementation-derived; the acceptance report MUST list every rerun with its reason chain and every unchanged operation reused.

## Acceptance

- 100% detection of files changed by M1–M3.
- Old snapshot/results/evidence retained; affected old data is `STALE`.
- New revisions reference the new snapshot.
- Unaffected committed operations have unchanged IDs/result hashes and zero new model invocation.
- Every rerun has a nonempty direct/reverse-dependency reason.
- No mutation composes accidentally with another.
- Crash during invalidation/reanalysis resumes atomically.
- Reports cannot treat stale results as normal conclusions.
- `mvn -o -q verify` plus all three mutation scenarios pass.

## Hard stops

Missed changed file, in-place historical overwrite, whole-project rerun, unexplained rerun, stale evidence used for `CONFIRMED`, guessed dynamic dependency, or continued execution after an unresolved critical relation fails POC-4.

## Handoff

Emit `work/handoffs/POC-4-final.json` with per-mutation diff, invalidation/reuse sets, reason chains, invocation counts, old/new snapshot and result hashes. Next: POC-5.
