# 方案可行性評估：完整 Grill 討論

> 本文件記錄針對《方案可行性評估-完整討論.md》的完整 Grill 討論，包括問題、建議答案、使用者決策與最終共同理解。
>
> Grill 目的：把技術方案從「技術元件堆疊」收斂成可驗證的產品決策、Runtime 語義與 MVP 驗收標準。

---

## 一、Grill 啟動

原始討論文件中存在一個重要矛盾：

- 前文建議 **CONTINUUM 優先 PoC**。
- 文件結尾又建議先做 **自研 POC-1，再接入 CONTINUUM 比較**。

Grill 以產品定位、成功標準、狀態所有權、測試對象、Evidence 與驗收方式為主軸，逐輪收斂。

---

## 二、第一輪：產品定位與 MVP 邊界

### Q1 — 產品到底要解決哪一個核心問題？

選項：

- A. 通用長任務 Agent Runtime
- B. Java 舊系統分析工具
- C. 軟體交接／維護知識產生工具
- D. 以上整合，但第一版先聚焦其中一項

**建議答案：D，但 MVP 明確聚焦 C。**

不要先把產品定位成通用 Runtime。先證明「舊系統分析任務可以可靠接續並產出可追溯交接成果」。

**使用者決策：B＋C。**

確定產品聚焦：

- Java 舊系統分析
- 軟體交接／維護知識成果

### Q2 — MVP 的成功條件是「能恢復」還是「能產生正確成果」？

不能只驗證 Process 能重新啟動。真正的成功條件是：

> 針對一個 Java 舊系統，完成部分分析後強制中斷；重新啟動並修改部分原始碼、切換模型；系統能保留未受影響成果、標記受影響成果、只重做必要部分，最後產出每項結論都可追溯到檔案、行號與版本 Hash 的報告。

**建議答案：採用成果一致性標準。**

**使用者決策：同意。**

### Q3 — Runtime 的核心狀態由誰擁有？

選項：

- A. 直接採用 CONTINUUM
- B. 自己做 Runtime，再把 CONTINUUM 接進來比較
- C. LangGraph＋CONTINUUM 同時使用
- D. 自己定義 Task、Checkpoint、Evidence、Event，外部專案只作參考或實驗對照

建議原則：

> MVP 只允許一個元件擁有任務狀態；第一版由自研最小 Runtime 擁有，CONTINUUM 作為外部基準與可替換實驗對象，不成為核心依賴。

**建議答案：D。**

**使用者決策：同意。**

### Q4 — 第一個測試系統要多真實？

第一個測試 corpus 應該是：

> 一個真實 Java 舊系統的 30–50 個檔案，至少包含 Controller／Servlet、Service、DAO、DTO、JSP 或 XML 設定，以及跨檔案呼叫關係。

BugNET 是 .NET/C#，不能拿來直接驗證 JavaParser。多語言支援延後。

**建議答案：B。**

**使用者決策：同意。**

### Q5 — 要宣稱什麼程度的稽核與合規能力？

第一版明確宣稱：

> MVP 提供可驗證的分析證據、事件鏈與版本追溯；不宣稱 ISO 27001 合規。Hash Chain 只代表完整性與追溯技術，不等於完整合規。

**建議答案：B＋C。**

**使用者決策：同意。**

### 第一輪結論

- 產品定位：Java 舊系統分析＋軟體交接成果
- 成功標準：成果一致性，不是單純 Resume
- Runtime：自研最小 Runtime 擁有狀態
- 測試 corpus：真實 Java 舊系統 30–50 檔
- 合規表述：可驗證追溯，不宣稱 ISO 27001

---

## 三、第二輪：Checkpoint、異動與恢復語義

### Q6 — Checkpoint 要保存什麼？

每一個有效 Checkpoint 至少包含：

1. 任務狀態：Task ID、目前階段、完成百分比、待處理項目
2. 已驗證事實：結構化分析結論，而不是原始對話
3. Evidence 引用：檔案、行號、程式碼片段 Hash
4. 來源版本：專案版本 Hash、檔案 Hash、Parser 版本
5. 執行游標：最後一個已完成且已提交的工作單元
6. 模型與格式版本：模型、Prompt、分析 Schema、Checkpoint Schema

不能只保存 `current_file` 或 `progress_percentage`。

**使用者決策：同意。**

### Q7 — 原始碼異動後，恢復範圍怎麼決定？

採用以下規則：

```text
檔案變更
  ↓
直接 Evidence 失效
  ↓
反向依賴分析
  ↓
受影響結論失效
  ↓
局部重分析
```

先以檔案 Hash 判定直接失效，再透過 Code Graph 的反向依賴找出受影響的類別、方法與分析結論；只有依賴鏈上受影響的成果需要重做。

**使用者決策：同意。**

### Q8 — Tool Call 已完成但 Checkpoint 尚未寫入時怎麼辦？

每個工作單元使用穩定的 `operation_id`。恢復時：

- 若 Execution Event 與 Evidence 已有完成紀錄：採用既有結果，不重複執行
- 若狀態不明：標記為 `REQUIRES_REVIEW`
- 不得直接假設成功或失敗

**使用者決策：同意。**

### Q9 — 模型切換能否覆寫原有結論？

新模型不得直接覆寫舊結論。必須建立新的 Analysis Result Revision，引用相同或新的 Evidence，並標記：

- `CONFIRMED`
- `CONFLICTING`
- `REQUIRES_REVIEW`

模型切換是分析版本變更，不是單純換一個推論引擎。

**使用者決策：同意。**

### Q10 — 哪些情況必須人工介入？

以下情況一律暫停自動流程，進入 `REQUIRES_REVIEW`：

- 原始碼 Hash 改變，且影響到已確認結論
- Checkpoint 或 Event Chain 驗證失敗
- Tool Call 狀態不明
- 新舊模型對關鍵結論產生衝突
- Parser 版本變更導致 Symbol／Call Graph 不一致
- 找不到原始 Evidence 行號
- 分析結果缺少必要證據

**使用者決策：同意。**

### 第二輪結論

- Checkpoint 保存結構化事實、Evidence、版本與執行游標
- 原始碼異動採依賴影響分析，不全量重跑
- Tool Call 使用 `operation_id` 處理 Crash Window
- 模型切換建立新版本，不覆寫舊結論
- 不確定狀態一律進入 `REQUIRES_REVIEW`

---

## 四、第三輪：工作單元、交易一致性與 MVP 排除範圍

### Q11 — 任務最小工作單元要多大？

第一版以「單一類別或單一分析問題」為工作單元，例如：

```text
分析一個類別
  ↓
取得 callers / callees
  ↓
抽取一組事實
  ↓
建立 Evidence
  ↓
提交 Analysis Result
  ↓
更新 Checkpoint
```

每個工作單元必須能被：

- 獨立重試
- 獨立判定完成或失敗
- 獨立關聯 Evidence
- 獨立避免重複執行

**使用者決策：同意。**

### Q12 — Checkpoint 如何保證不保存半成品？

每次提交遵守以下順序：

```text
1. 寫入 Analysis Result
2. 寫入 Evidence
3. 寫入 Execution Event
4. 驗證資料完整性
5. 在同一個 SQLite Transaction 中更新 Checkpoint
6. Commit 成功後，才承認工作單元完成
```

若中途 Crash：

- Transaction 尚未 Commit：整筆視為未完成
- Transaction 已 Commit：視為可恢復的完整結果
- Event 與 Result 不一致：進入 `REQUIRES_REVIEW`

**使用者決策：同意。**

### Q13 — 你要防禦哪一種資料竄改？

第一版威脅模型定義為：

> 防止一般錯誤、Checkpoint 損毀與未經授權的普通修改；能偵測沒有同步更新簽章的資料變更，但不宣稱能防禦主機管理員或資料庫管理者。

**建議答案：A＋B。**

C、D 需要外部簽章金鑰、權限隔離、遠端或 WORM 儲存，不能靠本地 SQLite 自己完成。

**使用者決策：同意。**

### Q14 — 「恢復成功」要用什麼量化？

POC 不以「程式正常結束」作為唯一通過條件：

| 指標 | 通過條件 |
|---|---|
| 已確認事實遺失 | 0 筆 |
| 未受影響成果重做 | 0 筆，或每筆有明確理由 |
| Evidence 失配 | 0 筆 |
| 重複 Analysis Result | 0 筆 |
| Crash 後恢復 | 能從最後有效 Checkpoint 繼續 |
| 原始碼異動偵測 | 100% 偵測測試中已修改檔案 |
| 模型切換 | 舊 Schema 可讀，新結果不覆寫舊結果 |
| 報告追溯 | 抽查結論全部能定位到檔案、行號、Hash |

Parser／Runtime 的一致性與模型分析正確率分開衡量。

**使用者決策：同意。**

### Q15 — 第一版明確不做什麼？

MVP 暫時排除：

- Web UI
- 多租戶
- 分散式執行
- 多 Agent 並行協作
- 完整多語言支援
- 自動修改原始碼
- 自動修復衝突結論
- 完整 ISO 27001 控制
- 生產級跨機器高可用
- 對所有 Java Framework 的完整解析

第一版只做：

> 單機 CLI、單一 Java 專案、單一任務、可中斷恢復、局部重分析、模型切換、Evidence-backed Markdown／JSON 報告。

**使用者決策：同意。**

### 第三輪結論

- 工作單元以單一類別／分析問題為主
- Result、Evidence、Event、Checkpoint 使用同一個 Transaction
- MVP 防護目標是錯誤與普通未授權修改，不宣稱防禦管理員
- 以資料一致性指標驗收，不只看程式是否結束
- MVP 限定單機 CLI、單一 Java 專案、單一任務

---

## 五、第四輪：事實、推論與報告可信度

### Q16 — 專案版本的唯一識別方式

每次任務啟動時建立不可變的 `source_snapshot_id`，至少包含：

- 專案根目錄
- Git commit，若有 Git
- 所有納入分析檔案的路徑與 SHA-256
- 忽略檔案規則
- 分析開始時間
- Snapshot manifest 的 Hash

所有 Evidence 與 Analysis Result 都引用 Snapshot，而不是只記錄目前檔案內容。

**使用者決策：同意。**

### Q17 — Parser 結果與 LLM 結論的權威順序

採用以下規則：

> 語法、Symbol、行號、直接呼叫關係等可由 Parser／Graph 確定的內容，Parser 是權威來源；LLM 不得推翻它，只能基於它提出語意解釋。

動態反射、XML 注入、框架路由等 Parser 無法確定的關係，必須標記為推論或未知。

**使用者決策：同意。**

### Q18 — LLM 輸出是否必須結構化且可驗證？

LLM 不直接輸出自由格式報告，而必須輸出符合 Schema 的資料，例如：

```json
{
  "claims": [
    {
      "claim": "OrderService 呼叫 OrderDAO.save",
      "type": "CALL_RELATIONSHIP_INTERPRETATION",
      "confidence": "HIGH",
      "evidence_refs": ["ev_123"],
      "reasoning": "..."
    }
  ],
  "unknowns": [],
  "next_steps": []
}
```

系統必須驗證：

- JSON Schema
- Evidence ID 是否存在
- Evidence 是否屬於當前 Snapshot
- 行號是否仍在檔案範圍內
- Claim 類型是否允許該 Evidence 支持
- 缺少證據時不得標記為 `CONFIRMED`

**使用者決策：同意。**

### Q19 — 哪些分析結論可以進入最終報告？

最終報告允許收錄：

- `CONFIRMED`：有直接、可重現 Evidence
- `INFERRED`：有間接 Evidence，且明確標示為推論
- `REQUIRES_REVIEW`：列在待確認區，不得偽裝成結論

以下不得進入一般結論區：

- `UNSUPPORTED`
- `CONFLICTING`
- `STALE`
- `UNKNOWN`
- Evidence 遺失或 Snapshot 不匹配的結果

**使用者決策：同意。**

### Q20 — 原始碼變更後，舊結果是否保留？

舊結果永遠保留，但狀態可以從 `CONFIRMED` 轉成 `STALE` 或 `REQUIRES_REVIEW`；新分析建立新的 Result Revision。

狀態轉移：

```text
CONFIRMED
   ↓ source changed
STALE
   ↓ re-analysis
CONFIRMED
```

若新舊結果矛盾：

```text
STALE / CONFIRMED
        +
new CONFLICTING
        ↓
REQUIRES_REVIEW
```

**使用者決策：同意。**

### 第四輪結論

- 使用不可變 `source_snapshot_id`
- Parser／Graph 是結構事實的權威來源
- LLM 只能輸出可驗證的結構化 Claim
- 報告區分 `CONFIRMED`、`INFERRED`、`REQUIRES_REVIEW`
- 原始碼變更後保留舊結果，以 `STALE`／新 Revision 管理

---

## 六、第五輪：Runtime 操作語義

### Q21 — Task Lifecycle 要不要明確建模？

任務只能沿著明確狀態轉移：

```text
CREATED
  ↓
INDEXING
  ↓
ANALYZING
  ↓
CHECKPOINTING
  ↓
PAUSED / INTERRUPTED
  ↓
RESUMING
  ↓
COMPLETED
```

異常狀態另外區分：

```text
FAILED
REQUIRES_REVIEW
CANCELLED
```

禁止依靠「某個欄位有沒有值」猜測任務狀態。

**使用者決策：同意。**

### Q22 — MVP 是否允許修改原始碼或執行業務副作用？

第一版 Code Intelligence 僅允許：

- 讀取原始碼
- 建立索引
- 呼叫本地模型
- 寫入 SQLite／報告／事件紀錄
- 複製或建立分析用 Snapshot

禁止：

- 修改原始碼
- 執行被分析系統
- 執行資料庫 Migration
- 呼叫外部 API
- 部署或刪除檔案
- 執行任意 Shell 指令

**使用者決策：同意。**

### Q23 — 是否支援並行分析？

MVP 採用：

> 單一 Task、單一 Runtime Worker、工作單元依序執行；SQLite 保留未來多 Worker 所需的 `operation_id`、鎖定與版本欄位，但第一版不啟用並行分析。

**使用者決策：同意。**

### Q24 — 模型輸出不穩定時，是否保存完整重現資訊？

每次模型呼叫保存：

- `operation_id`
- 模型名稱與版本／Tag
- Prompt Template 版本
- 輸入資料的 Hash
- Parser／Evidence 查詢結果的 Hash
- Sampling 參數
- 原始模型輸出
- Schema 驗證結果
- 解析後的 Claims
- 呼叫開始與結束時間
- 錯誤與重試次數

同一工作單元重試後產生不同結果，不自動選擇結果，而標記為 `CONFLICTING` 或 `REQUIRES_REVIEW`。

**使用者決策：同意。**

### Q25 — 報告是否由資料庫結果產生，而不是由 LLM 直接撰寫？

最終 Markdown／JSON 報告由已驗證的 Analysis Result、Evidence 與 Snapshot Manifest 確定性產生。LLM 可以提供分析內容，但不得直接決定未驗證內容是否進入最終報告。

報告至少分成：

1. 任務與 Snapshot 資訊
2. 已確認架構事實
3. 帶證據的語意分析
4. 推論與信心等級
5. 待人工確認事項
6. 過期或衝突結果
7. 未完成工作
8. 生成版本與報告 Hash

**使用者決策：同意。**

### 第五輪結論

- Task 使用明確狀態機
- MVP 僅做唯讀分析，不執行業務副作用
- 單一 Worker，暫不做並行
- 保存每次模型呼叫的完整重現資訊
- 報告由已驗證資料確定性產生，LLM 不直接控制報告內容

---

## 七、第六輪：元件責任與實驗順序

### Q26 — SQLite 的資料所有權

使用同一個 SQLite database，但分離資料模組與所有權：

| 模組 | 唯一寫入者 | 內容 |
|---|---|---|
| `source_snapshot` | Snapshot Manager | 專案版本與檔案 Hash |
| `code_index` | Indexer | 類別、方法、Symbol、Call Graph |
| `task_state` | Runtime | 任務狀態與執行游標 |
| `checkpoint` | Checkpoint Manager | 可恢復的結構化狀態 |
| `evidence` | Evidence Manager | 檔案、行號、內容 Hash |
| `analysis_result` | Analysis Pipeline | Claim、Revision、狀態 |
| `execution_event` | Event Logger | 工具呼叫、錯誤、重試、恢復 |
| `report` | Report Generator | 報告版本與輸出 Hash |

原則：

> 可以共用資料庫，不共用資料所有權；CodeGraph 不得直接修改 Runtime Checkpoint，LLM 不得直接寫入已確認結果。

**使用者決策：同意。**

### Q27 — POC 的實作順序

採用：

```text
POC-0：Snapshot 與資料模型
  ↓
POC-1：自研 Runtime 的 Crash → Restart → Resume
  ↓
POC-2：JavaParser／CodeGraph 的索引與查詢
  ↓
POC-3：Ollama 結構化 Claim 與 Evidence 驗證
  ↓
POC-4：原始碼異動、局部失效、局部重分析
  ↓
POC-5：模型切換與報告產生
  ↓
CONTINUUM：以相同測試案例作為外部比較基準
```

CONTINUUM 不取消，而是延後到有明確內部基準後再比較。

**使用者決策：同意。**

### Q28 — Java Parser 的第一版選擇

第一版以 JavaParser 作為 Java AST／Symbol 的主要來源；Tree-sitter 只作為未來多語言擴充或輔助解析，不讓兩者同時成為 Java 結構事實的競爭權威。

若 JavaParser 與 CodeGraph 結果衝突，系統不得自動選一個，而是建立 Parser Conflict 並進入 `REQUIRES_REVIEW`。

**使用者決策：同意。**

### Q29 — MVP 的操作介面

第一版只提供 CLI，至少包含：

```text
create-task <project>
index <task-id>
run <task-id>
status <task-id>
pause <task-id>
resume <task-id>
inject-failure <task-id> <failure>
validate <task-id>
report <task-id>
```

`inject-failure` 必須能測試：

- Process 中斷
- Ollama 不可用
- Checkpoint 寫入前中斷
- Checkpoint 損毀
- 原始碼檔案變更
- 模型切換

**使用者決策：同意。**

### Q30 — 是否建立「無恢復能力」的基準組？

至少比較兩組：

| 組別 | 行為 |
|---|---|
| Baseline | 直接以檔案清單／Prompt 執行，無結構化 Checkpoint、無 Evidence 版本管理 |
| Proposed | 使用 Snapshot、Checkpoint、Evidence、局部失效與模型版本管理 |

比較：

- 中斷後遺失的成果
- 重複分析次數
- Token／模型呼叫數
- 恢復時間
- 受影響範圍判定
- 報告 Evidence 完整率
- 人工 Review 數量

**使用者決策：同意。**

### 第六輪結論

- SQLite 可共用，但各資料模組有唯一寫入者
- 先做自研 Runtime 基準，再接入 CONTINUUM 比較
- Java 第一版以 JavaParser 為結構權威
- 第一版只提供可故障注入的 CLI
- 必須與無恢復能力的 Baseline 比較

---

## 八、第七輪：部署、資料保存與人工介入

### Q31 — 原始碼與分析資料的保存策略

MVP 不把完整原始碼永久複製進 SQLite。SQLite 只保存：

- Snapshot Manifest
- 檔案路徑
- Hash
- 必要的 Evidence 片段
- 分析結果

若需要跨機器重現，另外保存明確的 Snapshot Archive。

Evidence 必須記錄：

- 原始檔案路徑
- Snapshot ID
- 起始／結束行號
- 內容 Hash
- 必要時的內容片段
- 檔案在 Snapshot 中是否仍存在

**使用者決策：同意。**

### Q32 — 路徑與機密資訊處理

第一版必須避免把以下資料直接寫入 Log、Prompt、報告或 Evidence：

- API Key
- 存取 Token
- 密碼
- 連線字串
- 個人資料
- 不必要的絕對路徑
- 模型服務的認證資訊

若原始碼中存在疑似機密，系統只保存遮罩後片段與偵測結果，除非使用者明確選擇保留原始 Evidence。

**使用者決策：同意。**

### Q33 — 人工 Review 的最小介面

第一版不做完整 Web UI，但必須提供 CLI 形式的 Review 機制：

```text
review list <task-id>
review show <review-id>
review accept <review-id>
review reject <review-id>
review supersede <review-id> <result-id>
```

每次人工決定保存：

- Review ID
- 操作者
- 時間
- 原始狀態
- 決定
- 理由
- 被選定的 Result Revision
- 相關 Evidence

**使用者決策：同意。**

### Q34 — 人工介入後是否允許繼續自動執行？

若任務遇到關鍵衝突或證據失效，Runtime 必須暫停；人工完成 Review 並提交決定後才可繼續。

```text
REQUIRES_REVIEW
       ↓
人工確認／駁回／選擇版本
       ↓
Review committed
       ↓
RESUMABLE
       ↓
RESUMING
```

人工 Review 是恢復條件的一部分，不只是備註欄位。

**使用者決策：同意。**

### Q35 — 可重現性要做到哪一層？

MVP 的可重現性目標：

> 相同 Snapshot、Parser 版本、Prompt 版本、模型版本與輸入 Evidence，系統能重建同一組結構化輸出與報告資料；不要求 LLM 每次原始文字輸出完全一致，但若結果不同，必須保留差異並進入比較流程。

分開評估：

- Runtime 重現性：Checkpoint／Evidence／狀態轉移完全一致
- 模型重現性：輸出可能不同，但差異可追溯
- 報告重現性：相同已確認資料產生相同報告

**使用者決策：同意。**

### 第七輪結論

- SQLite 不永久保存完整原始碼，只保存 Snapshot、Hash 與必要 Evidence
- 機密資料不得擴散到 Log、Prompt、報告
- 必須有 CLI 形式的 Review 流程
- 關鍵 Review 未完成前，Runtime 不得繼續
- Runtime、模型、報告分別定義可重現性

---

## 九、第八輪：驗收門檻與產品邊界

### Q36 — 第一個 Java 測試專案的代表性

測試專案不能只用檔案數量衡量，必須覆蓋：

- Controller／Servlet → Service
- Service → DAO
- DAO → SQL／資料存取
- DTO／Entity 的資料流
- XML 設定到 Java 元件的關聯
- JSP 或模板到 Controller 的入口關係
- 至少一個動態或 Parser 無法完全確定的關係
- 至少一個故意修改後會影響多個下游結論的檔案

**使用者決策：同意。**

### Q37 — POC 的硬性停止條件

只要出現以下任一情況，POC 不得宣稱成功：

- 已確認結論因 Crash 遺失
- Checkpoint 宣稱完成但缺少 Result／Evidence
- 未受影響工作被無理由重做
- 原始碼變更未被偵測
- Evidence 指向錯誤行號或錯誤 Snapshot
- 模型切換直接覆寫舊結果
- Checkpoint 損毀後仍繼續執行
- `REQUIRES_REVIEW` 狀態被自動繞過
- 報告包含沒有 Evidence 的 `CONFIRMED` 結論

**使用者決策：同意。**

### Q38 — 分析正確率如何評估？

建立人工標註的 Ground Truth，至少評估：

- Symbol／方法定位
- 直接呼叫關係
- 跨層依賴
- 入口與資料流
- Claim 是否有足夠 Evidence
- `CONFIRMED` 是否真的應該確認
- `INFERRED` 是否被正確標記
- `UNKNOWN` 是否被錯誤填補

Parser 可確定的事實與 LLM 語意結論分開計分。

**使用者決策：同意。**

### Q39 — MVP 是否只支援一種任務類型？

第一版只支援：

> 建立 Java 舊系統的可追溯架構與交接分析報告。

暫不支援：

- 自動修 Bug
- 自動重構
- 自動產生測試
- 自動 Migration
- 自動部署
- 通用問答
- 任意 Agent 任務

**使用者決策：同意。**

### Q40 — 什麼情況代表值得繼續投資？

只有在以下條件全部達成後，才進入下一階段產品化：

1. POC-1 能通過 Crash → Restart → Resume
2. POC-2 能正確建立 Java 結構索引
3. POC-3 能產生具 Evidence 的結構化 Claims
4. POC-4 能針對原始碼異動做局部失效與重分析
5. POC-5 能完成模型切換且不破壞歷史結果
6. Baseline 比 Proposed 顯著增加恢復成果與追溯能力
7. 人工抽查報告沒有關鍵無證據結論
8. 失敗情況能可靠停在 `REQUIRES_REVIEW`

**使用者決策：同意。**

### 第八輪結論

- 測試 corpus 必須包含真實跨層關係與異動影響鏈
- 任一核心一致性失敗都不得宣稱 POC 成功
- Parser 事實與 LLM 結論分開評估
- MVP 只支援單一 Java 交接分析任務
- 完成所有 POC 驗收條件後，才值得產品化投資

---

## 十、最終共同理解

使用者最後確認：**Q1–Q40 全部同意，並確認共同理解。**

### 產品定位

第一版不是通用 Agent Runtime，而是：

> 面向 Java 舊系統的長時間、可中斷、可恢復、可追溯架構分析與軟體交接工具。

唯一任務類型：

> 建立 Java 舊系統的可追溯架構與交接分析報告。

暫不做自動修 Bug、重構、Migration、部署、通用問答或任意 Agent 任務。

### 核心成功條件

```text
分析舊系統
  ↓
完成部分成果
  ↓
Process 被終止
  ↓
原始碼部分變更
  ↓
模型切換
  ↓
重新啟動
  ↓
辨識哪些成果仍有效
  ↓
只重做受影響部分
  ↓
產出可追溯交接報告
```

單純「重新啟動後能跑完」不算成功。

### Runtime 決策

- 第一版由自研最小 Runtime 擁有任務狀態
- CONTINUUM 延後作為外部比較基準
- LangGraph 暫不作核心依賴
- 單機 CLI
- 單一 Task
- 單一 Worker
- 暫不做並行分析
- 任務使用明確狀態機
- 關鍵不確定狀態必須進入 `REQUIRES_REVIEW`

### Checkpoint 決策

Checkpoint 必須保存：

- Task State
- 已驗證事實
- Pending Steps
- Evidence 引用
- Snapshot／檔案 Hash
- Parser、Prompt、Model、Schema 版本
- 最後已提交的工作單元
- Checkpoint 格式版本

不能只保存目前檔案、進度百分比或對話歷史。

### 資料一致性

每個工作單元使用穩定的 `operation_id`，並以 SQLite Transaction 原子提交：

```text
Analysis Result
  + Evidence
  + Execution Event
  + Checkpoint
  ↓
同一 Transaction Commit
```

若 Tool Call 已完成但 Checkpoint 未完成：

- 已知完成：重用結果
- 狀態不明：`REQUIRES_REVIEW`
- 不得直接重複執行或假設成功

### 原始碼異動

- 每次任務建立不可變 `source_snapshot_id`
- 保存完整檔案 Manifest 與 Hash
- 先判定直接失效
- 再透過依賴圖做影響分析
- 只重新分析受影響項目
- 舊結果保留，轉為 `STALE`
- 新分析建立新的 Result Revision

### Parser 與 AI 邊界

- JavaParser 是第一版 Java 結構事實的主要權威
- CodeGraph 負責索引與關係查詢
- Tree-sitter 延後作多語言擴充
- Parser 可確定的 Symbol、行號、直接呼叫關係，LLM 不得推翻
- LLM 只能基於 Parser 與 Evidence 產生語意解釋
- LLM 輸出必須符合結構化 Schema
- 沒有 Evidence 的 Claim 不得標記為 `CONFIRMED`

### 報告規則

報告由已驗證資料確定性產生，不由 LLM 直接自由撰寫。

報告區分：

- `CONFIRMED`
- `INFERRED`
- `REQUIRES_REVIEW`
- `STALE`
- `CONFLICTING`
- `UNKNOWN`

無 Evidence、Snapshot 不匹配或狀態衝突的結果，不得進入一般結論區。

### 安全與合規

MVP：

- 不修改原始碼
- 不執行被分析系統
- 不執行任意 Shell 指令
- 不呼叫外部 API
- 不把 API Key、密碼、Token 擴散到 Log 或報告
- Hash Chain 只宣稱完整性與追溯能力
- 不宣稱 ISO 27001 合規

### 實作順序

```text
POC-0：Snapshot 與資料模型
POC-1：Crash → Restart → Resume
POC-2：JavaParser／CodeGraph
POC-3：結構化 Claim 與 Evidence 驗證
POC-4：原始碼異動與局部重分析
POC-5：模型切換與報告
CONTINUUM：外部比較基準
```

### 最終結論

> 先做一個單機、唯讀、單一 Java 舊系統任務的自研 Runtime，使用不可變 Snapshot、結構化 Checkpoint、版本化 Evidence、局部影響分析與確定性報告生成，證明「中斷、原始碼異動、模型切換後仍能可靠接續並保留可追溯成果」。完成內部基準後，再用 CONTINUUM 做對照，而不是一開始就把它當核心依賴。

---

## 十一、狀態

- Grill 討論：完成
- Q1–Q40：全部同意
- 共同理解：已確認
- 本文件用途：作為後續 POC 設計、資料模型、驗收標準與實作順序的決策紀錄
