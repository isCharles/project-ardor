# Isolated infrastructure only; use dev-backend.ps1 for the native backend.
param([ValidateRange(1, 300)][int]$HealthTimeoutSeconds = 90)
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $PSScriptRoot
$logFile = Join-Path $PSScriptRoot 'dev-up.log'
. (Join-Path $PSScriptRoot 'LocalLauncher.Common.ps1')
$compose = @('compose', '--project-directory', $root, '-f', (Join-Path $root 'docker-compose.dev.yml'), '-p', 'project-ardor-dev')
function Wait-ServiceHealthy {
    param([string]$Service)
    $containerId = & docker @compose ps -q $Service
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { return $false }
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        $status = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq 'healthy') { return $true }
        if ($status -in @('exited', 'dead')) { return $false }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}
$exitCode = 0
$transcribing = $false
try {
    Start-Transcript -Path $logFile -Force | Out-Null
    $transcribing = $true
    Assert-ArdorDockerEngine -ProjectRoot $root
    $config = Get-ArdorComposeConfiguration -ProjectRoot $root -Development
    # Do not stop the user's deployment to prepare independent development data.
    & docker @compose up -d postgres redis
    if ($LASTEXITCODE -ne 0) { throw "开发基础设施启动失败。" }
    if (-not (Wait-ServiceHealthy 'postgres') -or -not (Wait-ServiceHealthy 'redis')) {
        throw "开发基础设施健康检查失败；请检查 project-ardor-dev 容器状态，尚不可启动应用。"
    }
    $native = $config.'x-ardor-native-environment'
    Write-Host 'PostgreSQL 与 Redis 已就绪（独立开发数据，不使用部署数据库）。' -ForegroundColor Green
    Write-Host '下一步：运行 scripts\dev-backend.ps1，再在 frontend 运行 npm run dev。'
    Write-Host "Redis：127.0.0.1:$($native.REDIS_PORT)；后端端口：$($native.BACKEND_PORT)"
    Write-Host '如果部署版占用了应用端口，请先手动停止它或配置其他开发端口。'
}
catch {
    $exitCode = 1
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "日志：$logFile"
}
finally {
    if ($transcribing) { try { Stop-Transcript | Out-Null } catch { } }
    if (-not $env:ARDOR_DEV_NONINTERACTIVE) { [void](Read-Host '按回车关闭窗口') }
}
exit $exitCode
