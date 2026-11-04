$pocToolsPath = "C:\softwares"

$javaHome = "$pocToolsPath\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9\jdk-21.0.13+9"
$mavenHome = "$pocToolsPath\maven\apache-maven-3.10.0"

if (-not (Test-Path $javaHome)) {
    throw "Missing Java 21 at $javaHome"
}
if (-not (Test-Path $mavenHome)) {
    throw "Missing Maven 3.10 at $mavenHome"
}

$env:JAVA_HOME = $javaHome
$env:MAVEN_HOME = $mavenHome
$env:MAVEN_OPTS = "-Dmaven.repo.local=$pocToolsPath\m2-repository"

# 清掉所有舊 Maven PATH，確保優先用 3.10
$cleanPath = ($env:Path -split ';' | Where-Object {
    $_ -and
    $_ -notmatch 'apache-maven-3\.[0-9]+\.[0-9]+' -and
    $_ -notmatch 'maven\\bin$' -and
    $_ -notmatch 'maven/bin$'
}) -join ';'

$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$cleanPath"

Write-Host "POC Environment Initialized" -ForegroundColor Green
java -version
mvn -version