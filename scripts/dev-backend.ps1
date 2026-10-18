# Native backend uses isolated development data and an external private file store.
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalLauncher.Common.ps1')
$config = Get-ArdorComposeConfiguration -ProjectRoot $root -Development
$native = $config.'x-ardor-native-environment'
if (-not $native -or -not $native.DATABASE_URL -or -not $native.ARDOR_ENCRYPTION_KEY) { throw "开发环境缺少数据库或加密密钥。" }
foreach ($property in $native.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
}
if (-not $env:LOCALAPPDATA) { throw "LOCALAPPDATA 不可用，无法确定开发文件目录。" }
$env:ARDOR_RESUME_STORAGE = Join-Path $env:LOCALAPPDATA 'ProjectArdor\development\resumes'
Push-Location (Join-Path $root 'backend')
try {
    Write-Host "启动开发后端 http://localhost:$($env:BACKEND_PORT) （调试端口仅监听 localhost:5005）"
    & mvn spring-boot:run '-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005'
    if ($LASTEXITCODE -ne 0) { throw "Maven 启动后端失败（退出码 $LASTEXITCODE）。" }
}
finally { Pop-Location }
