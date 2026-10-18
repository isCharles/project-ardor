# Full build + Docker deploy, with a preflight check that stops you from
# rebuilding for nothing when the IDE (or a dev server) already owns 8080/3000.
# Usage: double-click start.cmd, or run this script from a terminal.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Get-PortOwner {
    param([int]$Port)
    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $connection) { return $null }
    $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        Port = $Port
        ProcessId = $connection.OwningProcess
        ProcessName = if ($process) { $process.ProcessName } else { "unknown" }
    }
}

Write-Host ""
Write-Host "=== 部署前检查端口 ===" -ForegroundColor Cyan
$conflicts = @()
foreach ($port in @(8080, 3000)) {
    $owner = Get-PortOwner -Port $port
    if ($owner) { $conflicts += $owner }
}
if ($conflicts.Count -gt 0) {
    foreach ($item in $conflicts) {
        Write-Host ("端口 {0} 被 {1} (PID {2}) 占用" -f $item.Port, $item.ProcessName, $item.ProcessId) -ForegroundColor Yellow
    }
    throw @"
部署需要停止占用这些端口的进程：
  - 8080：IDEA 里 Run/Debug 的后端，或 Docker 的 backend 容器
  - 3000：VS Code 的 Next.js dev，或 Docker 的 frontend 容器
请先停止它们，再重新运行。
"@
}
Write-Host "端口 8080 和 3000 都空闲，可以部署" -ForegroundColor Green

Write-Host ""
Write-Host "=== 开始构建并部署（会编译 jar 并重建 Docker 镜像，约 1–3 分钟） ===" -ForegroundColor Cyan
& (Join-Path $root "start.ps1")
if ($LASTEXITCODE -ne 0) { throw "部署失败，请查看上方输出" }
Write-Host ""
Write-Host "部署完成：http://localhost:3000" -ForegroundColor Green
