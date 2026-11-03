# POC-3 — Structured Claims and Evidence Validation

`spec_id: POC-3` · `spec_version: 1.0.0` · prerequisite: accepted `POC-2-final.json` and local `qwen3:8b` digest

## Goal

Use local Ollama to produce schema-valid semantic claims from bounded CodeGraph/evidence inputs, validate every claim before persistence, and prove that unsupported or conflicting output cannot become confirmed report data.

## Fixed model contract

Endpoint is loopback Ollama only. Model tag/digest must match READY environment manifest. Prompt version is `claim-v1.0.0`; temperature 0, top_p 1, seed 42 when supported, streaming disabled, JSON schema response requested. Persist exact parameters, input/query/raw-output hashes, raw output, schema result, timestamps, retry number, and parsed claims. Absolute paths and secrets are redacted before prompt construction.

## Work packets

### P3-WP1 — Evidence Manager

Allowed: `evidence/**`, its persistence adapter/tests. Create evidence only from the current immutable snapshot. Validate path, inclusive line range, exact bytes/content hash, snapshot membership, and redaction. Evidence IDs are stable for identical snapshot/kind/path/range/content hash. Changed/missing content cannot validate.

### P3-WP2 — Ollama adapter and prompt builder

Allowed: `analysis/**` Ollama adapter/prompt builder, tests using a local deterministic HTTP fixture only for protocol errors, actual-model smoke separately. The adapter exposes one interface: analyze a bounded work item and return raw invocation plus parsed response. It must not write confirmed results.

Prompt includes task, subject, authoritative parser facts, bounded evidence excerpts, claim schema, and explicit unknown handling. It excludes conversation history and unrelated corpus files.

### P3-WP3 — Claim validator and revision persistence

Validate `analysis.schema.json`, evidence existence/snapshot/range/hash, allowed evidence by claim type, parser non-contradiction, and status rules. Only validated claims enter `analysis_result`. `CONFIRMED` without valid evidence becomes `UNSUPPORTED` and opens review; inferred claims require reason. Persist revision and evidence links in the Runtime transaction.

A retry yielding a materially different normalized claim appends a `CONFLICTING` revision and opens review; it never replaces the earlier revision or auto-selects one.

### P3-WP4 — Actual local-model and recovery scenario

Run at least one account, catalog, and order analysis work item through actual `qwen3:8b`. Kill during one invocation. If no committed invocation/result exists, retry under same operation; if committed, reuse. Ollama unavailable exits 40 and preserves committed work. Re-enable Ollama and resume.

## Acceptance

- Actual Ollama `/api/chat` or supported structured endpoint observed on loopback with locked model digest.
- At least one valid `CONFIRMED`, one valid `INFERRED`, one deliberate `UNSUPPORTED`, and one conflicting retry case.
- Every confirmed/inferred claim resolves to current snapshot evidence and valid lines/hash.
- A fixture response with missing evidence, wrong snapshot, malformed JSON, parser contradiction, and secret echo is rejected/redacted with expected error/state.
- Raw output and parsed claims retained; old revisions unchanged.
- Crash/unavailable recovery follows operation rules.
- `mvn -o -q verify` and actual-model smoke both pass.

Model semantic accuracy is reported separately from Runtime/evidence integrity. A model's plausible prose never overrides failed validation.

## Hard stops

External API use, unpinned model, free-form report generation, confirmed claim without evidence, parser fact overridden by model, secret/absolute path persistence, retry overwrite, or mocked-only model proof fails POC-3.

## Handoff

Emit `work/handoffs/POC-3-final.json` with invocation/result/evidence exports, model tag+digest, rejection fixtures, actual-model transcript hashes, and metric report. Next: POC-4.
