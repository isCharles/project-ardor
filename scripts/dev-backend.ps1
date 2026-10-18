# Runs the backend on the host (hot reload + debugger) against the Dockerized
# PostgreSQL and Redis. Secrets are read from the repository .env and never printed.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "缺少 .env；先按 docs/SETUP.md 复制 .env.example 并填写密码与加密密钥"
}

$allowed = @(
    "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_PORT",
    "BACKEND_PORT", "ARDOR_ENCRYPTION_KEY", "ARDOR_ADMIN_EMAILS",
    "ARDOR_HTTP_PROXY_HOST", "ARDOR_HTTP_PROXY_PORT", "ARDOR_ALLOW_PRIVATE_API_BASE_URLS"
)
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        $name = $Matches[1]
        $value = $Matches[2].Trim('"').Trim("'")
        if ($allowed -contains $name) { Set-Item -Path "Env:$name" -Value $value }
    }
}

$dbPort = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { "5433" }
$dbName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "ardor" }
$env:DATABASE_URL = "jdbc:postgresql://localhost:$dbPort/$dbName"
$env:DATABASE_USER = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "ardor" }
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"

if (-not $env:ARDOR_ENCRYPTION_KEY) {
    throw ".env 缺少 ARDOR_ENCRYPTION_KEY，后端无法启动"
}

Push-Location (Join-Path $root "backend")
try {
    Write-Host "启动后端 http://localhost:8080 （调试端口 5005，VS Code 可 Attach）"
    mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
}
finally {
    Pop-Location
}
