# ChatGPT 討論內容：分支 · 方案可行性評估

> 來源連結：https://chatgpt.com/share/6ae9c8ee-6e74-83ee-84a4-464d36492e08

---

## 對話片段 1

routes/share.$shareId.($action)

---

## 對話片段 2

6ae9c8ee-6e74-83ee-84a4-464d36492e08

---

## 對話片段 3

hasConversationShareOgImageTemplate

---

## 對話片段 4

hasImagegenConversationOgImageVariant

---

## 對話片段 5

Preview of shared ChatGPT conversation

---

## 對話片段 6

ecdd68fe-a49f-4be7-acfa-0d26bc70baf3

---

## 對話片段 7

6ae9be3a-a7a0-83ee-8131-474d335f024f

---

## 對話片段 8

Our latest and most advanced model

---

## 對話片段 9

9cb725e7-64c3-4427-828b-adbf39b907ab

---

## 對話片段 10

conversation_followup_suggestions_eligible

---

## 對話片段 11

5fde873e-b877-4247-8f1b-d7deb39af91d

---

## 對話片段 12

1e66054e-0178-473f-a306-19e3d8528285

---

## 對話片段 13

闡述ISO 27001在Hash-chained Event Log中的具體應用限制

---

## 對話片段 14

請針對Hash-chained Event Log如何影響ISO 27001合規性進行更深入說明，並探討實際上還需哪些額外措施。

---

## 對話片段 15

receiver_followup_capability_scope

---

## 對話片段 16

receiver_followup_prompt_version

---

## 對話片段 17

receiver_followup_share_surface

---

## 對話片段 18

請繼續細說MVP設計中關於Context Manager和Analysis Pipeline的核心功能與實現細節。

---

## 對話片段 19

receiver_followup_section_prefix

---

## 對話片段 20

這份規劃已經可以從「架構討論」進入「工程驗證」階段。我認同直接做 PoC，但在開始寫程式前，我建議再補上幾個關鍵設計，避免測試全部通過，實際上卻沒有驗證到真正的核心能力。

## 一、先修正四個工程細節

### 1. 不要讓 CodeGraph 與 Runtime 直接共用資料表

可以共用 SQLite 作為儲存媒介，但必須區分資料責任。

| 資料模組 | 負責元件 | 主要內容 |
|---|---|---|
| Code Index | CodeGraph / JavaParser | 檔案、類別、方法、呼叫關係 |
| Task State | Long Task Runtime | 任務狀態、執行進度、Checkpoint |
| Evidence Store | Evidence Manager | 原始碼位置、Hash、分析證據 |
| Analysis Result | Analysis Pipeline | 已驗證事實、分析結果、待確認事項 |
| Execution Event | Runtime | 工具執行紀錄、錯誤、重試、恢復紀錄 |

**原則：共用儲存，不共用資料所有權。**

例如 CodeGraph 可以更新索引，但不能直接修改 Runtime 的 Checkpoint。

### 2. Context 達到 80% 不代表直接清空對話

建議改成以下流程：

```text
監控 Context 使用量
        │
        ▼
是否達到 Checkpoint 門檻？
        │
        ▼
整理目前分析成果
        │
        ▼
將成果轉成結構化資料
        │
        ▼
保存 Evidence + Checkpoint
        │
        ▼
驗證資料已成功寫入
        │
        ▼
建立新的 Context
        │
        ▼
繼續執行原任務
```

這裡最重要的是：

- 不能只保存「目前分析到第幾個檔案」。
- 必須保存已確認的分析事實。
- 必須保存尚未完成的分析項目。
- 必須保存每項結論對應的程式碼證據。
- 必須確認 Checkpoint 成功寫入後，才允許捨棄舊 Context。

否則只是清空對話，並不是真正的任務接續。

### 3. 檔案異動後，不能只標記 REQUIRES_REVIEW

應該進一步進行依賴影響分析。

例如：

```text
OrderService.java
       │
       ├── OrderDAO.java
       ├── OrderDTO.java
       └── OrderMapper.java
```

假設 OrderDAO.java 被修改：

1. 比對修改前後 SHA-256。
2. 標記 OrderDAO 的分析結果失效。
3. 查詢依賴圖，找出受影響的類別與呼叫鏈。
4. 只重新分析受影響的部分。
5. 保留未受影響的已驗證成果。

這樣才能真正達成增量恢復，而不是每次恢復都重新掃描整個專案。

### 4. 模型切換必須保存分析版本資訊

Qwen 7B 換成 14B 時，不應只記錄模型名稱。

至少要保存：

| 欄位 | 用途 |
|---|---|
| model_name | 模型名稱 |
| model_version | 模型版本或 Tag |
| prompt_version | 分析 Prompt 版本 |
| parser_version | Parser 版本 |
| analysis_schema | 結構化輸出格式版本 |
| source_hash | 原始碼版本 |
| checkpoint_version | Checkpoint 格式版本 |

這樣才能追蹤模型切換前後的分析差異。

---

## 二、PoC 建議採用的最小架構

第一版不需要完整 Web UI，也不需要先建置複雜的 Agent 系統。

```text
┌──────────────────────────────────────────┐
│              CLI / Task API              │
│                                          │
│  create-task / run / pause / resume      │
│  status / report / inject-failure        │
└───────────────────┬──────────────────────┘
                    │
┌───────────────────▼──────────────────────┐
│             Long Task Runtime            │
│                                          │
│  Task Scheduler                          │
│  Context Manager                         │
│  Checkpoint Manager                      │
│  Recovery Manager                        │
│  Model Adapter                           │
└───────────────────┬──────────────────────┘
                    │
┌───────────────────▼──────────────────────┐
│             Code Intelligence             │
│                                          │
│  JavaParser                              │
│  CodeGraph Adapter                       │
│  Dependency Analyzer                     │
│  Evidence Collector                      │
└───────────────────┬──────────────────────┘
                    │
┌───────────────────▼──────────────────────┐
│             Analysis Pipeline            │
│                                          │
│  Ollama / Qwen                           │
│  Fact Extraction                         │
│  Relationship Analysis                   │
│  Evidence Validation                     │
│  Report Generator                        │
└───────────────────┬──────────────────────┘
                    │
┌───────────────────▼──────────────────────┐
│             Persistence Layer             │
│                                          │
│  SQLite                                  │
│  Task State / Checkpoint                 │
│  Evidence / Analysis Result              │
│  Execution Event                         │
└──────────────────────────────────────────┘
```

### 第一版技術選型

| 元件 | 建議技術 | 第一版責任 |
|---|---|---|
| 開發語言 | Python | Runtime 與流程控制 |
| Java Parser | JavaParser | Java 語法與符號解析 |
| 多語言 Parser | Tree-sitter | 預留擴充 |
| Code Graph | CodeGraph PoC | 索引及關係查詢 |
| AI Runtime | Ollama | 本地模型推論 |
| AI Model | Qwen 7B / 14B | 程式碼語意分析 |
| Persistence | SQLite | 任務及分析狀態 |
| Hash | SHA-256 | 檔案版本驗證 |
| Report | Markdown / JSON | 交接報告 |

**一個重要提醒：BugNET 是 .NET/C# 系統，不適合直接拿來驗證 JavaParser。** 如果要測 JavaParser，應使用 Java 專案；若要測多語言能力，則可以另外增加 C# 測試組。

---

## 三、將七項測試轉成正式驗收標準

我建議不要只驗證「程式有沒有重新啟動」，而要驗證恢復前後的資料一致性。

| 測試項目 | 核心驗收條件 |
|---|---|
| 初始索引 | 50 個檔案均有索引紀錄及 Hash |
| Context 壓縮 | Checkpoint 保存完整，無已確認事實遺失 |
| 強制中斷 | Kill Process 後可以從最後一筆有效 Checkpoint 恢復 |
| 外部干擾 | 可以辨識 Ollama 中斷及原始碼異動 |
| 任務恢復 | 已完成且未受影響的工作不重複執行 |
| 模型切換 | 7B 與 14B 可以讀取相同 Checkpoint Schema |
| 報告驗證 | 每項程式碼結論都能追溯到檔案、行號及 Hash |

另外增加三項我認為更重要的測試：

**測試 8：重複執行（Idempotency）**

在工具執行完成、但 Checkpoint 尚未更新時強制中斷。恢復後不得產生重複資料或重複副作用。

**測試 9：Checkpoint 損毀**

人為修改 Checkpoint JSON 或 SQLite 資料，確認系統能偵測資料不一致，而不是直接使用錯誤狀態繼續執行。

**測試 10：分析結果可追溯**

隨機抽取 20 項分析結論，人工核對其原始碼行號、呼叫關係及證據是否正確。

這三項測試比單純確認程式能否 Resume 更能反映企業實際使用價值。

---

## 四、我建議 MVP 分成三個里程碑

| 里程碑 | 開發範圍 | 完成標準 |
|---|---|---|
| POC-1：長任務恢復 | SQLite、Task Runtime、Checkpoint、Crash Recovery | 中斷後能正確接續 |
| POC-2：程式碼理解 | JavaParser、CodeGraph、Ollama | 能分析 Java 類別及呼叫關係 |
| POC-3：整合驗證 | Context Manager、Evidence、模型切換、報告 | 中斷後能接續分析並產生可追溯報告 |

**開發順序很重要：先驗證長任務恢復，再整合程式碼理解。**

如果一開始就把 CONTINUUM、CodeGraph、JavaParser、Ollama 全部整合，最後即使失敗，也很難判斷究竟是哪個元件造成問題。

---

## 五、第一個 PoC 的真正驗證目標

我認為整個產品最重要的不是「AI 能不能分析 50 個 Java 檔案」。

而是以下這個情境：

> AI 已經花費兩個小時分析一套舊系統，完成 70% 的工作。此時 Python Process 被強制終止，電腦重新啟動，原始碼又有部分異動。重新啟動後，即使更換 AI 模型，也能辨識哪些成果仍然有效、哪些需要重做，最後接續完成原本的任務。

如果這個情境可以可靠通過，就已經驗證了產品最重要的技術核心。

**我的建議是先實作 POC-1，不急著整合 CONTINUUM。** 先建立一個獨立的 Python Runtime，定義 Task、Checkpoint、Evidence、Event 四種資料物件，跑通 Crash → Restart → Resume。接著再把 CONTINUUM 接進來，測試它能否取代或改善自研 Runtime 的恢復機制。

這樣才能客觀比較「採用 CONTINUUM」與「自行開發 Runtime」的成本、可靠性及可控性。

---

If you want, I can:

- 繼續詳述MVP設計架構
- 展開比較測試細節與計劃
- 闡述ISO 27001在Hash-chained Event Log中的具體應用限制

---

## 對話片段 21

7cf3e067-3d63-4798-8f75-02efa23aeab3

---

## 對話片段 22

e04b48b5-e3c1-44c0-804a-fb253401a77f

---

## 對話片段 23

10cbe8d2-5aa4-4b52-a8b5-0585499ef5fb

---

## 對話片段 24

is_visually_hidden_from_conversation

---

## 對話片段 25

bbb219dd-b2a1-4c6a-984d-ba98bb038ff9

---

## 對話片段 26

86ef326f-2eed-4f16-9263-d891a4249969

---

## 對話片段 27

這份修正與架構定案非常精準，完全切中企業級 AI 研發的核心痛點。特別是「避免同時使用 LangGraph 與 CONTINUUM 造成狀態衝突」以及「釐清 Hash-chaining 與 ISO 27001 合規性的本質區別」，這兩個觀點展現了資深架構師對系統複雜度與資安邊界的把關。
針對你提出的最終定案與技術驗證路徑，以下我從工程落地的角度做幾點呼應與補充：
一、 架構分層與自研界線的共識
你重新梳理的三層架構（User Application / Layer 2: Code Intelligence / Layer 1: Long Task Runtime + Persistence & Evidence）非常清晰。這樣的切分帶來兩個決定性的工程優勢：
 * 解耦「代碼理解」與「狀態恢復」：
   讓 CodeGraph / JavaParser 專心處理靜態分析與符號對應，讓 CONTINUUM（或自研 Runtime）專心處理 Checkpoint 與工作流。兩者透過統一的 SQLite Evidence Store 交換資料，互不干擾。
 * 聚焦核心差異化價值（Core IP）：
   我們不重複發明 Parser，也不一開始就自幹複雜的 Graph Engine；我們把精力投注在 Context Manager（上下文精簡與語意壓縮）、Analysis Pipeline（分析邏輯） 與 Evidence-backed Report Generator（可驗證的交接報告） 上。這才是讓資安稽核與架構師願意買單的關鍵能力。
二、 關於 ISO 27001 的審查現實
你提到的補充完全正確。在資安稽核中：
 * Hash-chained Event Log 解決的是 Integrity（完整性與防篡改） 的技術層面。
 * 但 ISO 27001 ISMS 認證更看重的是管理與防禦縱深（Defense-in-depth）：例如資料庫檔案的作業系統層級權限（ACL）、DB 帳號與應用程式帳號的分離、稽核日誌的不可抹除性（WORM 儲存或定時異地備份至唯讀區）、以及金鑰管理（Key Management）。
因此，在 MVP 階段，我們將 SQLite 視為「技術除錯與會話日誌（Execution Trace）」的儲存媒介，而不宣稱其直接等同於 ISO 27001 合規憑證；企業級合規則透過後續的「日誌匯出與唯讀封存機制」來滿足。
三、 執行「故障注入測試（Failure Injection Test）」的具體腳本規劃
你提出的七步驟測試非常棒，這正是驗證開源專案能否在地端小模型（如 Qwen 7B/14B）環境下存活的黃金試金石。我們可以直接把這套測試標準化為 MVP 的驗收標準：
| 測試階段 | 故障注入動作 | 預期系統行為 (Pass Criteria) |
|---|---|---|
| 1. 正常啟動 | 丟入 50 個 Java 舊系統檔案 | 成功透過 JavaParser / CodeGraph 建立本地 SQLite 索引 |
| 2. Context 瀕臨滿載 | 模擬 Prompt 達到模型極限（如 80%） | 觸發 Semantic Checkpoint，將已驗證事實寫入 DB，清空舊對話 |
| 3. 強制中斷 (Crash) | 在分析到第 25 個檔案時 kill -9 Python Process | 程式崩潰，但 SQLite 中的 Checkpoint 停在正確的斷點 |
| 4. 外部干擾 | 關閉 Ollama 服務 / 或手動修改已分析的某個 Java 檔 | 系統重啟後進行 Environment Revalidation，偵測到檔案 Hash 變更，標記 REQUIRES_REVIEW |
| 5. 恢復執行 (Resume) | 重新啟動服務，指定原 Task ID 繼續執行 | 系統自動略過已完成部分，修復受影響的檔案，從斷點接續 |
| 6. 模型切換 | 在恢復時將底層模型從 Qwen 7B 換成 14B | 藉由 Semantic Checkpoint 中的結構化事實（非肥大對話紀錄），新模型無縫接續任務 |
| 7. 驗證產出 | 任務完成後輸出交接報告 | 報告中每一個呼叫鏈與結論，皆附帶對應的原始碼檔案與行號證據 |
四、 下一步行動建議
按照你的思路，我們不寫冗長的文件，直接動手做 PoC 驗證：
 * 環境備妥：準備一包包含 30~50 個經典 Java 專案檔案（或直接拿開源的舊版 Java 專案如 BugNET 的部分原始碼）。
 * 組合測試：以 CONTINUUM 邏輯 + 本地 SQLite + CodeGraph/JavaParser + Ollama (Qwen) 搭建一個最小可行性腳本。
 * 跑一次上述的故障注入測試。

---

## 對話片段 28

0d69ee90-f20e-43be-970c-cf0330f510e4

---

## 對話片段 29

ab61c5e6-80ac-4e3f-a185-5cfe1d40ab69

---

## 對話片段 30

d4bda3ec-84d6-4553-adf9-565e78cc8b7d

---

## 對話片段 31

agentledger-runtime - npm Package Security Analysis - Socket

---

## 對話片段 32

AGENTLEDGER-RUNTIME  Runtime reliability layer for Agent Harness stacks.  latest  npm  [Button: Copy purl]  Image: Socket score  Version   1.5.4  Version published last month  Maintainers 1  Created...

---

## 對話片段 33

checkpointing · GitHub Topics · GitHub

---

## 對話片段 34

CYRAX321 / CONTINUUM  Sponsor  Star  * Code * Issues * Pull requests * Discussions  CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), a...

---

## 對話片段 35

CONTINUUM  mcp  Security Audit  Pass Aug 23, 2026  [Button: Current Data] [Button: AI Report]  Health Pass  * License â€” License: Apache-2.0 * Description â€” Repository has a description * Active r...

---

## 對話片段 36

agentledger-runtime 1.5.5 - Docs.rs

---

## 對話片段 37

AGENTLEDGER-RUNTIME 1.5.5  Runtime reliability layer for Agent Harness stacks   `# AgentLedger Rust Runtime  This directory contains the Rust runtime-core package for AgentLedger 1.5.5. The default fe...

---

## 對話片段 38

AGENTLEDGER-RUNTIME 1.5.5  Runtime reliability layer for Agent Harness stacks   Documentation  * Coverage * 0% 0 out of 734 items documented 0 out of 234 items with examples * Size * Source code size:...

---

## 對話片段 39

CONTINUUM  Favorite  Autonomous AgentsObservabilityDeveloper Tools  by Cyrax321  OverviewSchemaRelated ServersScoreDiscussions  Python  Hybrid  Which integrations are available for this server?  Prov...

---

## 對話片段 40

GitHub - Cyrax321/CONTINUUM: CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), an idempotent action ledger that refuses duplicate side effects, and a hash-chained tamper-evident event log, all exposed as a deny-by-default MCP server. Framework-agnostic, Python 3.11+ · GitHub

---

## 對話片段 41

ABOUT  In early 2026 I saw long running agents fail on recovery, not reasoning. Checkpoints were treated as proof to continue, not evidence to verify. Surveying Temporal, LangGraph, ACRFence 2603.206...

---

## 對話片段 42

AGENTLEDGER-RUNTIME  Runtime reliability layer for Agent Harness stacks  INSTALLATION  In a virtualenv (see these instructions if you need to create one):  pip3 install agentledger-runtime  DEPENDENC...

---

## 對話片段 43

AgentSearch — find AI agents and MCP servers

---

## 對話片段 44

Call-graph edges are mined from these.  Output  score: 0.23  Usability How easy to use: docs, license, auth, capabilities, repo.  Gates 3  `structured_spec`  mapping  * 1 if the agent exposes any stru...

---

## 對話片段 45

CONTINUUM — Open Source AI Project | AI Kendra

---

## 對話片段 46

MCP · OPEN SOURCE  CONTINUUM  CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), an idempotent action ledger that refuses duplicate sid...

---

## 對話片段 47

code-review-graph: Local Tree-sitter code graph served over MCP so agents read only affected files

---

## 對話片段 48

1. Home 2. Best of Vibe Coding 3. MCP servers for developers 4. code-review-graph  9TH OF 86 IN MCP SERVERS FOR DEVELOPERS  CODE-REVIEW-GRAPH  Local Tree-sitter code graph served over MCP so agents r...

---

## 對話片段 49

AGENTLEDGER  English | 中文  Image: Python 3.11+ Image: Version 1.3.x stable Image: License Apache-2.0 Image: Runtime Durable Image: Storage SQLite/Postgres/MySQL Image: Replay Evidence Image: Tool Led...

---

## 對話片段 50

AGENTLEDGER  English | 中文  Image: Python 3.11+ Image: Version 1.5.x stable Image: License Apache-2.0 Image: Runtime Durable Image: Storage SQLite/Postgres/MySQL Image: Replay Evidence Image: Tool Led...

---

## 對話片段 51

AGENTLEDGER  Image: Python 3.11+ Image: Version 1.6.x stable Image: License Apache-2.0 Image: Runtime Durable Image: Storage SQLite/Postgres/MySQL Image: Replay Evidence Image: Tool Ledger  Your agen...

---

## 對話片段 52

CodeGraph - Coding Documentation

---

## 對話片段 53

CODEGRAPH¶  Alternative code-graph backend: tree-sitter parsing into a SQLite/FTS5 index, served to agents over stdio MCP. Sits alongside graphify behind the backend registry, so which one agents use...

---

## 對話片段 54

Self-Improvement TUI P Rust★ 69MIT】7.9 sandbase-skills 88 installable open-source Agent Skills for research, social Python★ 59Apache-2.07.9 AgentDebugX A debugging framework for agentic AI systems: di...

---

## 對話片段 55

INTRODUCTION  CodeGraph is a local-first code-intelligence tool. It parses your codebase with tree-sitter, stores every symbol, edge, and file in a local SQLite database, and exposes the result as a...

---

## 對話片段 56

Cyrax321/CONTINUUM — Pragmatist agent rig · AgentRigs

---

## 對話片段 57

CYRAX321/CONTINUUM  CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), an idempotent action ledger that refuses duplicate side effects,...

---

## 對話片段 58

OpenClaw Desktop App Skills — Full A–Z Catalog (9,903)

---

## 對話片段 59

* Agentic Workflow Designer * Agentic X402 * AgenticCreed SignUP Lead * agentkey * AgentKeys * AgentLedger * Agentlens * AgentMail * AgentMail Integration * agentmail-cli * AgentMem * AgentMemory * Ag...

---

## 對話片段 60

Issues · Cyrax321/CONTINUUM · GitHub

---

## 對話片段 61

* ### Follow-up to #607: docstrings for recovery/engine.py decision surface  documentationImprovements or additions to documentationImprovements or additions to documentation  good first issueGood fo...

---

## 對話片段 62

GitHub - Cyrax321/Cyrax321 · GitHub

---

## 對話片段 63

* spps-linux-experiment-results - Cross platform validation on EPYC 7763 Ubuntu 24.04, 12006 of 12006 PASS * spps-experiments - ESA 2026 submission on Ryzen 5 7235HS, bijective O(n)  ENGINEERING - AGE...

---

## 對話片段 64

GitHub - Cyrax321/CONTINUUM: CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), an idempotent action ledger that refuses duplicate side effects, and a hash-chained tamper-evident event log, all exposed as a deny-by-default MCP server. Framework-agnostic, Python 3.11+. · GitHub

---

## 對話片段 65

Release tags additionally ship built wheels attached to GitHub Releases.  Paths that need no clone and no local install:  Path  | How --- | --- Install from PyPI  | `pip install continuum-agent==0.1.2...

---

## 對話片段 66

Persistence - Docs by LangChain

---

## 對話片段 67

PERSISTENCE  Copy page  LangGraph has a built-in persistence layer, implemented through checkpointers. When you compile a graph with a checkpointer, the checkpointer saves a `checkpoint` of the graph...

---

## 對話片段 68

CodeGraph — Code Knowledge Graph for Claude Code, Cursor & Codex | MCP Server

---

## 對話片段 69

MCP Server · Code Knowledge Graph · 100% Local  STOP BURNING TOKENS on grep loops  CodeGraph builds a pre-indexed code knowledge graph for Claude Code, Cursor, and Codex. AI agents query the graph in...

---

## 對話片段 70

CodeGraph — deterministic code knowledge graph · FirLab

---

## 對話片段 71

Product 02 · deterministic code knowledge graph  CODEGRAPH  tree-sitter → SQLite + FTS5 · CLI + MCP · no model  One index, three front doors. tree-sitter parses a repository into a project-level inde...

---

## 對話片段 72

CodeGraph: a local code knowledge graph for agents and people

---

## 對話片段 73

CODEGRAPH WHO CALLS THIS, AND WHAT DOES CHANGING IT REACH  CodeGraph parses a repository with tree-sitter into a local index of symbols, calls and imports, and answers structural questions from it, o...

---

## 對話片段 74

CodeGraph - Visual Studio Marketplace

---

## 對話片段 75

CODEGRAPH  Cross-language code intelligence for AI agents and developers. CodeGraph builds a semantic graph of your codebase — functions, classes, imports, call chains — and exposes it through 42 MCP...

---

## 對話片段 76

Get thread  cURL  `curl --request GET \ --url https://api.smith.langchain.com/v1/deepagents/threads/{threadID} \ --header 'X-Api-Key: <api-key>'`  200  401  403  404  502  `{}`  GET THREAD  Return th...

---

## 對話片段 77

Ainetwork toolkit integration - Docs by LangChain

---

## 對話片段 78

On this page  * Installing dependencies * Set environmental variables * Get AIN blockchain private key * Initialize the AINetwork toolkit * Initialize the Agent with the AINetwork toolkit * Example u...

---

## 對話片段 79

Read Shared Dataset Examples With Runs - Docs by LangChain

---

## 對話片段 80

"completion_token_details": {}, "total_cost": "<string>", "prompt_cost": "<string>", "completion_cost": "<string>", "prompt_cost_details": {}, "completion_cost_details": {}, "price_model_id": "3c90c3c...

---

## 對話片段 81

Bittensor integration - Docs by LangChain

---

## 對話片段 82

sys_resp = llm_sys( "What is bittensor and What are the potential benefits of decentralized AI?" ) print(f"Response provided by LLM with system prompt set is : {sys_resp}")  THE TOP_RESPONSES PARAMETE...

---

## 對話片段 83

PROFILES  Package per-provider and per-model defaults that Deep Agents applies when a model is selected  Copy page  > ## Documentation Index > > Fetch the complete documentation index at: https://doc...

---

## 對話片段 84

Airbyte typeform (Deprecated) integration - Docs by LangChain

---

## 對話片段 85

)  # check the documentation linked above for a list of all streams `  Now you can load documents the usual way  `docs = loader.load() `  As `load` returns a list, it will block until all documents ar...

---

## 對話片段 86

Update a thread - Docs by LangChain

---

## 對話片段 87

Error  `A valid request URL is required to generate request examples`  200  400  401  403  404  422  502  `{ "agent_id": "3c90c3cc-0d44-4b50-8888-8dd25736052a", "created_at": "2023-11-07T05:31:56Z",...

---

## 對話片段 88

Deep Agents overview - Docs by LangChain

---

## 對話片段 89

* Persist memory across conversations and threads * Control filesystem access with declarative permission rules that restrict which files agents can read or write * Require human approval for sensitiv...

---

## 對話片段 90

Manage assistants - Docs by LangChain

---

## 對話片段 91

MANAGE ASSISTANTS  Copy page  This page describes how to create, configure, and manage assistants. Assistants allow you to customize your deployed graph’s behavior through configuration—such as model...

---

## 對話片段 92

LEARN  Tutorials, conceptual guides, and resources to help you get started.  Copy page  In the Learn section of the documentation, you’ll find a collection of tutorials, conceptual overviews, and add...

---

## 對話片段 93

LangSmith data plane - Docs by LangChain

---

## 對話片段 94

ON THIS PAGE  * Server infrastructure * ”Listener” application * PostgreSQL * Redis * Communication * Ephemeral metadata * Data plane features * Data region * Autoscaling * Static IP addresses * Payl...

---

## 對話片段 95

Thinking in LangGraph - Docs by LangChain

---

## 對話片段 96

ON THIS PAGE  * Start with the process you want to automate * Step 1: Map out your workflow as discrete steps * Step 2: Identify what each step needs to do * LLM steps * Data steps * Action steps * U...

---

## 對話片段 97

memory | langgraph.checkpoint | LangChain Reference

---

## 對話片段 98

Pythonlanggraph.checkpointmemory  Module v4.3.1 (latest)●Since v1.0  MEMORY  ATTRIBUTES  attribute WRITES_IDX_MAP: dictattribute ChannelVersions: dict[str, str | int | float]attribute PendingWrite: t...

---

## 對話片段 99

langgraph.checkpoint | LangChain Reference

---

## 對話片段 100

Python langgraph.checkpoint  LANGGRAPH.CHECKPOINT  DESCRIPTION  LANGGRAPH CHECKPOINT  To help you ship LangGraph apps to production faster, check out LangSmith. LangSmith is a unified developer platf...

---

## 對話片段 101

checkpoints | langgraph | LangChain Reference

---

## 對話片段 102

PythonlanggraphCheckpointing  CHECKPOINTING  Checkpoints allow LangGraph agents to persist their state within and across multiple interactions. A checkpoint is a snapshot of the graph state at a give...

---

## 對話片段 103

How do I configure checkpointing in LangGraph?

---

## 對話片段 104

HOW DO I CONFIGURE CHECKPOINTING IN LANGGRAPH?  Last updated: October 9, 2025  CONTEXT  When using LangGraph, customers need to understand how to properly configure checkpointing to manage thread sta...

---

## 對話片段 105

INTERRUPTS  Copy page Copy page  Interrupts allow you to pause graph execution at specific points and wait for external input before continuing. This enables human-in-the-loop patterns where you need...

---

## 對話片段 106

* `thread_id` is your pointer: set `config={"configurable": {"thread_id": ...}}` to tell the checkpointer which state to load. * Interrupt payloads surface via `stream.interrupts`: when using event st...

---

## 對話片段 107

PERSISTENCE  LangGraph’s persistence layer gives agents short-term memory through checkpointers and long-term memory through stores.  Copy page Copy page  Persistence lets LangGraph applications keep...

---

## 對話片段 108

Security-review answers - Understand Documentation

---

## 對話片段 109

SECURITY-REVIEW ANSWERS¶  Reusable answers for security questionnaires and vendor assessments. They are deliberately conservative and point at the source behind each claim.  Confirm against your rele...

---

## 對話片段 110

Check out an offline license - Understand Documentation

---

## 對話片段 111

CHECK OUT AN OFFLINE LICENSE¶  Use this to run Understand on a machine with no internet access. You generate a request code in Understand, exchange it for a reply code at scitools.com/offline, and en...

---

## 對話片段 112

Create an offline install kit for Windows - Understand Documentation

---

## 對話片段 113

CREATE AN OFFLINE INSTALL KIT FOR WINDOWS¶  The Windows installer is a small download that fetches the components you select, so it needs internet while it runs. For machines that have none — air-gap...

---

## 對話片段 114

What leaves the machine? (network & telemetry) - Understand Documentation

---

## 對話片段 115

WHAT LEAVES THE MACHINE? (NETWORK & TELEMETRY)¶  This page lists every network destination Understand can contact, what triggers each, and how to disable it. It is written for firewall/egress reviews...

---

## 對話片段 116

Local AI with no cloud calls - Understand Documentation

---

## 對話片段 117

LOCAL AI WITH NO CLOUD CALLS¶  Understand's AI runs entirely on your own hardware by default — no code or prompts leave the machine unless you deliberately switch to a cloud provider. This page is th...

---

## 對話片段 118

What can the AI access? (permissions & privacy) - Understand Documentation

---

## 對話片段 119

Understand Documentation  What can the AI access? (permissions & privacy)  WHAT CAN THE AI ACCESS? (PERMISSIONS & PRIVACY)¶  This page is for anyone who has to answer "is it safe to turn this on?" —...

---

## 對話片段 120

Download Understand & create an account - Understand Documentation

---

## 對話片段 121

DOWNLOAD UNDERSTAND & CREATE AN ACCOUNT¶  1. CREATE A SCITOOLS ACCOUNT¶  Go to licensing.scitools.com and, if you don't have one, Register (name, email, password). Your licenses and claim codes live...

---

## 對話片段 122

Understand: Static Code Analysis Tool — SciTools

---

## 對話片段 123

Understand by SciTools  EVERY CHANGE STARTS WITH “WHAT CALLS THIS?” “WHAT BREAKS?” “WHO OWNS THIS?” “IS IT COMPLIANT?”  Understand brings code exploration, graphs, metrics, standards checking, and AI...

---

## 對話片段 124

Understand AI — overview & setup - Understand Documentation

---

## 對話片段 125

UNDERSTAND AI — OVERVIEW & SETUP¶  Understand AI adds LLM-generated explanations and chat on top of Understand's exact, parsed model of your code. By default it runs a local model entirely on your ma...

---

## 對話片段 126

Install Understand - Understand Documentation

---

## 對話片段 127

INSTALL UNDERSTAND¶  First download Understand and create an account — the latest build is always at licensing.scitools.com/download (supported OS versions are listed under supported languages & plat...

---

## 對話片段 128

Choose a model (local LLM or cloud provider) - Understand Documentation

---

## 對話片段 129

Choose a model (local LLM or cloud provider)  CHOOSE A MODEL (LOCAL LLM OR CLOUD PROVIDER)¶  Understand ships with several local models and can also use external runtimes (LM Studio, Ollama) or cloud...

---

## 對話片段 130

Understand Documentation - Understand Documentation

---

## 對話片段 131

UNDERSTAND DOCUMENTATION¶  Understand helps you read, navigate, measure, and check large or unfamiliar codebases across 17+ languages. These docs are organized around what you're trying to do — not t...

---

## 對話片段 132

GitHub - LordCasser/atlas: Atlas — a local-first semantic code graph engine. Tree-sitter parses 15 languages into deterministic facts; CLI and MCP tools expose symbol search, call graphs, dataflow tracing, and barrel re-export resolution for AI agents. · GitHub

---

## 對話片段 133

ATLAS  Local-first semantic knowledge graph engine for LLM agents.  Atlas parses source code with tree-sitter, stores deterministic code facts in a local SQLite database, and exposes those facts throu...

---

## 對話片段 134

GitHub - aryansahni30/AgentLedger · GitHub

---

## 對話片段 135

AGENTLEDGER  The trust layer for AI coding agents.  Claude says done. AgentLedger checks — against real exit codes, not self-reports.  Website · Quickstart · How it works · Claude Code plugin · CLI ·...

---

## 對話片段 136

AgentLedger/docs/zh/HARNESS_STACK.md at main · yaogdu/AgentLedger · GitHub

---

## 對話片段 137

AGENT HARNESS STACK  AgentLedger 是面向 Agent Harness stack 的 runtime reliability layer。它本身不是完整 Harness 产品；当它和 planning、orchestration、observability、model access、tools、storage、sandbox infrastructure 等系统组...

---

## 對話片段 138

GitHub - chi-ga/CONTINUUM-1: CONTINUUM: Verifiable semantic recovery for long-running AI agents. Semantic checkpoints (not conversation dumps), an idempotent action ledger that refuses duplicate side effects, and a hash-chained tamper-evident event log, all exposed as a deny-by-default MCP server. Framework-agnostic, Python 3.11+. · GitHub

---

## 對話片段 139

chi-ga / CONTINUUM-1 Public  forked from Cyrax321/CONTINUUM  LATEST COMMIT    HISTORY  155 Commits  FOLDERS AND FILES  Name  | Name  | Last commit message  | Last commit date --- | --- | --- | --- .g...

---

## 對話片段 140

GitHub - denzharkov/codegraph-mcp: Local MCP server for Claude Code: tree-sitter repo graph, semantic code search, impact analysis. Pure JS + WASM, no native builds. · GitHub

---

## 對話片段 141

CODEGRAPH-MCP  Local MCP server that gives Claude Code (CLI and the VS Code extension) a queryable model of your codebase — where things are defined, who calls what, what depends on what, and what wa...

---

## 對話片段 142

GitHub - compass-soul/codegraph: Local code dependency graph CLI. tree-sitter + SQLite. File and function-level tracing. No network calls. · GitHub

---

## 對話片段 143

CODEGRAPH  Local code dependency graph CLI. Parse codebases with tree-sitter, store in SQLite, query at file and function level. No network calls, no data leaves your machine.  INSTALL  git clone htt...

---

## 對話片段 144

GitHub - suatkocar/codegraph: Codebase intelligence MCP server — semantic code graph with vector search. Native Rust, 32 languages, 44 tools, sub-second indexing. · GitHub

---

## 對話片段 145

ARCHITECTURE  `Source Files ──→ tree-sitter ──→ Extractor ──→ SQLite DB (32 langs)     (native parse)   (nodes+edges)   ├── FTS5 (keyword index) ├── sqlite-vec (vector index) └── edges (graph structu...

---

## 對話片段 146

GitHub - sunerpy/codegraph-rust: Deterministic tree-sitter + SQLite/FTS5 code knowledge graph for coding agents and developers: symbols, calls and dependencies over a CLI, an MCP server and a local browser viewer. No AI/LLM inside; byte-stable output. · GitHub

---

## 對話片段 147

sunerpy / codegraph-rust Public  * Notifications You must be signed in to change notification settings * Fork 1 * Star  * Code * Issues 1 * Pull requests 1 * Actions * Projects * Security and quality...

---

## 對話片段 148

AgentLedger/README.zh-CN.md at main · yaogdu/AgentLedger · GitHub

---

## 對話片段 149

AGENTLEDGER  English | 中文  你的 Agent 调用了一个工具。它真的执行了吗？可以安全重试吗？之后能证明发生过什么吗？  AgentLedger `1.5.x` 是面向 Agent Harness stack 的 runtime reliability layer。它不负责让 Agent 更会“思考”，也不替代完整 Harness 生态；它负责让 Agent run 在...

---

## 對話片段 150

GitHub - lzehrung/codegraph: CLI, MCP and TS library that turns source code into a map of files, symbols, references, and dependencies using tree-sitter. · GitHub

---

## 對話片段 151

CODEGRAPH  Give your coding agent a map of the repository, not a pile of search results.  codegraph is a local CLI and TypeScript library that turns a source tree into a resolved map of files, symbol...

---

## 對話片段 152

GitHub - isink17/codegraph: codegraph is a local-first code context engine and MCP server for repositories. It incrementally indexes source code into a lightweight SQLite graph, then exposes high-signal tools for symbol lookup, call graph traversal, impact analysis, related test discovery, and semantic search. Built in Go with a single-binary workflow, it is designed to work · GitHub

---

## 對話片段 153

codegraph  Public  * NotificationsYou must be signed in to change notification settings * Fork1 (1) * Star4 (4)You must be signed in to star a repository  ABOUT  codegraph is a local-first code conte...

---

## 對話片段 154

docs/src/oss/langgraph/checkpointers.mdx at main · langchain-ai/docs · GitHub

---

## 對話片段 155

title  | Checkpointers description  | LangGraph checkpointers save graph state as checkpoints at each step, enabling persistence, human-in-the-loop, and fault-tolerant execution. keywords  | checkpoi...

---

## 對話片段 156

GitHub - yaogdu/AgentLedger: Durable execution and reliability runtime for production AI agents. · GitHub

---

## 對話片段 157

AGENTLEDGER  English | 中文  Your agent called a tool. Did it happen? Can you retry safely? Can you prove it later?  AgentLedger `1.5.x` is a runtime reliability layer for Agent Harness stacks. It does...

---

## 對話片段 158

langgraphjs/docs/docs/concepts/persistence.md at main · langchain-ai/langgraphjs · GitHub

---

## 對話片段 159

PERSISTENCE  LangGraph has a built-in persistence layer, implemented through checkpointers. When you compile graph with a checkpointer, the checkpointer saves a `checkpoint` of the graph state at eve...

---

## 對話片段 160

docs/src/oss/langgraph/persistence.mdx at main · langchain-ai/docs · GitHub

---

## 對話片段 161

title  | Persistence description  | LangGraph's persistence layer gives agents short-term memory through checkpointers and long-term memory through stores.  {/* Anchor stubs for backwards-compatible...

---

## 對話片段 162

Cyrax321 / CONTINUUM Public  * ### Uh oh!  There was an error while loading. Please reload this page.  * Notifications You must be signed in to change notification settings * Fork 47 * Star  * Code *...

---

## 對話片段 163

Install & run Understand fully offline - Understand Documentation

---

## 對話片段 164

([Colby McHenry](https://colbymchenry.github.io/codegraph/core-concepts/how-it-works/?utm_source=chatgpt.com))

---

## 對話片段 165

urlCodeGraph - colbymchenryturn912289search10

---

## 對話片段 166

[CodeGraph - colbymchenry](https://colbymchenry.github.io/codegraph/getting-started/introduction/?utm_source=chatgpt.com)

---

## 對話片段 167

urlCodeGraph - isink17turn912289search3

---

## 對話片段 168

[CodeGraph - isink17](https://github.com/isink17/codegraph?utm_source=chatgpt.com)

---

## 對話片段 169

image_group{"query":["CodeGraph tree sitter SQLite MCP code intelligence architecture","CodeGraph MCP call graph visualization"]}

---

## 對話片段 170

![Image](https://images.openai.com/static-rsc-4/dOiVqQiTnmtBDr58zOIujWyKsRtDHPOBDusQ3xeP9z3OW41S2YDjdj1fPnQBDh04hb0dXyfANv9RWzL7Yj9aFFM9GtuZR0dGZT_W2sjFxLNvfrGS1SoG33NEM6aNFpVxycYl6C9Spz6B36juOPv9Y9yhk3bbNRrhCahA5-krNQNvGWeup4oRJ20h7i7PkEhM?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/toOAe4aMMYhPUi0KwjqnNjDts3sYZNmAHdXTRi34RwDowY0FYRarXrNXG38QN7Pew8_jxTQ9XIMeYhzpFm00xb_nNsTwJn1MrgBjVCw_TNnwqL04rqqyg7pcunkJN4jTmpmsNitR2e-Mp7qD19TrXNhDUx9kVppey3fD7h0hVT2_z8Ib_bYqLpAfwbkoM8aM?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/iom19Qomb6i_AA1DKlGg4FPbj9AxJ_Rpam13DPoBE_m6GVnH5kd0uCgO3L3e3_n2kmFUGe1WIG8hCAIzz8vWdVt0tE1INE-L4fEANxVTsk9-r_yf1fgN9JIEv3fuhjBYXXEY_xicgXqfcENH5HOGzrjNtN9F0rs6xUK3kntzQuo4udQwkU6WkejIuge7tAc0?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/t6N8VFZfBvxMKPbCHaQqz8uXGiB5vIm7uV41OPQK_tcTtzkCNzGPGGbaGUImhaDlfnhXojji2iwMHMjSRZlVwQI9Zi8C5WfBtPWjcQk4tkR0dYnOTL3Ncap8MoBw0LqU7bHr96mdqkZyjq7-se-kF5APhQ0f7plZX6d-859ByjYKboDYOqWgAyr2vgaHNFXO?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/UDlIxdIDkiCh7iANB0QoKAA5ncfbmFzlyQ4K9DldI84mzkWZ3PIMVD69inCP1NwZHdlJsksRTtf4AByCSJzOmQRRgJXQWKOePRwyxGxQf3Qj9v1fPAglDqfkhJIXMGxadIG4aGd1mHTvRlM-tZfOK8IpZFZFXCdYLZrLTCuvF2I1D1ENFbf50dOseKOc3QW9?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/Koyk7pWX6Hfwvnsfp80ErAcqOK-7Tg2oHs8Cqt3RxCQxdBAcc376zDndKcTUDrR7wAtF1XTu8g4fdlV3Xl7Z5Vi6zv34SgtLE0JUN8wpLiX0cqzGYBwbL2nJ8SAvLA2BKf_7TTxrp6pZcSAgzcuBX_z-GMlNR_CtgxKSH0DBcNQq5RRZvxFopfrq3c2QZHCu?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ry9pbcU7yDDErXPxuxxNzonpJM-uikv7dajAPqmBGUZOE970Bm9XR5kPTVBQnlzFPbTibv2sXoARs2eeanD_t-AWaS0qhVEoOeOmvTHPSAg4eU3MK2rx2ov25X7oWqPjKadfY9OP1w9vjOZ8EvjjXjVfFqNCsKgnSYoPnCE2cZs5kiwUU7SBA6MZ79OLzSD-?purpose=fullsize)

---

## 對話片段 171

CodeGraph MCP call graph visualization

---

## 對話片段 172

Introducing MCP Code Graph by JudiniLabs | Ivan Djordjevic posted on the topic | LinkedIn

---

## 對話片段 173

CodeGraph实战：让Claude Code省94% token的本地代码搜索神器，Windows小白也能装 - Law's Blog

---

## 對話片段 174

CodeGraph tree sitter SQLite MCP code intelligence architecture

---

## 對話片段 175

Coding specifically for the Love of Structure: Why I Built CodeGraphContext | by Shashank shekhar singh | Feb, 2026 | Medium

---

## 對話片段 176

Guía de CodeGraph: Grafo de conocimiento de código local para agentes de IA

---

## 對話片段 177

Почему Claude Code пропускает правки? Ответ — CodeGraph | ZavCloud

---

## 對話片段 178

CodeGraph: AI 코딩 에이전트를 위한 사전 인덱싱 코드 지식 그래프 - 읽을거리&정보공유 - PyTorchKR

---

## 對話片段 179

citeturn912289search0turn912289search2turn912289search8

---

## 對話片段 180

([ai-docs.scitools.com](https://ai-docs.scitools.com/help/air-gapped/install-offline.html?utm_source=chatgpt.com))

---

## 對話片段 181

urlSciTools Understandhttps://scitools.com/understand

---

## 對話片段 182

[SciTools Understand](https://scitools.com/understand?utm_source=chatgpt.com)

---

## 對話片段 183

image_group{"query":["SciTools Understand code architecture graph","SciTools Understand local AI code analysis interface"]}

---

## 對話片段 184

![Image](https://images.openai.com/static-rsc-4/KWyGElydVCjclB91e056lIVkh-1dfkgEFMTklWWcOJPB_eu49YFmVl-rbX9k8Al53OFLiwt6GzzFEMT6bcR1vW_aSrSV-InTK8j435mSM_xS0Rcr81E1A4pUffuqksQPJmEW6TE-Gs2GBlwVql_IG9Nbijt5m5DH9o2dnGi-NZK-eisEwOdUZptClBLs6KKk?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/9cS-asv-eTyOFChFDccg4z_UWlAifS1TZGOyNO_ZRYcgXSBBMMDJrrzRPneJJi7X2kQbUPZO-78bfLPqasAKjcifvDRfZB4IMCtmxTGmdmJzXtPU9ZC1fqzFRNYdX2ffMMe3f2c2UzFO2Xnl-ffSu_Ol8d7XuHGemeCqlriO6ukVVkUF2Q7ADkFcBsFcj79H?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/TRaB9CiiHuFUGPSYjGwR2fjprUra8zxmnZG3tdPHPeY_Wm4hjJvvTrv132Nb8nM0Gd6sRt26cwsAWe1FiU8eWKhIqvhLjYnPj4OxiSlOSIbIKc-D_-E3pFK0pcNjAMlcNpN4X9iV-Q4he163qef6mjcf7YL94tWIuZQogTOQ9sLsB5f3I3qR--ePnvDma34D?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ID9-mo28_5ft3RfF0dLTxYeHnJnHCe83mQIknBVplMXmozmnqt8Ttxmk4nVlyx8jml9ahIwmlsuJVgGTxw-jwXZ0WUamhNh0AEUufc8IJnAhGmau0oJHOzHtzc-_C5Hci70lTFNfL-X-87f1otlU3HizfbVLrh0Mon8__rt1CWajdCLX29TAJPNs9MomSao6?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/VETagXM2IIDAm6qgJN7VeLzaH1DMHnFeuC7ycEe0dkMa2f3yqm1cEycQLa-iAad6S-EbvogxFZVgjlBytT8o88rBLEX76RTzcAicxQP7w7TQiDTgakY1OaeTQ7eEfpxqHkJ2u2dYIhCMK9qMe_dIUPitNgHYdF1HPe-wAXAuXTv_bjsJ_6ViX-x-UW6G9FyE?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/IdxY_1ieWirDMcWbvXO_aQREIinfYv2E_h4qQyPWt8aC1hGXl_Jg3dGeTUinSiZfDyCJjXuFG-Wav1W3W5coTqGYUnwzsy51hn9KavaqJC91wFBPEETKCEwLulHQ1i7IBXKcddtnVkYwsgTKZx952Vx4VDf0WReoFSSt0IaXAo3FR8hmQ9MxT8Kgtv19A4si?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/l9Mq-T4HtUQP08t8Y4rCPbbx5oWm6FBB-z3lceNNKvF8z-eCsP1dVBv4nvPmcHPeoCpDGYSG19e10E1h4W-chRgNGNT_ryxH3p-lE3NuvOoQAg9IrGy_IkopT1YYiyYPKtbcBdnM2ICoUWFvuu9f8xhfLeUCz36p1hGgvT-AbmHL_c0tQln34NLtUN66nvWE?purpose=fullsize)

---

## 對話片段 185

SciTools Understand code architecture graph

---

## 對話片段 186

グラフィカルビュー | ソースコード解析ツール Understand | テクマトリックス株式会社

---

## 對話片段 187

SciTools Understand local AI code analysis interface

---

## 對話片段 188

CodeCheck | Understand by SciTools

---

## 對話片段 189

Spaghetti Code | Understand by SciTools

---

## 對話片段 190

Inherited Code | Understand by SciTools

---

## 對話片段 191

Understand by SciTools | Graphs

---

## 對話片段 192

Understand - Download - Softpedia

---

## 對話片段 193

Understand: The Software Developer's Multi-Tool

---

## 對話片段 194

urlCONTINUUM GitHub 原始碼turn584005search0

---

## 對話片段 195

[CONTINUUM GitHub 原始碼](https://github.com/Cyrax321/CONTINUUM?utm_source=chatgpt.com)

---

## 對話片段 196

image_group{"query":["CONTINUUM semantic checkpoint agent recovery architecture","CONTINUUM GitHub semantic recovery dashboard"]}

---

## 對話片段 197

![Image](https://images.openai.com/static-rsc-4/tR_xuMf9Ef6e2g2Cn17RTNp1Qug-Ph9xYMKgYlAUZdzGoS7Ld5RSfIFlyQKpV-pzQBJvwLFW3VcZ7YhE_vEjhLf5E7j7kJ-kN-N2MgVMTlcORkBRPwvgL09onFbWHbGZFA-22CKfGagqmNY9DeHIQuqw7B8V85gy5KExZSX0Iav_lV7MIyKUGDfJFLQmyBO2?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/4RHTyiM5TzbnkjJjlWm9mD4gQ-d6i_TmlndqcLqTT3raz3jmboaO3L10h7PvmbItWOVpEY9nyLfLm3E4okt3HWzFEssfWpigWUFWBG3LA2m8mjDlewQ1eWdnl7udxgC7G2k0am_95xiyTbULby_-gzJgMWAYxi8tz0EPX1VQW7zvPF6Iu4cXxLmv6WH6PCBK?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/jolOX7padYNqrY86A_lzQayzUxQ58R9ZXh0Y97uLONghOtudLm-m9EQwkkMUG2xrbneZonp0mF5dMl7FkMCWTfADO04veNGHIZ29SlN2vN0afFEmQu09cxNlTyEyJbyDqiyo4IN5H0wpfSixaFkZsgqxjJeZ_yY1HW_9uxrw9xb-zu7tks91vvATlNoIOO_c?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ucfrWZR8xymvpiGoXBi2Hh_rCQG0hpz3YxGFQAv7D5V9RyHRSDAuIaJgs38XBiF2h7ZW9evN5vCu-YET7vSRLb-m3bBLdFdlRw038Q7dlmgWTH5gjF_w5YQ7NTxJ_4jfOPRyuKO-fNhak-_NXIoV-qTn1XifrIVU7uWlL1T0FiCzQ4suF8wPA8hqQiAaMvNw?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/Y7lbz2zkJ_Jl1fcR4sxMmDnexxj4Qxrv7wDZpgC_u_Jg4_amZf67IRa9-yqDolpMznbqr2nXzP3aQji45IoUMyn5O0B6nLKNzlI2G189o3yHTYpki4mL8yLQWFjBeuOymDENTFXAlyPPEkW9gTYsz9bcqI4xcchaPVC6e8A5mqY_LHZ9VKteh6ZpgWgYbiEh?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/o1cJyuoU7QRbTmvaZceH1sTbjlw_ohyhScXe6tq-JwrL7PU0DOZojsEqBvcyHQU0q5SpQNl326jSsQW4H5KrYb1nxG59geq2CYLtlHDxXHVhLFDYeJm-HZ5QMYyP-h9ryoaXpfH9WgPLr2MPt8nU72L_wzAJgMOR0ZkvGlMfb-gM59zYdL-A0dW5qsFG0HRy?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/285dO-IMyjmRnTKEm3UjgjuXxVrQvVq785cETU1i8_Jmav85KV8UARMbzSCAIqZ2GD4Ww8NW02lgdVl7qfD60Q1reoxwhaQI3TqqyLQmaWUwrsFham_xuOdpIeZ8DtYyYNxRrxrJ2bsAlY_4X9NHTG6_TrWQtDCnn8FbkKCYAO-v0FDNFXOZsDhrHA4JJEoj?purpose=fullsize)

---

## 對話片段 198

CONTINUUM semantic checkpoint agent recovery architecture

---

## 對話片段 199

Breaking Ground with AIOS: Introducing the LLM Agent Operating System | by Shawn Chumbar | Medium

---

## 對話片段 200

CONTINUUM GitHub semantic recovery dashboard

---

## 對話片段 201

GitHub - elara-labs/code-context-engine: Save 94% on AI coding tokens. Index your codebase, agents search instead of reading files. Works with Claude Code, Codex, Copilot, Cursor, Gemini CLI. Local MCP server, free, open source. · GitHub

---

## 對話片段 202

LangGraph Checkpointing Ensures Reliable Agentic AI Execution | Karan Singh posted on the topic | LinkedIn

---

## 對話片段 203

Main Agent loop + checkpoint-writer subagent 原理图

---

## 對話片段 204

Why Agent State Breaks: Threads and Checkpointers

---

## 對話片段 205

Dive into Claude Code: The Design Space of Today's and Future AI Agent Systems | alphaXiv

---

## 對話片段 206

5 Agent Design Patterns for Long-Running AI Agents | by Ana Bildea, PhD | Agentic Builders | Apr, 2026 | Medium

---

## 對話片段 207

urlLangGraph Persistencehttps://docs.langchain.com/oss/python/langgraph/persistence

---

## 對話片段 208

[LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence?utm_source=chatgpt.com)

---

## 對話片段 209

這份報告已經相當接近可以進入 MVP 技術選型的階段。不過，我仔細檢視後，有幾個地方我認為需要修正，尤其是 **CONTINUUM 的成熟度、AgentLedger 的專案可信度，以及 LangGraph 的 Checkpoint 機制**。

我的整體判斷是：

**這五個專案不應該全部拿來當成競品，有些應該直接採用，有些應該借鏡，有些則應該先做技術驗證。**

我從產品定位、技術可行性及 MVP 開發成本三個角度評估。

# 一、五個專案的重新評估

| 專案 | 我的評價 | 建議處理方式 |
|---|---|---|
| LangGraph | 成熟的 Workflow / Agent 框架 | 研究機制，評估是否採用 |
| AgentLedger | 需要先確認專案來源及實際程式碼 | 暫不列為核心依賴 |
| CONTINUUM | 與長任務恢復目標高度吻合 | 優先實際測試 |
| SciTools Understand | 成熟的商業程式分析工具 | 作為競品及分析能力標竿 |
| CodeGraph | 符合地端程式知識索引需求 | 優先整合測試 |

## 1. LangGraph：不需要自己重新發明 Workflow Engine

它最有價值的地方是：

- State Graph
- Node 執行管理
- Checkpoint
- Thread Persistence
- Interrupt / Resume

但報告中有一點需要修正：

LangGraph 並不是預設一定保存全量 Prompt 與所有歷史訊息。

它保存什麼，取決於開發者定義的 State Schema。

如果 State 裡面放了完整 messages，才會有 Context 膨脹問題。

因此，我建議不要直接否定 LangGraph，而是研究它的狀態持久化機制，再決定是否採用。

官方文件：urlLangGraph Persistencehttps://docs.langchain.com/oss/python/langgraph/persistence

## 2. AgentLedger：暫時不要列為正式技術依賴

這裡我要特別提醒。

目前我沒有找到足以確認先前所描述的 AgentLedger 專案及其完整功能的可靠官方證據。

因此，之前報告所提到的：

- Causal Action Ledger
- Financial / Action Vault
- Replay
- Hash Verification

不能直接當成已經確認的產品功能。

建議先取得確切 GitHub Repository，再做原始碼審查。

在此之前，可以把這些功能列為我們自己的技術需求，而不是依賴一個尚未確認的專案。

## 3. CONTINUUM：目前最值得深入測試

image_group{"query":["CONTINUUM semantic checkpoint agent recovery architecture","CONTINUUM GitHub semantic recovery dashboard"]}

urlCONTINUUM GitHub 原始碼turn584005search0

這個專案確實存在，而且與我們的核心需求相當接近。

它有幾個值得研究的機制：

| 功能 | 對我們的價值 |
|---|---|
| Semantic Checkpoint | 不需要保存整段對話 |
| SQLite Persistence | 本地任務持久化 |
| Hash-chained Event Log | 執行紀錄完整性驗證 |
| Environment Revalidation | 恢復前檢查環境變化 |
| Idempotent Action Ledger | 避免重複執行副作用 |
| Model Switch | 不同模型接續任務 |
| MCP Server | 與其他 Agent 整合 |

但有兩個重要限制。

**第一：專案自我測試不等於獨立生產環境驗證。**

README 公開了大量測試及 Benchmark 結果，但仍需要我們自己執行 Crash Recovery 測試。

**第二：語意 Checkpoint 不等於完整工作成果。**

例如：

```text
已完成：
OrderServlet 分析

待處理：
PaymentService 分析
```

這還不夠。

必須同時保存分析結果、程式碼版本、證據位置及未完成步驟。

否則恢復後仍可能需要重新分析。

我的建議：把 CONTINUUM 列為第一優先技術驗證對象。

## 4. SciTools Understand：最重要的商業競品

image_group{"query":["SciTools Understand code architecture graph","SciTools Understand local AI code analysis interface"]}

urlSciTools Understandhttps://scitools.com/understand

這個產品值得認真研究。

它已經提供：

- 程式結構解析
- Call Graph
- Cross Reference
- Dependency Analysis
- Code Metrics
- Python API
- CLI
- Local AI

而且官方文件已明確提供完全離線安裝及執行方式，也支援本地模型與 Ollama。citeturn912289search0turn912289search2turn912289search8

這代表我們不能把「地端 AI 程式碼分析」當成唯一的差異化。

真正需要區隔的是：

Understand 偏向程式碼理解與靜態分析。

我們則希望進一步做到：

**把分析工作變成可以持續數小時、跨 Session、跨模型、能中斷恢復的自主任務。**

## 5. CodeGraph：非常適合放進 MVP

image_group{"query":["CodeGraph tree sitter SQLite MCP code intelligence architecture","CodeGraph MCP call graph visualization"]}

這裡需要注意，CodeGraph 是多個不同專案使用的名稱，必須確認具體 Repository。

例如目前找到的：

urlCodeGraph - isink17turn912289search3

它具備：

- Tree-sitter
- SQLite
- Symbol Index
- Call Graph
- Dependency Graph
- Incremental Indexing
- MCP
- Hybrid Search

另一個版本：

urlCodeGraph - colbymchenryturn912289search10

也採用 Tree-sitter + SQLite + MCP 的設計。citeturn912289search12

這類工具非常符合我們的需求。

因為小模型不需要自己讀取 100 個檔案來找出 Method 關係。

它只需要呼叫：

```text
find_symbol()
find_callers()
find_callees()
find_dependencies()
get_source()
get_impact()
```

就可以取得必要的程式資訊。

不過要注意：Tree-sitter 能解析語法，不代表一定能正確解析 Java 的完整型別及動態呼叫關係。這部分仍需要實際驗證。

---

# 二、我建議調整原本的雙層架構

原本的設計是：

```text
Layer 2：Code Intelligence
Layer 1：Long-running Runtime
```

我認為還需要一個獨立的 Evidence / Knowledge 儲存概念。

不一定要變成第三層，但必須明確區分三種資料：

1. 程式碼事實。
2. AI 分析結論。
3. 任務執行狀態。

建議架構如下：

```text
┌─────────────────────────────────────────┐
│           User Application              │
│                                         │
│     專案分析 / 交接文件 / 影響分析        │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│       Layer 2：Code Intelligence         │
│                                         │
│  Code Scanner                           │
│       │                                 │
│  CodeGraph / JavaParser                 │
│       │                                 │
│  Symbol / Call Graph / SQL              │
│       │                                 │
│  Analysis Pipeline                      │
│       │                                 │
│  Ollama / Qwen                           │
│       │                                 │
│  Report Generator                       │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│       Layer 1：Long Task Runtime         │
│                                         │
│  Task Orchestrator                      │
│       │                                 │
│  Semantic Checkpoint                    │
│       │                                 │
│  Recovery Manager                       │
│       │                                 │
│  Context Manager                        │
│       │                                 │
│  Model Adapter                          │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│       Persistence & Evidence             │
│                                         │
│  SQLite                                 │
│  ├── Task State                         │
│  ├── Execution Event                    │
│  ├── Checkpoint                         │
│  ├── Evidence                            │
│  └── Analysis Result                    │
│                                         │
│  File Storage                           │
│  ├── JSON                               │
│  ├── Markdown                           │
│  └── Source Snapshot / Hash             │
└─────────────────────────────────────────┘
```

這樣未來上層不只有程式碼分析，還可以替換成其他應用。

---

# 三、最值得做的事情：不要全部自行開發

我建議採取以下技術組合：

| 功能 | 建議方案 | 自行開發程度 |
|---|---|---|
| Java Parser | JavaParser | 少量整合 |
| 多語言 Parser | Tree-sitter | 少量整合 |
| 程式知識圖譜 | CodeGraph | 優先採用 |
| 任務恢復 | CONTINUUM | 優先驗證 |
| Workflow | Python 自行控制或 LangGraph | MVP 簡化 |
| Context 管理 | 自行開發 | 核心能力 |
| LLM Adapter | Ollama API | 自行封裝 |
| Evidence | SQLite | 自行設計 |
| 報告 | Python + Markdown | 自行開發 |

其中有一個關鍵：

**不要同時使用 LangGraph 和 CONTINUUM 管理同一份任務狀態，否則容易形成兩套 Checkpoint 的一致性問題。**

MVP 可以先讓 CONTINUUM 負責持久化與恢復，Python 負責確定性工作流程。

等驗證完成，再決定是否需要 LangGraph。

---

# 四、最重要的技術實驗：我建議做一個比較測試

不要只看五個專案的 README。

我們直接建立一個實驗，測試：

| 測試項目 | A 組 | B 組 | C 組 |
|---|---|---|---|
| Runtime | 自製 SQLite | LangGraph | CONTINUUM |
| Checkpoint | JSON | Graph State | Semantic State |
| 模型 | 同一個 Qwen | 同一個 Qwen | 同一個 Qwen |
| 任務 | 分析 50 個 Java 檔案 | 相同 | 相同 |
| Context | 相同限制 | 相同 | 相同 |

執行以下故障注入：

1. 正常執行。
2. Context 達到 70%。
3. 強制 Kill Python Process。
4. 關閉 Ollama。
5. 修改一個已分析的 Java 檔案。
6. 重新啟動。
7. 更換模型。
8. 繼續完成剩餘任務。

記錄：

| 指標 | 評估目的 |
|---|---|
| 恢復成功率 | 是否能真正 Resume |
| 重複分析次數 | 是否浪費 Token |
| 遺失成果數量 | 是否有資料遺失 |
| 恢復時間 | 是否能快速接續 |
| Token 消耗 | 小模型是否能負擔 |
| 分析正確率 | 最終報告品質 |
| VRAM 峰值 | 地端硬體負擔 |

這個實驗會比單純研究架構圖更有價值。

---

# 五、還有一個重要問題：ISO 27001

你的報告提到：

> 使用 SQLite 保存 Hash-chained Event Log，可以滿足 ISO 27001 稽核要求。

這句話我建議修正。

Hash-chained Event Log 可以提供：

- 紀錄完整性驗證。
- 異動偵測。
- 執行歷程追溯。
- 證據來源追蹤。

但它本身不能直接代表符合 ISO 27001。

還必須考慮：

- 存取權限控制。
- 管理者權限分離。
- 日誌保存期限。
- 備份及還原。
- 時間同步。
- 日誌匯出及稽核。
- 金鑰管理。
- 防止資料庫管理者直接竄改紀錄。

尤其 SQLite 是本地資料庫，Hash Chain 也不能單獨防止有權限的人同時修改資料及重新計算 Hash。

因此，正式環境還需要將重要紀錄定期匯出到受保護的唯讀儲存區。

---

# 六、我的最終建議：MVP 採用與自行開發的界線

我會這樣定案：

| 元件 | 決策 | 原因 |
|---|---|---|
| CONTINUUM | 優先 PoC | 驗證語意接力與恢復 |
| CodeGraph | 優先 PoC | 減少自行開發程式索引的成本 |
| JavaParser | 採用 | 補強 Java 專屬解析 |
| Ollama | 採用 | 地端模型服務 |
| SQLite | 採用 | 本地持久化 |
| Context Manager | 自行開發 | 這是產品核心差異 |
| Analysis Pipeline | 自行開發 | 形成真正的使用者價值 |
| Evidence Manager | 自行開發 | 確保分析成果可追溯 |
| Report Generator | 自行開發 | 形成最終交付成果 |
| LangGraph | 暫緩 | 避免增加第二套狀態管理 |

## 七、我認為目前最值得驗證的三件事

第一，**CONTINUUM 能不能真正做到小模型跨 Session 接力，而不是只有一般工作流程恢復。**

第二，**CodeGraph 能不能正確理解你的 Java 舊系統，特別是 Servlet、DAO、JSP、SQL、XML 之間的關係。**

第三，**7B／14B 小模型在有結構化程式知識輔助的情況下，能否產生比直接把程式碼丟給 AI 更有價值的分析成果。**

這三個問題全部得到正面結果，就有充分理由繼續投資這個 MVP。

**我不建議現在就開始從零撰寫 Runtime。先用 CONTINUUM + CodeGraph + Ollama 組合做一次 50 個 Java 檔案的實際驗證，找出現有工具無法滿足的缺口，再決定哪些部分值得自行開發。**

這樣可以避免花幾個月重複開發市場上已經存在的功能。

---

## 對話片段 210

59d108dc-8f0a-4f0c-8850-59e089e7a1ba

---

## 對話片段 211

350996c3-69ac-4256-bb24-2803d29720ce

---

## 對話片段 212

28183394-98a6-4afe-af97-98b6c465f871

---

## 對話片段 213

ccaf7cfa-7ce6-4f9d-b421-9494f8c4f41c

---

## 對話片段 214

1246d624-5054-4639-ba7a-0072cbe7d7dc

---

## 對話片段 215

09684460-c6f8-474a-a1a9-2e1931b2f088

---

## 對話片段 216

c97a09d4-7d11-4545-9dab-3071f09626d0

---

## 對話片段 217

18333ade-eb69-40d0-840b-0e0c8a596b07

---

## 對話片段 218

thread_6ae9be403128832587d224d0fcd0e600

---

## 對話片段 219

c8273031-ec72-4dcf-bf41-4447c03806c3

---

## 對話片段 220

cec1542f-507b-471d-89ca-8fa13c02770f

---

## 對話片段 221

b9dad8b6-8a1c-4875-b24a-4aaabfddc1d8

---

## 對話片段 222

reasoning_title_content_transition

---

## 對話片段 223

The output of this plugin was redacted.

---

## 對話片段 224

29805e5a-efe6-4fd7-9d2c-d7397f240297

---

## 對話片段 225

a741f4d1-7533-477c-acc0-c5aa453c10f3

---

## 對話片段 226

fd3c3959-bf20-43cc-aaf0-61fa902168ab

---

## 對話片段 227

de3a40d7-68e6-443c-8464-d3887a83ae6a

---

## 對話片段 228

正在搜尋 "AgentLedger" agent runtime reliability layer GitHub checkpoint recovery

---

## 對話片段 229

正在搜尋 "CONTINUUM" semantic checkpoint AI agent GitHub Cyrax321

---

## 對話片段 230

正在搜尋 CodeGraph Tree-sitter MCP SQLite GitHub codegraph

---

## 對話片段 231

正在搜尋 SciTools Understand offline AI local LLM official documentation

---

## 對話片段 232

正在搜尋 LangGraph persistence checkpoint thread_id resume official docs

---

## 對話片段 233

502bed4c-31a2-4e29-a44f-c69ffa42e463

---

## 對話片段 234

6a8f5022-c8b8-4da0-a0d1-d53b0610f9e5

---

## 對話片段 235

1c5a4b40-6699-4728-ae81-f4a9457d5f0b

---

## 對話片段 236

a01e8dc7-5767-4b4a-82ac-24efbc3673fc

---

## 對話片段 237

179b288c-3093-4f42-aa6e-a298f82bad95

---

## 對話片段 238

bbb21680-5aba-456f-8239-613cf3472386

---

## 對話片段 239

e0e7e21b-b153-4c84-b643-139d4b61d782

---

## 對話片段 240

這是一份針對 LangGraph、AgentLedger、CONTINUUM、Understand、CodeGraph 五個核心競品的深度拆解與地端落地評估報告。
競品技術定位總覽
                                  【AI 理解與程式模型層】
                                            ▲
                                            │
                     ┌──────────────────────┴──────────────────────┐
                     │                                             │
             [ SciTools Understand ]                          [ CodeGraph ]
         (商業/專業靜態分析+語意索引)                   (開源 Tree-sitter + MCP 模式)
                                            │
                                            │ (結合)
                                            ▼
                                  【長任務 Runtime 核心】
                                            │
     ┌──────────────────────────────────────┼──────────────────────────────────────┐
     ▼                                      ▼                                      ▼
[ LangGraph ]                        [ AgentLedger ]                         [ CONTINUUM ]
( Graph/Node 狀態圖 )           ( 可追溯 Causal Ledger/工具日誌 )          ( 語意 Checkpoint + 狀態驗證 )

五大核心競品深度拆解
1. LangGraph（Graph 狀態驅動的 Workflow 引擎）
 * 定位：Agent 流程控制與 Session 級別狀態持久化框架。
 * 架構與機制：
   * State Graph：以有向圖描述 Agent 行為，每一個 Node 執行結束後透過 Checkpointer 將當前 State（字典/消息串列）寫入 SQLite/Postgres。
   * Resume 機制：透過 thread_id 恢復最新 Checkpoint。若執行中斷，從上一個節點重新觸發。
 * 痛點與侷限：
   * Context 膨脹：預設保存全量 Prompt 與對話 Context（messages key），未解決長任務小模型 Context Window 爆表問題。
   * 無環境驗證：若在執行中斷期間原始碼檔案已被手動變更，LangGraph 盲目讀取舊 State 續傳會導致邏輯混亂。
2. AgentLedger（Agent 行為可追溯與幂等控制層）
 * 定位：Agent 執行側寫（Trace）與外部 Action/Tool 追溯 Ledger。
 * 架構與機制：
   * Causal Action Ledger：記住 Agent 發出的每一個 Tool Call / Side-effect。
   * Replay & Audit：提供 Hash 檢驗與 Trace Replay，若系統 Crash，可追溯「哪一個 Tool Call 已經執行完畢、哪一個留在半空中」。
 * 痛點與侷限：偏向安全審計與支出控制（Financial/Action Vault），欠缺完整的程式分析模型與知識圖譜構建能力。
3. CONTINUUM（可驗證的語意 Checkpoint 恢復引擎）
 * 定位：專為長時間運行的 AI Agent 設計的 Semantic Recovery Layer。
 * 架構與機制：
   * Semantic Checkpoint：拒絕 Dump 對話文字，僅提取「當前任務目標、已驗證的事實（Provenances）、未完成步驟（PlanStep）」。
   * Environment Revalidation：恢復執行前，自動比對檔案 Hash 與環境變數，若檔案在 Crash 期間被改動，標註 REQUIRES_REVIEW 並觸發區域 Repair 流程。
   * Hash-chained Event Log：使用類似區塊鏈的 Event Log（Append-only）保證紀錄不被竄改，確保可追溯性。
 * 痛點與侷限：為較新的架構專案，底層重構頻率高，適合借鏡其架構設計思想而非直接引入整套 SDK。
4. SciTools Understand（企業級靜態分析與離線 AI 標杆）
 * 定位：專業程式碼解析、依賴圖表繪製與離線 AI 搜尋工具。
 * 架構與機制：
   * Parser 優先：先將程式碼編譯成標準 AST 與 C/C++/Java/C# Symbol Table，點對點查詢 Call Graph。
   * Air-gapped AI Support：支援地端 LLM 介面，AI 詢問直接基於 Parser 解析出來的結構化模型回答，精準度極高。
 * 對 MVP 啟示：證明了「先建結構模型，再用 AI 解析」是唯一能解決幾十萬行 Java 舊系統分析的正確路徑。
5. CodeGraph（輕量化 Tree-sitter + MCP 程式知識庫）
 * 定位：利用開源 Parser 建立本地程式庫 Symbol Index 的 MCP 服務。
 * 架構與機制：
   * 利用 Tree-sitter 多語言解析器提取 Symbol 與 Dependency，寫入 SQLite。
   * 提供 MCP (Model Context Protocol) 介面，讓 LLM 透過工具查詢類別關係，不把整支程式碼載入 Context。
核心 Checkpoint & Crash Recovery 機制縱向對比
| 對比維度 | LangGraph | AgentLedger | CONTINUUM |
|---|---|---|---|
| 持久化內容 | 全量 Graph State / 歷史 Message | Tool Call 側寫與 Action Log | 語意 Checkpoint（精簡事實+未完成步驟） |
| Crash 恢復邏輯 | 從最後一個 Node 重新帶入舊 Context | 重新比對 Tool ID，防止重複觸發 Side-effect | 重新驗證環境 (Revalidation)，修復過期事實後接續 |
| Context 膨脹控制 | 較弱（需手動寫 Reducer 裁剪對話） | 無（不處理 Prompt/Context） | 極強（丟棄歷史對話，僅保留精簡 State） |
| 外部變更防禦 | 無（無法感知環境檔案被修改） | 無 | 有 (Hash-chained Evidence & File Digest) |
| 適合層級 | Workflow 狀態轉移 | API / Tool 安全防禦 | 小模型 Context 接力與長任務恢復 |
地端落地方案與架構選擇（ISO 27001 / Air-Gapped 評估）
針對完全離線（Air-gapped）、ISO 27001 稽古稽核與地端 Ollama/vLLM 小模型運算要求：
1. 狀態與 Checkpoint 層設計（吸取 CONTINUUM + LangGraph 專長）
 * 捨棄「全量 Message 持久化」：地端 Qwen 3.5 8B/14B 模型 Context Window 限制明顯（8k-16k 性能最佳）。採用 CONTINUUM 的 Semantic Checkpoint 模式：
   Checkpoint State = {
   "task_id": "BUGNET_MIG_001",
   "current_phase": "CONTROLLER_MAPPING",
   "verified_facts": [ {"symbol": "UserController.cs", "java_target": "UserController.java", "status": "VERIFIED"} ],
   "pending_steps": [ "Map AuthFilter to Spring Security FilterChain" ],
   "environment_hashes": { "UserController.cs": "a1b2c3d4..." }
}

 * 使用 SQLite 保存 Hash-chained Event Log：所有 LLM 的推理結果與工具呼叫均附加 SHA256 簽名與時間戳記寫入地端 SQLite，滿足 ISO 27001 稽核軌跡要求。
2. 程式碼解析與知識模型層（整合 CodeGraph 理念）
 * 本地化 Tree-sitter Parser + SQLite：地端部署 Python/FastAPI 服務，利用 Tree-sitter 與 JavaParser 自動掃描舊系統（如 BugNET 或 Java Web），產出 SQLite 格式的 Symbol Table 與 Call Graph。
 * MCP / Tool 整合：AI Agent 每次執行時，不盲目讀取全量程式碼，而是透過 Tool 查詢 SQL：「SELECT callers FROM call_graph WHERE target = 'ProcessOrder'」，僅注入必要的上下文。
實作構想與下一步
我們自己開發的最簡 Runtime 只需要具備三個核心模組：
 * CodeIndexer：基於 Tree-sitter / JavaParser 的本地 SQLite 結構化索引器。
 * SemanticCheckpointManager：仿造 CONTINUUM 邏輯，當 Context 達到 70% 閾值時，強制 LLM 輸出「結構化階段性小結」，清空舊 Context 並重啟 Session。
 * EvidenceLogger：記錄每一段 AI 推理所採用的原始碼檔案、行號與 Hash 驗證碼。
相關影片推薦
LangGraph Persistence and Checkpointing Deep Dive
這支影片深入解析了包含 CONTINUUM 與 LangGraph 在內的語意 Checkpoint 與持久化恢復機制，非常適合用於進一步評估長任務狀態管理的實作細節。

---

## 對話片段 241

109896a5-0524-4088-822d-4474759bbda1

---

## 對話片段 242

c3adf40d-5899-4cad-bb2e-dd7f9ae828a3

---

## 對話片段 243

dbd4cc83-ec3a-4629-8771-478a1fb5deec

---

## 對話片段 244

ddf51164-5bb2-4cd6-92a2-d36951ce59d5

---

## 對話片段 245

6ae923b8-8550-83ec-87f4-36ab40760776

---

## 對話片段 246

branching_from_conversation_title

---

## 對話片段 247

branching_from_conversation_owner

---

## 對話片段 248

AWS Marketplace: CodeScene: Technical Debt Management Tool (BYOL)

---

## 對話片段 249

CODESCENE: TECHNICAL DEBT MANAGEMENT TOOL (BYOL)  Info  Sold by: CodeScene   Deployed on AWS  AWS Free Tier  Manage technical debt, improve code quality, prioritize refactoring, and automate reviews,...

---

## 對話片段 250

CAST Imaging-App modernization to accelerate Azure | Microsoft Marketplace

---

## 對話片段 251

CAST IMAGING-APP MODERNIZATION TO ACCELERATE AZURE  BY CAST  Contact me  Just a moment, logging you in...  OverviewRatings + reviewsDetails + support    Application Modernization Migration of Legacy...

---

## 對話片段 252

CAST Imaging-App modernization to accelerate Azure

---

## 對話片段 253

CAST IMAGING-APP MODERNIZATION TO ACCELERATE AZURE  作者 CAST  與我連絡  Just a moment, logging you in...  概觀評分與評論詳細資料 + 支援    Application Modernization Migration of Legacy Apps refactoring rearchitecting...

---

## 對話片段 254

ARCHITECTURAL ANALYSES  ARCHITECTURAL COMPONENTS ARE NOT CONFIGURED  With CodeScene's Architectural Components, you can:  * Image  Provide CodeScene with your architectural knowledge  You know what y...

---

## 對話片段 255

Focus Areas — CodeScene 1 Documentation

---

## 對話片段 256

FOCUS AREAS¶  The 4 factors identifies the areas that require attention. The focus areas let you explore the details and plan the necessary actions.  CODE HEALTH¶  * CodeScene’s Framework for Managin...

---

## 對話片段 257

CodeScene — CodeScene 1 Documentation

---

## 對話片段 258

Documentation  CODESCENE¶  Welcome to the CodeScene documentation!  This documentation is divided into sections, each being suited for different types of information you might be looking for.  NEW USE...

---

## 對話片段 259

CAST HIGHLIGHT  ENTERPRISE ARCHITECTURE SOFTWARE BY CAST  SEE WHO'S SKILLED IN THIS  Add as skill Learn more  ABOUT  CAST Highlight is a software intelligence product, available as SaaS, that provide...

---

## 對話片段 260

CodeScene - Visual Studio Marketplace

---

## 對話片段 261

CODESCENE - CODE ANALYSIS AND REFACTORING  WHAT IS CODESCENE FOR VISUAL STUDIO CODE?  CodeScene is the only code analysis tool with a proven business impact. It serves as a safeguard against introduc...

---

## 對話片段 262

harnesses.sh — the agent harness directory

---

## 對話片段 263

LANGGRAPH · LANGCHAIN  ★ 36,751 · high  Low-level graph-based agent orchestration runtime with automatic checkpointing, durable execution, human-in-the-loop interrupts, and streaming (v1.2 in May 202...

---

## 對話片段 264

Durable LangGraph interrupt and resume - MongoDB Atlas Agent Engine - MongoDB Docs

---

## 對話片段 265

DURABLE LANGGRAPH INTERRUPT AND RESUME  Durable workflows use LangGraph’s native `interrupt()` and `Command(resume=...)` control flow while the Orchestration Engine (OE) owns the durable suspension s...

---

## 對話片段 266

The Runtime Behind Production Deep Agents

---

## 對話片段 267

THE RUNTIME BEHIND PRODUCTION DEEP AGENTS  Sydney Runkle  Vivek Trivedy  April 20, 2026  KEY TAKEAWAYS  * A good harness gives your agent the right prompts, tools, and skills. But deploying long-runn...

---

## 對話片段 268

The Open Source Agent Toolkit in 2026 – O’Reilly

---

## 對話片段 269

THE OPEN SOURCE AGENT TOOLKIT IN 2026  The seven layers that survive production, and the open source pick for each  Image: Paolo Perrone  By Paolo Perrone July 14, 2026 • 17 minute read  LinkedInXFac...

---

## 對話片段 270

INTELLIGENCE  Forge's AI-powered codebase understanding system  INTELLIGENCE  Intelligence is Forge's system for deeply understanding your codebase. It scans your repositories to detect your stack, p...

---

## 對話片段 271

Instant insight into your applications | CAST

---

## 對話片段 272

100% DETERMINISTIC  BY BOOSTING AI ACCURACY 2X+, CAST SPEEDS SOFTWARE DELIVERY WHILE CUTTING RISK AND COST  SCALE YOUR AGENTIC DEV  WITH CAST, AI AGENTS CAN NOW:  MAP YOUR ENTIRE SOFTWARE ARCHITECTUR...

---

## 對話片段 273

CAST Imaging components: an overview | Documentation

---

## 對話片段 274

CAST IMAGING COMPONENTS: AN OVERVIEW  A description of the components that together form CAST Imaging  * * *  OVERVIEW  CAST Imaging is a grouping of various components that work together to produce...

---

## 對話片段 275

CAST Imaging | Documentation - V2

---

## 對話片段 276

CAST IMAGING  CAST Imaging consists of different components and products:  * AIP Console package * AIP Core for Imaging + AIP Node package * PostgreSQL * CAST Imaging instance  At a broad level, the...

---

## 對話片段 277

Exploring applications with CAST Imaging Express | Documentation

---

## 對話片段 278

EXPLORING APPLICATIONS WITH CAST IMAGING EXPRESS  OVERVIEW  CAST Imaging Express provides architects and tech leads with a powerful visualization of an application’s architecture, dependencies, and i...

---

## 對話片段 279

CAST Imaging Agents | Documentation

---

## 對話片段 280

CAST IMAGING AGENTS  Explore an application, trace the impact of a change and investigate quality with an agentic chatbot that chooses and runs the tools it needs  * * *  OVERVIEW  Available in ≥ 3.6...

---

## 對話片段 281

CAST IMAGING V3 DOCUMENTATION  📖 Go to V2 documentation  What would you like to do?  Choose your path to get the most out of CAST Imaging...  GET STARTED IN MINUTES  Want to get up and running quickly...

---

## 對話片段 282

SOFTWARE HEALTH  GET PERSONALIZED EXPLANATIONS, AI SERVICE RECOMMENDATIONS, AND SUGGESTED ACTIONS.  Image: Application Briefing  QUICKLY PRIORITIZE APPLICATIONS FOR GREATEST IMPACT ON REDUCING TECH D...

---

## 對話片段 283

Use AI assistance | Documentation

---

## 對話片段 284

USE AI ASSISTANCE  Use CAST Imaging’s AI-driven services to understand objects, transactions, and application behaviour  * * *  OVERVIEW  Did you know that CAST Imaging can interface with various thi...

---

## 對話片段 285

GitHub - temporal-community/temporal-ai-agent-memory-state-stripe: A refund agent that keeps its place after a crash: agent memory versus execution state, shown with Temporal and Stripe test mode. Runs offline for $0. · GitHub

---

## 對話片段 286

HOW OTHER APPROACHES COMPARE  Most agent products keep memory (what the agent knows) or a transcript. Several also keep execution state for their own runtime.  Kind  | Examples  | After a crash --- |...

---

## 對話片段 287

What can CodeScene Single-User MCP do? - CodeScene

---

## 對話片段 288

WHAT CAN CODESCENE SINGLE-USER MCP DO?  CodeScene Single-User MCP focuses on local Code Health workflows. It gives your AI assistant the tools it needs to review code, safeguard AI-generated changes,...

---

## 對話片段 289

How CodeScene Is Deployed: Architecture & Options for Enterprise Teams - CodeScene

---

## 對話片段 290

HOW CODESCENE IS DEPLOYED: ARCHITECTURE & OPTIONS FOR ENTERPRISE TEAMS  WHO THIS IS FOR  This article is for platform, security and engineering leaders who are planning a CodeScene deployment for hun...

---

## 對話片段 291

Scale AI Coding Safely and Mitigate Technical Debt | CodeScene

---

## 對話片段 292

Image: Background  Case study: Agentic Refactoring  ACCELERATE WITH AI, WITHOUT LOSING CONTROL  Scale AI safely, keep code quality high and increase delivery speed. All powered by CodeHealth™, the sc...

---

## 對話片段 293

HOW IT WORKS  SEE WHAT STATIC ANALYSIS MISSES  CodeScene reveals how your teams and code evolve together, exposing hidden risks, high-impact technical debt, and turning code health into business perf...

---

## 對話片段 294

How Cody understands your codebase | Sourcegraph

---

## 對話片段 295

Blog How Cody understands your codebase  February 15, 2024  Alex Isken  HOW CODY UNDERSTANDS YOUR CODEBASE  Context is key for AI coding assistants. Cody uses several methods of context fetching to p...

---

## 對話片段 296

CHAT  Chat with Cody in your code editor or via the Sourcegraph web app to get intelligent suggestions, code autocompletions, and contextually aware answers.  You can chat with Cody to ask questions...

---

## 對話片段 297

DEEP SEARCH  Supported on Enterprise Starter and Enterprise plans. For new customers, the Enterprise AI Terms of Use apply and Deep Search is enabled by default. For existing customers with licenses...

---

## 對話片段 298

CODY  Supported on Sourcegraph Enterprise.  Available on VS Code, JetBrains, Visual Studio, and the Web app.  Cody is an AI coding assistant that uses all the latest LLMs and your development context...

---

## 對話片段 299

Durable Execution Platform | Temporal

---

## 對話片段 300

Temporal  A DURABLE EXECUTION PLATFORM THAT LETS YOU WRITE THE CODE YOU WANT  Image: Open Source Initiative Logo 23,505 Stars  How Temporal works Go inside the machine and see how Temporal works. Exp...

---

## 對話片段 301

Durable AI | Temporal Documentation

---

## 對話片段 302

DURABLE AI  Temporal gives AI applications and agents Durable Execution: a Workflow resumes automatically after a crash, a network timeout, or a multi-day wait for a human to approve a step. Temporal...

---

## 對話片段 303

Temporal Platform Documentation

---

## 對話片段 304

For the complete documentation index, see /llms.txt. This page is also available as raw Markdown at /index.md. Append `.md` to any page URL to fetch its Markdown.  Temporal Docs  *   *   *   *   *...

---

## 對話片段 305

Event History | Temporal Documentation

---

## 對話片段 306

EVENT HISTORY  With Temporal, your Workflows recover from crashes automatically. This is made possible by the Event History, a complete and durable log of everything that has happened in the lifecycl...

---

## 對話片段 307

TASKS  WHAT IS A TASK?​  A Task is a unit of work for Workers. The Temporal Service places Tasks on Task Queues, and Workers poll for and process them to advance Workflows, run Activity attempts, or...

---

## 對話片段 308

Durable Execution Solutions | Temporal

---

## 對話片段 309

THE WORLD’S BEST AI RUNS ON TEMPORAL  Build applications the way OpenAI, Lovable, Replit, Cursor, and Retool do with our open-source platform. Add agentic capabilities to any application with your fr...

---

## 對話片段 310

持久化 - LangChain 文件 - LangChain 教學

---

## 對話片段 311

本頁內容  * 為什麼使用持久化 * 核心概念 * 執行緒 * 檢查點 (Checkpoints) * 超級步 (Super-steps) * 檢查點名稱空間 * 獲取與更新狀態 * 獲取狀態 * StateSnapshot 欄位 * 獲取狀態歷史 * 查詢特定檢查點 * 重放 * 更新狀態 * 記憶體儲存 (Memory store) * 基本用法 * 語義搜尋 * 在 LangGraph 中...

---

## 對話片段 312

AI agent frameworks compared on state, approvals, tracing and lock-in

---

## 對話片段 313

Release dates are in UTC, from each project’s GitHub releases page.  Framework (maintainer)  | Licence  | Languages  | Latest release  | Models  | State and durable execution  | Human approval  | MCP...

---

## 對話片段 314

10 Best Open Source AI Agent Frameworks in 2026 (Compared)

---

## 對話片段 315

BEST OPEN SOURCE AI AGENT FRAMEWORKS IN 2026  I looked at 53 open source frameworks for building AI agents and ranked the ones I'd pick. I checked which models it works with, whether a run can pause...

---

## 對話片段 316

Best Open-Source AI Agent Frameworks in 2026: 15 Frameworks for Teams That Want to Own Their Stack

---

## 對話片段 317

Mastra and Flowise both take a cleaner open-core approach: their core frameworks are unrestricted Apache-2.0, and only a specific enterprise directory (`ee/` for Mastra, `enterprise/` for Flowise) sit...

---

## 對話片段 318

What is codeanalyzer-java? | codeanalyzer-java

---

## 對話片段 319

WHAT IS CODEANALYZER-JAVA?  codeanalyzer-java is a standalone, self-contained JVM tool that performs static analysis on enterprise Java applications. You hand it a project (or a single source string)...

---

## 對話片段 320

Soot | Soot - A framework for analyzing and transforming Java and Android applications

---

## 對話片段 321

SOOT  SOOT - A FRAMEWORK FOR ANALYZING AND TRANSFORMING JAVA AND ANDROID APPLICATIONS  WHAT IS SOOT?  Image  Originally, Soot started off as a Java optimization framework. By now, researchers and prac...

---

## 對話片段 322

JavaParser: Java 1-25 parser and AST library | Hysen Labs

---

## 對話片段 323

javaparser/javaparser  JAVAPARSER: A JAVA 1 TO 25 PARSER AND AST LIBRARY FOR SOURCE ANALYSIS  Java 1-25 Parser and Abstract Syntax Tree for Java with advanced analysis functionalities.  6,172 stars 1...

---

## 對話片段 324

AI Coding Platform | Powered by Claude  CURSOR  AI coding platform for the full Software Development Lifecycle.  Request to buy  * Use caseCode  Cursor is an AI coding platform used by teams to write...

---

## 對話片段 325

How to Choose AI Agent Frameworks in 2026? - Vercel

---

## 對話片段 326

8 BEST AI AGENT FRAMEWORKS COMPARED FOR CONTROL, STATE, AND COORDINATION  Vercel  ArticlesAI SDK  3 Aug 2026  17 min read  Copy  Use six checks to choose an AI agent framework. Compare your team's la...

---

## 對話片段 327

Understand Anything | explore any codebase as a graph

---

## 對話片段 328

UNDERSTAND ANYTHING — INTERACTIVE KNOWLEDGE GRAPH FOR ANY CODEBASE  Turn any codebase into an interactive knowledge graph you can explore, search, and ask questions about — works with Claude Code, Co...

---

## 對話片段 329

INTERRUPTS  Interrupts allow you to pause graph execution at specific points and wait for external input before continuing. This enables human-in-the-loop patterns where you need external input to pr...

---

## 對話片段 330

JASTG: Java AST structural graph—A tool for reproducible extraction of structural dependency graphs from Java systems - ScienceDirect

---

## 對話片段 331

Volume 35, September 2026, 102802  JASTG: JAVA AST STRUCTURAL GRAPH—A TOOL FOR REPRODUCIBLE EXTRACTION OF STRUCTURAL DEPENDENCY GRAPHS FROM JAVA SYSTEMS  https://doi.org/10.1016/j.softx.2026.102802Ge...

---

## 對話片段 332

Reengineering software systems into microservices: State-of-the-art and future directions - ScienceDirect

---

## 對話片段 333

Table 5. Tools for monolithic system analysis.  Purpose  | Tool/Library  | Details  | Technology  | Study IDs --- | --- | --- | --- | --- Static analysis (source code)  | Java call graph (open source)...

---

## 對話片段 334

Code Analysis & Codebase Onboarding Tools — SciTools

---

## 對話片段 335

The code analysis platform  ONE ANALYSIS ENGINE. MANY WAYS TO UNDERSTAND CODE.  Explore code in Understand, teach it with OnBoard, query it through your editor and APIs, or bring it directly to AI ag...

---

## 對話片段 336

Get started  SEE YOUR OWN CODEBASE IN UNDERSTAND  A full-featured trial that installs in minutes and needs no build system. Or walk through your architecture with an engineer who has seen a few thous...

---

## 對話片段 337

Open Source Code Analyzers in Java

---

## 對話片段 338

OPEN SOURCE CODE ANALYZERS IN JAVA  25 projects  Dependency Finder  Suite of tools for analyzing compiled Java code: extracts dependency graphs and OO metrics from class files, computes API differenc...

---

## 對話片段 339

Understand Plugins, API & CLI — SciTools

---

## 對話片段 340

Plugins  UNLIMITED POTENTIAL WITH UNDERSTAND PLUGINS  Unleash the powerful Understand API on your code to find the information you need, displayed in a user friendly format. Most plugins are already...

---

## 對話片段 341

Feature list  UNDERSTAND FEATURES  Understand is your comprehensive multi-tool for development insights and code analysis.  Start free trial See pricing  Twelve capabilities in one tool · Certified f...

---

## 對話片段 342

What is an architecture & why build one? - Understand Documentation

---

## 對話片段 343

WHAT IS AN ARCHITECTURE & WHY BUILD ONE?¶  An architecture in Understand is an abstract hierarchy layered over your source code — a named, nested grouping of files and entities that you define, indep...

---

## 對話片段 344

Features | Understand by Scitools

---

## 對話片段 345

Free Trial  Image  UNDERSTAND FEATURES  Understand is your comprehensive multi-tool for development insights and code analysis  Code navigation Quickly and efficiently move through your codebase. With...

---

## 對話片段 346

Dependency Analysis for Large Codebases — SciTools

---

## 對話片段 347

Dependency analysis  SEE WHAT YOUR CODE ACTUALLY DEPENDS ON  Analyze dependencies between modules, classes, functions, and custom architectures. Make informed decisions regarding refactoring, optimiz...

---

## 對話片段 348

What is Understand and what can it do for me? - Understand Documentation

---

## 對話片段 349

WHAT IS UNDERSTAND AND WHAT CAN IT DO FOR ME?¶  Understand is a static analysis and code-comprehension tool. You point it at source code and it builds a cross-referenced database of everything in it...

---

## 對話片段 350

Code Dependency & Call Graphs — SciTools

---

## 對話片段 351

Graphs  BRING YOUR CODE TO LIFE  Understand draws dependency, call, butterfly, control flow and UML diagrams straight from the parsed model of your project. Nothing is hand-drawn, so nothing is stale...

---

## 對話片段 352

SEE YOUR CODEBASE AS A CONNECTED SYSTEM  Understand parses your entire codebase and builds a connected model of its structure and relationships. Instead of searching through files manually, you can se...

---

## 對話片段 353

documentation/docs/encyclopedia/event-history/event-history.mdx at main · temporalio/documentation · GitHub

---

## 對話片段 354

id  | event-history title  | Event History description  | Discover how Temporal uses the Event History to recreate a Workflow's state in the case of failure, such as a Worker crash, and how it uses r...

---

## 對話片段 355

GitHub - codescene-oss/codescene-vscode: VS Code extension providing CodeScene analysis functionality. · GitHub

---

## 對話片段 356

CODESCENE - CODE ANALYSIS AND REFACTORING  WHAT IS CODESCENE FOR VISUAL STUDIO CODE?  CodeScene is the only code analysis tool with a proven business impact. It serves as a safeguard against introduci...

---

## 對話片段 357

temporal-dotnet-agents/docs/architecture/MAF/durability-and-determinism.md at main · temporal-community/temporal-dotnet-agents · GitHub

---

## 對話片段 358

DURABILITY AND DETERMINISM IN TEMPORAL AGENT WORKFLOWS  This document explains how Temporal's durability and determinism guarantees work in the context of agent orchestration workflows, particularly...

---

## 對話片段 359

CodeScene · GitHub Marketplace · GitHub

---

## 對話片段 360

1. Marketplace 2. Apps 3. CodeScene  Image: CodeScene logo  CODESCENE  ABOUT  A software engineering intelligence tool tool to identify and prioritize technical debt and evaluate your efficiency  By...

---

## 對話片段 361

documentation/docs/evaluate/understanding-temporal.mdx at main · temporalio/documentation · GitHub

---

## 對話片段 362

id  | understanding-temporal title  | Understanding Temporal sidebar_label  | Understanding Temporal description  | Workflows, Activities, Workers, Task Queues, and Event History. The parts of a Temp...

---

## 對話片段 363

documentation/docs/index.mdx at main · temporalio/documentation · GitHub

---

## 對話片段 364

id  | index title  | Temporal Platform Documentation sidebar_label  | Home hide_table_of_contents  | true hide_title  | true description  | Official docs for Temporal, the open source Durable Executi...

---

## 對話片段 365

jetbrains/README.md at main · sourcegraph/jetbrains · GitHub

---

## 對話片段 366

SOURCEGRAPH: CODY + CODE SEARCH  Use Cody, the AI coding assistant, plus Code Search directly from your JetBrains IDE.  * Cody is an AI coding assistant that helps you understand, write, and fix code...

---

## 對話片段 367

GitHub - laude-institute/headlong: An open source agent microharness featuring persistent agency and recursive LLMs. Of bash, by bash, for bash; it's shells all the way down. · GitHub

---

## 對話片段 368

headlong  Public  * NotificationsYou must be signed in to change notification settings * Fork134 (134) * Star1.3k (1.3k)You must be signed in to star a repository  ABOUT  An open source agent microha...

---

## 對話片段 369

handbook/content/departments/engineering/teams/cody/about-cody-faq.md at main · sourcegraph/handbook · GitHub

---

## 對話片段 370

ABOUT CODY FAQ  WHAT IS CODY?  WHAT IS CODY (ELI5)?  Cody is an AI coding assistant that lives in your editor that can find, explain, and write code. Cody uses a combination of AI (specifically Large...

---

## 對話片段 371

GitHub - adventurewave-labs/awesome-agent-reliability: A curated list for securing autonomous AI agents — configs, runtime, tools, MCP, and red-teaming · GitHub

---

## 對話片段 372

Securing it is covered under Security › MCP Security.  (IBM/BeeAI's Agent Communication Protocol — ACP — merged into A2A in August 2025 and no longer exists as a separate spec. If you see it reference...

---

## 對話片段 373

GitHub - javaparser/javaparser: Java 1-25 Parser and Abstract Syntax Tree for Java with advanced analysis functionalities. · GitHub

---

## 對話片段 374

ABOUT  Java 1-25 Parser and Abstract Syntax Tree for Java with advanced analysis functionalities.  javaparser.org  TOPICS  abstract-syntax-treeastcode-analysiscode-generationcode-generatorjavajavadoc...

---

## 對話片段 375

GitHub - alirux/carve: Static analysis tool for Java/Spring codebases, supporting modernisation of legacy Spring applications · GitHub

---

## 對話片段 376

CARVE  Static analysis tool for Java/Spring codebases, built on top of Spoon and JGraphT.  Primary use case: supporting modernisation of legacy Spring applications by mapping dependencies and detectin...

---

## 對話片段 377

GitHub - gdela/socomo: Tool to visualize and maintain the composition of java code. · GitHub

---

## 對話片段 378

1. gdela 2. socomo   socomo  Public  * NotificationsYou must be signed in to change notification settings * Fork7 (7) * Star63 (63)You must be signed in to star a repository  ABOUT  Tool to visualize...

---

## 對話片段 379

GitHub - glue-tools-ai/awesome-codebase-intelligence: A curated list of tools, research, and resources for understanding, analyzing, and building intelligence on top of codebases · GitHub

---

## 對話片段 380

AWESOME CODEBASE INTELLIGENCE  > A curated list of tools, research, and resources for understanding, analyzing, and building intelligence on top of codebases.  Modern software organizations lose 20-3...

---

## 對話片段 381

GitHub - Tonic-Box/JStudio: A Java reverse engineering and static analysis IDE for analyzing, decompiling, and transforming Java bytecode. · GitHub

---

## 對話片段 382

FEATURES  MULTI-VIEW EDITOR  Code Views  * Source - Decompiled Java with syntax highlighting * Bytecode - JVM instructions with color-coded opcodes * Dual - Source and bytecode side-by-side, linked by...

---

## 對話片段 383

Jar Analyzer: local JAR bytecode auditing | Hysen Labs

---

## 對話片段 384

Getting Started - Sourcegraph docs

---

## 對話片段 385

Code Analysis based on context and relevance | CodeScene

---

## 對話片段 386

Welcome to CAST Imaging Express | Documentation

---

## 對話片段 387

docs/src/oss/langgraph/interrupts.mdx at main · langchain-ai/docs · GitHub

---

## 對話片段 388

([Hysen Labs](https://hysenlabs.com/projects/jar-analyzer-jar-analyzer?utm_source=chatgpt.com))

---

## 對話片段 389

urlJar Analyzer GitHubhttps://github.com/jar-analyzer/jar-analyzer

---

## 對話片段 390

[Jar Analyzer GitHub](https://github.com/jar-analyzer/jar-analyzer?utm_source=chatgpt.com)

---

## 對話片段 391

([Colby McHenry](https://colbymchenry.github.io/codegraph/getting-started/introduction/?utm_source=chatgpt.com))

---

## 對話片段 392

urlCodeGraph 專案文件https://colbymchenry.github.io/codegraph/getting-started/introduction/

---

## 對話片段 393

[CodeGraph 專案文件](https://colbymchenry.github.io/codegraph/getting-started/introduction/?utm_source=chatgpt.com)

---

## 對話片段 394

entity["software","JavaParser","Java AST parser"]

---

## 對話片段 395

entity["software","Understand Anything","codebase knowledge graph"]

---

## 對話片段 396

entity["software","JStudio","Java reverse engineering IDE"]

---

## 對話片段 397

entity["software","Jar Analyzer","Java bytecode analysis tool"]

---

## 對話片段 398

entity["software","CodeGraph","local code intelligence graph"]

---

## 對話片段 399

([Sourcegraph](https://sourcegraph.com/docs/getting-started?utm_source=chatgpt.com))

---

## 對話片段 400

urlSourcegraphhttps://sourcegraph.com/

---

## 對話片段 401

[Sourcegraph](https://sourcegraph.com/?utm_source=chatgpt.com)

---

## 對話片段 402

image_group{"query":["Sourcegraph code search cross repository interface","Sourcegraph Cody AI codebase understanding"]}

---

## 對話片段 403

![Image](https://images.openai.com/static-rsc-4/0gklBZiukeuYf72NY-UgoQDuRS2vvTh9QfiuxQ4dTduqk76spl-bs6-KiyVu3AWT5Bum4aoDbOAvVQu3ueV9bsR7MvD85uRuSyjQFHQl47hqT_yq4XhBmMbkxWwbkUWY1yh_uuLxgbRA1ySYP_OLlUvGNw8e1K3z5BS8zavbjutUJLgdD17vUUnJ4Lxehq3Q?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/fHHEj3j7irKpjH6YDVEVbHkaCUM8QJBStXZjVasClmYGnJCZoTkkRga2tQ1PCNdnxuTPSYZ3gqmmXtVtH5cwNbnDGgIcBeiTAOmkvXruY6nk9BctfiPZ4VcNqsxBLl4lgUA0ljXLkc9yibdjCR1oVHbLU1ZomE4Hp26WUXH8WxCRzr_pN9vScR5rIouodD-Y?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/8XfrNuzNeFLymFuvgfRcwcFVE0_rFQxoFPCXk6HTjAyuCzwI6AgBWM3BVp-MMm9PioIj3njcgZ6tAWLvP-TLTUHAhmCiFaZpArDedwzxPArY4pRO9P8EuP0YKRxOZvauqf6EynEbgKiEl9FtGLSQ8_OPi7XQRMhvtOxIGwXOE_3rig57ZSqho5dOa-naioix?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/NhBTo6HkIJvLHTXZ-b9gLh0fPHscvtdxTxCR0ZDaEiqgE0qaZKJ-mMdEEVNja_wzd-Bi5tN9JvmBBqazS2S7k8L28CXE736-AIC0QNF2grqvDnkYTZq8aTXhzYmOpK_gZNh_UVtfjl0ZbQs7YAh5d6TJIpLbZ7pX1MdVF80OEruw6OSq-jttjojfb0aGPdOr?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ExiglbRURG_4UwSqeEZ_faDLdSVcATyUuVDrRTX5msal-PB5BAnKfjcWILhEAcDkUgli9W2GNljfDBaT3InWjtqLa4S94JAeM7aTOgQ9Giu9zMCtjFuptqrK-sFP-BOZRPFad6l--0VS6fMgbyBSB5EJZH8_9m8Hwaa5iMVmCjJkbIGdq0MXpzIXR4DPmjFU?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/dj_CYMzQKOOSLyO2isYHfOyYgIRkY81gmqdDbrTOzPtKwQCYr86ApH5XHxhlUrkoitN90hzyueW5pOiHrXW6pC57h9mkpnc0bzaOJ59KKK8DJTuYouqT-_LqZsMtICsGxufoHY4_yB-yB3ztIFaetnWR6XpPArAos0pFgpzoCEMk5JdHa_3eJjDOjleTW8YO?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/20cGDgyl17dFmhX5KWSgLV6p60lyYbxq6-J5GDGOyVv3z-qF91LlBtiHn__VFk8XUXNgRbY9YlYy3cq9i3L_aeQOH43UDlcC2NrciCU04Uj6D3GRJnDVdta0jUMFpvuiMAryypzYi75qLlsZU9VPtvGylDQn-GKj2KR04TnsnCDR6m_zUkS_MgIrCNGp-RGx?purpose=fullsize)

---

## 對話片段 404

Sourcegraph Cody AI codebase understanding

---

## 對話片段 405

Sourcegraph architecture - Sourcegraph docs

---

## 對話片段 406

How Cody provides remote repository awareness for codebases of every size | Sourcegraph

---

## 對話片段 407

Sourcegraph code search cross repository interface

---

## 對話片段 408

Code Understanding, Oversight and Evolution | Sourcegraph

---

## 對話片段 409

Introducing Sourcegraph Enterprise Starter | Sourcegraph Blog

---

## 對話片段 410

Cross-repository code navigation | Sourcegraph

---

## 對話片段 411

([CodeScene](https://codescene.com/product?utm_source=chatgpt.com))

---

## 對話片段 412

urlCodeScenehttps://codescene.com/

---

## 對話片段 413

[CodeScene](https://codescene.com/?utm_source=chatgpt.com)

---

## 對話片段 414

image_group{"query":["CodeScene code health visualization dashboard","CodeScene software architecture hotspots visualization"]}

---

## 對話片段 415

![Image](https://images.openai.com/static-rsc-4/DjGrzK590hZcdkCx6EQ04ty3-xKAarb18HCVfe9xCsPRxVOi6ISwpP3HfSEztOXAcrztO2DA_ILBkN27oSQokwk5E3wSLpbU6iZGEzSu3vsX_coYTxEt8XIiIQ8i-371uRThRfKjpWX2PPaIxi_nhqQ35iBctNQLWK29m-lOjsqAIROMAuhzDrLYohkujWmn?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/rd6MiKXCOFmF-yrkLA_-ZnVIVrp00iRzHG54COaMLVUDdVqahp1zWYjyojq-3LXxRq4uR3FrVrBeBa8NLATKO6etaGTnpdteRQDVchyQWo04DDjUc1ExuOeSmt7-L36ogbmeZYQ9pfRWiFiwhJmhzhCLtKmcTaoHqs4doxhmJz4Hep95CJhv-f4v_I4MmwB_?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/UebBSdnJrf6_0NBLEfC7MGICgSwIBn9LeOmd0musFUPYBDJiqM97qXo24YGIISb25OioHDF8jWnvC2ZNx_i4eoDsqsuNWhe8qON2IcZoK844Q3y54OTG6FV5OiS3MTZHmwSNeieeKGBqkeGmrujT45E3aK-oIYrKpZK1cmEjtyA-Pvnj8cZLiHaMHwIWc-92?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ZA_cd37kuCNti9QrKl5BnpR9XcweBvrHxX7qClK-SfhP1IbXzsVQZrt4QaAeatT-3A65rB6Xwe9jpkBd89RHyQUrBRiO5SSN66MTTG4zpzpAUkVPX6c5ICZ7fbHHirD9pUYjyA_ELE7I527Y0-j8ofP4YOJfTNRp-sCUp9o3BPbkExRBEt5L4fiQz4BXXN9s?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/kTuoNjtsSICgvLrhfTnDbR400jrVk5dHpHqVCUdcVH7tZ-hYl1dFv501TEjK-rg5PROgiM9rZf53tP_mysXUFccbpISIXFPxI9PjBMzIC0m0HwRIxU7e6C-rQMaNQalffler0VS7iMu8q-_mP5P3rt3lN7MgtnTeZhngYdi0eSQJtBcgizS3ds9vKV_GZ9bv?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/pWanXeSVslbdXbMJ70qCGYdC-ggBfS4U5f2i6ARx4Cm5ct0rqBjVoMfKaj8jsp8Po60eaLXwQM7mhDFCGwbRCV9fkcOc2MiYkfCP8MdncYiDWRg2kN5YBrJQRP4kN3EJ8CXejKhFYrOZdxCww4q0_qkZaPGHT1oFLUGRbN8040cokltRC1uJL388WiyjQiIq?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/FCqtPTuIp4brF4HLEPVf4U-4l0Y4eiVrIV-EM6uS2LQYK7szgg6mEt4C5AuaJhDKQaibWJ5_IFaAwmFwC5Z93Yn6peH6b4chpc3zuKm8XThunZTlLlYW04aJ036Jbf_DqT6fb1NH7RJZpjxWAomIbHVlCsUtoS2AGrHUasFBtnTvSvJfghygxRD1C5SRBsYF?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/sysBU2OIHYb0RG1ON8PTraJtPGZQGgzPI-mrReTgbah-bmZFMSPY9pkqAEeQnkUBKLeWE126xS201U2t5bYRUgUFVm152-A-zoFUza3R5bgLcvD5E9g53XPuPBD28lUCvdGMlyPfDYS4aNNXtNZjC7jd0cMMNwah2eBeWlqVUBHcpti5ISDbl1xc76DxLGK3?purpose=fullsize)

---

## 對話片段 416

CodeScene software architecture hotspots visualization

---

## 對話片段 417

Architectural Analyses — CodeScene 4.2.1 Documentation

---

## 對話片段 418

Tech Debt Examples - Prioritize Technical Debt with CodeScene

---

## 對話片段 419

CodeScene code health visualization dashboard

---

## 對話片段 420

Deep insights into code health - Metrics

---

## 對話片段 421

CodeScene integrates with BitBucket | Announcement

---

## 對話片段 422

Support and build effective development teams | CodeScene

---

## 對話片段 423

Architectural Analyses — CodeScene 6.6.7 Documentation

---

## 對話片段 424

Improve Code Quality and Code Health | CodeScene

---

## 對話片段 425

([Documentation](https://doc.castsoftware.com/imagingexpress/?utm_source=chatgpt.com))

---

## 對話片段 426

urlCAST Imaginghttps://www.castsoftware.com/products/imaging

---

## 對話片段 427

[CAST Imaging](https://www.castsoftware.com/products/imaging?utm_source=chatgpt.com)

---

## 對話片段 428

image_group{"query":["CAST Imaging application architecture visualization","CAST Imaging Java application dependency graph"]}

---

## 對話片段 429

![Image](https://images.openai.com/static-rsc-4/fykbkvcBeAXhitm-gQvT9A2OxBC0YwlEVYHAR3BJifIQKrfCVR4-tEaH54GwPKDZSb5rBg3CkfkSmPpbNPeBdOCFEd0Oc1tbp24nTwp2XfqooPIXemlg4ELjGfdAWAdr2FOnK17EW4wK7Rb8UIwc78pqb86YvDZWYl-2WwmPhPaWc7s5TW14oH8YTWgkBL8r?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/q2Yr8vx2rr4gr-hA7MAIzd0cgV2NmQKvST3-5-kqCWMEsjv0nJxhhYhejYlyhagWoFjMeQhjW3P0a6toglCG79fEtZoBpO1gJW-IOrQbDEfBv59O3Xdi3N-pphiOvoNKdZONFI2v2GtU2hF-X3PNdh0TkzADG9cZr_ftg9TRRrVhphJzcBrp2EvtESIJYU5n?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/MBRNWEtnBM5C7IHIwj5NBNYaxFEtH1TyxTJZyUS78Q0O7U5cCr-OE7A7cJJ5qjwAgsnW5AJluqTfDNuFcHHAT1WaA4GA4SQvUtKtImNS9zuQKL8nnLDBIFXH31m7U13ktTI91STGNrJ5qh1uW6wxTsBwuMqYzZkkJNR5-Xhg6hMOD_KxVFs6oFdhSeeVYQ3f?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/tSh5fRKqiQ1onfgTXfammL7yq1qMEt7oRmodBczS0XIjtRpvMi6h99vjlXC_icb99N16mOxn3n9XzPFOAwdu2s34ABo39103NsOrerFVDQraB_43yu7peW4wMWzL49lDFeZRdwUdEYroPtRWSUcpUaSJFO7meKY7dYmhhalIz5hB5fpWQttL8G1AaW3DgkS6?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/k-PeW8InVlDVE8Cu3jeswr45GCuo3_skjVZxuf-vj5WqINH-EZzyFYHX6Ie1YMCj2PIf3Mmt8ezR8kNVT87wFMOFkulrASV3pKbARjafn-xKpr70vmTuRGjWF6zDDgrUircftyWG6iGjItiVU64mC4eHUnLz2atMwtg4IZGTzeHBS66hZYBKzwrb9xPBfftx?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/Yy-FUqhFHriUZuE7JiFA4TLn5cVHBYFC97ENHs9-q5gcwCAt0qJSPF_1VWXyLb4o0UOSweOltggfGBbHb1F2oFvIRjLPXKOjasYKB63VPj-0rTQs_M4RfiLEi0HGEOP2opDjCN7h5zHAJPOSKxZEuz7bOPIYJSEZaZ4RhOwDctKZ3u_hZ-MDNv4anbOVyT3R?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/HNjIpJ807hKnkftcbC_8Zx7BTtywPwy3de4PSUuoDJa2p-xPIwjWPC9N5-2T_CER_1YBMX69IR6atwXCfUVgVrWaPYp2c1x20-OCFE-i__O5PAeVSQQxnc5q9Mf1jcn9CumJK81xdaLxSWR78wx1uPqVWrYXWg1LEfFtmqBFijsLu_E-aCqdh8HD_8aYLgsd?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/e0LRrrZH_Ju2CJAKV4o8nTCKkFT12flkBGKnIiIWozV-0ZDaujkY7ACTdh8Ar9GX-sojZjoCd1oQ1ve_1NJSSy7Mw_2DeOuBHZDK1asWBkKuESuW7WlGkOps1FiWiEbe-7xnv7lkYtHEMSUSzFbff8XQ1RPLxNbe_W_vRZvD6x_-5wGDUONKXYX9SdAwrFxm?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/QXot69DAM7_Sdu2nQxgOdJxn_hi47xP9hR-2PE3ztmJLaYQx6qgcbuSbjlWbxpCfhVQSM_Nqm953wg8qLf7aqh9EaKEt1TAWTCUMsJwbZOXd3YxUs19ECzRcbnVmOJz82lc7tyGOPVae9H-fqOBAn3l3_9Vk4VfAjCm85FTWKBjVnlE62qN0Rv1yYPPa1vbL?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/iwuT_MIiQeLCyWO52TG3wZPhKsxJioNcvBZmVwMSPT8c6pfHtEJWdEfQvtpq_06QY1r0Zt1U8yzaQA2eY0tGix-cQb-bAkk6dKuNyTiSYJXG6KDKRVXeGhxceuQ2NZO9ceuFQ7zBkL6X1oTybO_GJWJ222shvcgyQytd2C56O5DxXPwd75p8vEgcQgzeUcjB?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/DJlRPaa1nF49066aOGFEm35lmPeCX5FUsu7g_A25eOBPuz8xl42uMnPx8FgRFyGy5uwR-PoaPJIlArSPzv0U6Taey6fnWqxwqct4Yk1NMIGiSypowQvrfrNvwyAWllRmtzVLwhv_lcdRu_obKQW-EPWUjNXpowWK7Mj00akUfUnMIg7HYmcMGXoPl43iINKb?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/R4KTAcQRIcRC2S0gQONoRuw3jootAA7J0U60qS2tXfxsYJbMD0nb8gbyDhmo0gMePPDa9eIuVF-246BJ3hhRbXiRDa5P1YS1p4MVhFe7LjTscSGVZJXfEldUD6DLKv5iFKJl41g0NozphTCKmZtucEenmkr_iXzuvsb7m1bDsYIyFynK98fkcwm4Kn_Qdeem?purpose=fullsize)

---

## 對話片段 430

CAST Imaging application architecture visualization

---

## 對話片段 431

Application Discovery :: English

---

## 對話片段 432

CAST Imaging Reviews 2026: Details, Pricing, & Features | G2

---

## 對話片段 433

AWS Marketplace: CAST Imaging Express (now with MCP server)

---

## 對話片段 434

CAST Imaging Java application dependency graph

---

## 對話片段 435

Changing applications with CAST Imaging Express | Documentation

---

## 對話片段 436

How CAST Imaging works :: English

---

## 對話片段 437

Exploring applications with CAST Imaging on Cloud | Documentation

---

## 對話片段 438

Leverage AI services | Documentation

---

## 對話片段 439

Improving applications with CAST Imaging Express | Documentation

---

## 對話片段 440

How Maps Can Accelerate Application Development And Software Maintenance

---

## 對話片段 441

([SciTools](https://scitools.com/understand?utm_source=chatgpt.com))

---

## 對話片段 442

citeturn566927search0turn566927search1

---

## 對話片段 443

image_group{"query":["SciTools Understand software architecture dependency graph","SciTools Understand call tree code analysis interface"]}

---

## 對話片段 444

![Image](https://images.openai.com/static-rsc-4/9cS-asv-eTyOFChFDccg4z_UWlAifS1TZGOyNO_ZRYcgXSBBMMDJrrzRPneJJi7X2kQbUPZO-78bfLPqasAKjcifvDRfZB4IMCtmxTGmdmJzXtPU9ZC1fqzFRNYdX2ffMMe3f2c2UzFO2Xnl-ffSu_Ol8d7XuHGemeCqlriO6ukVVkUF2Q7ADkFcBsFcj79H?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/KWyGElydVCjclB91e056lIVkh-1dfkgEFMTklWWcOJPB_eu49YFmVl-rbX9k8Al53OFLiwt6GzzFEMT6bcR1vW_aSrSV-InTK8j435mSM_xS0Rcr81E1A4pUffuqksQPJmEW6TE-Gs2GBlwVql_IG9Nbijt5m5DH9o2dnGi-NZK-eisEwOdUZptClBLs6KKk?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/WB21za0mWX1wzqnRhajJi0K8YEh0EveMAmJWvHkLLbYrFWw_vorNr022gRDnB8VxOlYc51M_nhn95IM_X2IdKcn-1Coh1KL7c3U3YjhI5Zyhf8zJRRS_g8vj0dxbYgCoLRShvvgaCKmxJekmr5Ljb0h0-Yb4jYDODc_1Rxt8NGekFtmv4AV_xChe988KXKpR?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/86iQEYjfoKmPKKLomd5r0OhDAr2TM0TjD89w8gtgmHpPuXyDD92yDLK-iiVRNZ7OHSg_l3nM-5pDJWsfEkn_O_pPf134OLRL5R2bOFCeCh-QxOVJPqfsOwfq5yHCCEYXGQ-QZZyEobrN5oHEwIhsviPTFO4sO38uXPs4Nd51MYpsH3NWz-cXbHHNZdCpIOxC?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/ID9-mo28_5ft3RfF0dLTxYeHnJnHCe83mQIknBVplMXmozmnqt8Ttxmk4nVlyx8jml9ahIwmlsuJVgGTxw-jwXZ0WUamhNh0AEUufc8IJnAhGmau0oJHOzHtzc-_C5Hci70lTFNfL-X-87f1otlU3HizfbVLrh0Mon8__rt1CWajdCLX29TAJPNs9MomSao6?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/TRaB9CiiHuFUGPSYjGwR2fjprUra8zxmnZG3tdPHPeY_Wm4hjJvvTrv132Nb8nM0Gd6sRt26cwsAWe1FiU8eWKhIqvhLjYnPj4OxiSlOSIbIKc-D_-E3pFK0pcNjAMlcNpN4X9iV-Q4he163qef6mjcf7YL94tWIuZQogTOQ9sLsB5f3I3qR--ePnvDma34D?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/mmDo8_ioIxxp41usHqNqEiU3t4wFoRTu6TiFdlBrqoKl1k_Vyf9LBZ6AgfG43hQcznXY-IVYH5jl2UEYyn2tupWP5ruMzkqYzIMy-O7qDMwl8pPNtT-R7B43ResFIjvWQujl9B_PbXrOQrRzL4FDzMh7HG357GkPaPD9oo32Ww9jwXR_xFEVcggjUX17qrLc?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/vnTl_-z78bafAkGC4meWXg3tENvowmFYe2wqsZc4t1VIm-yBkHtKlJAGqLiEleFx947Ix3aHmGToy2Rnnnmewzrl5wWGJE3ST9_6xOOGrd6qeljuTIeBOLQXu-3xPTR7nvfNqdiZ2eve66cuLBtrBpeJ4N7aSxO4VzfBxxeKM1rDmdgLjgHuDuQYtDpqaysr?purpose=fullsize)

---

## 對話片段 445

SciTools Understand call tree code analysis interface

---

## 對話片段 446

テクマトリックス、ソースコード解析ツール「Understand 5」日本語版を発売|CodeZine（コードジン）

---

## 對話片段 447

SciTools Understand software architecture dependency graph

---

## 對話片段 448

Unlocking Code Quality: Exploring Static Analysis with SciTools Understand | by Chiribiri Oussama | Medium

---

## 對話片段 449

ユースケース：影響分析 | ソースコード解析ツール Understand | テクマトリックス株式会社

---

## 對話片段 450

([GitHub](https://github.com/Cyrax321/CONTINUUM?utm_source=chatgpt.com))

---

## 對話片段 451

urlCONTINUUMhttps://github.com/Cyrax321/CONTINUUM

---

## 對話片段 452

[CONTINUUM](https://github.com/Cyrax321/CONTINUUM?utm_source=chatgpt.com)

---

## 對話片段 453

([GitHub](https://github.com/yaogdu/AgentLedger?utm_source=chatgpt.com))

---

## 對話片段 454

urlAgentLedgerhttps://github.com/yaogdu/AgentLedger

---

## 對話片段 455

[AgentLedger](https://github.com/yaogdu/AgentLedger?utm_source=chatgpt.com)

---

## 對話片段 456

([Temporal 文檔](https://docs.temporal.io/?utm_source=chatgpt.com))

---

## 對話片段 457

urlTemporalhttps://temporal.io/

---

## 對話片段 458

[Temporal](https://temporal.io/?utm_source=chatgpt.com)

---

## 對話片段 459

image_group{"query":["Temporal durable execution workflow architecture","Temporal workflow activity retry diagram"]}

---

## 對話片段 460

![Image](https://images.openai.com/static-rsc-4/qO-3VoVAOLKVlU-dPFUyc1GCkAa7ZFaR_1bi0xxVlayuiNUjDxLVPyCXEUyNxub2obcuC8UovbhUeYpaJZFMpWThkje_T6a0n1dmyNxGj03QiDLBmNV-a0Cte7-H0bu3HqlEVRMLyMlpYoL4IzpIGiJ2SlSZUYJnAb_SsQR2yiEwhyJELOUtwowoTYvq9_J3?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/rtE0l5IYtoyuDpIGwhS8-wIupKs04MS23T2F3utFc7NAlAOFVlX70tktYiUlVh5jm4M4UNBElyah__KLAuMGhYqM6YA0eKCVkoickK05QiQK6Hh-zCBMqZ5egwplS-lBKpF-NbpWmmxlsYIvrbTz0DsvtaQEhwjCTwKBBLNpPnk9AaWyynWkZlh5qltN3NJ2?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/qgFOg-zuky9TalxP0OL7cDK_Sryr7nkgrs7w1PPcX1GIhrTfGxxwVv1lfFzSkvGjwtI4e_KNHdqSI5vrf2jATb1nYXBBot3_WQcJvOiUdW55UPBfp3HmrqJpcHcpxHRX_txHkofo8szAPYLHcjR78V0amNIO4l75d0F_umRMyYmmKnlyCs9seSX2Xoel4y_0?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/i3iX2w7_KHDDrfA--Lgj13RmzFuc20-srFf5rRMwhd05Dc2zp2OvhMG-nviAgrSoMd_SoWribIqLIWMH3ceA5zOgzt_Bj9wNw1QRiW_UKkB0WZeAjbBW2Jpam0BRdrwDrJ68pc9cOxSnh5fMDvsC8Mmmt2MkE0S1iOH5c7dkEIy9aarU8BsZNXDW-srWgEm-?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/4nicjoU0o6_RBcQQfWU_mYwtQMBJYHnKtWJzLOG_J0UaQJnHEXLhs9XuJPLBgJUjmQFHphVWelLcdKuJfuQ7PcY3wXt1rs_g5sfnJv_ukyG6TVWLqahTOYzTb3f5u3y7jKCKBPYh4pBBqEyXuxswe2h04dzHd3oCwfYZXUrrxzl8bHRCQoU9dQeXuaypuEDN?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/cv2kj5VmChlCwN_OAMN2WhaPCFH5nHvCMUP9Q5SJeA7EOX7o7NV4cTUWyImBG9DkSjeLiOz8k84F9ALfXdGWDsak-vUNj1loy1-Kq5yfiUwWVb1Sq3pq5RnHZkMeTAQW75Kz8EcOjZjty4wLze-M65bkc2aW55iEsbHaykYK5TM3DcX0jMVRfys11BnMKVb3?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/GSqJjgFiaLuoUHmY_nB6OYR2otXhPIrrEIHR5raQR_NRw4RB82vJLiAIxTcWxqVP5JvmfRzmgpiIU_WoIxTmelRd3ci6lyIO9c6pfZEeU354SDrLgaIsrhm1Dv6835xumgNKDCcK2-n71LhPlfeQUE6QWA09PWYda0F-AZ9Cfv80x8rgpsyjvEB-4azHvF2-?purpose=fullsize)

---

## 對話片段 461

Temporal workflow activity retry diagram

---

## 對話片段 462

We Replaced Retry Spaghetti With Temporal — The Incident Pattern Changed Overnight | by Ark Protocol | Mar, 2026 | Medium

---

## 對話片段 463

Build a Temporal Application from scratch in Go | Learn Temporal

---

## 對話片段 464

Temporal durable execution workflow architecture

---

## 對話片段 465

Workflow execution engines like Temporal guarantee durable progress by persisting Event History and advancing execution via replay (until it reaches a terminal state or a timeout/retry… | Sri Chavali

---

## 對話片段 466

Temporal Durable Execution: Creating Bulletproof Applications

---

## 對話片段 467

Temporal: Durable Execution That Survives the Apocalypse // sprawl.sys

---

## 對話片段 468

How Temporal Guarantees Fault-Tolerant Systems: A Deep Dive 🚀 | by Siddhesh Jadhav | Medium

---

## 對話片段 469

Building more reliable applications with Temporal - DEV Community

---

## 對話片段 470

citeturn566927search5turn566927search14

---

## 對話片段 471

([GitHub](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/interrupts.mdx?utm_source=chatgpt.com))

---

## 對話片段 472

urlLangGraphhttps://www.langchain.com/langgraph

---

## 對話片段 473

[LangGraph](https://www.langchain.com/langgraph?utm_source=chatgpt.com)

---

## 對話片段 474

image_group{"query":["LangGraph checkpoint persistence architecture diagram","LangGraph agent workflow graph visualization"]}

---

## 對話片段 475

![Image](https://images.openai.com/static-rsc-4/1O1pX3yn9zbLrEk12Qne1e86br9uKol-eEXkoAJjdrioKJjSK0xDFm_wROiGT8LNNhIevVqwvM82VlO48apNAZh5rKwPoOjQbo55_86V_zQvb9sw-YpxP-FFSlRLx6Bk-Hup1S5DUGGrqizgwnPLfHRzUqNk7zsR995hOHyTY2Ms4O4Oye44akcPlPLYlSab?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/RkorK-xQHKuvZJHxntNsouojkcXDSJLBREPxCUQd2iZVakZnKuH2UHgmwEoFN6GpyJBDDPi4kdR4K4kpKcjRE58kLmEhS3E6Tgfdq1rOThfZ1uqq5UPWEXfktflOFKyGC5Y-c8-bRcBqjxsAbydPPkmvk0RtJqObYeTGBwoUkzANkUQUSuaJWvGsqGQ6bPw6?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/E_SEVWGBpVTyhUD3QmesrIrsf40mxc01oVE7GbQJRu8xi-AGKCNQzHCJbJifr_n001cblTwihsxvvhVyVqzB4gsqZvdKXrRR95Mj-B8sRnX0Q_mdu5eZugyNt2g-hof5d1gbXKFLaZQFz9GWCdLA7pZFKjr5YOEekD-ReDm4oLN_BjCiM50h335fDKVAIYId?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/0mAPJQouwzRfT39inwmwWGdWQmyzoFA96dxphK8QbxLKKye40vjhHuCHIDxPMNFkr6NROWgoNC-JyEfNboAnh2VGxwLYQs1HixPlreb3t6EY9T_n5aHlvzGdl-rpFHu-COnLP-6sCqwcG0Jm6RLuIK4Db6kyY0Vp_NG63vyBPzhj7B2bLENhZ1bEw9vNCuCe?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/34sFNOnCwE0ktKqb_kb0eQS7BUWAzYgWh1a6ua_mkJjDDT7KTs6-B3pSAtinJncFsMYRTXSI5xTlWOBBrtZqNWTuQX38XVhki7JUFpjlCTypzPnk4Ma9NSMmfIo3dJrOnYFs8wtjx0iIlJH0EPDrxfEH0lzJQuD9dAWsJM13O9xVgzICqJwudx4oeo3T7tQ1?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/fwX7gxwmg4vwCOocEZxWbWm9EP_-6BDTFH-LNXqEViACm4356_3jU1ooxWqhWE5tQyNTGmENbU8htf_o8RhB9QQNnn8hUptFYpeZdJKl8_RcfpykEUEqv9Jyrc-d9sZhioPAQv-J8DODzH4mzkl3g7UromLLQ-H875UWkEENU0bMm8qyD7cSrgUQPnRZNP6k?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/trh2E9g1LZK1UIls_S5Uc63G4nqOuFxev04Rh5ZKZ8CBCEjAnIzsKslgFMkF9ojhwTPwoPGqOJa2rdfEA-gQAZ1V3BOcPodRhDZNvUBfznBr9YC46NEPVM-2F0At5BQYZvksG6dLapu0NH7GJdGaKqkOWQvfA5W3WhBJGXGJjhA2O1j8bTJrGG_nQTa85qOa?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/3-5rFRaD_1rDp_fRhsKfHnEe7OYHUUQIJkH_6q3cf8hkp6fiZsGTgkQcPaDIQcDAVaufcd5kXDYH9qkhMnx7-vBXqWK-bV7wV2RKOx6FrAbALffgjSKn1QbncjhX86hnQSKPv3W_hvRBhIZ_Vws0x3mmDZz5HsZkxLpItLau3uXW8tQF5m2Qj3VXCWbMlHSf?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/d4xVnLBL0RcdPz2JGET5yEXxxiJVnSnNVk_Jvkp1ajtfwqKX9W5ro-VhRZrx6OiTj7ORiJlI6ZoOP8neG6xp3qq-131XW79UU2dj16wk4frBFKROIp4eMaQFALgMP2A-s79ce1PpvdOciRQMYcUTpzwre8SKd6o4_afDvMan7hsfvD1BDVi73zwdMYhjueWo?purpose=fullsize)

---

## 對話片段 476

LangGraph agent workflow graph visualization

---

## 對話片段 477

Building a Data Cleaning Agent with LangGraph

---

## 對話片段 478

LangGraph checkpoint persistence architecture diagram

---

## 對話片段 479

LangGraph 是怎么让 Agent "记住"对话的？拆解 thread 与 Checkpoint-腾讯云开发者社区-腾讯云

---

## 對話片段 480

Executing tasks with LangGraph — 2 | by Krishnan Sriram | Medium

---

## 對話片段 481

Unify Launches Agents for Account Qualification using LangGraph and LangSmith

---

## 對話片段 482

LangGraph Essentials in Python: Build AI Agent Workflows with State, Routing, and Human-in-the-Loop | by Rick Hightower | Mar, 2026 | Medium

---

## 對話片段 483

How LangGraph's Checkpointer Revolutionizes AI Workflows | SWAPNANIL SHARMAH ~ GenAI and Machine Learning Engineer posted on the topic | LinkedIn

---

## 對話片段 484

[note] LangGraph | PJCHENder I/O

---

## 對話片段 485

Build durable AI agents with LangGraph and Amazon DynamoDB | AWS Database Blog

---

## 對話片段 486

entity["software","CONTINUUM","long-running AI agent recovery project"]

---

## 對話片段 487

long-running AI agent recovery project

---

## 對話片段 488

entity["software","AgentLedger","durable AI agent runtime"]

---

## 對話片段 489

entity["software","Temporal","durable workflow platform"]

---

## 對話片段 490

entity["software","LangGraph","LangChain agent orchestration framework"]

---

## 對話片段 491

LangChain agent orchestration framework

---

## 對話片段 492

有，而且目前網路上已經有不少產品與開源專案，分別涵蓋你規劃的兩層架構。

不過，我先講一個重要結論：

**目前有很多產品能做到「AI 理解程式碼」，也有不少框架能做到「長任務 Checkpoint 與恢復」，但這兩種能力通常分屬不同產品。**

你的構想不是完全沒有競爭者，而是把兩個領域整合成一個可以持續執行、能中斷恢復、能產出可驗證技術成果的平台。

我將它們分成四類來比較。

# 一、第一類：長任務 Runtime 與 Checkpoint

這一類最接近你規劃的底層 Layer 1。

| 專案 | 開源／商業 | 主要功能 | 與你的 MVP 的關係 |
|---|---|---|---|
| entity["software","LangGraph","LangChain agent orchestration framework"] | 開源＋商業服務 | Agent 工作流程、狀態保存、Checkpoint、恢復 | 非常接近 |
| entity["software","Temporal","durable workflow platform"] | 開源＋商業雲端 | 長時間工作流程、失敗恢復、重試 | Runtime 的重要參考 |
| entity["software","AgentLedger","durable AI agent runtime"] | 開源 | Agent 執行紀錄、恢復、重播、證據 | 與可靠性設計高度相關 |
| entity["software","CONTINUUM","long-running AI agent recovery project"] | 開源 | 語意 Checkpoint、狀態驗證、恢復 | 與你的核心構想非常接近 |

### 1. LangGraph

image_group{"query":["LangGraph checkpoint persistence architecture diagram","LangGraph agent workflow graph visualization"]}

官方網站：urlLangGraphhttps://www.langchain.com/langgraph

這是我認為你最應該優先研究的專案之一。

它具有：
- Graph 工作流程控制。
- 每個節點執行後保存狀態。
- Checkpoint。
- 中斷後恢復。
- Human-in-the-loop。
- 多步驟 Agent 執行。

它的 Checkpoint 可以保存 Graph State，透過相同的 `thread_id` 接續執行。官方也提供 SQLite、PostgreSQL 等 Checkpointer。citeturn566927search5turn566927search14

**與你的差異：**

LangGraph 提供的是 Agent 工作流程與狀態管理基礎，你還需要自己實作：
- Java 專案分析流程。
- 程式碼索引。
- 分析證據管理。
- 技術交接報告。
- 專案知識累積。

我的建議：第一版不一定要自己從零實作 State Machine，可以先研究 LangGraph 的 Checkpoint 機制，評估是否直接採用。

### 2. Temporal

image_group{"query":["Temporal durable execution workflow architecture","Temporal workflow activity retry diagram"]}

官方網站：urlTemporalhttps://temporal.io/

這個專案值得研究的原因是，它本來就專注於長時間、可靠的工作流程。

例如：

```text
開始任務
   ↓
執行分析
   ↓
保存狀態
   ↓
系統突然關閉
   ↓
重新啟動
   ↓
恢復工作流程
   ↓
繼續執行
   ↓
完成任務
```

Temporal 的核心是 Durable Execution，官方說明支援在程式崩潰、網路故障或基礎設施中斷後恢復工作流程。citeturn566927search7

但要注意：

Temporal 是通用工作流程平台，不是專門為小模型 Context Window 設計的 Agent 接力系統。

如果你未來希望支援：
- 多個 Agent 同時工作。
- 長時間程式分析。
- 多任務排程。
- 服務中斷後恢復。
- 跨機器執行。

Temporal 就會是很值得研究的底層技術。

### 3. AgentLedger

GitHub：urlAgentLedgerhttps://github.com/yaogdu/AgentLedger

這個專案與你的構想相當接近。

它的定位是 Agent Runtime Reliability Layer，重點包括：

- 長任務 Checkpoint。
- Crash Recovery。
- Tool Ledger。
- 執行紀錄。
- Replay。
- Evidence。
- SQLite、PostgreSQL 等儲存後端。

它不是要取代 LangGraph，而是希望補足 Agent 執行過程的可靠性。citeturn740632search0

我認為值得列入技術評估清單。

### 4. CONTINUUM

GitHub：urlCONTINUUMhttps://github.com/Cyrax321/CONTINUUM

這個專案尤其值得注意。

它提出一個與我們之前討論非常接近的觀念：

**不要保存整段對話作為任務記憶，而是保存能夠讓 Agent 繼續工作的語意 Checkpoint。**

它的設計包含：

- Semantic Checkpoints。
- Idempotent Action Ledger。
- Hash-chained Event Log。
- 環境重新驗證。
- SQLite / PostgreSQL 持久化。

這與你希望解決的「Context Window 滿了，換一個 Session 繼續工作」高度相關。citeturn740632search9

不過，這類新興專案應先檢查程式碼品質、測試覆蓋率、授權及實際 Crash Recovery 測試，不宜只看 README 就直接當成生產級元件。

---

# 二、第二類：AI 程式碼理解與逆向工程

這一類最接近你的上層 Layer 2。

這裡有幾個成熟度較高的產品。

## 1. SciTools Understand

image_group{"query":["SciTools Understand software architecture dependency graph","SciTools Understand call tree code analysis interface"]}

官方網站：urlSciTools Understandhttps://scitools.com/understand

這是我認為你必須深入研究的競品。

它不是一般的 AI Coding Assistant，而是專業的程式碼靜態分析與理解工具。

主要功能：

| 功能 | 說明 |
|---|---|
| Code Navigation | Class、Function、Variable 導覽 |
| Call Graph | 方法呼叫關係 |
| Cross Reference | 程式碼交互引用 |
| Dependency Graph | 模組依賴關係 |
| Architecture | 軟體架構分析 |
| Metrics | 複雜度與品質指標 |
| Offline AI | 本地 AI 輔助程式理解 |
| API / CLI | 自動化分析 |

特別值得注意的是，它已經提供離線 AI 助理，可以使用解析後的程式模型回答問題，而不是只把原始程式碼塞進 LLM Context。官方也說明支援離線及 Air-gapped 環境。citeturn566927search0turn566927search1

官方公開的起始價格為每月 US$100，採年約制；實際企業授權仍需詢價。citeturn566927search0

**對你的啟發：**

它已經證明一件事：

使用者真正需要的不是 AI 幫忙讀程式碼，而是先建立一個可靠的程式結構模型，再讓 AI 根據這個模型回答問題。

這與我們規劃的 Parser + LLM 架構一致。

## 2. CAST Imaging

image_group{"query":["CAST Imaging application architecture visualization","CAST Imaging Java application dependency graph"]}

官方網站：urlCAST Imaginghttps://www.castsoftware.com/products/imaging

這是企業級應用系統逆向工程與架構分析平台。

主要功能：

- 自動分析應用程式結構。
- 建立程式元件知識庫。
- 分析程式依賴。
- 視覺化應用程式架構。
- 變更影響分析。
- 找出應用程式技術風險。

官方文件指出支援超過 150 種技術，包括 Java/JEE、.NET、SQL、Web 及 Mainframe。citeturn566927search9

**它與你的產品非常接近的地方：**

都是希望讓使用者不必逐一閱讀龐大的舊系統程式碼，就能理解整個系統的組成與依賴。

主要差別在於你的構想還希望加入長時間自主執行、跨 Session 恢復，以及地端小模型的任務接力。

## 3. CodeScene

image_group{"query":["CodeScene code health visualization dashboard","CodeScene software architecture hotspots visualization"]}

官方網站：urlCodeScenehttps://codescene.com/

它比較偏向軟體品質、技術債與開發風險分析。

主要功能：
- Code Health。
- 技術債分析。
- 程式複雜度。
- 變更熱點。
- 維護風險。
- 團隊開發活動分析。

它的特色是結合程式碼結構與版本控制歷史，找出容易出問題的區域。citeturn566927search13

這類工具可以成為你未來的擴充方向，但不是第一版 MVP 的直接替代品。

## 4. Sourcegraph

image_group{"query":["Sourcegraph code search cross repository interface","Sourcegraph Cody AI codebase understanding"]}

官方網站：urlSourcegraphhttps://sourcegraph.com/

主要能力：

- 跨 Repository 搜尋。
- Code Navigation。
- Cross Reference。
- AI Code Search。
- 自然語言詢問程式碼。
- 程式變更影響分析。

其中 Deep Search 可以透過 AI 搜尋程式碼並提供有來源依據的答案。citeturn740632search4

它比較偏向大型企業的程式碼搜尋與理解，不是完整的長任務 Runtime。

---

# 三、第三類：開源程式碼理解工具

如果你希望降低 MVP 開發成本，這一類非常重要。

| 專案 | 主要功能 | 適合你的用途 |
|---|---|---|
| entity["software","CodeGraph","local code intelligence graph"] | 程式碼結構圖、Symbol、Dependency | 建立程式知識索引 |
| entity["software","Jar Analyzer","Java bytecode analysis tool"] | JAR 分析、Call Chain、Taint Analysis | Java 舊系統逆向工程 |
| entity["software","JStudio","Java reverse engineering IDE"] | Java Bytecode、AST、Call Graph | 深入分析 Java 程式 |
| entity["software","Understand Anything","codebase knowledge graph"] | 多 Agent 分析、知識圖譜、架構視覺化 | AI 程式碼理解 |
| entity["software","JavaParser","Java AST parser"] | Java AST、Symbol Resolution | 自行開發 Parser |

### 值得特別研究的兩個專案

**A. CodeGraph**

urlCodeGraph 專案文件https://colbymchenry.github.io/codegraph/getting-started/introduction/

它採用 Tree-sitter 解析程式碼，將 Symbol、關係及檔案資訊存入 SQLite，並提供 MCP、CLI 等介面。

這個設計與我們之前規劃的：

```text
程式碼
  ↓
Parser
  ↓
Symbol Index
  ↓
Dependency Graph
  ↓
MCP
  ↓
AI Agent
```

非常接近。citeturn740632search6

**B. Jar Analyzer**

urlJar Analyzer GitHubhttps://github.com/jar-analyzer/jar-analyzer

這個專案針對 Java Bytecode 分析，功能包括：

- Method Call Chain。
- DFS 呼叫鏈分析。
- Taint Analysis。
- Java Web 元件入口分析。
- Control Flow Graph。
- AI Assistant。

如果未來需要分析沒有完整原始碼的舊 Java 系統，這個方向值得研究。citeturn740632search7

---

# 四、第四類：競品功能比較

這張表是我認為最值得你參考的部分。

| 產品／專案 | 長任務恢復 | 程式結構分析 | AI 語意理解 | 地端部署 | 技術交接報告 |
|---|---|---|---|---|---|
| LangGraph | ★★★★★ | — | ★★★★★ | 可 | 需自行開發 |
| Temporal | ★★★★★ | — | 需整合 | 可自架 | 需自行開發 |
| AgentLedger | ★★★★★* | — | 需整合 | 可 | 證據／執行紀錄 |
| CONTINUUM | ★★★★★* | — | 需整合 | 可 | 需自行開發 |
| Understand | — | ★★★★★ | ★★★★ | ★★★★★ | 有報表能力 |
| CAST Imaging | — | ★★★★★ | 依產品方案 | 需確認部署方案 | 有分析成果 |
| CodeScene | — | ★★★★ | 輔助分析 | 依方案 | 有品質報告 |
| Sourcegraph | — | ★★★★★ | ★★★★★ | 依產品方案 | 需整合 |
| CodeGraph | — | ★★★★ | 交由外部 Agent | ★★★★★ | 需自行開發 |
| Jar Analyzer | — | ★★★★★ | 有 AI Assistant | ★★★★★ | 部分支援 |

註：
- 星級是依照公開功能與你的 MVP 需求所做的相對評估，不是產品官方評分。
- `—` 表示不是該產品的核心功能。
- `*` 表示專案定位與功能設計高度相關，但不代表已經達到成熟商業平台的可靠性保證。
- 「地端部署」是指具備本地或自架可能性，不代表所有功能、模型與企業授權都完全離線。

---

# 五、這些競品對你的 MVP 有什麼影響？

我認為應該區分三種情況。

### 情況 A：直接使用現有工具，不自己開發

例如：

```text
LangGraph
   +
Understand
   +
Ollama
```

優點：
- 開發時間短。
- 可以快速驗證產品價值。
- 不必重新開發成熟的程式分析功能。

缺點：
- 系統整合成本。
- 產品能力受限於現有工具。
- 不容易掌握完整任務生命週期。

### 情況 B：自己開發 Runtime，整合現有 Parser

例如：

```text
             自行開發
       Long-running Runtime
                │
                ▼
          Task Orchestrator
                │
        ┌───────┴────────┐
        ▼                ▼
    Checkpoint       Recovery
        │                │
        └───────┬────────┘
                │
                ▼
           Code Analyzer
                │
       ┌────────┼────────┐
       ▼        ▼        ▼
   JavaParser CodeGraph SQLGlot
       │        │        │
       └────────┼────────┘
                ▼
              Ollama
                │
                ▼
          技術交接報告
```

這是我最推薦的 MVP 方向。

原因是：
- Runtime 是你想驗證的核心。
- Parser 不需要全部自己開發。
- LLM 可以專注在業務邏輯理解。
- 未來可以更換模型與分析工具。

### 情況 C：全部自行開發

包含：
- Runtime。
- Checkpoint。
- Parser。
- Symbol Index。
- Dependency Graph。
- LLM Adapter。
- Agent。
- Report Generator。

這種方式自由度最高，但第一版開發成本也最高。

我不建議現在採用。

---

# 六、我對這個產品的競爭定位建議

這裡有一個很重要的判斷。

如果你的產品只是：

> 把程式碼切片，送給 Ollama 分析，最後產生 Markdown。

那麼市場上已經有許多工具可以做到類似的事情，差異化不夠。

但如果你的產品定位是：

> 使用者交付一個複雜任務，AI 自動規劃、長時間執行、保存工作狀態、跨 Session 接續，並且累積可以追溯與驗證的專案知識。

這就不只是程式碼分析工具了。

我建議把競爭優勢放在五個地方：

| 差異化能力 | 現有市場的常見情況 | 你的產品目標 |
|---|---|---|
| 長任務管理 | Agent 通常由特定工作流程或 Session 控制 | 任務可以持續數小時或更久 |
| 跨 Session 接力 | 部分工具有狀態保存，但不一定涵蓋完整任務 | 自動交接與恢復 |
| 跨模型接力 | 常與特定模型或 Agent Host 綁定 | 模型可替換 |
| 專案知識累積 | 程式索引與聊天記憶通常分開 | 結構化知識持續累積 |
| 可驗證成果 | AI 回答可能缺乏完整證據 | 每個重要結論都有程式碼依據 |

## 七、我建議你優先深入研究的五個專案

如果是為了決定第一版 MVP 的技術架構，我會按照以下順序：

| 優先順序 | 專案 | 研究目的 |
|---|---|---|
| 1 | LangGraph | 研究 Checkpoint、Resume、狀態管理 |
| 2 | AgentLedger | 研究 Agent 執行可靠性與 Evidence |
| 3 | CONTINUUM | 研究語意 Checkpoint 與跨 Session 接續 |
| 4 | Understand | 研究成熟的程式碼結構分析與離線 AI |
| 5 | CodeGraph | 研究本地程式知識圖譜與 MCP |

**另外，我特別建議你先實際比較 LangGraph、AgentLedger 和 CONTINUUM 的恢復機制。**

因為你的第一個核心假設是：

「小模型的 Context Window 用完，或者程式被強制中斷後，能不能真正接續原本的任務？」

這三個專案提供了很好的研究起點。

而 Understand 則是另一個重要的競品基準：它能幫你確認，自己開發的程式分析層究竟還缺少哪些成熟能力。

**下一步最有價值的工作，是製作一份「五個核心競品的深度拆解報告」：逐一比較架構、原始碼、Checkpoint 機制、模型接入方式、授權、部署方式，以及哪些功能值得直接採用、哪些才是真正值得自己開發的差異化能力。**

---

## 對話片段 493

bcb30367-867d-4abc-8cab-1e405b8341be

---

## 對話片段 494

c08ce665-6f7d-4c41-b3dd-4096da72de28

---

## 對話片段 495

3770d200-e683-4538-bed9-d88842aa74bc

---

## 對話片段 496

7757ff41-12c4-4c17-bf96-bb25651c6a20

---

## 對話片段 497

adc7b7e1-0744-457e-990e-a138e51924f9

---

## 對話片段 498

b1fe9beb-9e48-4107-97ce-4663ff51acc9

---

## 對話片段 499

177de171-4f15-4073-a35b-2440ce3b963d

---

## 對話片段 500

dcfb688d-471e-4103-b873-f75313da7817

---

## 對話片段 501

thread_6ae9286ca5108321bc8800dc0e94bb16

---

## 對話片段 502

8ac7a639-2d3b-4d03-a6d6-c7d5f94458f4

---

## 對話片段 503

315d6f45-57a6-47a5-8d79-fa74dced35a8

---

## 對話片段 504

1a9066e0-1067-47a6-83aa-4f080271bf23

---

## 對話片段 505

c2022f5d-6685-47c8-9eeb-cb8858a601d9

---

## 對話片段 506

aac2f937-73e4-4898-9b69-200f71a6b51e

---

## 對話片段 507

04190c0b-3300-4274-b9d7-7c81a0d55b6f

---

## 對話片段 508

正在搜尋 open source durable execution agent framework checkpoint resume long running AI agents LangGraph Temporal Letta AutoGen OpenHands codebase analysis reverse engineering tools 2026

---

## 對話片段 509

正在搜尋 commercial AI codebase understanding architecture analysis platform Sourcegraph Cody Cursor Claude Code CodeScene CAST software intelligence features

---

## 對話片段 510

正在搜尋 open source Java code analysis call graph architecture reverse engineering tool JavaParser Understand alternatives

---

## 對話片段 511

d6bd4d4f-abde-47d7-ba2c-2087e7346d44

---

## 對話片段 512

4af8df97-7e1b-4469-8699-d1e9f3664c88

---

## 對話片段 513

775af237-9e16-4755-b562-b880fe8a63e7

---

## 對話片段 514

bbb21cb0-80aa-4e40-9a7e-e09ad3ec5994

---

## 對話片段 515

fd3a9009-ae80-4052-95af-8ed725f785c4

---

## 對話片段 516

5379523f-9ca2-4a45-b993-afa45e25ff03

---

## 對話片段 517

3bc4a3ab-3ff7-4864-9bfe-5d353f774a1b

---

## 對話片段 518

705ed590-f032-41d0-b156-96ed453c75df

---

## 對話片段 519

這份 MVP 規劃方向正確，而且已經具備一個很重要的特點：**不是先做出一套功能龐大的 AI Agent 平台，而是用一個真實的舊系統分析任務，同時驗證底層 Runtime 與上層 AI 分析能力。**

不過，我建議在正式定案前，調整幾個關鍵設計。尤其是 Checkpoint 的保存方式，否則可能出現「看起來可以 Resume，實際上卻遺失工作成果」的問題。

以下是我建議的第一版 MVP。

# 一、MVP 的產品定位

第一版先不要定位成完整的 AI 程式開發平台。

建議定位為：

**AI 長任務執行平台：舊系統逆向工程驗證版**

使用者只需要提供一個 Java 舊系統子目錄，系統就能：

1. 自動盤點程式碼。
2. 分析 Class、Method 與 SQL。
3. 分批呼叫地端小模型。
4. 持續保存分析進度。
5. 中斷後自動恢復。
6. 最後產出具有程式碼證據的技術分析報告。

這個 MVP 要回答兩個問題：

| 驗證方向 | 核心問題 | 成功的意義 |
|---|---|---|
| 底層 Runtime | 任務中斷後能否正確恢復？ | 證明長任務執行機制可行 |
| 上層 Code Intelligence | 小模型能否理解實際程式？ | 證明產品具有實際使用價值 |

兩者必須分開驗證。

例如，Runtime 成功恢復了 100 次，但最後產生的報告沒有價值，產品仍然不成功。

反過來，AI 能產生漂亮的報告，但每次中斷都要重新分析，也不符合這個產品的核心目標。

---

# 二、第一版 MVP 的整體架構

我建議先採用非常精簡的雙層架構。

```text
┌──────────────────────────────────────────────┐
│             使用者操作介面                    │
│                                              │
│  選擇專案目錄 / 建立任務 / 查看進度 / 查看報告 │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│       Layer 2：Code Intelligence              │
│       程式碼智慧分析應用                      │
│                                              │
│  ┌──────────────┐   ┌─────────────────────┐  │
│  │ Code Scanner │   │ Java Parser         │  │
│  │ 檔案盤點      │   │ Class / Method      │  │
│  └──────────────┘   └─────────────────────┘  │
│                                              │
│  ┌──────────────┐   ┌─────────────────────┐  │
│  │ Analyzer     │   │ LLM Adapter         │  │
│  │ 分析流程控制  │──▶│ Ollama / Local LLM  │  │
│  └──────────────┘   └─────────────────────┘  │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ Report Generator                       │  │
│  │ 技術交接文件 / 架構分析 / 資料流報告    │  │
│  └────────────────────────────────────────┘  │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│       Layer 1：Long-running Task Runtime     │
│       通用長任務執行平台                      │
│                                              │
│  ┌──────────────┐   ┌─────────────────────┐  │
│  │ Orchestrator │   │ Task State Machine  │  │
│  │ 任務管理      │   │ 狀態管理             │  │
│  └──────────────┘   └─────────────────────┘  │
│                                              │
│  ┌──────────────┐   ┌─────────────────────┐  │
│  │ Checkpoint   │   │ Recovery Manager    │  │
│  │ 進度保存      │   │ 中斷恢復             │  │
│  └──────────────┘   └─────────────────────┘  │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ Execution Controller                   │  │
│  │ 控制分析步驟、重試、逾時與執行順序       │  │
│  └────────────────────────────────────────┘  │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│              Local Storage                   │
│                                              │
│  SQLite：任務狀態與執行紀錄                   │
│  JSON：Checkpoint 快照                        │
│  Markdown：分析報告                           │
│  JSON：Method 分析結果                        │
└──────────────────────────────────────────────┘
```

這裡有一個重要原則：

**Layer 2 不應該自己管理任務恢復，所有任務的生命週期都交給 Layer 1。**

未來即使把上層應用換成：
- AI 測試平台
- 程式碼安全分析
- 系統架構逆向工程
- 技術文件產生
- 維運問題診斷

底層 Runtime 都不需要重新設計。

---

# 三、底層 Runtime：我建議修正 Checkpoint 設計

你原本提出使用 `state.json` 保存進度，方向沒錯，但如果要驗證真正的中斷恢復，單純依靠 JSON 檔案還不夠。

## 3.1 不建議只用 state.json

例如：

```json
{
  "task_id": "legacy_analysis_001",
  "current_stage": "ANALYZING",
  "completed_modules": [
    "OrderServlet.java",
    "OrderDAO.java"
  ],
  "pending_modules": [
    "PaymentService.java"
  ]
}
```

這個設計有三個問題：

1. 不知道某個檔案究竟分析到哪個 Method。
2. 不知道分析結果是否已經成功寫入磁碟。
3. 不知道程式中斷時，LLM 是否已經執行完畢但尚未保存結果。

因此，我建議：

**SQLite 作為唯一的任務狀態來源，JSON 作為可讀取的 Checkpoint 快照。**

## 3.2 Checkpoint 應該以 Step 為單位

不要只記錄：

```text
OrderServlet.java 已完成
```

而應該記錄：

```text
OrderServlet.java
 ├── doGet()          COMPLETED
 ├── doPost()         COMPLETED
 ├── createOrder()    COMPLETED
 ├── validateOrder()  PENDING
 └── queryOrder()     PENDING
```

這樣即使分析到一半中斷，也只需要重新執行尚未完成的工作。

建議每一個 Step 都有自己的執行紀錄：

| 欄位 | 用途 |
|---|---|
| task_id | 任務識別碼 |
| step_id | 工作步驟識別碼 |
| module_id | 所屬模組 |
| symbol_id | Class / Method 識別碼 |
| status | PENDING / RUNNING / COMPLETED / FAILED |
| attempt_count | 執行次數 |
| input_hash | 輸入程式碼 Hash |
| output_path | 分析結果位置 |
| output_hash | 分析結果 Hash |
| started_at | 開始時間 |
| completed_at | 完成時間 |

## 3.3 真正的 Resume 必須處理三種中斷

這是我認為 MVP 最值得測試的地方。

| 中斷時機 | 可能發生的問題 | 正確恢復方式 |
|---|---|---|
| Step 尚未開始 | 尚無分析結果 | 直接執行 |
| LLM 正在分析 | 沒有完整結果 | 重新執行該 Step |
| LLM 已完成，但結果尚未保存 | 執行結果可能遺失 | 重新執行或核對已保存產物 |
| 結果已保存，但狀態尚未更新 | 狀態與實際成果不一致 | 核對 Hash 後補登完成狀態 |

這裡要特別說明：

MVP 不必追求分散式系統的 Exactly-once Execution。

實務上可以採取：

**At-least-once Execution + Idempotent Step + Result Verification**

也就是：
- 同一個 Step 可能重跑。
- 但不能因為重跑而產生錯誤或重複結果。
- 每個 Step 都必須能驗證產物是否存在、是否完整。

這才是可靠的中斷恢復機制。

---

# 四、上層 Code Intelligence：不要只做 Method 摘要

這是第二個需要調整的地方。

你提出的三階段：

```text
Method 分析
    ↓
Class 分析
    ↓
全域報告
```

可以作為第一版主流程，但如果只有這三個步驟，最後可能得到一份文字很多、實際架構價值有限的報告。

我建議增加一個非常重要的能力：

**程式碼之間的關聯分析。**

例如：

```text
OrderServlet
     │
     ▼
OrderService
     │
     ▼
OrderDAO
     │
     ▼
Oracle Database
     │
     ├── ORDER_HEADER
     └── ORDER_DETAIL
```

使用者真正想知道的不是：

「OrderServlet 的 doPost() 做了什麼？」

而是：

「使用者送出訂單後，整個系統發生了什麼事？」

因此，第一版應該有以下五個分析階段。

| 階段 | 分析內容 | 主要執行者 |
|---|---|---|
| P1 | 檔案盤點、Class、Method、SQL 抽取 | Python / Parser |
| P2 | Method 業務邏輯分析 | Local LLM |
| P3 | Class 之間的呼叫關係 | Parser + Local LLM |
| P4 | 資料流與資料表關聯分析 | SQL Parser + Local LLM |
| P5 | 系統架構與技術交接報告 | Local LLM + Python |

注意：呼叫關係、SQL 與資料表名稱應盡可能由 Parser 抽取，而不是全部交給 LLM 猜測。

例如：

```text
OrderServlet.createOrder()
    calls
OrderService.createOrder()
    calls
OrderDAO.insertOrder()
    executes
INSERT INTO ORDER_HEADER
```

如果能夠從原始程式碼找到這些證據，就應該直接引用證據。

LLM 的工作是解釋這些關係代表什麼業務意義。

## 建議的第一版報告內容

最後的 Markdown 不應只是程式摘要，而應該包含：

| 報告章節 | 使用者價值 |
|---|---|
| 1. 系統功能概述 | 快速知道這個子系統在做什麼 |
| 2. 系統模組架構 | 了解有哪些主要 Class 與模組 |
| 3. 核心業務流程 | 了解主要操作的執行順序 |
| 4. 程式呼叫關係 | 了解 Method 之間的依賴 |
| 5. 資料庫存取分析 | 知道使用哪些資料表及 SQL |
| 6. 外部系統介接 | 找出 API、其他系統與服務依賴 |
| 7. 關鍵程式位置 | 能快速定位原始程式碼 |
| 8. 技術風險與待確認事項 | 找出需要人工確認的地方 |

每個重要結論都應有：

- 檔案名稱
- Class / Method
- 程式碼行號
- 分析依據
- 信心程度
- 是否經過驗證

例如：

```text
結論：
建立訂單時，系統會先寫入訂單主檔，再寫入訂單明細。

證據：
OrderDAO.java
  insertOrderHeader()   Line 125
  insertOrderDetail()   Line 183

分析狀態：
已找到 SQL 證據，尚未驗證交易回滾行為。

信心程度：
高（SQL 與呼叫順序有原始程式碼支持）
```

這樣才有機會成為真正可用的技術交接文件。

---

# 五、MVP 技術選型

第一版不要把技術堆得太複雜。

我建議：

| 元件 | 第一版選擇 | 責任 |
|---|---|---|
| 開發語言 | Python | Runtime 與分析流程 |
| 任務資料庫 | SQLite | 保存任務狀態 |
| Checkpoint | JSON | 進度快照 |
| Java Parser | JavaParser 或 Python 輔助解析 | Class / Method |
| SQL Parser | SQLGlot | SQL 抽取與分析 |
| LLM | Ollama | 本地模型推論 |
| 模型 | Qwen Coder 7B / 14B | 程式語意分析 |
| 報告 | Markdown | 技術交接文件 |
| 執行環境 | Windows 11 + Podman | 本地部署 |

第一版先不要導入：

- Neo4j
- pgvector
- Kubernetes
- 多 Agent 協作
- 分散式任務排程
- 複雜 Web 前端
- 多模型動態路由

這些都不是目前兩個核心假設的必要條件。

---

# 六、第一版 MVP 的驗收標準

我會把驗收分成兩大部分，而不是只看 30 分鐘能否跑完。

## A. 底層 Runtime 驗收

| 測試編號 | 測試項目 | 驗收標準 |
|---|---|---|
| R01 | 正常執行 | 能完成所有預定分析 Step |
| R02 | 執行中強制 Kill | 能從最後有效 Checkpoint 恢復 |
| R03 | LLM 執行期間 Kill | 能重新執行該 Step |
| R04 | 結果寫入後 Kill | 不會遺失已保存的分析成果 |
| R05 | 重複啟動 | 不會重複產生錯誤成果 |
| R06 | 模型服務中斷 | 任務進入可恢復狀態 |
| R07 | 分析失敗 | 能記錄錯誤並依規則重試 |
| R08 | 連續執行 | 能持續運作 30 分鐘以上 |

其中 R02～R04 比單純測試 30 分鐘更重要。

因為它們真正驗證的是 Runtime 的可靠性。

## B. 上層 AI 分析驗收

| 測試編號 | 測試項目 | 驗收標準 |
|---|---|---|
| A01 | Class / Method 抽取 | 與人工抽查結果一致 |
| A02 | Method 分析 | 能正確描述主要處理邏輯 |
| A03 | 呼叫關係 | 能找到主要程式呼叫鏈 |
| A04 | SQL 分析 | 能正確識別主要資料表 |
| A05 | 業務流程 | 能還原至少一條完整核心流程 |
| A06 | 技術報告 | 內容可供架構師閱讀 |
| A07 | 證據追溯 | 重要結論能定位原始程式 |
| A08 | 不確定性標示 | 不會把推測當成已確認事實 |

另外，我建議加入一個最重要的使用者價值指標：

**人工閱讀時間節省比例。**

例如：

```text
人工分析：
工程師花費 4 小時閱讀 50 個檔案。

AI 輔助：
AI 執行 1 小時，工程師花費 30 分鐘檢查報告。

人工時間節省：
(4 - 0.5) / 4 = 87.5%
```

這只是示意數字，實際驗收時應以人工獨立分析作為基準組，再與 AI 輔助分析比較，並確認報告品質沒有明顯下降。

因為我們最後要證明的不是 AI 能不能產生 Markdown，而是：

**AI 能不能讓工程師更快理解一個陌生的舊系統。**

---

# 七、建議的 MVP 開發順序

我不建議一次把所有功能寫完。

採取五個階段，每個階段都有可執行的成果。

| Phase | 開發內容 | 階段成果 |
|---|---|---|
| Phase 1 | Runtime、SQLite、Task State、Checkpoint | 可建立、保存、恢復任務 |
| Phase 2 | Java Scanner、Class / Method Parser | 可盤點 50～100 個檔案 |
| Phase 3 | Ollama Adapter、Method Analyzer | 可逐一分析 Method |
| Phase 4 | 呼叫關係、SQL、資料流分析 | 可還原核心程式流程 |
| Phase 5 | Report Generator、驗收測試 | 產生完整技術交接報告 |

每個 Phase 都應該能獨立測試。

尤其 Phase 1 必須先用假任務測試中斷恢復，不要等到整個 AI 分析系統完成後才測試 Runtime。

---

# 八、我認為還缺少一個關鍵設計：測試資料集

這是整個 MVP 能否客觀驗證的關鍵。

建議準備一個固定的 Java 舊系統測試子專案：

```text
mvp-test-project/
│
├── servlet/
│   ├── OrderServlet.java
│   └── PaymentServlet.java
│
├── service/
│   ├── OrderService.java
│   └── PaymentService.java
│
├── dao/
│   ├── OrderDAO.java
│   └── PaymentDAO.java
│
├── model/
│   ├── Order.java
│   └── Payment.java
│
├── sql/
│   ├── order.sql
│   └── payment.sql
│
└── config/
    └── application.properties
```

這個測試專案應該事先由人工建立一份「標準答案」。

例如：

- 有幾個 Class？
- 有幾個 Method？
- 訂單建立流程是什麼？
- 哪些 Method 會呼叫 OrderDAO？
- 使用哪些資料表？
- 付款流程是否會更新訂單狀態？

然後讓 AI 分析結果與標準答案比較。

這樣才能知道小模型究竟分析得好不好，而不是只靠閱讀報告後的主觀感覺。

---

# 九、第一版 MVP 的最終範圍

我會把第一版正式限制在以下範圍：

| 項目 | MVP 決策 |
|---|---|
| 執行模式 | 單機 |
| 使用者 | 單一使用者 |
| 分析對象 | Java 舊系統子目錄 |
| 檔案規模 | 50～100 個檔案 |
| 任務數量 | 同一時間執行一個任務 |
| 模型 | 地端小模型 |
| 任務恢復 | 支援 Process Kill / Restart |
| 分析方式 | Parser + Deterministic Pipeline + LLM |
| 報告格式 | Markdown + JSON |
| 前端 | 第一版可先用 CLI 或簡易 Web UI |
| 多 Agent | 不支援 |
| 多模型協作 | 暫不支援 |
| 分散式執行 | 不支援 |

## 最終成功判斷

這個 MVP 必須同時通過三個關卡：

| 關卡 | 必須證明的事情 |
|---|---|
| 第一關：可靠性 | 任務中斷後，可以恢復，不需要從頭分析 |
| 第二關：分析能力 | 地端小模型能夠理解程式，產出有證據的洞察 |
| 第三關：使用價值 | 報告能實際縮短工程師理解舊系統的時間 |

**三關全部通過，才值得擴大成通用長任務平台。**

我的建議是：第一版先不要急著開發漂亮的操作介面，也不要急著導入向量資料庫。先把「一個任務能執行、能中斷、能恢復、能完成分析、能產出可信報告」這條完整流程做通。

這條流程一旦驗證成功，你們就不只是做出一個 Java 分析工具，而是建立了未來可以承載 AI 測試、資安分析、維運診斷等應用的底層 Runtime。

---

## 對話片段 520

487ece71-bca8-4d59-90b4-c229dc663e8d

---

## 對話片段 521

650f00ac-c257-4ea5-83e3-02a44d1e4125

---

## 對話片段 522

5ff2ccb2-e9a5-419b-b6d0-b13d2015c55d

---

## 對話片段 523

bbb21ee6-b464-4386-a679-e7337527cc5d

---

## 對話片段 524

11f4d3b6-ee6e-4471-8e00-a940abd65c37

---

## 對話片段 525

規劃這個雙層架構的第一版 MVP（最小可行性產品），核心目標不是追求「功能最多」，而是要驗證兩個最關鍵的假設：
 * 底層驗證：任務在長時間執行、甚至中斷後，能否靠 Checkpoint 真正做到「無縫接力（Resume）」？
 * 上層驗證：地端小模型配合拆解後的分析步驟，能否產出對使用者真正有價值的架構與程式洞察？
為此，MVP 絕對不能一開始就挑戰 5,000 個檔案的全系統分析，而應該採取「垂直切片（Vertical Slice）」策略。
一、 MVP 的功能範疇：鎖定「單一子系統逆向工程」
建議將 MVP 的目標縮小為：輸入一個包含 50～100 個檔案的舊子系統資料夾（例如幾個 Servlet、DAO 與 SQL），自動產出一份結構化的「技術交接與資料流分析報告」。
這個範圍夠小，能在短時間內驗證完整流程；又夠具體，能直接展現商業價值。
二、 底層：通用長任務 Runtime (MVP 規格)
底層 Runtime 在 MVP 階段不需要複雜的分佈式排程，只需要具備「單機狀態機與耐中斷機制」：
 * 任務狀態機（Task State Machine）：
   將整個長任務拆解為明確的階段：
   * PENDING（等待執行）
   * SCANNING（盤點檔案與結構）
   * ANALYZING（分批分析 Method / Module）
   * SYNTHESIZING（彙整報告）
   * COMPLETED / FAILED
 * 原子化 Checkpoint 機制：
   每一個階段完成時，將進度即時寫入本地的 state.json：
   {
  "task_id": "legacy_analysis_001",
  "current_stage": "ANALYZING",
  "completed_modules": ["OrderServlet.java", "OrderDAO.java"],
  "pending_modules": ["PaymentService.java"],
  "context_summary": "已確認訂單建立流程，正在分析金流串接..."
}

 * 中斷與恢復（Interrupt & Resume）：
   當使用者中斷程式或系統重啟時，Runtime 啟動會先檢查 state.json，自動從上次斷開的 pending_modules 繼續執行，而不是從頭來過。
三、 上層：程式碼智慧分析應用 (MVP 規格)
上層應用在 MVP 階段聚焦於「靜態結構解析 + 本地小模型推論」：
 * 輕量掃描與切片（Parser）：
   寫一個簡單的 Python 掃描器，讀取目標資料夾，列出所有 Class 與 Method 簽名，建立簡單的索引。
 * 確定性工作流（Deterministic Pipeline）：
   不要讓小模型自由發揮「想做什麼就做什麼」，而是透過 Python 程式碼硬性規定步驟：
   * 步驟 1：請小模型逐一總結每個 Method 的業務邏輯（分批執行）。
   * 步驟 2：將各 Method 摘要交給小模型，組合出 Class 層級的互動關係。
   * 步驟 3：彙整全域資料，產出最終 Markdown 報告。
 * 本地模型介面（LLM Adapter）：
   透過 Ollama 或 vLLM 呼叫本機模型（如 qwen2.5-coder:7b 或 14b），並在 Python 端加上 try-except 與格式校驗，確保模型吐出來的 JSON 或文字不會崩潰整個流水線。
四、 第一版 MVP 的驗證指標（Success Criteria）
要判斷這個 MVP 是否成功，只需要測試三個場景：
 * 耐力測試：讓它連續跑 30 分鐘分析 50 個檔案，中途人為強制殺掉行程（Kill Process），重新啟動後看它是否能從斷點恢復。
 * Token 與記憶體控制：確認在整個過程中，本機 VRAM 佔用穩定，沒有發生因為歷史對話過長導致的 OOM（Out of Memory）。
 * 產出可用性：人工檢視最後產出的 Markdown 報告，確認它確實幫架構師省去了手動閱讀幾十個檔案的時間。

---

## 對話片段 526

08b9572f-e586-46b8-801e-9c48b952f0c9

---

## 對話片段 527

2ada4c96-cd92-4a2a-bd8e-e540305042e4

---

## 對話片段 528

0cb48a39-3821-4ad0-8c9d-137f6f2f53e9

---

## 對話片段 529

我認為我們應該先暫停討論 AST、RAG、SQLite、Checkpoint、Agent 等技術細節。

因為這些都是實現手段，不是產品的目的。

我們應該回到最根本的問題：

**這個專案究竟能替使用者解決什麼問題？使用者為什麼需要它？為什麼不直接使用 VS Code、Copilot、Cursor 或 ChatGPT？**

我從使用者的角度重新分析。

# 一、這個專案要解決的核心問題

目前 AI 輔助程式開發有幾個很明顯的限制：

| 使用者遇到的問題 | 現有 AI 工具的不足 | 本專案能提供的價值 |
|---|---|---|
| 專案太大 | 一次無法讀取所有程式 | 持續分析整個專案 |
| Context 太小 | 對話長度有限 | 自動接力，不必重新開始 |
| 分析時間太長 | 需要人工不斷下指令 | 自動執行長時間任務 |
| 不理解舊系統 | 缺少系統全貌 | 建立系統知識庫 |
| 程式分散 | 不知道程式之間的關係 | 建立依賴關係 |
| 分析結果遺失 | 換 Session 後必須重新說明 | 保存工作狀態與成果 |
| 小模型能力有限 | 很難一次處理複雜任務 | 拆解工作，降低推理負擔 |
| 維護困難 | 找不到問題來源 | 提供可追溯的分析證據 |

這些問題其實指向一個共同需求：

**使用者希望把一件複雜、耗時、需要反覆思考的工作交給 AI，讓 AI 能夠持續完成，而不是每次都要人陪著 AI 工作。**

---

# 二、使用者可以得到什麼好處？

我將它分成八大價值。

## 1. 不必一直陪著 AI 工作

這應該是最直接的價值。

傳統使用方式：

```text
使用者：分析這個專案
AI：開始分析
AI：分析到一半
使用者：繼續
AI：繼續分析
使用者：接下來呢？
AI：請提供更多資訊
使用者：繼續
```

使用者必須不斷參與。

本專案：

```text
使用者
  │
  ▼
提出分析目標
  │
  ▼
AI 自動拆解任務
  │
  ▼
持續分析
  │
  ├── 自動保存
  ├── 自動接力
  ├── 自動恢復
  ├── 自動檢查
  └── 自動產生報告
          │
          ▼
       通知使用者
```

使用者只需要定義目標及驗收條件。

這可以降低大量人工操作時間。

## 2. 可以分析原本超出模型能力範圍的大型專案

例如：

- 5,000 個 Java 檔案。
- 2,000 個 JSP。
- 1,000 個 SQL。
- 數十個子系統。
- 數百張資料表。

即使地端模型只有 7B，也可以透過任務拆解、程式分析工具、知識檢索及持續接力，逐步處理。

這裡要注意：

不是讓 7B 模型突然變聰明，而是讓它不必一次處理超出能力的工作。

## 3. 不必每次重新教 AI 整個專案

這是很大的使用者價值。

例如第一次分析：

```text
OrderService
  ├── OrderDAO
  ├── OrderMapper
  └── OrderValidator
```

第二次使用者想了解：

「修改訂單交貨日期會影響哪些功能？」

AI 可以直接利用之前建立的知識。

不必重新掃描整個專案。

使用者獲得的是：

**可以累積的專案知識，而不是一次性的 AI 對話。**

## 4. 把複雜工作交給 AI，自己只負責決策

例如：

使用者提出：

「請分析整個採購系統，找出所有可能造成訂單資料不一致的地方。」

系統可以自行：

1. 盤點相關程式。
2. 找出資料庫寫入位置。
3. 建立呼叫關係。
4. 找出跨系統介面。
5. 分析交易邏輯。
6. 找出異常處理。
7. 整理潛在風險。
8. 提供證據及改善建議。

使用者不必逐一指定要分析哪些檔案。

## 5. 工作成果可以持續累積

一般聊天型 AI 的成果通常散落在不同對話中。

本專案可以將每次工作轉化為可重複利用的知識資產。

例如：

| 工作任務 | 累積成果 |
|---|---|
| 第一次系統分析 | 系統架構 |
| 第二次資料庫分析 | ER 關係 |
| 第三次程式分析 | Class 關聯 |
| 第四次維運問題分析 | 故障知識 |
| 第五次程式修改 | 影響分析紀錄 |

這些成果可以持續更新。

## 6. 不受單一模型或 AI 平台限制

使用者可以依工作需求選擇：

- 本機小模型。
- 本機大型模型。
- 雲端模型。
- 不同 Agent。
- 不同程式分析工具。

例如：

簡單分析交給 7B。

複雜推理交給 14B 或更大型模型。

涉及敏感程式碼時，使用地端模型。

這讓使用者可以自行控制成本、效能及資料安全。

## 7. 可以長時間執行，不必擔心中斷

例如：

今天晚上交代：

「分析整個舊系統，產生完整技術交接文件。」

系統可以：

- 自動排程。
- 自動分析。
- 自動保存。
- 自動接力。
- 遇到中斷後恢復。
- 隔天繼續工作。

使用者不需要保持對話視窗開啟。

但這需要 Runtime 有獨立的持續執行能力，不能只依賴聊天視窗。

## 8. 可以把 AI 從問答工具變成工作執行工具

這是最重要的產品價值。

| 傳統 AI | 本專案 |
|---|---|
| 使用者問問題 | 使用者交辦工作 |
| AI 回答 | AI 執行任務 |
| 單次對話 | 長時間任務 |
| Context 有限 | 跨 Session 接力 |
| 知識分散 | 專案知識累積 |
| 人工持續引導 | 自動任務管理 |
| 回答即結束 | 驗證完成才結束 |

---

# 三、哪些使用者最需要這個產品？

我認為不應該只鎖定程式設計師。

至少有五種使用者。

| 使用者 | 使用情境 | 主要價值 |
|---|---|---|
| 程式設計師 | 大型專案理解、除錯、修改 | 減少理解成本 |
| 系統架構師 | 系統逆向工程、架構分析 | 快速取得全貌 |
| 維運工程師 | 問題追蹤、影響分析 | 降低除錯時間 |
| 技術主管 | 技術盤點、品質與風險分析 | 掌握系統狀況 |
| 系統接手人員 | 舊系統交接、新人熟悉 | 降低交接成本 |

另外，對企業而言，還有一個重要使用情境：

**不希望將公司程式碼、系統設定及業務資料上傳雲端的企業。**

地端執行就是這類使用者的重要賣點。

---

# 四、我認為最有價值的三個應用場景

如果要讓這個產品真正有市場價值，我不建議一開始就宣稱它能處理所有 AI 工作。

應該先鎖定三種高價值場景。

### 場景 A：大型舊系統逆向工程

使用者只要提供專案：

「請完整分析這套系統，產生技術交接文件。」

最後取得：

- 系統架構。
- 程式模組。
- Class 關係。
- 資料庫關係。
- 業務流程。
- 外部介面。
- 維運指南。
- 未知風險。

### 場景 B：大型程式修改與影響分析

使用者提出：

「我要修改訂單交貨日期的處理邏輯。」

系統自動：

1. 搜尋相關程式。
2. 分析呼叫關係。
3. 找出資料庫影響。
4. 找出相關 API。
5. 找出受影響測試案例。
6. 產生修改計畫。
7. 執行修改（經授權）。
8. 執行測試。
9. 產生修改報告。

### 場景 C：長時間自動化技術研究

例如：

「請研究這個專案的安全性，找出高風險依賴及可能的漏洞。」

系統持續：

- 掃描程式。
- 分析相依套件。
- 搜尋安全問題。
- 整理證據。
- 分類風險。
- 產生改善建議。

---

# 五、產品真正的差異化在哪裡？

這是我認為最需要認真思考的地方。

因為 VS Code、Copilot、Cursor、Claude Code 等工具，已經能夠搜尋程式碼、使用 Agent、執行工具及修改程式。

所以不能只把「自動接力」當成產品的唯一賣點。

我認為真正的差異化應該是：

| 差異化能力 | 使用者價值 |
|---|---|
| 跨 Session 長任務 | 不需要人工持續接續 |
| 跨模型接力 | 不受單一模型限制 |
| 專案知識累積 | 不必反覆理解同一專案 |
| 可驗證的分析結果 | 不只是 AI 自行生成答案 |
| 長期任務管理 | 可追蹤、暫停、恢復及交接 |
| 地端執行 | 保護企業程式碼 |
| 團隊共用知識 | 團隊成員可以共享分析成果 |
| 工作成果可追溯 | 知道分析依據、版本及執行紀錄 |

我會把產品的核心價值濃縮成一句話：

> **讓使用者把大型、複雜、耗時的工作交給 AI，AI 能夠自己規劃、持續執行、記住進度、自動接續，直到完成可驗證的工作成果。**

---

# 六、我想進一步確認產品定位的一個關鍵問題

目前我們討論的架構，其實可能發展成兩種不同的產品。

| 比較 | 方向 A：AI 長任務執行平台 | 方向 B：AI 程式碼智慧分析平台 |
|---|---|---|
| 核心目的 | 讓 AI 自動完成長時間工作 | 讓 AI 深入理解大型程式 |
| 適用範圍 | 程式、文件、研究、資料分析等 | 主要針對軟體 Repository |
| RAG | 通用知識檢索 | Code RAG |
| Checkpoint | 核心功能 | 核心功能 |
| Agent | 通用任務 Agent | 程式分析 Agent |
| 主要使用者 | 各領域專業人員 | 程式人員、架構師、維運人員 |
| 產品延展性 | 非常高 | 專業性較高 |

**我的建議是：採用 A 作為底層平台，以 B 作為第一個專業應用。**

也就是：

底層建立通用的長任務 Runtime，上層先開發 Codebase Intelligence。

這樣未來可以延伸到：

- 自動化測試。
- 系統維運。
- 資安分析。
- 技術文件產生。
- 系統架構盤點。
- 軟體現代化。

如此一來，你開發的就不只是一個程式碼 RAG 工具，而是一個能讓地端 AI 持續完成複雜工作的執行平台。

---

## 對話片段 530

bbb21f18-de35-4bdf-8193-2bdad1586d60

---

## 對話片段 531

473c5550-5924-4136-97f9-18af70ffab07

---

## 對話片段 532

bf9ff01c-c95a-479c-a5bc-41583c3795b9

---

## 對話片段 533

34172653-5d88-4da7-b77a-b2200c230dc2

---

## 對話片段 534

9dc86d91-21a1-49cb-bfef-f4effd5704ed

---

## 對話片段 535

這份補充我認為方向正確，而且比直接導入完整 RAG 更務實。

不過，我想再往前推進一步：**這套系統不應該只是一個 Code RAG，而應該是一個具備持久化狀態、增量索引、自動接力及完成驗證能力的程式分析平台。**

你提出的三個痛點，分別涉及索引維護、任務可靠性及小模型控制。我再補充幾個落地時容易被忽略的問題。

# 一、對三個實務解法的技術評估

| 項目 | 原方案 | 我的補充建議 |
|---|---|---|
| 增量索引 | Git Diff | 加入檔案 Hash 與 Symbol 依賴追蹤 |
| Checkpoint | JSON / SQLite | SQLite 作為狀態主資料庫，JSON 作為交接快照 |
| Tool Calling | 固定 JSON 指令 | JSON Schema + 白名單 + Python 執行器 |
| 失敗恢復 | 自動重啟 | 加入冪等性及執行紀錄 |
| 分析完整性 | 分析結果保存 | 加入證據、來源位置及驗收條件 |

## 1. 增量索引還需要考慮依賴變動

例如：

```text
OrderService.java
       │
       ▼
OrderDAO.java
       │
       ▼
OrderMapper.xml
```

假設只修改 `OrderDAO.java`。

不能只更新 DAO 的 AST。

還必須檢查：

- 哪些 Class 呼叫它？
- 哪些 Method 依賴它？
- 原本建立的呼叫圖是否需要更新？
- 既有分析結果是否受到影響？

因此建議：

```text
Git Diff
   │
   ▼
File Hash
   │
   ▼
AST Incremental Parser
   │
   ▼
Symbol Index Update
   │
   ▼
Dependency Graph Update
   │
   ▼
Affected Analysis Detection
```

這樣才能避免索引與實際程式碼不一致。

## 2. Checkpoint 必須支援崩潰恢復

我建議不要只在 Context 快滿時才保存。

應該採用：

**每個 Task Step 完成後立即保存。**

例如：

```text
TASK-001
  │
  ├── Step-001 完成 → SAVE
  ├── Step-002 完成 → SAVE
  ├── Step-003 完成 → SAVE
  ├── Step-004 執行中
  │
  └── Ollama 異常中斷
             │
             ▼
       Runtime Recovery
             │
             ▼
       載入最近 Checkpoint
             │
             ▼
       確認 Step-004 狀態
             │
             ▼
       恢復或重新執行
```

這裡有一個細節：

不能直接假設執行中的 Step 一定沒有產生結果。

例如程式分析已完成，但還沒寫入資料庫就發生中斷。

因此必須使用：

- Task ID
- Step ID
- Execution ID
- Artifact Hash
- Execution Status

並讓重複執行具有冪等性（Idempotency）。

---

# 二、我認為還需要補上另外五個核心機制

這五項會直接影響系統能不能長時間運作。

| # | 機制 | 解決的問題 |
|---|---|---|
| 1 | Context Budget Manager | 避免 Token 超量 |
| 2 | Model Router | 根據任務難度選擇模型 |
| 3 | Evidence Manager | 確保分析結果有程式碼證據 |
| 4 | Task Dependency Scheduler | 控制分析先後順序 |
| 5 | Completion Verifier | 避免假完成 |

特別說明其中三項。

### A. Model Router：不要讓小模型處理所有事情

例如：

| 任務 | 建議處理方式 |
|---|---|
| 列出 Class | Python / AST |
| 找出 Method | AST |
| 找出 SQL | SQL Parser |
| 程式碼分類 | 7B |
| 單一 Method 解釋 | 7B |
| 跨模組流程分析 | 較大型模型 |
| 複雜業務邏輯推理 | 較大型模型 |
| 格式驗證 | Python |
| 完成度計算 | Python |

這樣可以把小模型的 Token 消耗集中在真正需要語意理解的地方。

### B. Evidence Manager：分析結果必須能追溯

例如：

```json
{
  "finding_id": "F-001",
  "finding": "訂單交貨日期由 OrderService 更新",
  "evidence": [
    {
      "file": "OrderService.java",
      "symbol": "updateDeliveryDate",
      "line_start": 120,
      "line_end": 145
    }
  ],
  "confidence": "high",
  "verification_status": "verified"
}
```

這樣後續 Session 不必重新分析，只要讀取結果及證據即可。

但要注意，程式碼位置可能因修改而改變，因此正式版本還應保存 Git Commit、檔案 Hash 及 Symbol ID。

### C. Completion Verifier：不能讓 Agent 自己宣布完成

例如：

```text
Java Analysis
  100% 完成

JSP Analysis
  100% 完成

SQL Analysis
  100% 完成

Database Mapping
  85% 完成

Business Flow
  70% 完成
```

即使模型認為已經完成，也不能將整個任務標記為 DONE。

必須符合事先定義的驗收條件。

---

# 三、整合後的完整架構

我建議正式將架構分成七個核心層次：

```text
┌─────────────────────────────────────────┐
│           1. Task Orchestrator          │
│       任務拆解、排程、生命週期管理        │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           2. Code Intelligence           │
│                                         │
│   AST / Symbol / BM25 / Vector / Graph  │
│                                         │
│           增量索引與依賴分析              │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           3. Knowledge Manager           │
│                                         │
│    L1 原始程式碼                         │
│    L2 結構化程式知識                     │
│    L3 業務分析知識                       │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           4. Context Manager             │
│                                         │
│    Token Budget / Context Aggregator    │
│    Context Monitor / Context Handoff    │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           5. Agent Runtime               │
│                                         │
│    Model Router / Tool Executor          │
│    Local LLM / Agent Session             │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           6. Persistence Engine          │
│                                         │
│    SQLite / Checkpoint / Artifact        │
│    Execution Log / Recovery              │
└───────────────────┬─────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│           7. Verification Engine         │
│                                         │
│    Evidence / Coverage / Quality         │
│    Completion / Final Handoff            │
└─────────────────────────────────────────┘
```

這七層應該保持模組解耦。

尤其不要讓 Ollama 或某一個 Agent Framework 成為整個系統的核心依賴。

---

# 四、實作順序：我建議稍微調整

原本的三階段方向正確，但我建議將自動接力提早驗證。

| 階段 | 開發內容 | 主要驗收目標 |
|---|---|---|
| P0 | Task Runtime + SQLite | 任務可持久化 |
| P1 | Java AST + Symbol Index | 能取得完整程式結構 |
| P2 | BM25 + Code Search | 能精準搜尋程式 |
| P3 | Context Manager + Handoff | Context 滿載自動接續 |
| P4 | LLM Analysis + Evidence | 能產生可追溯分析結果 |
| P5 | Incremental Indexing | Git 修改後局部更新 |
| P6 | Dependency Graph + RAG | 跨模組分析 |
| P7 | Completion Verifier | 自動判斷任務完成 |

為什麼把 P0 放第一？

因為你的核心目標不是建立一個搜尋程式碼的工具，而是建立一個能夠持續執行數小時甚至數天的任務系統。

應該先證明「任務不會因為 Session 消失而中斷」，再擴充程式分析能力。

---

# 五、我建議先做一個最小可行的實驗

先不要急著導入 pgvector、Neo4j、完整 RAG。

使用：

- Python
- SQLite
- JavaParser
- Ollama
- JSON Checkpoint
- BM25

建立一個測試專案。

測試條件：

| 項目 | 測試設定 |
|---|---|
| Java 檔案 | 100 個 |
| 模型 | 地端 7B |
| Context | 限制在小型 Context |
| 單次任務 | 分析 5～10 個 Method |
| Handoff | Context 達門檻自動接續 |
| 中斷測試 | 強制停止 Ollama |
| 恢復測試 | 重新啟動 Runtime |
| 最終驗收 | 100 個檔案都有分析狀態 |

最重要的測試不是模型能不能回答問題，而是：

1. 能否自動完成多次 Session 接力？
2. 是否會重複分析？
3. 是否會遺失分析結果？
4. 是否能從中斷處恢復？
5. 是否能正確辨識尚未完成的工作？
6. 是否能產生完整的最終報告？

---

## 六、我認為這個專案真正的定位

我會將它定義為：

**Autonomous Codebase Analysis Runtime（ACAR）**

它不只是 RAG，也不只是 Agent。

它的核心價值是：

> 讓有限 Context、有限吞吐量的地端模型，透過程式分析工具、持久化知識庫、任務排程及自動交接機制，持續完成原本超出單次模型工作能力的大型程式分析任務。

這個定位很重要。

因為未來即使更換：

- Ollama → vLLM
- Qwen → Gemma
- SQLite → PostgreSQL
- BM25 → OpenSearch
- 單一 Agent → 多 Agent

整個任務 Runtime 仍然可以繼續使用。

**我最建議先完成的是 P0 + P1 + P3 的核心驗證：任務持久化、程式結構索引、自動 Session 接力。**

這三項成功，才算真正建立了整個系統的骨架。

---

## 對話片段 536

bbb21ccb-97c8-4358-9522-bd77143d184a

---

## 對話片段 537

5b141af0-70b1-4c39-9047-e17e1d9a9c7d

---

## 對話片段 538

這份架構分析非常透徹，精準地點出了在企業級大宗專案中，「傳統向量 RAG 的盲點」以及「為什麼小模型需要混合式索引與三層知識庫（L1/L2/L3）」。
特別是將系統資料明確劃分為「靜態知識（程式碼與依賴）」與「動態知識（任務 Checkpoint 與接力進度）」，並透過 AST、Dependency Graph 與 Task Runtime 進行解耦，這正是解決長任務（Long-running Tasks）上下文遺失與 Token 浪費的黃金解法。
針對這種架構落地，有幾個實務上的關鍵執行細節：
一、 落地時的 3 個實務痛點與對應解法
 * 靜態索引（L1/L2）的維護成本
   * 問題：企業專案天天在 Git Commit，如果每次都要重新跑 AST 解析和依賴圖構建，會消耗大量 CPU 與 IO。
   * 解法：結合 Git Hook（如 pre-commit）或 CI/CD Pipeline，採用增量更新（Incremental Indexing）。只有被修改的檔案（git diff 抓到的檔案）才重新跑 AST 解析與局部依賴重構，未變動的檔案直接複用快取。
 * 動態知識（L3 與 Checkpoint）的同步機制
   * 問題：當 AI Agent 在執行長任務（例如：花 2 小時重構 20 個舊 Servlet）時，如果中間斷線或 Token 耗盡，動態狀態容易遺失。
   * 解法：採用檔案系統原子寫入（Atomic File Write）或輕量級本地 SQLite，將每一個 Task Step 的結果即時寫入結構化 JSON / YAML Checkpoint 中，確保隨時可以無縫重啟接力（Handoff）。
 * 地端小模型的工具調用（Function Calling / Tool Use）精確度
   * 問題：7B 或 14B 等級的小模型在自主調用 RAG 搜尋工具時，常發生參數給錯、 SQL 語法錯誤或無限迴圈。
   * 解法：在 Orchestrator 層加上確定性校驗（Deterministic Guardrails）。例如，限制 LLM 只能輸出固定的 JSON 查詢指令（如 {"action": "search_method", "target": "updateDeliveryDate"}），由 Python 腳本來執行實際查詢，再把乾淨的結果餵回模型，避免小模型在決策鏈上迷失。
二、 下一步可以怎麼推動？
既然整套架構的藍圖已經清晰，實作時可以採取「由下而上、分步迭代」的策略：
 * 第一步：先寫一個簡單的 Python 腳本，用 JavaParser 或 Python AST 把專案內的 Methods 與 Class 抓出來，建立基本的 L2 結構化索引（JSON 格式）。
 * 第二步：結合 BM25（或 Python 的 Whoosh 全文檢索）與簡單的 SQLite，讓小模型能透過關鍵字精準找到目標 Method，解決基礎的程式碼尋找問題。
 * 第三步：導入 Task Checkpoint 與 Handoff 機制，讓 AI Agent 可以跨 Session 記錄進度。

---

## 對話片段 539

8a0d7131-3e30-48df-9424-53c82d1a3df1

---

## 對話片段 540

f981549d-ecf2-48e9-8ac3-c5718890fb7b

---

## 對話片段 541

c0966373-40cd-4472-ab08-cc481d28a16e

---

## 對話片段 542

An Exploratory Study of Code Retrieval Techniques in Coding Agents[v1] | Preprints.org

---

## 對話片段 543

2.3. SEMANTIC SEARCH  Semantic code search is the task of retrieving relevant code given a natural language query. Instead of matching keywords, it aims to understand the intent and contextual meaning...

---

## 對話片段 544

RAG for Code: Building Retrieval Systems Over Codebases | Hélain Zimmermann

---

## 對話片段 545

RAG Systems  RAG FOR CODE: BUILDING RETRIEVAL SYSTEMS OVER CODEBASES  Image: Hélain ZimmermannBy Hélain Zimmermann·Co-Founder & CTO @ Ailog · ex-INRIA researcher·Apr 9, 2026  10 min read advanced  RA...

---

## 對話片段 546

cole-medin-knowledge-base/concepts/codebase-indexing.md at main · coleam00/cole-medin-knowledge-base · GitHub

---

## 對話片段 547

type  | concept title  | Codebase Indexing description  | Building a searchable index or map of a codebase so the agent can retrieve the right files instead of scanning everything. tags  | indexing c...

---

## 對話片段 548

Benchmarking AI agent retrieval strategies on Kubernetes bug fixes | CNCF

---

## 對話片段 549

BENCHMARKING AI AGENT RETRIEVAL STRATEGIES ON KUBERNETES BUG FIXES  Posted on May 8, 2026 by Brandon Foley  CNCF projects highlighted in this post  I’ve been using AI coding agents as part of my dail...

---

## 對話片段 550

Retrieval-Augmented Code Generation: A Survey with Focus on Repository-Level Approaches | alphaXiv

---

## 對話片段 551

Submitted 20 May 2026  en  RETRIEVAL-AUGMENTED CODE GENERATION: A SURVEY WITH FOCUS ON REPOSITORY-LEVEL APPROACHES  ImageCarnegie Mellon University Chinese University of Hong KongImageSouthern Univers...

---

## 對話片段 552

Submitted 25 Jan 2026  Newer version published 20 May 2026, read v3  RETRIEVAL-AUGMENTED CODE GENERATION: A SURVEY WITH FOCUS ON REPOSITORY-LEVEL APPROACHES  ImageCarnegie Mellon University Chinese Un...

---

## 對話片段 553

How to Build and Evaluate RAG for Code Search

---

## 對話片段 554

CODE SEARCH USING RETRIEVAL-AUGMENTED GENERATION (RAG)  A practical guide to repository RAG: how retrieval and generation fit together, how code-aware search choices affect results, and how to test a...

---

## 對話片段 555

Build a RAG System from Scratch for Codebase Q&A

---

## 對話片段 556

BUILD A RAG SYSTEM FROM SCRATCH: CREATE AN AI THAT ANSWERS QUESTIONS ABOUT YOUR CODEBASE  Build a repository-aware AI assistant with code-aware chunking, hybrid retrieval, citations, incremental inde...

---

## 對話片段 557

Augmented Code Language Models: A Survey on Advanced Code Intelligence

---

## 對話片段 558

3.1.1  RETRIEVAL-THEN-GENERATE PARADIGM  RAG system operationalizes the core retrieve-then-generate paradigm, a two-stage workflow designed to ground the model’s output in external, verifiable knowled...

---

## 對話片段 559

RAG: Attaching Traceable Knowledge to Models | Insights

---

## 對話片段 560

11. BEYOND RAG: GREP-STYLE TOOL SEARCH IS RETRIEVAL TOO  Image: RAG uses a prepared knowledge index, while grep-style tool search explores a live code workspace and verifies results  At this point, i...

---

## 對話片段 561

Practical Code RAG at Scale: Task-Aware Retrieval Design Choices under Compute Budgets — JetBrains Research

---

## 對話片段 562

CodeRAG: Finding Relevant and Necessary Knowledge for Retrieval-Augmented Repository-Level Code Completion

---

## 對話片段 563

What is retrieval-augmented generation, and what does it do for generative AI? - The GitHub Blog

---

## 對話片段 564

([JetBrains Kairos](https://kairos.jetbrains.com/research/articles/practical-code-rag-at-scale?utm_source=chatgpt.com))

---

## 對話片段 565

([ACL Anthology](https://aclanthology.org/2025.emnlp-main.1187.pdf?utm_source=chatgpt.com))

---

## 對話片段 566

([The GitHub Blog](https://github.blog/ai-and-ml/generative-ai/what-is-retrieval-augmented-generation-and-what-does-it-do-for-generative-ai/?utm_source=chatgpt.com))

---

## 對話片段 567

有，而且這已經是 AI 程式開發領域的一個重要方向，通常稱為：

- **Codebase RAG**：程式碼庫檢索增強生成。
- **Repository-level RAG**：整個程式庫的知識檢索。
- **Code Intelligence**：程式碼智慧分析。
- **Repository Indexing**：程式碼庫索引。

例如，把一個 Java 專案裡所有的 `.java`、`.jsp`、`.xml`、`.sql` 等檔案建立索引，讓 AI 在回答問題時，可以找出相關程式碼。

但是，我先提出一個重要觀念：

**把所有程式碼放進 RAG，不代表 AI 就真正理解了整個系統。**

RAG 解決的是「如何找到需要的程式碼」，不是「如何完整理解程式」。

下面從目的、優缺點及你的長任務自動接力架構來分析。

# 一、為什麼有人把整個程式庫放進 RAG？

主要有六個目的。

| 目的 | 說明 | 實際應用 |
|---|---|---|
| 1. 程式碼搜尋 | 透過自然語言找程式 | 找出訂單處理程式 |
| 2. 程式碼理解 | 找出相關程式及依賴 | 解釋 OrderService |
| 3. 業務邏輯追蹤 | 找出跨檔案的業務流程 | 訂單從建立到出貨 |
| 4. AI 程式開發 | 讓 AI 參考既有程式風格 | 新增 API |
| 5. 系統維護 | 協助除錯及影響分析 | 修改一個 Class 會影響哪些模組 |
| 6. 系統逆向工程 | 從程式碼還原系統架構 | 建立技術交接文件 |

例如，你有一套舊的 JSP/Servlet + Java + Oracle 系統。

你可以問：

「系統中哪些程式會修改採購訂單的交貨日期？」

RAG 可以先找出：

```text
OrderServlet.java
OrderService.java
OrderDAO.java
OrderMapper.xml
order_update.sql
```

再把相關程式碼交給 LLM 分析。

這比讓小模型從頭掃描整個專案有效率得多。

GitHub 也曾介紹過利用程式碼索引及語意搜尋，讓 AI 能夠利用整個 Repository 的知識回答問題。citeturn424891search7

# 二、把所有程式碼放進 RAG 的好處

我整理成 10 項。

| # | 好處 | 實際價值 |
|---|---|---|
| 1 | 不需要每次重新讀取全部程式 | 降低 Token 消耗 |
| 2 | 可以跨檔案搜尋 | 找到不同模組的相關程式 |
| 3 | 支援自然語言查詢 | 不必知道 Class 名稱 |
| 4 | 支援大型專案 | 不受單一 Context Window 限制 |
| 5 | 可以重複利用分析結果 | 減少重複推理 |
| 6 | 提升小模型的可用性 | 只提供必要程式碼 |
| 7 | 可以建立程式知識庫 | 長期保存系統知識 |
| 8 | 支援影響分析 | 找出修改可能影響的程式 |
| 9 | 支援新進人員交接 | 快速了解既有系統 |
| 10 | 可以跨專案查詢 | 比較不同系統的共用功能 |

其中，我認為對你的需求最重要的是前三項：

**降低 Token 消耗、跨檔案搜尋、分析結果重複利用。**

因為你的目標是讓小型地端模型持續接力分析大型程式庫。

RAG 可以讓新 Session 不必重新閱讀之前已經分析過的程式。

# 三、把所有程式碼放進 RAG 的壞處

這裡才是需要特別注意的地方。

不是所有程式碼都適合直接轉成 Embedding。

以下是 10 個問題。

| # | 缺點 | 可能造成的問題 |
|---|---|---|
| 1 | 程式碼切割不當 | Method 被切斷，失去上下文 |
| 2 | 搜尋結果不完整 | 找到一個 Method，卻漏掉呼叫它的程式 |
| 3 | 語意相似不等於程式相關 | 找到相似程式，卻不是實際依賴 |
| 4 | Token 消耗仍然存在 | 找到的程式碼仍須交給 LLM 閱讀 |
| 5 | Embedding 建置成本 | 大量檔案需要轉換向量 |
| 6 | 索引更新困難 | 程式修改後必須同步更新 |
| 7 | 可能出現舊版本資料 | AI 讀到已經刪除或修改的程式 |
| 8 | 缺乏全局理解 | 不一定知道完整業務流程 |
| 9 | 複雜度增加 | 必須管理向量資料庫、索引及權限 |
| 10 | 可能產生錯誤推論 | 搜尋結果不足時，模型可能自行補足 |

其中有一個問題非常重要。

### 問題：RAG 找得到程式，不代表找得到完整呼叫鏈。

例如：

```text
A.java
  │
  ▼
B.java
  │
  ▼
C.java
  │
  ▼
D.java
  │
  ▼
Oracle DB
```

你問：

「哪個程式會修改訂單資料？」

RAG 可能只找到 C.java。

但是完整答案可能必須追蹤：

A → B → C → D → DB。

這就是一般向量 RAG 的限制。

2025 年的 CodeRAG 研究也指出，Repository-level 程式碼檢索必須處理程式間複雜依賴，單一路徑檢索可能遺漏重要資訊。citeturn424891search12

---

# 四、我認為不能只使用 Vector RAG

如果你的目標是整個系統的程式分析，我建議使用混合式架構。

不是只有向量資料庫。

而是結合四種技術。

| 技術 | 負責工作 | 優勢 |
|---|---|---|
| Vector RAG | 語意搜尋 | 找出概念相關程式 |
| BM25 / Full-text | 關鍵字搜尋 | 精確找到 Class、Method、變數 |
| AST / Symbol Index | 程式結構分析 | 找出類別、方法、參數 |
| Dependency Graph | 程式依賴分析 | 找出呼叫鏈及影響範圍 |

例如：

使用者問：

「採購訂單修改交貨日期時，會影響哪些資料表？」

系統可以這樣執行：

```text
使用者問題
    │
    ▼
Query Analyzer
    │
    ├── Vector Search
    │     找出相關業務程式
    │
    ├── BM25 Search
    │     搜尋日期欄位、SQL
    │
    ├── AST Search
    │     找出相關 Method
    │
    └── Graph Traversal
          找出完整呼叫鏈
                │
                ▼
          Context Aggregator
                │
                ▼
             Local LLM
                │
                ▼
             分析結果
```

這樣比單純 Vector RAG 更適合程式分析。

JetBrains Research 的 Code RAG 研究也指出，不同程式任務適合不同檢索方法，沒有一種檢索配置能在所有任務上都取得最佳效果。citeturn424891search3

---

# 五、針對你的需求，我更建議建立三層知識庫

這是我認為最值得採用的設計。

不要只把原始程式碼放進 RAG。

而是分成三層。

## L1：原始程式碼知識庫

保存原始程式碼及可搜尋索引。

例如：

```text
Code Repository
    │
    ├── Java
    ├── JSP
    ├── XML
    ├── SQL
    ├── Properties
    └── Configuration
```

主要用途：

- 找程式。
- 找 Method。
- 找 SQL。
- 找變數。
- 找相關程式碼。

## L2：結構化程式知識庫

這一層不只是保存程式碼，而是保存分析後的結構。

例如：

```json
{
  "class": "OrderService",
  "methods": [
    {
      "name": "updateDeliveryDate",
      "calls": [
        "OrderDAO.updateDeliveryDate"
      ],
      "tables": [
        "PURCHASE_ORDER"
      ]
    }
  ]
}
```

主要用途：

- Class 關係。
- Method 關係。
- 呼叫關係。
- 資料表關係。
- API 關係。
- 模組依賴。

## L3：業務知識庫

這一層保存 LLM 經過分析及驗證的結果。

例如：

```markdown
# 採購訂單交貨日期修改流程

1. 使用者進入訂單修改頁面。
2. OrderServlet 接收修改請求。
3. OrderService 驗證交貨日期。
4. OrderDAO 更新資料庫。
5. 更新 PURCHASE_ORDER。

相關程式：
- OrderServlet.java
- OrderService.java
- OrderDAO.java

待確認：
- 是否存在其他批次程式同步修改。
- 是否觸發外部供應商通知。
```

主要用途：

- 業務流程理解。
- 系統架構分析。
- 技術交接。
- 新人教育。
- 維運除錯。

---

# 六、這三層架構如何解決小模型 Context 不足？

這是關鍵。

假設有一個 5,000 個 Java 檔案的專案。

傳統做法：

```text
Session 1
讀取 100 個檔案
分析
摘要

Session 2
重新讀取大量檔案
分析
摘要

Session 3
再重新讀取
```

這樣會浪費大量 Token。

使用三層知識庫：

```text
             5,000 個 Java 檔案
                     │
                     ▼
              一次性建立索引
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
       Vector      AST       Dependency
       Index      Index        Graph
          │          │          │
          └──────────┼──────────┘
                     │
                     ▼
               分析結果保存
                     │
                     ▼
               業務知識庫
                     │
                     ▼
              新 Session 啟動
                     │
                     ▼
             讀取目前任務狀態
                     │
                     ▼
             只搜尋必要程式碼
                     │
                     ▼
                小模型分析
                     │
                     ▼
               保存新分析結果
                     │
                     ▼
                 自動接力
```

這樣每個 Session 只需要取得當前任務所需的資料。

不需要重新讀取整個 Repository。

---

# 七、但我還要提醒你一件事：不要把 RAG 當成永久記憶

這兩者應該分開。

| 項目 | RAG | Task Checkpoint |
|---|---|---|
| 保存程式碼 | 是 | 否 |
| 保存程式分析結果 | 可以 | 可以保存摘要 |
| 保存目前進度 | 不適合 | 是 |
| 保存下一步工作 | 不適合 | 是 |
| 搜尋相關程式 | 是 | 否 |
| Session 接力 | 輔助 | 核心功能 |
| 判斷任務完成 | 否 | 搭配驗證器 |

也就是：

- RAG 負責回答「相關資料在哪裡」。
- Checkpoint 負責回答「之前做到哪裡」。
- Task Orchestrator 負責回答「下一步要做什麼」。
- Completion Verifier 負責回答「是不是真的完成」。

這四個角色不要混在一起。

---

# 八、我的最終建議：不要做單純 Code RAG，應該做 Codebase Intelligence Platform

如果是我來設計你前面提到的長任務接力系統，我會採用：

**Codebase Intelligence + RAG + Autonomous Task Runtime**

| 模組 | 技術定位 |
|---|---|
| Repository Scanner | 專案掃描 |
| Code Parser | 程式碼解析 |
| Vector Index | 語意索引 |
| BM25 Index | 關鍵字索引 |
| Symbol Index | Class / Method 索引 |
| Dependency Graph | 程式依賴圖 |
| Knowledge Store | 分析知識保存 |
| Context Aggregator | 動態組合上下文 |
| Task Checkpoint | 任務狀態保存 |
| Handoff Engine | 自動 Session 接力 |
| Completion Verifier | 完成條件驗證 |

### 建議採用的技術

考慮地端部署：

- Ollama：地端 LLM。
- nomic-embed-text：Embedding。
- PostgreSQL + pgvector：向量及結構化知識。
- Apache Lucene / OpenSearch：全文檢索。
- JavaParser：Java AST。
- Neo4j：依賴關係圖（初期可以先不用）。
- Python：Orchestrator、Checkpoint、Handoff。

另外，**不建議一開始就將 5,000 個檔案全部轉成 Embedding**。先建立 AST、符號索引與 BM25，再根據實際查詢需求建立向量索引，通常更符合小模型的資源限制。

---

## 九、最後一個重要結論

我會把整個系統的資料分成兩種：

**第一種：靜態知識（Static Knowledge）**

- 原始程式碼。
- Class / Method。
- SQL。
- API。
- 呼叫關係。
- 資料庫結構。

**第二種：動態知識（Dynamic Knowledge）**

- 目前任務進度。
- 已完成分析。
- 尚未完成分析。
- 分析過程的決策。
- 發現的問題。
- 尚待確認事項。
- 下一個接手任務。

前者透過 Code RAG 和 Code Index 管理。

後者透過 Task Runtime 和 Checkpoint 管理。

**兩者結合，才有機會讓小型地端模型持續接力，逐步完成大型企業系統的逆向工程，而不是每次重新開始分析。**

這也是我認為最值得發展的架構方向。

---

## 對話片段 568

4bee6744-5a33-4023-8dea-b2e7730d4680

---

## 對話片段 569

2b631456-29c8-4246-9e76-2618210f588b

---

## 對話片段 570

754ced9e-bebb-4814-aaec-738601b5b8a0

---

## 對話片段 571

f8b26bd6-522d-432e-a753-cbb561a3a0fa

---

## 對話片段 572

e715d08f-2f9a-4d7a-af9f-5180c3fd45f5

---

## 對話片段 573

Retrieval-Augmented Code Generation: A Survey with Focus on Repository-Level Approaches

---

## 對話片段 574

Practical Code RAG at Scale: Task-Aware Retrieval Design Choices under Compute Budgets

---

## 對話片段 575

bfc9ffd5-ca7a-42be-a2eb-7bb7fb8b6f3d

---

## 對話片段 576

thread_6ae9247efb0483209fd868e28ca696b2

---

## 對話片段 577

83ccc4ce-1274-4657-a3eb-a09730c7ed0b

---

## 對話片段 578

2eacfa15-2874-4070-ba73-eb2bae3820aa

---

## 對話片段 579

正在搜尋 codebase RAG indexing all source code repository benefits drawbacks best practices code retrieval augmented generation

---

## 對話片段 580

c92a7690-593d-43a0-9c81-3946eb7bd5ac

---

## 對話片段 581

2c3f12f4-0b21-418c-8d86-6f425770689f

---

## 對話片段 582

b0df8212-b03a-4bf9-9ab3-744809f3c118

---

## 對話片段 583

bbb213cb-923f-40c6-b730-d30dc45fcd99

---

## 對話片段 584

6b2c3d5e-d157-4ce9-842d-6c3e56daaa5e

---

## 對話片段 585

有人把所有的程式寫入rag嗎 如果有的話目的是什麼有什麼好處有什麼壞處

---

## 對話片段 586

c004efd9-8261-4c51-86ac-393e87804b68

---

## 對話片段 587

9a2dce68-5e8a-482a-8338-4c9c0aab44c7

---

## 對話片段 588

5b0cf19a-4bec-4fec-a5d9-7acb8c993057

---

## 對話片段 589

# 地端小模型「長任務自動接續與程式分析交接架構」可行性評估

這個方案我認為**技術上可行，而且很值得實作，但必須區分「任務可以持續接續」與「模型能否真正完成分析」這兩件事。**

你真正要解決的問題是：

1. 地端模型的 Context Window 比較小。
2. 地端模型的 Token 吞吐量（Tokens per Second）比較低。
3. 單一程式分析任務可能持續數小時，甚至數天。
4. 對話 Context 滿了，不能讓任務中斷或遺失。
5. 必須能自動產生交接資料，啟動下一個 Context。
6. 新的 Context 必須知道前一個 Context 做了什麼、為什麼這樣做、還有哪些工作沒完成。
7. 不需要人工一直下達「繼續」指令。
8. 直到整個程式分析任務達到預先定義的完成條件，才正式結束。

這其實不是單純的 Agent，而是：

**Long-running Autonomous Task Runtime（長時間自主任務執行環境）。**

---

# 一、整體方案可行性

先將方案分成三個層次評估。

| 評估項目 | 可行性 | 說明 |
|---|---|---|
| Context Window 滿載自動交接 | ★★★★★ | 可透過 Token 監控與 Checkpoint 實現 |
| 跨 Session 自動接續 | ★★★★★ | 透過持久化任務狀態 |
| 不同 Agent 接手同一任務 | ★★★★☆ | 需要統一任務狀態與執行環境 |
| 地端小模型執行長任務 | ★★★★☆ | 必須拆解任務，避免一次輸入大量程式碼 |
| 自動分析大型程式庫 | ★★★★☆ | 搭配 AST、Call Graph、RAG 等工具 |
| 自動修改程式並測試 | ★★★★☆ | 需要測試、Git 與權限控制 |
| 無人介入持續執行 | ★★★★☆ | 可自動重試、交接，但不能保證所有異常都能自行排除 |
| 自動判斷整個系統已分析完成 | ★★★☆☆ | 必須建立可量化的完成標準 |

我的判斷：

**可以做到長時間自動執行，但不能把「模型說完成了」當作「任務真的完成了」。**

這是整個架構最重要的設計原則。

---

# 二、可以做到的事情（至少 10 項）

以下是我認為可以實際實作的功能。

## 1. Context Window 自動監控與交接

可以實現。

例如：

地端模型 Context Window 為 8K、16K 或 32K。

由 Runtime 持續監控 Token 使用量。

| Token 使用率 | 系統動作 |
|---|---|
| 0～60% | 正常執行 |
| 60～75% | 準備交接資料 |
| 75～85% | 建立 Checkpoint |
| 85% 以上 | 禁止大型新任務，準備切換 Context |

這樣就不會等到模型 Context 已經滿了才處理。

注意：Token 監控必須盡可能使用實際模型 Tokenizer 或 Host 提供的 Token 使用資訊，不能只用文字長度估算。

## 2. 同一個任務跨 Session 自動接續

可以實現。

例如：

```text
TASK-001
  │
  ├── Session-001
  │     ├── 完成專案盤點
  │     ├── 分析 20 個 Java 檔案
  │     └── Context 接近上限
  │
  ├── Checkpoint
  │
  ├── Session-002
  │     ├── 載入前次分析結果
  │     ├── 分析剩餘 Java 檔案
  │     └── Context 接近上限
  │
  ├── Checkpoint
  │
  ├── Session-003
  │     ├── 分析 SQL 與資料庫關聯
  │     └── 建立系統依賴圖
  │
  └── Session-N
        └── 最終分析與交付
```

這裡最重要的是：

Session 可以一直更換，但 TASK-001 的身分不能改變。

## 3. 自動產生交接文件

可以實現。

每次交接，自動產生：

```text
task/
  TASK-001/
    task.json
    task_plan.md
    checkpoint.json
    progress.md
    decisions.md
    unresolved.md
    analysis_results/
    execution_logs/
    test_results/
    handoff/
      handoff_001.md
      handoff_002.md
```

新 Session 不需要重新讀取所有對話紀錄，只需要讀取必要的任務狀態及證據。

## 4. 自動拆解大型程式分析任務

可以實現。

例如要分析一個包含 5,000 個 Java、JSP、XML、SQL 檔案的舊系統。

不應該讓模型一次分析 5,000 個檔案。

應該拆成：

| 階段 | 工作 |
|---|---|
| Phase 1 | 專案結構盤點 |
| Phase 2 | Java 類別分析 |
| Phase 3 | JSP、Servlet 分析 |
| Phase 4 | SQL 分析 |
| Phase 5 | 資料表關聯分析 |
| Phase 6 | API 與系統介面分析 |
| Phase 7 | 呼叫關係分析 |
| Phase 8 | 業務流程分析 |
| Phase 9 | 系統架構整理 |
| Phase 10 | 文件整合與驗證 |

每個階段再細分為小型 Task。

## 5. 自動保存程式分析結果

可以實現。

每次分析完成就保存結果，而不是等整個任務結束才保存。

例如：

```text
analysis_results/
  java/
    UserService.md
    OrderService.md
    PaymentService.md

  database/
    tables.md
    relationships.md
    sql_analysis.md

  architecture/
    system_overview.md
    dependency_graph.md
    call_graph.md
```

這可以大幅降低重複分析的 Token 消耗。

## 6. 自動判斷哪些檔案尚未分析

可以實現。

建立檔案分析清單：

| 檔案 | 狀態 | 分析版本 |
|---|---|---|
| UserService.java | 完成 | V1 |
| OrderService.java | 完成 | V1 |
| PaymentService.java | 分析中 | V1 |
| AccountServlet.java | 未分析 | - |
| order.jsp | 未分析 | - |

新 Session 啟動後，直接從未完成項目繼續。

## 7. 不同模型接手同一任務

可以實現。

例如：

- Qwen：程式碼初步分析。
- 小型模型：分類、摘要、結構化資料整理。
- 較大型模型：複雜業務邏輯推理。
- 靜態分析工具：呼叫關係、依賴關係。
- 測試工具：驗證分析結果。

但必須統一輸出格式，否則不同模型的分析結果可能無法整合。

## 8. 自動重試失敗任務

可以實現。

例如：

```text
分析 OrderService.java
       │
       ▼
     執行失敗
       │
       ▼
   記錄錯誤原因
       │
       ▼
   自動重新執行
       │
       ├── 成功 → 保存結果
       │
       └── 失敗 → 更換策略
                     │
                     ▼
                  人工介入
```

需要設定最大重試次數，避免無限循環。

## 9. 自動檢查分析結果完整性

可以實現。

例如分析 Java 類別時，必須確認：

- Class 名稱。
- Package。
- Import。
- Fields。
- Methods。
- 呼叫的其他 Class。
- SQL 操作。
- 資料表。
- 外部 API。
- 業務用途。

缺少必要欄位，就不能標記為完成。

## 10. 最終自動產生技術交接文件

可以實現。

最終輸出：

1. 系統架構文件。
2. 程式模組清冊。
3. Java Class 關聯。
4. API 介面文件。
5. 資料庫 ER 關係。
6. SQL 使用分析。
7. 業務流程。
8. 外部系統依賴。
9. 系統設定與環境。
10. 維運與除錯指南。
11. 未解決問題清單。
12. 系統現代化建議。

這與你之前希望進行的舊系統逆向工程、程式理解及 Spring Boot 現代化工作相當吻合。

---

# 三、不可以直接做到的事情（至少 10 項）

這裡必須特別注意。

有些事情不是增加 Checkpoint 就能解決。

## 1. 不能靠 Context 接力解決模型吞吐量不足

這是第一個重要問題。

假設：

| 模型 | 吞吐量假設 |
|---|---:|
| 小型地端模型 | 15 Tokens/s |
| 中型地端模型 | 35 Tokens/s |
| 大型模型 | 60 Tokens/s |

以上只是計算示例，不代表特定模型的實測效能。

假設一個分析任務需要處理 1,000 萬 Token。

小模型：

10,000,000 ÷ 15 ÷ 3,600

約需要 185 小時。

而且這還沒有計算：

- Prompt 建構。
- 程式碼讀取。
- 模型推理額外成本。
- Checkpoint。
- 工具執行。
- 重試。
- 結果驗證。

**Context 接力只能解決上下文容量問題，不能增加模型本身的推理速度。**

這點必須在架構上明確區分。

## 2. 不能保證小模型能理解複雜業務邏輯

例如：

```java
public void processOrder() {
    validate();
    calculate();
    save();
}
```

小模型可能知道程式執行順序。

但不一定能理解：

- 為什麼需要這個流程？
- 是否存在隱藏商業規則？
- 是否有跨系統交易？
- 是否有例外處理？
- 是否存在資料一致性問題？

這些需要結合上下游程式、資料庫及文件。

## 3. 不能只靠摘要保證交接正確

例如 Session-001 分析了 100 個 Java 檔案。

它產生一份 500 字摘要。

Session-002 只讀取摘要。

問題是：

摘要可能遺漏重要細節。

因此交接不能只有摘要，還必須保留：

- 原始分析結果。
- 檔案位置。
- 分析證據。
- 重要程式碼片段。
- 未解決問題。
- 已做出的決策。

摘要只是入口，不是唯一資料來源。

## 4. 不能保證 Agent 永遠不會進入無限循環

例如：

```text
分析失敗
  ↓
重新分析
  ↓
還是失敗
  ↓
再次分析
  ↓
還是失敗
```

必須加入：

- Retry Limit。
- Timeout。
- 任務失敗分類。
- 連續失敗偵測。
- Deadlock Detection。
- 人工介入機制。

## 5. 不能讓模型自行判定所有工作都完成

例如模型可能認為：

Java 檔案都已分析，所以系統分析完成。

但實際上：

- JSP 尚未分析。
- SQL 尚未分析。
- 資料庫關聯尚未分析。
- Batch 尚未分析。
- 外部系統介面尚未分析。

因此完成判定必須由 Orchestrator 根據任務清單、驗收條件及證據決定。

## 6. 不能假設不同模型具有相同的理解能力

Qwen、Gemma、DeepSeek 等模型，對同一份程式碼可能有不同理解。

不能只把前一個模型的自然語言摘要交給下一個模型，就認為它一定能正確接續。

必須建立統一的結構化交接格式。

## 7. 不能在沒有檔案鎖定的情況下讓多個 Agent 同時修改同一份程式

例如：

Agent A 修改 OrderService.java。

Agent B 也修改 OrderService.java。

可能造成：

- 修改互相覆蓋。
- Git Diff 不一致。
- 測試結果失效。
- 程式碼狀態損壞。

必須建立 Workspace Lock、File Lock 或 Git Branch 隔離機制。

## 8. 不能保證所有程式碼都能透過 AST 完整理解

AST 可以理解程式結構，但無法直接推導所有業務意義。

例如：

- 動態 SQL。
- Reflection。
- 動態類別載入。
- JSP 動態轉址。
- Stored Procedure。
- 外部系統執行結果。

這些需要靜態分析、動態分析及人工知識補充。

## 9. 不能保證地端模型在資源不足時仍能持續運作

例如：

- GPU VRAM 不足。
- RAM 不足。
- Ollama 異常。
- 模型服務中斷。
- Podman Container 停止。
- 電腦重新開機。

必須將任務狀態保存到模型服務以外的持久化儲存。

## 10. 不能保證完全不需要人工介入

尤其是：

- 無法理解的業務規則。
- 程式碼與文件互相矛盾。
- 資料庫結構缺失。
- 測試環境無法啟動。
- 分析結果存在重大歧義。

這些必須允許任務進入 `BLOCKED` 狀態，而不是讓模型自行編造答案。

---

# 四、我認為最重要的架構調整：不要讓 LLM 負責全部工作

這是我對原方案最重要的建議。

因為你特別強調地端小模型的 Context Window 和吞吐量問題，所以不能設計成：

```text
所有程式碼
    ↓
全部交給 LLM
    ↓
LLM 分析
    ↓
LLM 摘要
    ↓
LLM 交接
```

這種架構會消耗大量 Token。

應該改成：

## 混合式程式分析架構

```text
             Task Orchestrator
                     │
                     ▼
              Task Decomposer
                     │
          ┌──────────┴──────────┐
          │                     │
          ▼                     ▼
    Static Analyzer        File Analyzer
          │                     │
     AST / Parser          檔案切分
     Call Graph             程式分類
     SQL Parser             依賴識別
          │                     │
          └──────────┬──────────┘
                     │
                     ▼
               Context Manager
                     │
                     ▼
              Local LLM Engine
                     │
              Qwen / Ollama
                     │
                     ▼
             Structured Results
                     │
          ┌──────────┴──────────┐
          │                     │
          ▼                     ▼
    Knowledge Store       Checkpoint
          │                     │
          ▼                     ▼
     RAG / Graph DB       Handoff Manager
          │                     │
          └──────────┬──────────┘
                     │
                     ▼
               Task Scheduler
                     │
                     ▼
                Next Session
                     │
                     ▼
                持續接續
```

這個設計的核心：

**能用程式工具解決的事情，不要消耗 LLM Token。**

例如：

| 工作 | 建議執行方式 |
|---|---|
| 列出所有 Java 檔案 | Python |
| 取得 Class 清單 | Java Parser |
| 取得 Method 清單 | AST |
| 建立呼叫關係 | 靜態分析 |
| SQL 抽取 | SQL Parser |
| 資料表關聯 | SQL Parser + DB Metadata |
| 程式用途理解 | LLM |
| 業務流程推理 | LLM |
| 分析結果摘要 | LLM |
| 完整性檢查 | 程式規則 + LLM |
| 交接 | Runtime |

這樣才能真正降低小模型的負擔。

---

# 五、解決小模型吞吐量問題：我建議加入五個機制

這部分是整個系統能否實用的關鍵。

## 機制 1：增量分析（Incremental Analysis）

不要每次接續都重新分析整個專案。

例如：

```text
第一次：
分析 100 個 Java 檔案

第二次：
只分析新增的 20 個檔案

第三次：
只分析修改過的 5 個檔案
```

利用 SHA-256 判斷檔案是否變更。

沒有變更的檔案不必重複分析。

## 機制 2：分層分析（Hierarchical Analysis）

不要直接讓模型讀取每個檔案的所有內容。

分成三層：

| 層級 | 分析內容 | Token 消耗 |
|---|---|---|
| L1 | AST、Class、Method、依賴 | 低 |
| L2 | 單一類別、單一模組理解 | 中 |
| L3 | 跨模組業務流程與架構推理 | 高 |

先用 L1 篩選，再決定哪些內容需要 L2、L3。

## 機制 3：Context Budget Manager

每個任務設定自己的 Token 預算。

例如：

```json
{
  "task_id": "TASK-001",
  "model": "qwen2.5:7b",
  "context_window": 8192,
  "max_context_usage": 0.75,
  "reserved_tokens": 1500,
  "handoff_threshold": 0.75
}
```

注意：這是示意設定，實際數值要依模型、量化版本及 Host 的 Tokenizer 調整。

## 機制 4：分析結果快取

例如：

```text
OrderService.java
    │
    ▼
SHA256
    │
    ├── 已分析且版本相同
    │       ↓
    │    直接讀取分析結果
    │
    └── 檔案有變更
            ↓
         重新分析
```

避免大量重複推理。

## 機制 5：任務佇列（Task Queue）

不要讓模型決定下一個要分析什麼。

由 Orchestrator 管理：

```text
PENDING
   ↓
RUNNING
   ↓
COMPLETED

RUNNING
   ↓
FAILED
   ↓
RETRY

RUNNING
   ↓
BLOCKED
   ↓
WAITING_FOR_HUMAN
```

這樣即使模型重新啟動，也能知道下一步要做什麼。

---

# 六、最重要的部分：自動接力直到任務完成

我建議你把這個機制獨立設計成：

**Continuous Task Handoff Engine**

它不是單純的 Session Manager。

它必須負責整個任務生命週期。

```text
              建立分析任務
                    │
                    ▼
              Task Decomposer
                    │
                    ▼
              建立任務清單
                    │
                    ▼
              啟動 Agent
                    │
                    ▼
              執行程式分析
                    │
                    ▼
              保存分析結果
                    │
                    ▼
             Context 使用率？
                    │
          ┌─────────┴─────────┐
          │                   │
       未達門檻             接近門檻
          │                   │
          ▼                   ▼
       繼續分析           建立 Checkpoint
                              │
                              ▼
                         結束舊 Session
                              │
                              ▼
                         啟動新 Session
                              │
                              ▼
                         載入任務狀態
                              │
                              ▼
                         驗證工作環境
                              │
                              ▼
                         接續未完成任務
                              │
                              ▼
                         是否全部完成？
                              │
                    ┌─────────┴─────────┐
                    │                   │
                   否                  是
                    │                   │
                    ▼                   ▼
                繼續接力             最終驗收
                                        │
                                        ▼
                                    產生交接文件
                                        │
                                        ▼
                                      DONE
```

### 這裡有一個很重要的設計

不要讓 Agent 自己負責啟動下一個 Agent。

應該由獨立的 Runtime 負責。

原因是：

假設 Agent 因為 Context Window 滿載而停止，它可能已經沒有能力再執行下一個工具指令。

因此：

- Agent 負責分析。
- Checkpoint Manager 負責保存。
- Runtime 負責判斷是否需要交接。
- Session Launcher 負責啟動新 Session。
- Task Scheduler 負責安排下一個工作。

這樣才能避免整個接力機制依賴即將耗盡 Context 的模型。

---

# 七、交接機制必須具備的資料結構

我建議至少保存以下資訊。

`checkpoint.json`

```json
{
  "task_id": "TASK-001",
  "session_id": "SESSION-003",
  "handoff_count": 3,

  "task_status": "RUNNING",

  "current_phase": "JAVA_ANALYSIS",

  "progress": {
    "total_files": 5000,
    "completed_files": 1250,
    "failed_files": 12,
    "pending_files": 3738
  },

  "current_task": {
    "file": "OrderService.java",
    "status": "COMPLETED"
  },

  "next_action": {
    "type": "ANALYZE_FILE",
    "target": "PaymentService.java"
  },

  "completed_phases": [
    "PROJECT_SCAN",
    "DEPENDENCY_ANALYSIS"
  ],

  "pending_phases": [
    "JAVA_ANALYSIS",
    "JSP_ANALYSIS",
    "SQL_ANALYSIS",
    "BUSINESS_FLOW_ANALYSIS",
    "FINAL_REPORT"
  ],

  "handoff_reason": "CONTEXT_THRESHOLD",

  "verification": {
    "git_status_checked": true,
    "results_persisted": true,
    "checkpoint_validated": true
  }
}
```

這只是初步示例。

正式版本還應加入：

- Checkpoint 版本號。
- Workspace 路徑。
- Git Commit。
- 任務執行 Lease。
- 最後成功執行時間。
- Artifact Hash。
- 重試次數。
- 模型與 Prompt 版本。
- 分析結果來源及證據。
- 任務相依性。

尤其是 Git Commit 和 Artifact Hash，可以避免接手的 Agent 使用到不一致的程式碼版本。

---

# 八、我建議的實作技術組合

考慮你目前偏好的 Windows 11、Podman、Ollama 及地端模型環境。

我會這樣選擇：

| 功能 | 建議技術 | 用途 |
|---|---|---|
| 任務控制中心 | Python | Orchestrator |
| Agent 執行 | Python + Agent Host | 執行分析任務 |
| 地端模型 | Ollama | LLM 推理 |
| 任務佇列 | SQLite 起步 | 任務狀態 |
| Checkpoint | JSON + SQLite | 任務接力 |
| 分析結果 | Markdown + JSON | 結果保存 |
| 程式解析 | JavaParser | Java AST |
| SQL 分析 | SQLGlot | SQL 結構分析 |
| 程式依賴 | jQAssistant / 自訂分析 | 關聯分析 |
| 向量檢索 | pgvector | RAG |
| 知識圖譜 | Neo4j | Class、Method、Table 關係 |
| 程式版本 | Git | 變更追蹤 |
| 執行環境 | Podman | 容器化 |

不建議一開始就全部部署。

先完成任務接力核心，再逐步加入程式分析工具。

---

# 九、建議分成四個開發階段

| 階段 | 開發內容 | 驗收目標 |
|---|---|---|
| MVP-1 | Task Orchestrator + Checkpoint | 能保存並恢復任務 |
| MVP-2 | Context Monitor + Handoff Engine | Context 到門檻自動換 Session |
| MVP-3 | Code Analysis Pipeline | 能持續分析大型程式庫 |
| MVP-4 | Verification + Final Handoff | 能判斷完成、產生完整交接文件 |

### MVP-1：先驗證最重要的事情

不必一開始就分析整個 Java 專案。

先建立一個測試任務：

```text
TASK-001

工作：
分析 100 個 Java 檔案。

限制：
每個 Session 最多使用 4K Token。

測試：
1. 分析 10 個檔案。
2. 強制建立 Checkpoint。
3. 關閉目前 Session。
4. 自動啟動新 Session。
5. 載入 Checkpoint。
6. 接續分析剩餘檔案。
7. 最後產生完整報告。
```

驗收標準：

- 不會重複分析已完成檔案。
- 不會遺失已完成結果。
- 不會遺失尚未完成工作。
- 新 Session 能正確接續。
- 最終檔案數量及分析結果可核對。

這個測試通過，才代表真正具備長任務接力能力。

---

# 十、最終評估與我的建議

我認為這個方案有三個不同的技術難度：

| 核心能力 | 難度 | 是否建議優先實作 |
|---|---|---|
| Context Window 自動接力 | 中 | 第一優先 |
| 長任務狀態持久化 | 中 | 第一優先 |
| 大型程式庫自動分析 | 高 | 第二優先 |
| 小模型 Token 效率最佳化 | 高 | 第二優先 |
| 跨模型接力 | 高 | 第三優先 |
| 自動判斷分析完成 | 高 | 第三優先 |
| 完整技術交接文件 | 中高 | 第三優先 |

## 我認為還有一個更重要的觀念

你不應該把整個系統設計成：

「讓一個小模型一直工作，直到它完成。」

而應該設計成：

**「讓一個不依賴任何單一模型的任務執行系統，持續推進工作，模型只是其中可以替換的推理引擎。」**

兩者差異非常大。

前者模型一旦失效，任務就停止。

後者即使：

- Context Window 滿了。
- 模型重新啟動。
- Ollama 暫時中斷。
- Session 被關閉。
- 更換不同模型。
- 電腦重新開機。

只要任務狀態已經持久化，Runtime 就能恢復執行。

### 最終建議架構

我會將整個專案命名為：

**Autonomous Long-Running Task Runtime（ALTR）**

核心分成六個模組：

1. **Task Orchestrator**：任務拆解、排程與生命週期。
2. **Context Manager**：Token 監控、Context Budget 與自動交接。
3. **Checkpoint Manager**：任務狀態、進度及分析結果保存。
4. **Agent Session Manager**：建立、終止、切換及恢復 Agent Session。
5. **Execution Controller**：工具執行、重試、錯誤處理及資源管理。
6. **Completion Verifier**：檢查分析覆蓋率、驗證結果及最終交付。

**最重要的驗收目標：**

讓一個原本需要人工持續對話、可能因 Context 滿載而中斷的程式分析任務，變成可以在地端環境中自動拆解、執行、保存、交接、恢復、驗證，直到達到明確的完成條件。

這才是真正值得開發的長任務 Agent Runtime。memcite

---

## 對話片段 590

fa64afa7-4d18-4143-b012-9a1b6177acef

---

## 對話片段 591

b9c36a85-e2f1-407b-ae27-ee19bdc86ab6

---

## 對話片段 592

8656c912-875d-4c17-818d-4042d1cb37b0

---

## 對話片段 593

/images/ecosystem/apps/personal_context/icon.png

---

## 對話片段 594

4f243663-6185-4dc5-b6f2-3a003b07542a

---

## 對話片段 595

2a2b2bcf-c21c-42f9-b183-f348765ec091

---

## 對話片段 596

a7a89821-a8c7-443f-a61d-89186158aacf

---

## 對話片段 597

00c6e3b9-1bd2-47dd-a4b4-774a35c85525

---

## 對話片段 598

ed9c4d92-e601-49a2-87dc-29057a3699e8

---

## 對話片段 599

a055ba96-8def-43f2-ba2f-b23184373e02

---

## 對話片段 600

9341c6c1-eb45-4b7b-b6b4-e40fa1ddcf5c

---

## 對話片段 601

25c925ad-e8c8-47ad-b2b2-cd0b6fcfc1c5

---

## 對話片段 602

1222e5ae-b3c0-47cf-9f07-cb72189211ad

---

## 對話片段 603

d827a57c-63d3-499b-b139-9e3b52e0e3c5

---

## 對話片段 604

bbb21ec6-0f43-46e1-b458-ff1df36dca13

---

## 對話片段 605

4963a4fe-c259-4c62-88e3-86fa9f868cda

---

## 對話片段 606

評估以下的方案的可行性可以做到的點不可以做到各至少5個 尤其是地端模型比較小c o n g e x g吞吐量會比較小然後我需要能考慮能自動一直接續的直到 程式分析 完成可以接續交接

---

## 對話片段 607

abd0c85a-ce2d-4ef0-bb9b-4e0c3e41bf66

---

