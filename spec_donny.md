目前先準備環境，不要開始 POC-0 實作。現況有兩類工作：主機工具安裝，以及規格閘門修正。         
                                                                                               
 1. 必須準備的固定版本                                                                         
                                                                                               
 ┌─────────┬─────────────────────────┬────────────────┐                                        
 │ 項目    │ 必須版本                │ 現況           │                                        
 ├─────────┼─────────────────────────┼────────────────┤                                        
 │ OpenJDK │ 21 LTS，major 必須是 21 │ Java 8，不符合 │                                        
 ├─────────┼─────────────────────────┼────────────────┤                                        
 │ Maven   │ 3.10.0                  │ 3.9.6，不符合  │                                        
 ├─────────┼─────────────────────────┼────────────────┤                                        
 │ Ollama  │ 0.40.14                 │ 未安裝         │                                        
 ├─────────┼─────────────────────────┼────────────────┤                                        
 │ Model A │ qwen3:8b                │ 未安裝         │                                        
 ├─────────┼─────────────────────────┼────────────────┤                                        
 │ Model B │ qwen3:14b               │ 未安裝         │                                        
 └─────────┴─────────────────────────┴────────────────┘                                        
                                                                                               
 版本不能自行替換；契約鎖定於 specs/contracts/CANONICAL-CONTRACTS.md:34-53。                   
                                                                                               
 2. 建議使用隔離工具目錄                                                                       
                                                                                               
 不要先移除電腦原有的 Java/Maven。將 POC 工具放在獨立目錄，例如：                              
                                                                                               
 ```text                                                                                       
C:\poc-tools\
  jdk-21\
  apache-maven-3.10.0\
  ollama\
 ```                                                                                           
                                                                                               
 開一個專用 PowerShell，只在該視窗設定：                                                       
                                                                                               
 ```powershell                                                                                 
$env:JAVA_HOME = "C:\poc-tools\jdk-21"
$env:MAVEN_HOME = "C:\poc-tools\apache-maven-3.10.0"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
 ```                                                                                           
                                                                                               
 從各產品的官方版本封存下載精確版本；不要使用會自動取得最新版的安裝方式。                      
                                                                                               
 驗證：                                                                                        
                                                                                               
 ```powershell                                                                                 
java -version
mvn -version
 ```                                                                                           
                                                                                               
 必須看到：                                                                                    
                                                                                               
 - Java major version：21                                                                      
 - Maven：3.10.0                                                                               
 - Maven 顯示所使用的 Java 也是 21                                                             
                                                                                               
 若 java -version 正確但 mvn -version 顯示 Java 8，代表 JAVA_HOME 或 PATH 優先順序仍有問題。   
                                                                                               
 3. 安裝 Ollama 與模型                                                                         
                                                                                               
 安裝精確的 Ollama 0.40.14，然後驗證：                                                         
                                                                                               
 ```powershell                                                                                 
ollama --version
 ```                                                                                           
                                                                                               
 在環境建置階段允許網路時下載模型：                                                            
                                                                                               
 ```powershell                                                                                 
ollama pull qwen3:8b
ollama pull qwen3:14b
ollama list
 ```                                                                                           
                                                                                               
 再檢查模型資訊：                                                                              
                                                                                               
 ```powershell                                                                                 
ollama show qwen3:8b --modelfile
ollama show qwen3:14b --modelfile
 ```                                                                                           
                                                                                               
 要求：                                                                                        
                                                                                               
 - ollama list 必須出現兩個精確 tag。                                                          
 - 必須保存每個本機模型的完整 SHA-256 digest。                                                 
 - 不可把縮短的 ID 補零或自行推算成 digest。                                                   
 - 正式執行時 Ollama 只能由 loopback 存取。                                                    
                                                                                               
 4. Maven 相依套件要先下載，再切離線                                                           
                                                                                               
 正式 POC 執行要求使用：                                                                       
                                                                                               
 ```powershell                                                                                 
mvn -o ...
 ```                                                                                           
                                                                                               
 但 -o 只能使用已存在於本機 Maven repository 的依賴。因此 provisioning 流程應是：              
                                                                                               
 1. POC 的 pom.xml 確定後，在允許網路的 provisioning 階段解析所有鎖定依賴。                    
 2. 保存 Maven artifact 版本及 SHA-256。                                                       
 3. 關閉網路。                                                                                 
 4. 實際驗證：                                                                                 
                                                                                               
 ```powershell                                                                                 
mvn -o -q test
mvn -o -q package
 ```                                                                                           
                                                                                               
 建議為這個 POC 使用獨立 repository：                                                          
                                                                                               
 ```powershell                                                                                 
$env:MAVEN_OPTS = "-Dmaven.repo.local=C:\poc-tools\m2-repository"
 ```                                                                                           
                                                                                               
 避免其他專案先前下載的版本污染驗證。                                                          
                                                                                               
 5. 建立 READY environment manifest                                                            
                                                                                               
 完成後需要一份符合 specs/contracts/schemas/manifests.schema.json 的 manifest，概念如下：      
                                                                                               
 ```json                                                                                       
{
  "kind": "environment",
  "version": "1.0.0",
  "os": "Windows 10.0.22631",
  "arch": "x64",
  "java": {
    "version": "21",
    "vendor": "<實際 OpenJDK vendor>"
  },
  "maven": "3.10.0",
  "ollama": "0.40.14",
  "models": [
    {
      "tag": "qwen3:8b",
      "digest": "<64 位小寫 SHA-256>"
    },
    {
      "tag": "qwen3:14b",
      "digest": "<64 位小寫 SHA-256>"
    }
  ],
  "dependencies": [
    {
      "coordinate": "<groupId:artifactId>",
      "version": "<鎖定版本>",
      "sha256": "<artifact SHA-256>"
    }
  ],
  "status": "READY"
}
 ```                                                                                           
                                                                                               
 dependencies 必須列出實際解析的 Maven artifacts，不能僅填規格中的直接依賴。                   
                                                                                               
 6. 目前規格還有一個必須先修的閘門矛盾                                                         
                                                                                               
 即使工具安裝完成，也不能直接宣告 READY，原因如下：                                            
                                                                                               
 1. SPEC-MANIFEST.json 鎖定了現有檔案 hash。                                                   
 2. POC-0 執行包目前只包含 environment-observation.json，狀態是 NOT_READY。                    
 3. POC-0/README.md 卻要求執行包內必須有 READY environment manifest。                          
 4. 規格要求執行前 mvn -o package，但目前 repository 尚無 pom.xml。                            
 5. 規格要求執行 handoff validate-spec，但該 CLI 是尚未建立的 POC 產物。                       
                                                                                               
 因此正確處理順序是：                                                                          
                                                                                               
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │    安裝固定版本工具    │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │    下載兩個固定模型    │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │       建立並驗證       │                                                                    
 │ environment-manifest.  │                                                                    
 │          json          │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │   補齊 POC-0 pom.xml   │                                                                    
 │   與離線 Maven cache   │                                                                    
 │   provisioning 規則    │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │                        │                                                                    
 │  更新 POC-0 execution  │                                                                    
 │         bundle         │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │   重新計算 bundle 與   │                                                                    
 │   authoritative file   │                                                                    
 │         hashes         │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │ 重新鎖定 SPEC-MANIFEST │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │      執行規格驗證      │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │     環境閘門 OPEN      │                                                                    
 │                        │                                                                    
 └────────────┬───────────┘                                                                    
              │                                                                                
              │                                                                                
              │                                                                                
              │                                                                                
              ▼                                                                                
 ┌────────────────────────┐                                                                    
 │                        │                                                                    
 │      開始 P0-WP1       │                                                                    
 │                        │                                                                    
 └────────────────────────┘                                                                    
                                                                                               
 不要直接覆寫 specs/inputs/environment-observation.json；那會立即破壞現有 lock                 
 hash。應保留它作為歷史觀察，新增 READY manifest，然後正式重新產生 POC-0 bundle 與規格鎖。     
                                                                                               
 7. 最終 preflight                                                                             
                                                                                               
 完成規格閘門修正後，正式開始前依序確認：                                                      
                                                                                               
 ```powershell                                                                                 
java -version
mvn -version
ollama --version
ollama list
python specs\validate_specs.py
mvn -o -q -DskipTests package
 ```                                                                                           
                                                                                               
 只有下列條件全部成立才可開始：                                                                
                                                                                               
 - Java 21；                                                                                   
 - Maven 3.10.0 且使用 Java 21；                                                               
 - Ollama 0.40.14；                                                                            
 - 兩個模型 tag 與完整 digest 都吻合；                                                         
 - Maven 離線建置成功；                                                                        
 - 規格、corpus、bundle 所有 SHA-256 正確；                                                    
 - 新的 environment manifest 為 READY；                                                        
 - execution_gate 為 OPEN。                    