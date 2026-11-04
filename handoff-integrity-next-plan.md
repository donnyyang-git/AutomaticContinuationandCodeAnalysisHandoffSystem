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
- POC-2 handoff 為 accepted；POC-3 的 P3-WP1 Evidence Manager 已開始並完成本次實作。

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
- 下一步可進入 P3-WP2；實際模型 smoke 仍須依 READY environment manifest 選定 provider/model 後另行執行。

## 四、風險與限制

- 本次未取得或檢查任何舊環境的 `work/handoff.db`；不得推論其內容或狀態。
- `create-task` CLI 缺口尚未處理；需要 CLI 建立正式 task/snapshot 時，先補齊命令及其測試。
- Evidence Manager 的測試使用臨時資料庫，沒有修改正式工作 DB。

## 五、總結

本次確認 `work/handoff.db` 只是預設路徑，未發現實際 DB 或 stale checkpoint；resume gate 已由隔離測試覆蓋。POC-3 P3-WP1 已開始並完成本次 Evidence Manager 切片，CLI `create-task` 缺口保留為未處理事項。

## 六、本次交接更新

- 交接 ID：`HND-20261104-223925-7F3B1A2C`
- 最後更新時間：`2026-11-04T22:39:25+08:00`

### 開發歷程

| 時間（ISO 8601） | 進度 |
|---|---|
| 2026-11-04T22:39:25+08:00 | 確認預設 DB 不存在且未上傳；將缺少 `create-task` 記為未處理；完成 P3-WP1 Evidence Manager 與 23 個 Maven 測試（0 failures、0 errors、0 skipped）。 |
