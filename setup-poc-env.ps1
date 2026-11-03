$pocToolsPath = "C:\softwares"
$env:JAVA_HOME = "$pocToolsPath\Java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9\jdk-21.0.13+9"
$env:MAVEN_HOME = "$pocToolsPath\apache-maven-3.10.0"

# 設定 Path，確保優先順序：將自訂的 bin 放在最前面
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;" + $env:Path

# 設定 Maven Repository 隔離，避免污染全域套件
$env:MAVEN_OPTS = "-Dmaven.repo.local=$pocToolsPath\m2-repository"

# 進入此指令視窗後，立即驗證
Write-Host "POC Environment Initialized" -ForegroundColor Green
java -version
mvn -version