# LM Studio 排查记录

- 記錄 ID：`LMSTUDIO-20261105-064705-4D8A62F1`
- 建立時間：`2026-11-05T06:47:05+08:00`
- 分支：`fix/integrity-diagnostics`
- 診斷對象：LM Studio OpenAI-compatible local server 與 POC-3 WP2/WP4 Java provider
- 結果摘要：LM Studio 與 Model A 可用；短 completion、完整 analysis schema 和固定 corpus prompt 均能回覆。Java smoke 最終透過 HTTP/1.1、`reasoning_effort=none`、`max_tokens=1024` 完成。問題不是模型未載入，而是 Qwen3.5 會先產生 reasoning channel，較小輸出上限會令一般 `content` 留空或 JSON 被截斷；Java 非流式請求的原始參數組合另曾長時間等待。

## 環境

| 項目 | 值 |
|---|---|
| OS | Windows 11 x64 |
| Java / Maven | OpenJDK 21.0.13 / Maven 3.10.0 |
| LM Studio | environment manifest 記錄 1.3.3 |
| Endpoint | `http://127.0.0.1:1234/v1` |
| API mode | OpenAI-compatible |
| READY manifest Model A | `qwen/qwen3.5-9b`，`UNPINNED_MODEL` |
| Corpus | 固定 JPetStore corpus，位於 `target/p2-corpus` |

## 排查歷程

| 順序 | 探測 | 結果與判讀 |
|---|---|---|
| 1 | `curl.exe -sS --max-time 5 http://127.0.0.1:1234/v1/models` | HTTP endpoint 很快回應；清單包含 `qwen/qwen3.5-9b`。這只證明 server/model list 可用，不等同 completion 正常。 |
| 2 | Chat completion：`max_tokens=16`，要求只回 `OK` | HTTP 200、約 1.26 秒；`completion_tokens=16`、`reasoning_tokens=15`、`content` 空白、`finish_reason=length`。模型在極小 token budget 內先輸出 reasoning。 |
| 3 | Chat completion：`max_tokens=128` | 約 5 秒；127 token 是 `reasoning_content`，`content` 空白，`finish_reason=length`。PowerShell 直接送 JSON 時使用 `Invoke-RestMethod`，避免 PowerShell/curl quoting 將 body 改壞。 |
| 4 | 以 `reasoning_effort=low`、`/no_think`、`chat_template_kwargs.enable_thinking=false`、`think=false` 個別探測 | 在 128-token budget 下均未避免 reasoning channel 先耗盡預算；不採用這些未證實有效的設定。 |
| 5 | Chat completion：`max_tokens=512`，無 JSON Schema | 約 6.15 秒，回傳 `content="\n\nOK"`、`finish_reason=stop`；同一 completion 仍包含約 169 個 reasoning token。 |
| 6 | 最小 JSON Schema：`reasoning_effort=none`、`max_tokens=512` | 約 1.15 秒，JSON 回到一般 `message.content`，`reasoning_tokens=0`。證明此模型與 LM Studio 支援該控制參數。 |
| 7 | 完整 `analysis.schema.json`，短 prompt、`reasoning_effort=none`、512 tokens | 約 2.44 秒，回傳符合 schema 的空 analysis object。完整 schema 本身可用。 |
| 8 | Account 固定 corpus 片段加 WP2 bounded prompt 與完整 schema，PowerShell direct request | 約 17.73 秒，回傳兩個有效 claims，`reasoning_tokens=0`。證明完整 prompt/schema 組合可由本機 endpoint 完成。 |
| 9 | Java smoke 使用較早的預設 client/輸出設定 | 曾在約 181 秒、600 秒 timeout；後續一次 timeout 的 thread dump 顯示停在 `HttpClient.send`，未進入 schema validation 或 DB persistence。中間曾使用文件開頭而非 declaration 的 excerpt，該次結果不作為有效 smoke 證據。 |
| 10 | Java client 改用 HTTP/1.1，`reasoning_effort=none`，512 tokens | Java request 可在約 26 秒收到回覆，但 JSON 在 512-token 上限被截斷，回傳 `E_SCHEMA_INVALID`。這確認傳輸已恢復，需增加 generation budget。 |
| 11 | Java client 固定 HTTP/1.1、`reasoning_effort=none`、`max_tokens=1024`，evidence 限定於實際宣告/欄位行 | Model A Account/Catalog/Order smoke 均完成 schema validation、invocation/result 持久化、atomic work commit 與 event/checkpoint integrity 驗證。Account 額外注入 `PROCESS_KILL_AFTER_RESULT`，確認所有資料 rollback，再以同一 operation ID 重呼模型並成功提交。 |

## 最終有效設定

Java provider 目前採用以下 request 設定：

```text
HTTP version: HTTP/1.1
endpoint: http://127.0.0.1:1234/v1/chat/completions
model: READY manifest 選出的 Model A
reasoning_effort: none
temperature: 0
top_p: 1
seed: 42
max_tokens: 1024
stream: false
response_format: strict json_schema
```

HTTP/1.1、關閉 reasoning、提高 token budget 是一次組合修正；本次沒有逐一隔離 HTTP/1.1 對延遲的獨立影響。Qwen3.5 的 hidden reasoning channel 與過小 `max_tokens` 對空 `content`/JSON 截斷的影響，則已由短探測直接觀察。

## 可重跑命令

三個 subject 共用 opt-in 測試；不加 property 時測試會 skip，不會呼叫模型。

```powershell
.\setup-poc-env.ps1
mvn -o -q "-Dtest=P3Wp4LocalModelSmokeTest" "-Dhandoff.localModelSmoke=true" test
```

預設 subject 為 Account。Catalog 和 Order 分別加上：

```powershell
"-Dhandoff.smoke.subject=catalog"
"-Dhandoff.smoke.subject=order"
```

驗證整個不觸發實際模型的離線套件：

```powershell
mvn -o -q verify
```

最近一次完整 verify：45 個 tests、0 failures、0 errors、1 個預設 skip。三次實際 smoke 耗時：Account recovery 74.61 秒、Catalog 13.12 秒、Order 39.96 秒。

## 剩餘範圍

- 尚未在模型 inference 正在進行時強制終止 OS process；目前已驗證的是 `PROCESS_KILL_AFTER_RESULT` 故障注入、交易 rollback，以及同 operation retry。
- 尚未完成 provider unavailable 後重新啟用的完整端到端 recovery 情境。
- `create-task` CLI 缺口仍未處理；POC-3 final handoff artifact 尚未產製。
- Model digest 未提供；invocation 會記錄 `UNPINNED_MODEL`。
