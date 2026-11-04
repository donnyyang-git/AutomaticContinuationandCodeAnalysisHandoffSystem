# 交接紀錄：環境修正與 integrity 診斷

## 一、已完成事項

1. 環境修正完成
   - 依專案要求，已確認並啟用 Java 21 + Maven 3.10。
   - 依照 [setup-poc-env.ps1](setup-poc-env.ps1) 的設定，已修正 PATH 與 `JAVA_HOME` / `MAVEN_HOME`。
   - 企業代理 CA 問題已透過匯入 Java trust store 解除，Maven 可重新連線 Maven Central。

2. 專案驗證已回到正確層級
   - 在正確 Java/Maven 環境下，執行 `mvn -q -Dtest=IntegrityValidatorTest test`，成功回歸為 `EXIT: 0`。
   - 這表示目前程式邏輯本身沒有明顯的整體 regression。

3. diagnostic script 已整理完成
   - 新增 [scripts/check_checkpoint_integrity.py](scripts/check_checkpoint_integrity.py)
   - 新增 [scripts/README.md](scripts/README.md)
   - 目的：快速檢查 SQLite checkpoint 是否與 `IntegrityValidator` 的 SHA-256 計算方式一致，便於定位 stale / tampered state。

## 二、已確認的狀態

- 專案環境已可使用 Java 21 + Maven 3.10；目前 PowerShell 的 Maven 路徑已修正。
- `work/handoff.db` 是 CLI 預設路徑，但目前工作區不存在此檔案，兩個遠端分支也沒有此資料庫；因此尚無證據顯示有 stale checkpoint。
- 工作區另一個 `work/handoffs/POC-0-WP2/handoff-startup.db` 沒有 checkpoint rows，不能代替 `work/handoff.db`。
- `HandoffCommand` 尚未提供 `create-task` 子命令；此 CLI 缺口本次明確記為未處理，不阻擋 P3-WP1。
- POC-2 handoff 為 accepted；P3-WP1 Evidence Manager、P3-WP2 local LLM adapter/prompt builder 與 P3-WP3 ClaimValidator persistence slice 已完成本次實作。

## 三、接下來的計畫

### Phase 1：清理 stale runtime state
- 目前 `work/handoff.db` 不存在，因此沒有資料可清理，也沒有重建正式工作資料庫。
- 若之後從原環境取得該 DB，先備份並檢查後再決定是否清理；不可把預設路徑當成 DB 已存在的證據。

### Phase 2：確認 resume gate 正常
- 使用 JUnit 臨時資料庫驗證 pause/resume 可完成，以及 tampered checkpoint 會阻止 resume 並建立 review。
- 此驗證不代表曾經存在的工作 DB 已修復；CLI `create-task` 尚未實作，後續另行處理。

### Phase 3：正式進入 POC-3
- P3-WP1 已新增 Evidence Manager：驗證快照成員、來源檔案 hash/size、路徑與行範圍，並以穩定 ID 寫入既有 evidence schema。
- excerpt 遮蔽 JSON 敏感欄位、Authorization 值、JDBC URL credentials 與 PEM private-key 區塊；測試映射記錄於 `specs/POC-3/README.md`。
- P3-WP2 已新增 bounded prompt builder 與共用 OpenAI-compatible adapter，支援 Ollama/LM Studio，僅接受 READY manifest 所列 model 與 loopback HTTP endpoint。
- adapter 回傳 invocation metadata、hash、raw completion 與 parsed JSON，不直接寫 DB 或確認分析結果；六個本機 HTTP fixture 測試記錄於 `specs/POC-3/README.md`。
- P3-WP3 已新增 ClaimValidator：驗證 analysis schema、evidence 的 task/snapshot/hash/range/kind、parser declaration/direct-call 衝突、claim 狀態與 retry revision。
- invocation、result revision、evidence links 與 review 由呼叫端提供的同一 DB transaction 持久化；ClaimValidator 不自行 commit。12 個 WP3 隔離 DB 測試通過。
- `AtomicWorkCoordinator.commitAnalysisWorkItem` 已接通 Runtime work-item transaction：同一 commit 包含 evidence、invocation、result/review、event、checkpoint 與 work status/cursor；重試會重用既有 commit。
- 新增 Runtime 整合測試驗證 commit 後 event/checkpoint integrity、重試冪等，以及注入失敗時整組 rollback；WP3 Runtime 整合測試映射記錄於 `specs/POC-3/README.md`。
- 本機 LM Studio `/v1/models` 可連線，READY manifest 的 Model A `qwen/qwen3.5-9b` 列於載入模型；下一步進入 P3-WP4 實際模型 smoke 與恢復情境。

## 四、風險與限制

- 本次未取得或檢查任何舊環境的 `work/handoff.db`；不得推論其內容或狀態。
- `create-task` CLI 缺口尚未處理；需要 CLI 建立正式 task/snapshot 時，先補齊命令及其測試。
- P3-WP1/P3-WP2/P3-WP3 測試使用臨時資料庫或 loopback fixture，沒有修改正式工作 DB，也沒有連接實際模型。
- P3-WP3 persistence API 由 Runtime 呼叫端管理 transaction；`commitAnalysisWorkItem` 已完成 event/checkpoint/status 接線，但尚未執行實際模型 crash/recovery 情境。
- 尚未完成實際模型 smoke 與完整 recovery orchestration；`create-task` CLI 仍未處理。

## 五、總結

本次確認 `work/handoff.db` 只是預設路徑，未發現實際 DB 或 stale checkpoint；resume gate 已由隔離測試覆蓋。P3-WP1/2/3 與 Runtime atomic claim commit 接線已實作；全套離線 verify 為 44 個測試通過。下一步為 P3-WP4 實際模型 smoke/恢復情境；CLI `create-task` 仍保留待辦。

## 六、本次交接更新

- 交接 ID：`HND-20261105-004532-2D49A6F1`
- 最後更新時間：`2026-11-05T00:45:32+08:00`

### 開發歷程

| 時間（ISO 8601） | 進度 |
|---|---|
| 2026-11-04T22:39:25+08:00 | 確認預設 DB 不存在且未上傳；將缺少 `create-task` 記為未處理；完成 P3-WP1 Evidence Manager 與 23 個 Maven 測試（0 failures、0 errors、0 skipped）。 |
| 2026-11-04T22:54:53+08:00 | 完成 P3-WP2 prompt/provider fixture slice；兩種 provider 共用 loopback OpenAI-compatible request，manifest gate 與 prompt redaction 通過；全套 28 個 Maven 測試通過（0 failures、0 errors、0 skipped）。真實模型 smoke、invocation DB persistence、WP3 與 `create-task` 尚未完成。 |
| 2026-11-04T22:56:29+08:00 | 加入 provider HTTP 503 與 missing-model 錯誤映射測試；最終全套離線測試 29 個通過（0 failures、0 errors、0 skipped）。 |
| 2026-11-04T22:57:50+08:00 | 修正 WP2 測試使用的 JSON Schema fixture；重跑 `mvn -o -q test`，29 個測試通過（0 failures、0 errors、0 skipped）。 |
| 2026-11-05T00:17:11+08:00 | 完成 P3-WP3 claim validation/persistence slice 與測試；`mvn -o -q verify` 通過，41 個測試（0 failures、0 errors、0 skipped）。完整 Runtime event/checkpoint 接線和實際模型 smoke 尚未完成。 |
| 2026-11-05T00:21:13+08:00 | 加入 duplicate logical claim ID 的 preflight rejection，並以 `mvn -o -q verify` 完成最終檢查：42 個測試通過（0 failures、0 errors、0 skipped）。完整 Runtime event/checkpoint 接線和實際模型 smoke 仍待完成。 |
| 2026-11-05T00:45:32+08:00 | 完成 `commitAnalysisWorkItem` Runtime atomic integration，含 idempotent retry、event/checkpoint integrity 與 rollback 測試；`mvn -o -q verify` 通過（44 個測試，0 failures、0 errors、0 skipped）。本機 LM Studio 已列出 Model A；P3-WP4 實際模型 smoke/恢復情境待執行。 |
