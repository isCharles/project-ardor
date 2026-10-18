# Regression tests use synthetic configuration and mocked commands only.
param([string]$Scenario = '', [string]$FixtureRoot = '', [switch]$ValidateCompose)
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
function Assert-True($Value, [string]$Message) { if (-not $Value) { throw $Message } }
if (-not $Scenario) {
    $FixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('ardor-launcher-tests-' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path (Join-Path $FixtureRoot 'scripts'))
    [void](New-Item -ItemType Directory -Path (Join-Path $FixtureRoot 'backend'))
    $utf8 = [Text.UTF8Encoding]::new($true)
    foreach ($name in @('dev-backend.ps1','dev-up.ps1','start-deploy.ps1','LocalLauncher.Common.ps1')) {
        $source = Join-Path $repo ('scripts\' + $name)
        $bytes = [IO.File]::ReadAllBytes($source)
        Assert-True ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) "Missing UTF-8 BOM for PS 5.1: $name"
        Copy-Item -LiteralPath $source -Destination (Join-Path $FixtureRoot ('scripts\' + $name))
    }
    $repair = "Add-Content -LiteralPath `$env:ARDOR_TEST_CALLS -Value 'repair' -Encoding UTF8`nif (`$env:ARDOR_TEST_REPAIR_FAIL) { `$global:LASTEXITCODE=1 } else { `$env:ARDOR_TEST_ENGINE_READY='1'; `$global:LASTEXITCODE=0 }"
    [IO.File]::WriteAllText((Join-Path $FixtureRoot 'scripts\Start-DockerDesktopSafely.ps1'), $repair, $utf8)
    [IO.File]::WriteAllText((Join-Path $FixtureRoot 'start.ps1'), "Add-Content -LiteralPath `$env:ARDOR_TEST_CALLS -Value 'deploy' -Encoding UTF8`n`$global:LASTEXITCODE=0", $utf8)
    $shell = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path -LiteralPath $shell)) { $shell = Join-Path $PSHOME 'pwsh.exe' }
    $cases = @('backend-custom','backend-fail','dev-healthy','dev-unhealthy','dev-repair-ok','dev-repair-fail','preflight-free','preflight-docker','preflight-native','deploy-docker','deploy-stop-fail','compose-fail','invalid-json')
    foreach ($case in $cases) {
        & $shell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Scenario $case -FixtureRoot $FixtureRoot
        if ($LASTEXITCODE -ne 0) { throw "Failed scenario: $case (fixtures: $FixtureRoot)" }
    }
    if ($ValidateCompose) {
        $envFile = Join-Path $FixtureRoot 'synthetic.env'
        [IO.File]::WriteAllText($envFile, "POSTGRES_PASSWORD=synthetic-password`nARDOR_ENCRYPTION_KEY=synthetic-key`nREDIS_PORT=56389`nARDOR_DEV_POSTGRES_PORT=55439`nBACKEND_PORT=18081`n", $utf8)
        $previous = @{}
        foreach ($name in @('POSTGRES_PASSWORD','ARDOR_ENCRYPTION_KEY','REDIS_PORT','ARDOR_DEV_POSTGRES_PORT','BACKEND_PORT','COMPOSE_PROJECT_NAME','COMPOSE_FILE','COMPOSE_ENV_FILES')) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        try {
            $json = & docker compose --project-directory $FixtureRoot --env-file $envFile -f (Join-Path $repo 'docker-compose.dev.yml') config --format json
            Assert-True ($LASTEXITCODE -eq 0) 'Real Compose config parsing failed'
            $model = $json -join "`n" | ConvertFrom-Json
            Assert-True ($model.name -eq 'project-ardor-dev') 'Dev project is not isolated'
            Assert-True ($model.'x-ardor-native-environment'.REDIS_PORT -eq '56389') 'Native Redis interpolation mismatch'
            Assert-True ($model.services.redis.ports[0].published -eq '56389') 'Published Redis interpolation mismatch'
            Assert-True ($model.'x-ardor-native-environment'.DATABASE_URL -eq 'jdbc:postgresql://127.0.0.1:55439/ardor_dev') 'Dev database mismatch'
            Assert-True ($model.services.postgres.volumes[0].source -eq 'dev-postgres-data') 'Deployment data volume reused'
            Write-Host 'PASS real Compose interpolation and isolated volumes'
        }
        finally { foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') } }
    }
    Write-Host "PASS all $($cases.Count) scenarios; synthetic fixture logs: $FixtureRoot"
    exit 0
}
$env:ARDOR_TEST_CALLS = Join-Path $FixtureRoot 'calls.log'
$env:ARDOR_DEV_NONINTERACTIVE = '1'
$env:ARDOR_TEST_ENGINE_READY = ''
$env:ARDOR_TEST_REPAIR_FAIL = if ($Scenario -eq 'dev-repair-fail') { '1' } else { '' }
[IO.File]::WriteAllText($env:ARDOR_TEST_CALLS, '', [Text.UTF8Encoding]::new($false))
$script:stopped = $false
$native = @{DATABASE_URL='jdbc:postgresql://127.0.0.1:55439/ardor_dev';DATABASE_USER='fixture';DATABASE_PASSWORD='synthetic-password';ARDOR_ENCRYPTION_KEY='synthetic-key';REDIS_HOST='127.0.0.1';REDIS_PORT='56389';BACKEND_PORT='18081'}
$model = @{services=@{backend=@{ports=@(@{published='18081'})};frontend=@{ports=@(@{published='13001'})}};'x-ardor-native-environment'=$native}
function docker {
    Add-Content -LiteralPath $env:ARDOR_TEST_CALLS -Value ('docker ' + ($args -join ' ')) -Encoding UTF8
    $global:LASTEXITCODE = 0
    if ($args -contains 'config') {
        if ($Scenario -eq 'compose-fail') { $global:LASTEXITCODE=1; return }
        if ($Scenario -eq 'invalid-json') { return '{broken' }
        return ($model | ConvertTo-Json -Depth 8 -Compress)
    }
    if ($args[0] -eq 'info') {
        if ($Scenario -like 'dev-repair-*' -and -not $env:ARDOR_TEST_ENGINE_READY) { $global:LASTEXITCODE=1; return }
        return 'fixture-engine'
    }
    if ($args -contains 'stop') { if ($Scenario -eq 'deploy-stop-fail') { $global:LASTEXITCODE=1 } else { $script:stopped=$true }; return }
    if ($args -contains 'ps') { return ('fixture-' + $args[-1]) }
    if ($args[0] -eq 'inspect') { if ($Scenario -eq 'dev-unhealthy') { return 'unhealthy' }; return 'healthy' }
}
function Get-NetTCPConnection {
    param($State,$LocalPort,$ErrorAction)
    Add-Content -LiteralPath $env:ARDOR_TEST_CALLS -Value "port $LocalPort" -Encoding UTF8
    if (-not $script:stopped -and $LocalPort -eq 18081 -and $Scenario -in @('preflight-docker','preflight-native','deploy-docker','deploy-stop-fail')) { [pscustomobject]@{OwningProcess=43210} }
}
function Get-Process { param($Id,$ErrorAction); [pscustomobject]@{ProcessName=if($Scenario -eq 'preflight-native'){'java'}else{'com.docker.backend'}} }
function mvn {
    Assert-True (($args -join ' ') -match 'address=localhost:5005' -and ($args -join ' ') -notmatch 'address=\*') 'JDWP must bind loopback'
    Assert-True ($env:REDIS_PORT -eq '56389') 'Backend ignored custom Redis port'
    Assert-True ($env:DATABASE_URL -eq $native.DATABASE_URL) 'Backend reused deployment database'
    Assert-True ($env:ARDOR_RESUME_STORAGE -eq (Join-Path $env:LOCALAPPDATA 'ProjectArdor\development\resumes')) 'Private storage must be outside Git'
    Add-Content -LiteralPath $env:ARDOR_TEST_CALLS -Value 'mvn' -Encoding UTF8
    $global:LASTEXITCODE = if ($Scenario -eq 'backend-fail') { 17 } else { 0 }
}
$failed = $false
try {
    if ($Scenario -like 'backend-*') { & (Join-Path $FixtureRoot 'scripts\dev-backend.ps1') }
    elseif ($Scenario -like 'dev-*') { & (Join-Path $FixtureRoot 'scripts\dev-up.ps1') -HealthTimeoutSeconds 1 }
    else { & (Join-Path $FixtureRoot 'scripts\start-deploy.ps1') -PreflightOnly:($Scenario -notlike 'deploy-*') }
    $failed = $LASTEXITCODE -ne 0
}
catch { $failed = $true }
$calls = Get-Content -LiteralPath $env:ARDOR_TEST_CALLS -Encoding UTF8
$expected = $Scenario -in @('backend-fail','dev-unhealthy','dev-repair-fail','preflight-native','deploy-stop-fail','compose-fail','invalid-json')
Assert-True ($failed -eq $expected) "Unexpected success/failure: $Scenario"
if ($Scenario -like 'backend-*') { Assert-True ($calls -contains 'mvn') 'Backend never reached mock Maven' }
if ($Scenario -like 'preflight-*') {
    Assert-True (-not ($calls -match ' stop |repair|^deploy$| up ')) 'Check-only mode mutated services'
    Assert-True ($calls -contains 'port 18081' -and $calls -contains 'port 13001') 'Custom ports not checked'
    Assert-True (-not ($calls -contains 'port 8080' -or $calls -contains 'port 3000')) 'Unused default ports checked'
}
if ($Scenario -like 'dev-*') {
    Assert-True (-not ($calls -match ' stop ')) 'Dev setup stopped deployment'
    Assert-True (-not ($calls -match 'docker-compose.yml')) 'Dev setup used deployment Compose'
}
if ($Scenario -like 'dev-repair-*') {
    Assert-True (@($calls | Where-Object {$_ -eq 'repair'}).Count -eq 1) 'Safe repair not invoked exactly once'
    if ($Scenario -eq 'dev-repair-fail') { Assert-True (-not ($calls -match ' up ')) 'Setup continued after failed repair' }
}
if ($Scenario -eq 'deploy-docker') { Assert-True ($calls -contains 'deploy') 'Deploy did not run after freeing ports' }
if ($Scenario -eq 'deploy-stop-fail') { Assert-True (-not ($calls -contains 'deploy')) 'Deploy ran after failed stop' }
Assert-True (-not (($calls -join "`n") -match 'synthetic-password|synthetic-key')) 'Secrets leaked into logs'
Write-Host "PASS $Scenario"
