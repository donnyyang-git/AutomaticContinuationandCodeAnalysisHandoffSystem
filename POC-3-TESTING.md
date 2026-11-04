# POC-3 Test Record

This file records implementation tests for POC-3 without modifying the locked specification at `specs/POC-3/README.md`.

## WP1 — Evidence Manager

Run: `mvn -o -q "-Dtest=EvidenceManagerTest" test`

| Test | Behavior verified |
|---|---|
| `createsStableEvidenceForInclusiveLinesAndRedactsSecrets` | Stable evidence ID, inclusive-range hash, and redaction of JSON secrets, Authorization values, JDBC URL credentials, and PEM private keys. |
| `rejectsFilesThatChangedAfterSnapshotCreation` | Changed source bytes are rejected; no evidence row is written. |
| `revalidationRejectsEvidenceAfterSourceChanges` | Evidence is revalidated against snapshot bytes before use and rejected after source mutation. |
| `rejectsUnlistedFilesAndInvalidInclusiveRanges` | Files outside the snapshot and invalid inclusive ranges are rejected without writes. |

## WP2 — Provider and Prompt

Run: `mvn -o -q "-Dtest=OpenAiCompatibleAnalysisProviderTest" test`

Tests use a local loopback HTTP fixture only; they do not call a real model.

| Test | Behavior verified |
|---|---|
| `bothProvidersUseLoopbackChatCompletionsAndReturnParsedJson` | Ollama and LM Studio use the same `/v1/chat/completions` request, HTTP/1.1, fixed sampling (`temperature=0`, `top_p=1`, `seed=42`, `max_tokens=1024`, `reasoning_effort=none`), JSON-schema response format, and parsed completion result. |
| `promptContainsOnlyBoundedContextAndRedactsSecretsAndAbsolutePaths` | Prompt includes only task, subject, parser facts, bounded evidence, and schema; secrets and absolute paths are redacted. |
| `rejectsNonLoopbackEndpointsBeforeSendingRequests` | Non-loopback or non-HTTP endpoints fail before a provider request is sent. |
| `rejectsModelsNotListedInTheReadyEnvironmentManifest` | Models absent from a READY environment manifest are rejected. |
| `malformedCompletionIsRejectedAsInvalidSchemaOutput` | Non-JSON model completion returns `E_SCHEMA_INVALID`. |
| `mapsProviderAvailabilityAndMissingModelResponsesToContractErrors` | HTTP 503 maps to `E_LLM_UNAVAILABLE`; missing model maps to `E_MODEL_MISSING` without exposing the provider body. |

## WP3 — Claim Validation

Run: `mvn -o -q "-Dtest=ClaimValidatorTest" test`

Tests use temporary SQLite databases.

| Test | Behavior verified |
|---|---|
| `persistsValidatedClaimAndInvocationWithinCallerTransaction` | Persists a valid claim, invocation, and evidence link in the caller transaction; rollback removes writes; raw output is redacted and its hash matches the stored value. |
| `downgradesConfirmedClaimWithoutEvidenceAndOpensReview` | `CONFIRMED` without evidence becomes `UNSUPPORTED` and opens review. |
| `persistsInferredClaimWithReasonAndEvidence` | `INFERRED` retains its explicit reason and valid evidence link. |
| `rejectsInferredClaimWithoutExplicitReason` | `INFERRED` without a reason is rejected before result persistence. |
| `materiallyDifferentRetryAppendsConflictingRevisionAndOpensReview` | Different retry appends revision 2 as `CONFLICTING` and opens review without overwriting revision 1. |
| `rejectsEvidenceKindNotAllowedForClaimType` | Evidence outside the claim-type allowlist is rejected before persistence. |
| `rejectsMalformedResponseWithoutWritingResults` | Schema-invalid output is rejected without result or invocation rows. |
| `rejectsDuplicateLogicalClaimIdsBeforeWritingAnyRows` | Duplicate logical claim IDs are rejected before writes. |
| `rejectsEvidenceFromAnotherTaskSnapshotWithoutWritingResults` | Missing or wrong-snapshot evidence is rejected before writes. |
| `marksClaimContradictingParserDeclarationForReview` | Claim that conflicts with parser declaration authority enters review. |
| `marksClaimNegatingParserDeclarationForReview` | Claim that explicitly denies parser declaration authority enters review. |
| `marksClaimDenyingParserAuthoritativeCallForReview` | Explicit denial of a parser-authoritative direct call enters review. |

The implemented parser contradiction checks cover declaration-kind mismatch/negation and explicit denial of a named direct call; they are not a general natural-language contradiction detector.

## Runtime Transaction Integration

Run: `mvn -o -q "-Dtest=AtomicWorkCoordinatorTest" test`

| Test | Behavior verified |
|---|---|
| `validatedAnalysisCommitsAllArtifactsAndReusesCommittedOperation` | Evidence, result, invocation, event, checkpoint, and work status commit together; validates event/checkpoint integrity and idempotent retry. |
| `failureAfterValidatedResultsRollsBackEvidenceInvocationAndRuntimeArtifacts` | Injected failure rolls back evidence, result, invocation, event, checkpoint, and work status together. |

## WP4 — Actual Model Smoke

The actual-model test is opt-in and skipped by the default suite.

Base command: `mvn -o -q "-Dtest=P3Wp4LocalModelSmokeTest" "-Dhandoff.localModelSmoke=true" test`

Subject selection: add `-Dhandoff.smoke.subject=account`, `-Dhandoff.smoke.subject=catalog`, or `-Dhandoff.smoke.subject=order`. The default subject is Account.

| Test | Behavior verified |
|---|---|
| `analyzesOneFixedCorpusSubjectWithTheSelectedLocalModel` | Uses Model A from the READY environment manifest and a bounded fixed-corpus declaration/member excerpt; validates provider output, atomically persists invocation/result/evidence/event/checkpoint, and runs `IntegrityValidator`. Account additionally injects `PROCESS_KILL_AFTER_RESULT`, verifies transaction rollback, invokes the model again under the same operation ID, and commits successfully. |

Verified with `qwen/qwen3.5-9b`: Account recovery run 74.61 s, Catalog 13.12 s, and Order 39.96 s. Model digest was unavailable and recorded as `UNPINNED_MODEL`.

## Full Verification

Run: `mvn -o -q verify`

Latest result: 45 tests, 0 failures, 0 errors, 1 skipped. The single skipped test is the opt-in actual-model smoke.

Remaining POC-3 work: OS-level process termination during an active model invocation, provider-unavailable/re-enable recovery, final handoff artifact, and the deferred `create-task` CLI command.
