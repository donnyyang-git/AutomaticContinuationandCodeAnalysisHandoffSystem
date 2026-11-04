#!/usr/bin/env python3
# [修改] 2026-11-04 19:00 原因: 建立 checkpoint 差異診斷腳本, 說明: 讓 integrity mismatch 可重複驗證且可追蹤
import hashlib
import json
import os
import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path("work/handoff.db")


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def compute_expected(task_state_json: str, verified_facts_json: str, pending_steps_json: str,
                     evidence_refs_json: str, cursor_operation_id: str, snapshot_id: str,
                     parser_version: str, prompt_version: str, analysis_schema_version: str,
                     checkpoint_schema_version: str):
    return sha256(
        task_state_json
        + verified_facts_json
        + pending_steps_json
        + evidence_refs_json
        + (cursor_operation_id or "")
        + snapshot_id
        + parser_version
        + prompt_version
        + analysis_schema_version
        + checkpoint_schema_version
    )


def main() -> int:
    db_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DB
    if not db_path.exists():
        print(f"[WARN] database not found: {db_path}")
        return 2

    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(
            """
            SELECT task_id, sequence, task_state_json, verified_facts_json, pending_steps_json,
                   evidence_refs_json, cursor_operation_id, snapshot_id,
                   parser_version, prompt_version, analysis_schema_version, checkpoint_schema_version,
                   content_sha256
            FROM checkpoint
            ORDER BY task_id, sequence
            """
        ).fetchall()

        if not rows:
            print(f"[WARN] no checkpoint rows in {db_path}")
            return 3

        print(f"[INFO] checkpoint rows: {len(rows)}")
        for row in rows:
            expected = compute_expected(
                row["task_state_json"],
                row["verified_facts_json"],
                row["pending_steps_json"],
                row["evidence_refs_json"],
                row["cursor_operation_id"],
                row["snapshot_id"],
                row["parser_version"],
                row["prompt_version"],
                row["analysis_schema_version"],
                row["checkpoint_schema_version"],
            )
            ok = expected == row["content_sha256"]
            print(json.dumps({
                "task_id": row["task_id"],
                "sequence": row["sequence"],
                "expected_sha256": expected,
                "stored_sha256": row["content_sha256"],
                "match": ok,
            }, ensure_ascii=False))

        return 0 if all(
            compute_expected(
                row["task_state_json"],
                row["verified_facts_json"],
                row["pending_steps_json"],
                row["evidence_refs_json"],
                row["cursor_operation_id"],
                row["snapshot_id"],
                row["parser_version"],
                row["prompt_version"],
                row["analysis_schema_version"],
                row["checkpoint_schema_version"],
            ) == row["content_sha256"]
            for row in rows
        ) else 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
