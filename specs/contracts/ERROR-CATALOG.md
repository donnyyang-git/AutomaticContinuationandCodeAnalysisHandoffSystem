# Error Catalog v1.0.0

| Code | Exit | Trigger | Required state/action |
|---|---:|---|---|
| E_USAGE | 2 | Invalid CLI syntax | No durable change |
| E_ENV_VERSION | 10 | Tool/model/dependency version mismatch | Stop; blocker |
| E_SPEC_HASH | 10 | Spec or contract hash mismatch | Stop; blocker |
| E_CORPUS_HASH | 10 | Corpus file/hash mismatch | Stop; blocker |
| E_STATE_TRANSITION | 10 | Illegal task transition | No durable change |
| E_PRECONDITION | 10 | Missing prior artifact/handoff | Stop; blocker |
| E_REVIEW_REQUIRED | 20 | Open critical review | Task `REQUIRES_REVIEW` |
| E_MODEL_CONFLICT | 20 | Same claim differs across retries/models | New revisions; review |
| E_PARSER_CONFLICT | 20 | Structural authorities disagree | Review; no auto-selection |
| E_SCHEMA_INVALID | 30 | JSON/SQLite invariant violation | Reject write/output |
| E_EVENT_CHAIN | 30 | Event chain mismatch | Review; prohibit run/report |
| E_CHECKPOINT_CORRUPT | 30 | Checkpoint hash/schema mismatch | Review; do not resume |
| E_EVIDENCE_INVALID | 30 | Evidence missing, wrong range/snapshot/hash | Reject confirmation |
| E_OLLAMA_UNAVAILABLE | 40 | Local Ollama unavailable | Preserve committed work; stop |
| E_MODEL_MISSING | 40 | Required local tag/digest missing | Stop; blocker |
| E_RUNTIME | 50 | Unclassified runtime failure | Atomic rollback; `FAILED` unless recoverable |

Errors MUST emit `{code,message,task_id,operation_id?,details}` under `--json`. Details MUST be deterministic and MUST NOT expose secrets or absolute source paths.
