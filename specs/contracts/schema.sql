PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA busy_timeout = 5000;

CREATE TABLE schema_meta (
  schema_version TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL
) STRICT;

CREATE TABLE source_snapshot (
  snapshot_id TEXT PRIMARY KEY,
  project_name TEXT NOT NULL,
  git_commit TEXT,
  manifest_sha256 TEXT NOT NULL UNIQUE,
  ignore_rules_json TEXT NOT NULL,
  created_at TEXT NOT NULL
) STRICT;

CREATE TABLE snapshot_file (
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  path TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
  sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
  PRIMARY KEY(snapshot_id, path)
) STRICT;

CREATE TABLE task_state (
  task_id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  state TEXT NOT NULL CHECK(state IN ('CREATED','INDEXING','ANALYZING','CHECKPOINTING','PAUSED','INTERRUPTED','RESUMING','REQUIRES_REVIEW','COMPLETED','FAILED','CANCELLED')),
  phase TEXT NOT NULL,
  cursor_operation_id TEXT,
  pause_requested INTEGER NOT NULL DEFAULT 0 CHECK(pause_requested IN (0,1)),
  version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE work_item (
  operation_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  kind TEXT NOT NULL,
  subject TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('PENDING','RUNNING','COMMITTED','FAILED','STALE','REQUIRES_REVIEW')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  input_sha256 TEXT NOT NULL,
  committed_at TEXT,
  UNIQUE(task_id, kind, subject, input_sha256)
) STRICT;

CREATE TABLE code_symbol (
  symbol_id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  kind TEXT NOT NULL,
  qualified_name TEXT NOT NULL,
  path TEXT NOT NULL,
  start_line INTEGER NOT NULL CHECK(start_line > 0),
  end_line INTEGER NOT NULL CHECK(end_line >= start_line),
  signature TEXT,
  UNIQUE(snapshot_id, kind, qualified_name, path, start_line)
) STRICT;

CREATE TABLE code_relation (
  relation_id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  source_symbol_id TEXT NOT NULL REFERENCES code_symbol(symbol_id),
  target_symbol_id TEXT REFERENCES code_symbol(symbol_id),
  target_external_name TEXT,
  kind TEXT NOT NULL CHECK(kind IN ('DIRECT_CALL','CONTAINS','EXTENDS','IMPLEMENTS','INJECTS','XML_MAPS','VIEW_ENTRY','READS','WRITES','UNRESOLVED')),
  authority TEXT NOT NULL CHECK(authority IN ('PARSER','CONFIG','INFERRED','UNKNOWN')),
  evidence_path TEXT NOT NULL,
  evidence_line INTEGER CHECK(evidence_line > 0),
  CHECK((target_symbol_id IS NOT NULL) <> (target_external_name IS NOT NULL))
) STRICT;

CREATE TABLE evidence (
  evidence_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  kind TEXT NOT NULL,
  path TEXT NOT NULL,
  start_line INTEGER NOT NULL CHECK(start_line > 0),
  end_line INTEGER NOT NULL CHECK(end_line >= start_line),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256) = 64),
  redacted_excerpt TEXT,
  status TEXT NOT NULL CHECK(status IN ('VALID','STALE','MISSING','REDACTED')),
  created_at TEXT NOT NULL
) STRICT;

CREATE TABLE analysis_result (
  result_id TEXT PRIMARY KEY,
  logical_claim_id TEXT NOT NULL,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  operation_id TEXT NOT NULL REFERENCES work_item(operation_id),
  revision INTEGER NOT NULL CHECK(revision > 0),
  claim_type TEXT NOT NULL,
  subject TEXT NOT NULL,
  claim_text TEXT NOT NULL,
  confidence TEXT NOT NULL CHECK(confidence IN ('HIGH','MEDIUM','LOW')),
  status TEXT NOT NULL CHECK(status IN ('CONFIRMED','INFERRED','REQUIRES_REVIEW','UNSUPPORTED','CONFLICTING','STALE','UNKNOWN')),
  inference_reason TEXT,
  model_tag TEXT,
  prompt_version TEXT,
  schema_version TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(logical_claim_id, revision)
) STRICT;

CREATE TABLE analysis_result_evidence (
  result_id TEXT NOT NULL REFERENCES analysis_result(result_id),
  evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id),
  PRIMARY KEY(result_id, evidence_id)
) STRICT;

CREATE TABLE model_invocation (
  invocation_id TEXT PRIMARY KEY,
  operation_id TEXT NOT NULL REFERENCES work_item(operation_id),
  model_tag TEXT NOT NULL,
  model_digest TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  input_sha256 TEXT NOT NULL,
  evidence_query_sha256 TEXT NOT NULL,
  sampling_json TEXT NOT NULL,
  raw_output TEXT NOT NULL,
  raw_output_sha256 TEXT NOT NULL,
  schema_valid INTEGER NOT NULL CHECK(schema_valid IN (0,1)),
  error_text TEXT,
  retry_number INTEGER NOT NULL,
  started_at TEXT NOT NULL,
  ended_at TEXT NOT NULL
) STRICT;

CREATE TABLE execution_event (
  event_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  operation_id TEXT,
  sequence INTEGER NOT NULL CHECK(sequence > 0),
  event_type TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256) = 64),
  prev_hash TEXT NOT NULL CHECK(length(prev_hash) = 64),
  event_hash TEXT NOT NULL UNIQUE CHECK(length(event_hash) = 64),
  UNIQUE(task_id, sequence)
) STRICT;

CREATE TABLE checkpoint (
  checkpoint_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  sequence INTEGER NOT NULL CHECK(sequence > 0),
  task_state_json TEXT NOT NULL,
  verified_facts_json TEXT NOT NULL,
  pending_steps_json TEXT NOT NULL,
  evidence_refs_json TEXT NOT NULL,
  cursor_operation_id TEXT,
  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(snapshot_id),
  parser_version TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  model_tag TEXT,
  analysis_schema_version TEXT NOT NULL,
  checkpoint_schema_version TEXT NOT NULL,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256) = 64),
  created_at TEXT NOT NULL,
  UNIQUE(task_id, sequence)
) STRICT;

CREATE TABLE review (
  review_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  reason_code TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('OPEN','ACCEPTED','REJECTED','SUPERSEDED')),
  original_state TEXT NOT NULL,
  selected_result_id TEXT REFERENCES analysis_result(result_id),
  operator_id TEXT,
  decision_reason TEXT,
  created_at TEXT NOT NULL,
  decided_at TEXT
) STRICT;

CREATE TABLE failure_injection (
  injection_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  failure_name TEXT NOT NULL,
  target_operation_id TEXT,
  armed INTEGER NOT NULL CHECK(armed IN (0,1)),
  fired_at TEXT
) STRICT;

CREATE TABLE report (
  report_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_state(task_id),
  format TEXT NOT NULL CHECK(format IN ('markdown','json')),
  generator_version TEXT NOT NULL,
  content_path TEXT NOT NULL,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256) = 64),
  created_at TEXT NOT NULL,
  UNIQUE(task_id, format, generator_version, content_sha256)
) STRICT;

CREATE INDEX idx_work_task_status ON work_item(task_id, status);
CREATE INDEX idx_relation_target ON code_relation(snapshot_id, target_symbol_id);
CREATE INDEX idx_evidence_snapshot_status ON evidence(snapshot_id, status);
CREATE INDEX idx_result_task_status ON analysis_result(task_id, status);
CREATE INDEX idx_event_task_sequence ON execution_event(task_id, sequence);
