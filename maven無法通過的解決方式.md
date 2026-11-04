```powershell

PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> $JAVA_HOME = 'C:\softwares\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9\jdk-21.0.13+9'
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> $CERT = 'C:\Users\N000022057\AppData\Local\hermes\certs\FPGSSL-Intra-Root-CA.crt'
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> $TRUSTSTORE = "$JAVA_HOME\lib\security\cacerts"
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> 
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> & "$JAVA_HOME\bin\keytool.exe" `
>>   -importcert `
>>   -alias fpg-root-ca `
>>   -keystore "$TRUSTSTORE" `
>>   -storepass changeit `
>>   -file "$CERT" `
>>   -noprompt
警告: 使用 -cacerts 選項存取 cacerts 金鑰儲存庫
憑證已新增至金鑰儲存庫中
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> & "$JAVA_HOME\bin\keytool.exe" -list -keystore "$TRUSTSTORE" -storepass changeit | Select-String "fpg-root-ca"
警告: 使用 -cacerts 選項存取 cacerts 金鑰儲存庫

fpg-root-ca, 2026年11月4日, trustedCertEntry, 

PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> mvn -U -q verify                                     
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.
{"error":{"code":"E_INTEGRITY","message":"Checkpoint hash mismatch at sequence 1"}}
PS C:\MYLAB\VSCODE\AutomaticContinuationandCodeAnalysisHandoffSystem> 
```