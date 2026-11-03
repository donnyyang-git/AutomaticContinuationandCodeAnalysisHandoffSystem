from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import zipfile
from pathlib import Path

try:
    import jsonschema
except ImportError as exc:
    raise SystemExit("jsonschema is required to validate specification schemas") from exc

ROOT = Path(__file__).resolve().parent


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(name: str):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def validate_json(instance_name: str, schema_name: str) -> None:
    jsonschema.Draft202012Validator(load(schema_name), format_checker=jsonschema.FormatChecker()).validate(load(instance_name))


def main() -> int:
    errors: list[str] = []

    packages = ["POC-0", "POC-1", "POC-2", "POC-3", "POC-4", "POC-5", "CONTINUUM"]
    for package in packages:
        readme = ROOT / package / "README.md"
        if not readme.is_file():
            errors.append(f"missing {readme.relative_to(ROOT)}")
            continue
        text = readme.read_text(encoding="utf-8")
        for required in ("spec_version: 1.0.0", "## Goal", "## Work packets", "## Acceptance", "## Hard stops", "## Handoff"):
            if required not in text:
                errors.append(f"{package}: missing {required}")

    try:
        validate_json("inputs/corpus-manifest.json", "contracts/schemas/manifests.schema.json")
        validate_json("inputs/ground-truth.json", "contracts/schemas/ground-truth.schema.json")
        validate_json("inputs/environment-manifest.json", "contracts/schemas/manifests.schema.json")
    except Exception as exc:
        errors.append(f"JSON schema validation: {exc}")

    manifest = load("inputs/corpus-manifest.json")
    archive_path = ROOT / "inputs/jpetstore-corpus-v1.0.0.zip"
    try:
        with zipfile.ZipFile(archive_path) as archive:
            members = set(archive.namelist())
            for item in manifest["files"]:
                path = item["path"]
                if path not in members:
                    errors.append(f"archive missing {path}")
                    continue
                data = archive.read(path)
                if len(data) != item["size_bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                    errors.append(f"archive mismatch {path}")
            for attribution in ("LICENSE", "NOTICE"):
                if attribution not in members:
                    errors.append(f"archive missing attribution {attribution}")
    except Exception as exc:
        errors.append(f"archive validation: {exc}")

    for mutation in manifest.get("mutations", []):
        patch = ROOT / "inputs" / f"mutation-{mutation['id']}.patch"
        if not patch.is_file() or sha256(patch) != mutation["patch_sha256"]:
            errors.append(f"mutation mismatch {mutation['id']}")

    try:
        db = sqlite3.connect(":memory:")
        db.executescript((ROOT / "contracts/schema.sql").read_text(encoding="utf-8"))
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_schema WHERE type='table'")}
        required_tables = {"schema_meta", "source_snapshot", "snapshot_file", "task_state", "work_item", "code_symbol", "code_relation", "evidence", "analysis_result", "analysis_result_evidence", "model_invocation", "execution_event", "checkpoint", "review", "failure_injection", "report"}
        missing = required_tables - tables
        if missing:
            errors.append(f"schema missing tables {sorted(missing)}")
        fk = db.execute("PRAGMA foreign_keys").fetchone()[0]
        if fk != 1:
            errors.append("foreign_keys pragma is not enabled")
    except Exception as exc:
        errors.append(f"SQL validation: {exc}")

    contracts = (ROOT / "contracts/CANONICAL-CONTRACTS.md").read_text(encoding="utf-8")
    for value in (
        "JavaParser symbol solver | 3.28.2",
        "SQLite JDBC | 3.54.1.0",
        "Local LLM transport | OpenAI-compatible HTTP (`/v1/chat/completions`)",
        "Supported provider | Ollama 0.40.14 or LM Studio 0.3.x+",
        "qwen3:8b",
        "qwen3:14b",
    ):
        if value not in contracts:
            errors.append(f"contract missing pinned value: {value}")

    ground_truth = load("inputs/ground-truth.json")
    expected_counts = {"types": 25, "methods": 238, "xml_links": 25, "view_links": 5}
    for key, expected in expected_counts.items():
        if len(ground_truth[key]) != expected:
            errors.append(f"ground truth {key}: expected {expected}, got {len(ground_truth[key])}")

    lock = load("SPEC-MANIFEST.json")
    if lock.get("status") != "LOCKED" or lock.get("spec_set_version") != "1.0.0":
        errors.append("specification lock status/version mismatch")
    for item in lock.get("authoritative_files", []):
        path = ROOT / item["path"]
        if not path.is_file() or path.stat().st_size != item["size_bytes"] or sha256(path) != item["sha256"]:
            errors.append(f"authoritative file mismatch {item['path']}")
    for bundle_item in lock.get("execution_bundles", []):
        bundle = ROOT / bundle_item["path"]
        if not bundle.is_file() or bundle.stat().st_size != bundle_item["size_bytes"] or sha256(bundle) != bundle_item["sha256"]:
            errors.append(f"bundle mismatch {bundle_item['spec_id']}")
            continue
        try:
            with zipfile.ZipFile(bundle) as archive:
                bundle_manifest = json.loads(archive.read("BUNDLE-MANIFEST.json"))
                if bundle_manifest.get("spec_id") != bundle_item["spec_id"]:
                    errors.append(f"bundle identity mismatch {bundle_item['spec_id']}")
                for entry in bundle_manifest.get("files", []):
                    data = archive.read(entry["path"])
                    if len(data) != entry["size_bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                        errors.append(f"bundle entry mismatch {bundle_item['spec_id']}:{entry['path']}")
        except Exception as exc:
            errors.append(f"bundle validation {bundle_item['spec_id']}: {exc}")

    if errors:
        print(json.dumps({"status": "INVALID", "errors": errors}, ensure_ascii=False, indent=2))
        return 1
    observation = load("inputs/environment-observation.json")
    print(json.dumps({
        "status": "VALID",
        "specification_packages": len(packages),
        "corpus_files": len(manifest["files"]),
        "ground_truth_counts": expected_counts,
        "environment_ready": observation.get("overall_status") == "READY",
        "execution_gate": "OPEN" if observation.get("overall_status") == "READY" else "BLOCKED_ENVIRONMENT_NOT_READY",
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
