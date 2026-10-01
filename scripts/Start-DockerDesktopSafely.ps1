param(
    [ValidateRange(15, 300)][int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$localAppData = [IO.Path]::GetFullPath($env:LOCALAPPDATA)
$runtimeDirectories = @(
    @{ Path = (Join-Path $localAppData "Docker\run"); Sockets = @("dockerInference", "dockerEthernetVfkit", "userAnalyticsOtlpHttp.sock", "sailor-ingest.sock") },
    @{ Path = (Join-Path $localAppData "docker-secrets-engine"); Sockets = @("engine.sock") }
)
$logDirectory = Join-Path $localAppData "ProjectArdor"
$logPath = Join-Path $logDirectory "docker-recovery.log"

function Write-RecoveryEvent {
    param([Parameter(Mandatory = $true)][string]$Message)
    $entry = "$(Get-Date -Format o) $Message"
    Write-Host $entry
    try {
        if (-not (Test-Path -LiteralPath $logDirectory -PathType Container)) {
            New-Item -ItemType Directory -Path $logDirectory -ErrorAction Stop | Out-Null
        }
        Add-Content -LiteralPath $logPath -Value $entry -Encoding UTF8 -ErrorAction Stop
    }
    catch {
        Write-Warning "无法写入 Docker 恢复日志：$($_.Exception.Message)"
    }
}

function Test-DockerEngine {
    & docker info --format '{{.ServerVersion}}' *> $null
    return $LASTEXITCODE -eq 0
}

function Get-DockerDesktopProcesses {
    return @(Get-Process -Name "Docker Desktop", "com.docker.backend" -ErrorAction SilentlyContinue)
}

function Move-StaleRuntimeDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string[]]$SocketNames
    )

    $exactPaths = @($runtimeDirectories | ForEach-Object { [IO.Path]::GetFullPath($_.Path) })
    $fullPath = [IO.Path]::GetFullPath($Directory)
    if (-not ($exactPaths | Where-Object { [string]::Equals($_, $fullPath, [StringComparison]::OrdinalIgnoreCase) })) {
        throw "拒绝处理非预期的 Docker 运行时目录：$fullPath"
    }

    $directoryInfo = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $directoryInfo) { return $false }
    if (-not $directoryInfo.PSIsContainer -or ($directoryInfo.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "拒绝处理非普通目录：$fullPath"
    }

    # Test-Path can falsely report false for an inaccessible AF_UNIX endpoint.
    # Enumerate the parent and inspect metadata without opening the socket.
    $children = @(Get-ChildItem -LiteralPath $fullPath -Force -ErrorAction Stop)
    if ($children.Count -eq 0) { return $false }
    foreach ($child in $children) {
        if (($SocketNames -cnotcontains $child.Name) -or $child.PSIsContainer -or
            -not ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $child.LinkType) {
            throw "目录中有未识别的内容，未自动处理：$($child.FullName)"
        }
    }

    if ((Get-DockerDesktopProcesses).Count -gt 0) {
        throw "Docker Desktop 进程仍在运行，拒绝移动运行时目录。"
    }
    if (Test-DockerEngine) {
        throw "Docker Engine 已恢复，拒绝移动运行时目录。"
    }

    $suffix = (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $backup = "$fullPath.socket-backup-$suffix"
    if (Test-Path -LiteralPath $backup) { throw "备份目录已存在：$backup" }
    Move-Item -LiteralPath $fullPath -Destination $backup -ErrorAction Stop
    Write-RecoveryEvent "已保留临时 socket 目录：$fullPath -> $backup"
    return $true
}

function Wait-DockerEngine {
    param([int]$Seconds)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        if (Test-DockerEngine) { return $true }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return (Test-DockerEngine)
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "找不到 Docker CLI。"
    }
    if (Test-DockerEngine) {
        Write-RecoveryEvent "Docker Engine 已就绪；无需恢复。"
        exit 0
    }

    $desktop = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path -LiteralPath $desktop -PathType Leaf)) {
        throw "找不到 Docker Desktop：$desktop"
    }

    if ((Get-DockerDesktopProcesses).Count -gt 0) {
        Write-RecoveryEvent "Docker Desktop 正在运行，先等待 Engine 启动。"
        if (Wait-DockerEngine -Seconds $TimeoutSeconds) { exit 0 }
        throw "Docker Desktop 进程仍在运行但 Engine 未响应；未强制终止进程或移动文件。"
    }

    # Recover BOTH locations before launch. Moving only one and then launching
    # recreates it, causing the other failure to send us back to the first.
    foreach ($runtime in $runtimeDirectories) {
        Move-StaleRuntimeDirectory -Directory $runtime.Path -SocketNames $runtime.Sockets | Out-Null
    }

    Write-RecoveryEvent "启动 Docker Desktop；等待 Engine 最长 $TimeoutSeconds 秒。"
    Start-Process -FilePath $desktop -WindowStyle Hidden
    if (-not (Wait-DockerEngine -Seconds $TimeoutSeconds)) {
        throw "Docker Engine 仍未响应；已停止自动尝试，请检查后端日志。"
    }
    Write-RecoveryEvent "Docker Engine 已就绪。"
    exit 0
}
catch {
    Write-RecoveryEvent "恢复中止：$($_.Exception.Message)"
    exit 1
}
