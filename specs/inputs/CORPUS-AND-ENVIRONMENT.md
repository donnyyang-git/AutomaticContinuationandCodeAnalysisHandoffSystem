# Corpus and Environment Contract v1.0.0

Status: `LOCK-CANDIDATE`.

## Corpus

- Project: MyBatis JPetStore 6
- Source: https://github.com/mybatis/jpetstore-6
- Commit: `8f11fab492a473d9f98a4eb71de0af8d565f38fe`
- License: Apache-2.0; retain upstream `LICENSE` and `NOTICE` with any archive.
- Selection: the 41 paths in `corpus-files.txt` (25 Java, 7 MyBatis XML, 2 Spring XML, 7 JSP).
- Baseline archive: `jpetstore-corpus-v1.0.0.zip` generated only from the fixed commit and selection.

This corpus covers Controller → Service → Mapper, domain data, MyBatis SQL mappings, Spring configuration, JSP entry/view relations, and unresolved framework wiring.

## Ground truth scope

`ground-truth.json` MUST identify at least:

1. every selected Java type and method declaration;
2. direct Controller → Service calls;
3. direct Service → Mapper calls;
4. Mapper interface → XML statement links;
5. controller view names → selected JSP paths where determinable;
6. account, catalog, cart, and order layer paths;
7. unresolved/dynamic relations as `UNKNOWN`, never invented edges.

Parser metrics and LLM-claim metrics are separate. Ground truth MUST NOT be changed to make an implementation pass; corrections require a new corpus version.

## Mutation set

All mutations apply to a fresh copy of the baseline and are mutually exclusive unless a test explicitly composes them.

| ID | Change | Expected invalidation |
|---|---|---|
| M1 | Rename `CatalogService.getProductListByCategory` and its controller call consistently | changed service/controller evidence; dependent catalog claims only |
| M2 | Change the `OrderMapper.xml` statement used by order insertion without changing Java signatures | mapper XML evidence, order persistence/data-flow claims; unrelated account/catalog claims unchanged |
| M3 | Change `AccountController` view result from `account/SignonForm` to a new literal | account entry/view relation and its claims; order/catalog/cart unchanged |

Each mutation MUST be distributed as a unified UTF-8 LF patch with its SHA-256 recorded in `corpus-manifest.json`. Applying a patch to a non-baseline tree MUST fail.

## Environment

Required runtime:

- Windows x64 or Linux x64 recorded in the manifest;
- OpenJDK 21;
- Maven 3.10.0;
- Ollama 0.40.14;
- local `qwen3:8b` and `qwen3:14b`, each with recorded digest;
- all Maven dependencies pre-resolved for offline execution.

Current workstation observation at specification time: Maven 3.9.6 uses Java 8 and Ollama is absent. Therefore execution environment status is `NOT_READY`; provisioning is REQUIRED before POC-0. This observation does not alter required versions.

## Preflight

Before any work packet:

```text
java -version                        # major 21
mvn -version                         # 3.10.0 and Java 21
ollama --version                     # 0.40.14
ollama list                          # exact tags present
mvn -o -q -DskipTests package        # no network
handoff validate-spec --manifest specs/SPEC-MANIFEST.json
```

Any mismatch produces `E_ENV_VERSION`, `E_MODEL_MISSING`, `E_CORPUS_HASH`, or `E_SPEC_HASH` and stops before repository mutation.
