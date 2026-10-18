# Compose owns .env parsing and shell overrides. Never print the resolved model.
function Get-ArdorComposeConfiguration {
    param([string]$ProjectRoot, [switch]$Development)
    $file = if ($Development) { 'docker-compose.dev.yml' } else { 'docker-compose.yml' }
    $composeArguments = @('compose', '--project-directory', $ProjectRoot, '-f', (Join-Path $ProjectRoot $file))
    if ($Development) { $composeArguments += @('-p', 'project-ardor-dev') }
    $previousPreference = $ErrorActionPreference
    try {
        # PowerShell 5.1 can promote native stderr to terminating errors.
        $ErrorActionPreference = 'Continue'
        $json = & docker @composeArguments config --format json 2>$null
        $result = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    if ($result -ne 0) { throw '无法解析 Compose 配置，请检查 Docker CLI 和 .env；配置内容不会写入日志。' }
    try { return ($json -join "`n" | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw 'Compose 返回了无法识别的配置格式。' }
}

function Test-ArdorDockerEngine {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker info --format '{{.ServerVersion}}' *> $null
        return $LASTEXITCODE -eq 0
    }
    catch { return $false }
    finally { $ErrorActionPreference = $previousPreference }
}

function Assert-ArdorDockerEngine {
    param([string]$ProjectRoot)
    if (-not (Test-ArdorDockerEngine)) {
        & (Join-Path $ProjectRoot 'scripts\Start-DockerDesktopSafely.ps1')
        if ($LASTEXITCODE -ne 0 -or -not (Test-ArdorDockerEngine)) {
            throw 'Docker Engine 未就绪；已停止，请查看 docs/DOCKER_DESKTOP_SOCKET_RECOVERY.md。'
        }
    }
}
