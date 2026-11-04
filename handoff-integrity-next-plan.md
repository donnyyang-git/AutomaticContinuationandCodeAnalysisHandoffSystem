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

- 目前問題不再是環境不足，也不再是 Maven 依賴解析失敗。
- 當前仍有需要注意的，是工作資料庫中既有的 stale checkpoint 或歷史 DB 狀態，這會導致 resume / validate 流程在執行時觸發 `E_INTEGRITY`。
- 我們尚未啟動 POC-3 工作包，這次交接僅處理環境修正與 integrity 診斷，未進入功能實作。

## 三、接下來的計畫

### Phase 1：清理 stale runtime state
- 釐清目前 `work/handoff.db` 是否為舊 checkpoint 或損壞資料。
- 若確定為 stale state，先建立乾淨資料庫再重新驗證 `create-task` / `status` / `resume`。

### Phase 2：確認 resume gate 正常
- 重新跑最小驗證流程，確保 resume 不會在未清理 stale checkpoint 的情況下繼續前進。
- 確認 `REQUIRES_REVIEW`、review 記錄與 event chain 都符合規範。

### Phase 3：正式進入 POC-3
- 當 integrity gate 正常後，再開始 P3-WP1 及後續工作包。
- 先不進行任何額外重構，避免在未確認 stale state 前擴大改動範圍。

## 四、風險與限制

- 不在本交接範圍內啟動 POC-3 工作項目。
- 若直接在現有 `work/handoff.db` 上繼續執行，仍可能重現 `E_INTEGRITY`。
- 真正的關鍵修正，應以「清淨資料庫 + 確認 resume flow」作為前提，而不是直接開始功能開發。

## 五、總結

本次已完成的工作重點，是把專案從「環境阻塞」帶回到「正確的驗證軌道」，並留下可重複執行的 checkpoint 診斷腳本，供下一段工作接續使用。下一步應以清理 stale state 為核心，再進入 POC-3 工作包。
