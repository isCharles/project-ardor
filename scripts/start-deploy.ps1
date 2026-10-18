# Full build + Docker deploy. Preflight:
#   - if 8080/3000 are owned by Docker app containers, stop those containers and continue
#   - if they are owned by an IDE backend or a dev server, stop and ask the user (never kill it)
# Usage: double-click start.cmd, or run this script from a terminal.
#        Add -PreflightOnly to only run the port/Docker checks.
param([switch]$PreflightOnly)

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

function Test-DockerOwned {
    param($Owner)
    if (-not $Owner) { return $false }
    return $Owner.ProcessName -match '(?i)^(com\.docker|docker|vpnkit)'
}

function Wait-PortsFree {
    param([int[]]$Ports, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $busy = @($Ports | Where-Object { Get-PortOwner -Port $_ })
        if ($busy.Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

Write-Host ""
Write-Host "=== 部署前检查端口 ===" -ForegroundColor Cyan
$conflicts = @()
foreach ($port in @(8080, 3000)) {
    $owner = Get-PortOwner -Port $port
    if ($owner) { $conflicts += $owner }
}

if ($conflicts.Count -gt 0) {
    $dockerOwned = @($conflicts | Where-Object { Test-DockerOwned $_ })
    $otherOwned = @($conflicts | Where-Object { -not (Test-DockerOwned $_) })
    if ($otherOwned.Count -gt 0) {
        foreach ($item in $conflicts) {
            Write-Host ("端口 {0} 被 {1} (PID {2}) 占用" -f $item.Port, $item.ProcessName, $item.ProcessId) -ForegroundColor Yellow
        }
        throw @"
部署需要先停止这些进程：
  - 8080：IDEA 里 Run/Debug 的后端
  - 3000：VS Code / 终端里跑的 Next.js dev

Docker 容器占用会被脚本自动停止，但 IDE 或终端启动的进程不会被强制结束。
请先停止它们，再重新运行。
"@
    }

    Write-Host "端口被 Docker 容器占用，正在停止 backend/frontend 容器..." -ForegroundColor Yellow
    Push-Location $root
    try { cmd /c "docker compose stop backend frontend >nul 2>&1" | Out-Null } finally { Pop-Location }
    if (-not (Wait-PortsFree -Ports @(8080, 3000) -TimeoutSeconds 30)) {
        throw "已停止 Docker 应用容器，但 8080/3000 仍被占用，请稍后重试"
    }
    Write-Host "Docker 应用容器已停止，端口已释放" -ForegroundColor Green
} else {
    Write-Host "端口 8080 和 3000 都空闲，可以部署" -ForegroundColor Green
}

if ($PreflightOnly) {
    Write-Host "仅检查模式：不执行构建部署。" -ForegroundColor DarkGray
    exit 0
}

Write-Host ""
Write-Host "=== 开始构建并部署（编译 jar + 重建 Docker 镜像，约 1–3 分钟） ===" -ForegroundColor Cyan
& (Join-Path $root "start.ps1")
if ($LASTEXITCODE -ne 0) { throw "部署失败，请查看上方输出" }
Write-Host ""
Write-Host "部署完成：http://localhost:3000" -ForegroundColor Green
