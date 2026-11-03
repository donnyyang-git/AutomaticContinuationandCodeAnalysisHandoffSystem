# POC-2 — JavaParser and CodeGraph

`spec_id: POC-2` · `spec_version: 1.0.0` · prerequisite: accepted `POC-1-final.json`

## Goal

Index the fixed corpus with JavaParser 3.28.2, persist authoritative symbols/source ranges/direct calls, add configuration/view relations without pretending they are parser facts, and expose deterministic graph queries. Runtime recovery behavior must remain intact.

## Authority

`PARSER` owns Java declarations, source ranges, inheritance, and resolved direct calls. `CONFIG` owns explicit MyBatis/Spring/JSP links. Unresolved reflection/framework routing is `UNKNOWN` or an `UNRESOLVED` relation. No LLM is used.

## Work packets

### P2-WP1 — JavaParser adapter

Allowed: `index/**`, code-index persistence, tests, `index` wiring. Configure language level for the corpus and a CombinedTypeSolver using corpus source roots plus reflection. Parse only manifest-listed Java files. Persist deterministic symbol IDs from snapshot/kind/qualified-name/path/range.

A parse or symbol-solver failure is a recorded unresolved diagnostic, not a fabricated edge. Indexing the same snapshot twice MUST be idempotent.

### P2-WP2 — Direct relations and graph queries

Build `CONTAINS`, `EXTENDS`, `IMPLEMENTS`, and resolved `DIRECT_CALL` relations with evidence locations. Add a deep CodeGraph module interface for `callers`, `callees`, and `reverseDependents`; CLI adapters may add `query callers|callees|impact <symbol>` under `index` output conventions.

Ground-truth comparison separately reports declaration precision/recall and resolved direct-call precision/recall. Duplicate edges are prohibited.

### P2-WP3 — XML and JSP adapters

Parse selected MyBatis XML and Spring XML as data. Link mapper namespace+statement IDs to mapper methods with `XML_MAPS` authority `CONFIG`. Link literal controller views to selected JSP files with `VIEW_ENTRY`; constant propagation is allowed only for compile-time string constants. Dynamic routes remain unresolved.

If JavaParser and configuration assert contradictory unique targets, create `E_PARSER_CONFLICT`, task `REQUIRES_REVIEW`, and do not choose one.

### P2-WP4 — Recovery and determinism

Index through Runtime work items, one class or one configuration file per operation. Kill and resume midway using POC-1 harness. Already committed files are not reparsed. Two clean databases from identical input must contain the same logical symbol/relation sets after sorting, excluding generated UUID IDs/timestamps; normalized exports must be byte-identical.

## Acceptance

Against `ground-truth.json`:

- all 25 selected Java top-level types found at exact path/start line;
- all 238 declared methods found with matching type/name/parameter shape/start line;
- no invented selected-file declaration;
- all 25 mapper XML statement links found;
- five locked view links found or explicitly reported unresolved where ground truth authority permits;
- direct-call metrics reported separately; every mismatch lists expected/observed evidence;
- mid-index crash/resume causes zero committed-file repeats;
- index/query CLI smoke observed;
- `mvn -o -q verify` passes.

These fixed counts belong to corpus v1.0.0; a different corpus version must not silently reuse them.

## Hard stops

LLM-derived structural facts, swallowed parse errors, false resolved edges, line mismatch hidden by normalization, parser/config conflict auto-selection, full reindex after crash, or mutation of corpus files fails POC-2.

## Handoff

Emit `work/handoffs/POC-2-final.json` with normalized symbol/relation export, metric report, unresolved diagnostics, recovery transcript, and hashes. Next: POC-3.
