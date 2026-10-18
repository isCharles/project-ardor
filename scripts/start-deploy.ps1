# PreflightOnly resolves configured ports without stopping services.
param([switch]$PreflightOnly)
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalLauncher.Common.ps1')
function Get-PortOwners {
    param([int]$Port)
    $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        [pscustomobject]@{ Port = $Port; ProcessId = $connection.OwningProcess; ProcessName = if ($process) { $process.ProcessName } else { 'unknown' } }
    }
}
$config = Get-ArdorComposeConfiguration -ProjectRoot $root
$ports = @(@($config.services.backend.ports) + @($config.services.frontend.ports) |
    ForEach-Object { if ($_.published) { [int]$_.published } } | Select-Object -Unique)
if ($ports.Count -lt 1) { throw "Compose 未定义应用宿主机端口，无法完成部署检查。" }
$conflicts = @($ports | ForEach-Object { Get-PortOwners -Port $_ })
$otherOwned = @($conflicts | Where-Object { $_.ProcessName -notmatch '(?i)^(com\.docker|docker|vpnkit)' })
foreach ($item in $conflicts) {
    Write-Host ("端口 {0} 被 {1} (PID {2}) 占用" -f $item.Port, $item.ProcessName, $item.ProcessId)
}
if ($otherOwned.Count -gt 0) { throw "配置端口被本机进程占用，请手动停止 IDE/开发服务器；不会强制结束进程。" }
if ($PreflightOnly) {
    if ($conflicts.Count) { Write-Host '正式部署时需停止 Docker 应用容器；本次只检查，不停止。' }
    Write-Host '检查完成：未启动 Docker、未停止容器、未执行构建部署。'
    exit 0
}
Assert-ArdorDockerEngine -ProjectRoot $root
if ($conflicts.Count) {
    & docker compose --project-directory $root -f (Join-Path $root 'docker-compose.yml') stop backend frontend
    if ($LASTEXITCODE -ne 0) { throw '停止 Docker 应用容器失败。' }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $busy = @($ports | ForEach-Object { Get-PortOwners -Port $_ })
        if ($busy.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($busy.Count) { throw 'Docker 应用容器停止后，配置的端口仍被占用。' }
}
& (Join-Path $root 'start.ps1')
if ($LASTEXITCODE -ne 0) { throw '部署失败，请查看上方输出。' }
Write-Host '部署完成。请使用 Compose 配置的前端端口访问。' -ForegroundColor Green
