# scripts

此目錄存放可重複執行的診斷與驗證腳本，方便在修正完整性 checkpoint 問題時重跑與比對。

## check_checkpoint_integrity.py

用途：檢查 SQLite database 中的 checkpoint row 是否與 `IntegrityValidator` 計算方式一致。

使用方式：

```powershell
python .\scripts\check_checkpoint_integrity.py work\handoff.db
```

若資料庫不存在，腳本會輸出 warning；若存在 checkpoint 但 hash 不一致，輸出會標示 `match: false`，可直接用來定位損壞資料。
