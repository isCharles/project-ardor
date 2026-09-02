$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$projectRoot = $PSScriptRoot

function Test-DockerEngine {
    & docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Move-StaleDockerSocketDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$SocketName
    )

    $socketPath = Join-Path $Directory $SocketName
    if (-not (Test-Path -LiteralPath $socketPath)) {
        return $false
    }
    $socket = Get-Item -LiteralPath $socketPath -Force
    if (($socket.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
        return $false
    }

    $allowedParents = @(
        [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA "Docker")),
        [IO.Path]::GetFullPath($env:LOCALAPPDATA)
    )
    $resolvedDirectory = [IO.Path]::GetFullPath($Directory)
    if (-not ($allowedParents | Where-Object { $resolvedDirectory.StartsWith($_ + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) })) {
        throw "拒绝处理非 Docker 运行时目录：$resolvedDirectory"
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $backup = "$resolvedDirectory.socket-backup-$stamp"
    if (Test-Path -LiteralPath $backup) {
        throw "运行时备份目录已经存在：$backup"
    }
    Move-Item -LiteralPath $resolvedDirectory -Destination $backup
    New-Item -ItemType Directory -Path $resolvedDirectory | Out-Null
    Write-Host "已隔离陈旧 Docker 套接字（保留于 $backup）"
    return $true
}

function Start-DockerEngine {
    if (Test-DockerEngine) {
        return
    }

    $desktop = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path -LiteralPath $desktop -PathType Leaf)) {
        throw "找不到 Docker Desktop"
    }

    $dockerProcesses = Get-Process -Name "Docker Desktop", "com.docker.backend" -ErrorAction SilentlyContinue
    if (-not $dockerProcesses) {
        Move-StaleDockerSocketDirectory -Directory (Join-Path $env:LOCALAPPDATA "Docker\run") -SocketName "dockerInference" | Out-Null
        Move-StaleDockerSocketDirectory -Directory (Join-Path $env:LOCALAPPDATA "docker-secrets-engine") -SocketName "engine.sock" | Out-Null
        Start-Process -FilePath $desktop -WindowStyle Hidden
    }

    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Seconds 3
        if (Test-DockerEngine) {
            return
        }
    } while ((Get-Date) -lt $deadline)
    throw "Docker Engine 未能在 45 秒内启动，请查看 Docker Desktop 提示"
}

Push-Location (Join-Path $projectRoot "backend")
try {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "后端构建失败"
    }
}
finally {
    Pop-Location
}

Push-Location $projectRoot
try {
    Start-DockerEngine
    & docker compose up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose 启动失败"
    }
}
finally {
    Pop-Location
}

Write-Host "Project Ardor 已启动：http://localhost:3000"
