# POC 執行規格：共同理解

## 一、目前階段

共同理解已達成，目前沒有尚待釐清的決策。

現階段正式進入：

> 將《方案可行性評估-Grill討論.md》中已確認的 Q1–Q40，轉成可交由較小模型執行的 POC 規格。

此階段的工作是產出並鎖定 POC 執行規格，不再擴張架構討論，也不開始撰寫 POC 程式碼。

---

## 二、固定交付內容

必須建立下列七個自足執行套件：

1. **POC-0：Snapshot 與資料模型**
2. **POC-1：Crash → Restart → Resume**
3. **POC-2：JavaParser／CodeGraph**
4. **POC-3：結構化 Claim 與 Evidence 驗證**
5. **POC-4：原始碼異動與局部重分析**
6. **POC-5：模型切換與報告**
7. **CONTINUUM：外部比較基準**

另外必須建立：

- 一份總索引；
- 一組 canonical contracts；
- corpus manifest；
- environment manifest；
- 跨規格一致性檢查規則。

總索引只負責呈現執行順序、規格版本與追蹤狀態。任何 POC 執行套件都不得依賴總索引才能理解或執行。

---

## 三、自足執行套件

「獨立規格」定義為自足的執行套件，而不只是單一 Markdown 文件。

建議目錄形式如下：

```text
specs/
  INDEX.md
  contracts/
  POC-0/
    README.md
    WORK-PACKETS.md
    schemas/
    fixtures/
    acceptance/
    expected/
  POC-1/
    README.md
    WORK-PACKETS.md
    schemas/
    fixtures/
    acceptance/
    expected/
  POC-2/
  POC-3/
  POC-4/
  POC-5/
  CONTINUUM/
```

每個執行套件必須獨立包含：

1. 完整背景與目的；
2. 要驗證的假設；
3. 範圍與明確排除項目；
4. 固定技術決策；
5. 禁止自行變更的事項；
6. 前置條件；
7. 輸入產物及版本；
8. 所需 Schema 與契約副本；
9. 工作包；
10. fixture 與故障注入資料；
11. 驗收命令；
12. 預期輸出；
13. 硬性停止條件；
14. 必須保存的驗證證據；
15. handoff manifest 格式；
16. blocker 回報格式；
17. 完成回報格式。

不得使用「同前」「參照前文」「依照先前討論」等需要依賴其他對話或文件才能理解的表述。

POC 之間可以存在依賴，但依賴只能透過明確、版本化、可驗證的產物契約傳遞。例如 POC-1 可以使用 POC-0 建立的資料庫 Schema，但 POC-1 規格必須完整寫明：

- 需要哪些 POC-0 產物；
- Schema 與介面版本；
- 產物 Hash；
- 如何執行前置檢查；
- 前置條件不符時如何停止；
- 不得讓執行模型自行猜測 POC-0 的行為。

---

## 四、工作包拆分原則

每個 POC 執行套件必須再拆成數個可由較小模型獨立執行的工作包。

每個工作包必須：

- 能在全新的模型 Session 中執行；
- 只完成一個明確、可觀察的結果；
- 有固定輸入；
- 有明確的前置條件；
- 指定允許修改的檔案範圍；
- 指定禁止修改的檔案或契約；
- 有明確產出；
- 有可執行的驗收命令；
- 有預期輸出或資料狀態；
- 有硬性停止條件；
- 產生結構化 handoff manifest。

每次只將一個工作包交付給較小模型。後續工作包只能依賴已落地的 Repository 狀態、版本化產物及驗證結果，不得依賴前一個模型的對話記憶。

---

## 五、固定技術棧

POC 固定採用單一 Java 技術棧：

- **Java 21 LTS**
- **Maven**
- **Picocli CLI**
- **JavaParser**
- **SQLite JDBC**
- **Jackson**
- **JSON Schema Validator**
- **JUnit 5**
- **Ollama HTTP API**

採用單一 Maven 專案。MVP 不加入：

- FastAPI；
- Spring Boot；
- LangGraph；
- Web UI；
- 第二套 Runtime；
- 非必要的跨程序服務。

主要依賴及版本必須在 canonical contracts 或 environment manifest 中鎖定。執行模型不得自行更換框架、升級版本或加入替代技術棧。

---

## 六、規格語言與命名

規格遵守以下語言規則：

- 目標、限制、步驟與驗收說明使用繁體中文；
- 類別名稱、方法名稱、欄位名稱、CLI、狀態、錯誤碼、JSON Schema 與 SQL DDL 使用英文；
- RFC 2119 關鍵字保留英文大寫：`MUST`、`MUST NOT`、`SHOULD`、`MAY`；
- 每個領域術語只能有一個 canonical English name；
- 不得讓不同執行模型自行翻譯或創造新的識別字。

---

## 七、Corpus 管理

測試 corpus 必須是版本化、不可變的正式輸入產物。

Corpus 必須：

- 來自授權允許的真實 Java 專案；
- 固定上游 Repository URL；
- 固定 commit SHA；
- 記錄授權；
- 固定納入分析的 30–50 個檔案；
- 覆蓋 Controller／Servlet、Service、DAO、DTO／Entity、JSP 或模板、XML 設定及跨檔案呼叫；
- 包含至少一個 Parser 無法完全確認的動態關係；
- 包含至少一個修改後會影響多個下游結論的檔案；
- 建立人工標註的 Ground Truth；
- 產生 corpus manifest 與 SHA-256。

原始碼異動測試必須使用基準 corpus 的明確派生版本，不得直接修改基準版本。

Baseline、Proposed 與 CONTINUUM 必須使用相同的 corpus、Ground Truth、變更集及驗收案例。

確切 corpus 來源必須經過事實調查後選定，不得讓執行模型臨場搜尋或自行決定。

---

## 八、驗收與完成證據

禁止以模型自述作為完成證據。

每個工作包至少必須完成以下驗證：

1. 建置或編譯成功；
2. 工作包指定測試通過；
3. 實際執行受影響的 CLI 路徑；
4. 比對 SQLite 狀態、輸出檔或報告內容；
5. 保存驗證命令；
6. 保存退出碼；
7. 保存必要輸出；
8. 保存產物 SHA-256。

以下內容不得構成完成證據：

- 「已完成」；
- 「看起來正常」；
- 只提供程式碼；
- 只提供測試程式而未執行；
- 未執行的驗證；
- 推測性結果；
- 以 mock 回傳值證明真實流程成功。

未執行的驗證不得宣稱通過。

---

## 九、小模型的執行權限

較小模型只能執行規格，不能自行修改需求或共用契約。

若發現以下情況，模型必須停止當前工作包並提交結構化 blocker：

- Schema 缺少必要欄位；
- 驗收條件互相矛盾；
- 前置產物版本不符；
- corpus 缺失或 Hash 不符；
- 工具或依賴缺失；
- 必須修改允許範圍外的檔案；
- 規格要求無法由固定技術棧完成；
- 規格、契約或 fixture Hash 不符；
- 執行結果進入未定義狀態。

執行模型不得自行：

- 修改 Schema；
- 修改 canonical contracts；
- 放寬驗收條件；
- 更換技術棧；
- 增加未經核准的 fallback；
- 跳過故障案例；
- 將未完成項目留成 TODO；
- 以 placeholder、stub 或 no-op 假裝完成；
- 修改 Ground Truth 以配合實際結果。

規格問題必須由較強模型或人工審核。任何修正都必須產生新規格版本，不得由執行模型直接改寫既有規格。

---

## 十、Session 與 Handoff 規則

每個工作包必須能在全新模型 Session 中執行。

工作包輸入只包含：

- 當前工作包規格；
- 鎖定版本的契約；
- 鎖定版本的 fixture；
- Repository 當前狀態；
- 前置工作包產生的 handoff manifest；
- 前置工作包的驗收結果。

不得以自由格式對話摘要作為唯一交接資料。

每個工作包完成後必須產生 handoff manifest，至少包含：

```text
spec_id
spec_version
work_packet_id
work_packet_version
contract_version
corpus_version
environment_version
repository_revision
changed_files
artifact_paths
artifact_sha256
schema_version
database_version
verification_commands
verification_exit_codes
verification_results
unresolved_blockers
next_preconditions
completed_at
```

Handoff manifest 必須使用固定 Schema，並通過 Schema 驗證後才能開放下一個工作包。

---

## 十一、工作包執行與整合順序

工作包必須依規格順序逐一執行。

規則如下：

- 每次執行前驗證前置 handoff manifest；
- 每次只允許一個 Repository 寫入者；
- 驗收通過後才能執行下一工作包；
- 可以平行進行唯讀審查；
- 不允許多個小模型同時修改同一 Repository；
- 不允許平行修改共享 Schema、SQLite transaction、CLI 或狀態機；
- CONTINUUM 比較必須等 Proposed POC-0～POC-5 基準完成並鎖定後開始。

---

## 十二、規格版本與完整性

所有規格與執行產物都必須使用不可變版本及 SHA-256 管理。

每個執行套件至少包含：

```text
spec_id
spec_version
contract_version
corpus_version
sha256
supersedes
status
```

規則如下：

- 已交付執行的規格不得原地修改；
- 修正必須產生新版本；
- Handoff manifest 必須引用精確版本與 Hash；
- 規格 Hash 不匹配時必須停止執行；
- 契約 Hash 不匹配時必須停止執行；
- corpus Hash 不匹配時必須停止執行；
- 舊版本必須保留，供結果重現與稽核；
- 新版本必須記錄 `supersedes` 關係及修訂原因。

---

## 十三、環境準備與離線執行

「準備環境」與「執行 POC」必須分離。

準備階段可以依核准清單取得：

- Maven dependencies；
- Ollama models；
- Java corpus；
- 必要的本地工具。

準備完成後必須產生 environment manifest，記錄：

- JDK 版本；
- Maven 版本；
- 作業系統與架構；
- 依賴版本及 Hash；
- Ollama 版本；
- 實際模型 Tag；
- corpus 版本與 Hash；
- 工具版本；
- 準備完成時間。

POC 執行期間不得：

- 自動執行 `ollama pull`；
- 自動下載 corpus；
- 自動切換 Maven dependency；
- 存取外部模型 API；
- 自行選擇替代模型；
- 因缺少依賴而偷偷改用其他版本。

缺少依賴或模型時，必須立即提交 blocker。

POC-3 與 POC-5 使用的兩個實際 Ollama model tags 必須在 environment manifest 中鎖定，執行模型不得自行選擇或替換。

---

## 十四、規格產製與鎖定順序

規格產製順序固定如下：

```text
Canonical contracts
        ↓
Corpus／environment contract
        ↓
POC-0～POC-5 execution packages
        ↓
CONTINUUM comparison package
        ↓
跨規格一致性檢查
        ↓
規格版本鎖定
        ↓
交付較小模型逐包執行
```

具體步驟：

1. 建立共用契約，包括目錄結構、命名、Schema 版本、CLI 規則、狀態、錯誤碼、驗證證據與 handoff 格式；
2. 建立 corpus 與 environment contract；
3. 產出七份自足執行套件；
4. 對所有執行套件執行跨文件一致性檢查；
5. 確認每個工作包都有輸入、輸出、驗收及停止條件；
6. 鎖定規格版本與 SHA-256；
7. 依序交付較小模型執行；
8. 實作中若發現規格問題，建立明確規格修訂，不允許執行模型自行改需求。

---

## 十五、現階段禁止事項

在七份規格與共用契約完成、通過一致性檢查並鎖定前，不得：

- 開始 POC 程式實作；
- 讓小模型自行設計資料模型；
- 讓小模型自行選擇 corpus；
- 讓小模型自行選擇 Ollama 模型；
- 讓小模型自行調整驗收標準；
- 先實作 POC-0，再以偶然產生的資料結構約束後續 POC；
- 將未審核的實作結果反向視為正式契約；
- 開始 CONTINUUM 比較。

---

## 十六、最終結論

目前工作不是繼續擴張方案討論，也不是立即撰寫 POC 程式碼，而是：

> 先將已確認的產品邊界、Runtime 語義、資料一致性、Evidence、Checkpoint、模型切換、局部重分析、報告與驗收條件，轉成七個自足、版本化、可由較小模型逐工作包執行的 POC 規格套件；完成跨規格一致性檢查並鎖定後，才開始依序實作。
