$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$projectRoot = $PSScriptRoot

# Check Docker before building. Docker Desktop can crash on stale Windows
# AF_UNIX endpoints even when the Ardor source and images are intact.
& (Join-Path $projectRoot "scripts\Start-DockerDesktopSafely.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop 未能启动；请查看恢复记录，勿重置数据卷。"
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
    & docker compose up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose 启动失败"
    }
}
finally {
    Pop-Location
}

Write-Host "Project Ardor 已启动：http://localhost:3000"
