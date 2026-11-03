# Canonical Contracts v1.0.0

Status: `LOCK-CANDIDATE`  
Normative language: RFC 2119.  
Runtime: single-machine, single-task, single-worker, read-only analysis.

## 1. Repository contract

```text
pom.xml
src/main/java/io/handoff/
  cli/                 # Picocli adapters only
  runtime/             # task state machine and work scheduling
  snapshot/            # immutable source snapshots
  index/               # JavaParser and CodeGraph
  evidence/            # evidence validation and redaction
  `analysis/`        # OpenAI-compatible local LLM calls, claims, revisions
  review/              # human review decisions
  report/              # deterministic Markdown/JSON rendering
  persistence/         # SQLite adapters and migrations
src/main/resources/db/migration/
src/test/java/io/handoff/
work/
  task-<id>/handoff.db
  task-<id>/snapshots/
  task-<id>/reports/
  task-<id>/manifests/
```

Each package is a module with one interface. CLI classes MUST delegate to modules and MUST NOT contain business rules. SQLite is the sole durable state store. Files under the analyzed project are read-only.

Maven coordinates: `io.handoff:automatic-continuation-poc:1.0.0-SNAPSHOT`.

## 2. Pinned toolchain and dependencies

| Item | Required version |
|---|---|
| Java | OpenJDK 21 LTS, major version exactly 21 |
| Maven | 3.10.0 |
| Picocli | 4.7.7 |
| JavaParser symbol solver | 3.28.2 |
| SQLite JDBC | 3.54.1.0 |
| Jackson BOM/databind | 2.22.4 |
| NetworkNT JSON Schema Validator | 3.0.8 |
| JUnit Jupiter | 6.1.3 |
| Maven Compiler Plugin | 3.14.1 |
| Maven Surefire Plugin | 3.6.1 |
| Maven Shade Plugin | 3.6.1 |
| Local LLM transport | OpenAI-compatible HTTP (`/v1/chat/completions`) |
| Supported provider | Ollama 0.40.14 or LM Studio 0.3.x+ |
| Default endpoint | `http://127.0.0.1:11434` for Ollama; `http://127.0.0.1:1234/v1` for LM Studio |
| Model A | `qwen3:8b` (Ollama tag) or the exact loaded LM Studio model identifier |
| Model B | `qwen3:14b` (Ollama tag) or the exact loaded LM Studio model identifier |

The provider, endpoint, model identifier, and API mode MUST be persisted in every model invocation. `model_digest` MAY be persisted when the local provider exposes a content digest. When unavailable, the invocation MUST set `model_pin_status` to `UNPINNED_MODEL`; missing digest MUST NOT block local analysis, but reports MUST disclose the unpinned status. LM Studio MUST use its local OpenAI-compatible server; no cloud endpoint is permitted. A provider adapter MUST expose the same analysis interface and structured-output validation regardless of provider. The provider may be selected at preflight or by `--provider`; switching provider creates a new invocation/revision and MUST NOT overwrite prior results.

Dependencies MUST be resolved during provisioning. POC execution MUST use Maven offline mode (`mvn -o`). A model identifier MUST be captured in `environment-manifest.json`; Ollama or LM Studio content digests SHOULD be captured when available. Missing digest is recorded as `UNPINNED_MODEL`, not a preflight failure.

## 3. Identifier and time contract

- IDs MUST be lowercase UUIDv7 strings.
- `operation_id` is stable for one logical work item across retries.
- Timestamps MUST be UTC RFC 3339 with milliseconds (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
- Paths MUST be project-relative, UTF-8, `/` separated, Unicode NFC, without `.`/`..` segments.
- SHA-256 MUST be lowercase 64-character hexadecimal over exact bytes.
- JSON persisted for hashing MUST use UTF-8, lexicographically sorted object keys, no insignificant whitespace, LF endings, and no trailing newline.

## 4. Task lifecycle

States:

`CREATED`, `INDEXING`, `ANALYZING`, `CHECKPOINTING`, `PAUSED`, `INTERRUPTED`, `RESUMING`, `REQUIRES_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`.

Legal transitions:

| From | To |
|---|---|
| CREATED | INDEXING, CANCELLED, FAILED |
| INDEXING | ANALYZING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, FAILED, CANCELLED |
| ANALYZING | CHECKPOINTING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, FAILED, CANCELLED |
| CHECKPOINTING | ANALYZING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, COMPLETED, FAILED |
| PAUSED | RESUMING, CANCELLED |
| INTERRUPTED | RESUMING, REQUIRES_REVIEW, CANCELLED |
| RESUMING | INDEXING, ANALYZING, REQUIRES_REVIEW, FAILED |
| REQUIRES_REVIEW | RESUMING, FAILED, CANCELLED |

Terminal states have no outgoing transition. Illegal transitions MUST fail with `E_STATE_TRANSITION` and make no durable change.

## 5. Result and evidence states

Result states: `CONFIRMED`, `INFERRED`, `REQUIRES_REVIEW`, `UNSUPPORTED`, `CONFLICTING`, `STALE`, `UNKNOWN`.

- `CONFIRMED` MUST have at least one valid evidence reference from the same snapshot.
- `INFERRED` MUST have evidence and an explicit inference reason.
- `REQUIRES_REVIEW`, `CONFLICTING`, `STALE`, `UNKNOWN`, and `UNSUPPORTED` MUST NOT enter the normal conclusions section.
- Reanalysis MUST append a revision; it MUST NOT overwrite a prior result.
- Parser facts (`SYMBOL`, `DECLARATION`, `DIRECT_CALL`, `SOURCE_RANGE`) are authoritative. LLM claims MUST NOT contradict them.

## 6. CLI contract

Executable name: `handoff`. Every command supports `--db <path>` and `--json`. Human output goes to stdout, diagnostics to stderr. JSON output MUST validate against its command schema.

| Command | Required behavior |
|---|---|
| `run <task-id> [--provider ollama|lm-studio] [--endpoint <loopback-url>] [--model <id>]` | Execute pending work sequentially using the selected local provider |
| `create-task <project> --corpus-manifest <file>` | Create task and immutable source snapshot |
| `index <task-id>` | Parse snapshot and persist symbols/relations |
| `run <task-id> [--model <tag>]` | Execute pending work sequentially |
| `status <task-id>` | Print state, cursor, counts, review blockers |
| `pause <task-id>` | Request pause at next transaction boundary |
| `resume <task-id>` | Revalidate and continue from last valid checkpoint |
| `inject-failure <task-id> <failure> [--operation <id>]` | Arm one deterministic failure |
| `validate <task-id>` | Validate schema, hashes, event chain, evidence, checkpoint |
| `report <task-id> --format markdown|json` | Deterministically render validated data |
| `review list <task-id>` | List unresolved review records |
| `review show <review-id>` | Show revisions and evidence |
| `review accept <review-id> --operator <id> --reason <text>` | Commit acceptance |
| `review reject <review-id> --operator <id> --reason <text>` | Commit rejection |
| `review supersede <review-id> <result-id> --operator <id> --reason <text>` | Select replacement revision |

Failure names: `PROCESS_KILL_AFTER_RESULT`, `PROCESS_KILL_BEFORE_COMMIT`, `LLM_UNAVAILABLE`, `CHECKPOINT_CORRUPT`, `SOURCE_CHANGED`, `MODEL_SWITCH`, `PROVIDER_SWITCH`.

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | Command completed and requested invariant holds |
| 2 | CLI usage error |
| 10 | Precondition or environment mismatch |
| 20 | `REQUIRES_REVIEW` blocks progress |
| 30 | Integrity/schema validation failed |
| 40 | External local dependency unavailable |
| 50 | Runtime operation failed |

## 7. Transaction and recovery contract

One work item MUST commit the following in one `BEGIN IMMEDIATE` SQLite transaction:

1. evidence rows;
2. analysis result revision and result-evidence links;
3. model invocation, when applicable;
4. execution event;
5. work item status/cursor;
6. checkpoint.

A work item is complete only after commit. `operation_id` is unique. On resume:

- committed `operation_id`: reuse, never execute again;
- absent `operation_id`: execute;
- contradictory/partial observability: create review record and enter `REQUIRES_REVIEW`.

Database settings MUST include `PRAGMA foreign_keys=ON`, `journal_mode=WAL`, `synchronous=FULL`, and `busy_timeout=5000`. One process owns the writer lock.

## 8. Event hash chain

For each task, event sequence begins at 1. `prev_hash` for sequence 1 is 64 zeros. Event hash is:

```text
SHA256(UTF8(task_id + "\n" + sequence + "\n" + event_type + "\n" + occurred_at + "\n" + payload_sha256 + "\n" + prev_hash))
```

`payload_sha256` hashes canonical JSON. Validation MUST recompute every link in sequence order. Any mismatch returns exit 30, sets task to `REQUIRES_REVIEW` in a separate recovery transaction, and prohibits analysis/report generation.

## 9. Snapshot and invalidation contract

Snapshot ID is SHA-256 of canonical JSON containing repository-relative path, optional Git commit, ignore rules, and the sorted list of `{path,size,sha256}`. Snapshot content is immutable.

Changed file → directly linked evidence becomes `STALE` → results using stale evidence become `STALE` → reverse `code_relation` traversal marks dependent work items pending. Unaffected committed work MUST NOT rerun.

## 10. Secret handling

Before evidence, prompt, event payload, or report persistence, redact case-insensitive matches for password, passwd, secret, token, api_key, authorization, JDBC credentials, and PEM private-key blocks. Persist `[REDACTED:<sha256-prefix-12>]`. Absolute source paths MUST NOT appear in prompts or reports.

## 11. Deterministic reports

Sort by section rank, result status, claim type, stable subject, result ID. Use LF and UTF-8. JSON uses canonical JSON plus one trailing LF; Markdown uses fixed headings and one trailing LF. Same database state and generator version MUST produce byte-identical output and SHA-256.

## 12. Ownership

| Module | Sole writer |
|---|---|
| source snapshot | Snapshot Manager |
| code symbols/relations | Indexer |
| task/work state | Runtime |
| checkpoints | Checkpoint Manager |
| evidence | Evidence Manager |
| results/invocations | Analysis Pipeline |
| events | Event Logger |
| reviews | Review Manager |
| reports | Report Generator |

A module crosses another module's seam through its interface; it MUST NOT issue ad-hoc writes to another module's tables.

## 13. Acceptance gate

A package passes only when all specified commands were observed with expected exit codes, database assertions pass, produced hashes are recorded, and no hard-stop condition occurred. Executor prose is not evidence.