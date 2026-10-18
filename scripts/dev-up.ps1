# 一键准备本地开发所需的基础设施：PostgreSQL + Redis（带端口映射）。
# 后端和前端仍然在 IDEA / VS Code 里启动，这样才有热更新和断点。
# 运行日志保存在 scripts\dev-up.log，脚本出错时窗口不会直接关闭。
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$logFile = Join-Path $PSScriptRoot "dev-up.log"

function Test-DockerEngine {
    docker info --format "{{.ServerVersion}}" *> $null
    return $LASTEXITCODE -eq 0
}

function Wait-ServiceHealthy {
    param([string]$ContainerName, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $ContainerName 2>$null
        if ($status -eq "healthy" -or $status -eq "running") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

$exitCode = 0
try {
    Start-Transcript -Path $logFile -Force | Out-Null

    Write-Host ""
    Write-Host "=== Ardor 本地开发：检查 Docker ===" -ForegroundColor Cyan
    if (-not (Test-DockerEngine)) {
        $dockerExe = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
        if (-not (Test-Path -LiteralPath $dockerExe)) { throw "找不到 Docker Desktop，请手动启动后重试" }
        Write-Host "Docker 未运行，正在启动 Docker Desktop..." -ForegroundColor Yellow
        Start-Process -FilePath $dockerExe
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            Start-Sleep -Seconds 2
            if (Test-DockerEngine) { $ready = $true; break }
        }
        if (-not $ready) { throw "等待 Docker 启动超时，请手动打开 Docker Desktop 后重试" }
    }
    Write-Host "Docker 已就绪" -ForegroundColor Green

    Push-Location $root
    try {
        Write-Host ""
        Write-Host "=== 停止 Docker 内的应用容器（避免和本地 IDEA/VS Code 抢端口） ===" -ForegroundColor Cyan
        docker compose stop backend frontend *> $null

        Write-Host ""
        Write-Host "=== 启动 PostgreSQL + Redis（本地开发配置） ===" -ForegroundColor Cyan
        docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis
        if ($LASTEXITCODE -ne 0) { throw "docker compose 启动失败，请查看上方输出" }

        # Redis 的基础配置没有宿主机端口；如果容器是用旧配置创建的，这里自动重建一次。
        $mapped = docker port project-ardor-redis-1 6379 2>$null
        if (-not $mapped) {
            Write-Host "Redis 缺少宿主机端口映射，正在用开发配置重建..." -ForegroundColor Yellow
            docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate redis
            if ($LASTEXITCODE -ne 0) { throw "Redis 重建失败" }
        }

        Write-Host ""
        Write-Host "=== 等待服务健康检查 ===" -ForegroundColor Cyan
        $postgresOk = Wait-ServiceHealthy "project-ardor-postgres-1"
        $redisOk = Wait-ServiceHealthy "project-ardor-redis-1"
        if ($postgresOk -and $redisOk) {
            Write-Host "PostgreSQL 与 Redis 已就绪" -ForegroundColor Green
        } else {
            Write-Host "服务已启动，但健康检查未通过；请运行 docker compose ps 查看" -ForegroundColor Yellow
        }

        Write-Host ""
        Write-Host "==============================" -ForegroundColor DarkGray
        Write-Host " 现在可以启动应用：" -ForegroundColor White
        Write-Host "   1. IDEA: Run/Debug 'Ardor backend'" -ForegroundColor White
        Write-Host "   2. VS Code: Run and Debug -> 'Next.js dev'" -ForegroundColor White
        Write-Host "   3. 浏览器打开 http://localhost:3000" -ForegroundColor White
        Write-Host ""
        Write-Host " 连接信息：" -ForegroundColor White
        Write-Host "   PostgreSQL 127.0.0.1:5433   db=ardor   user=ardor   password=.env 里的 POSTGRES_PASSWORD" -ForegroundColor Gray
        Write-Host "   Redis      127.0.0.1:6379   无密码" -ForegroundColor Gray
        Write-Host "==============================" -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "运行日志：$logFile" -ForegroundColor DarkGray
    }
    finally {
        Pop-Location
    }
}
catch {
    $exitCode = 1
    Write-Host ""
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "详细日志：$logFile" -ForegroundColor Red
}
finally {
    try { Stop-Transcript | Out-Null } catch { }
    Write-Host ""
    if (-not $env:ARDOR_DEV_NONINTERACTIVE) { Read-Host "按回车关闭窗口" }
}
exit $exitCode
