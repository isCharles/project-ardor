param(
    [Parameter(Mandatory = $true)][string]$DistDirectory,
    [Parameter(Mandatory = $true)][string]$ExpectedThumbprint
)

$ErrorActionPreference = 'Stop'
$expected = ($ExpectedThumbprint -replace '\s', '').ToUpperInvariant()
if ($expected -notmatch '^[0-9A-F]{40}$') {
    throw 'Expected signer thumbprint must be a 40-character SHA-1 certificate thumbprint.'
}

$dist = (Resolve-Path -LiteralPath $DistDirectory).Path
$appPath = Join-Path $dist 'win-unpacked\Project Ardor.exe'
$metadataPath = Join-Path $dist 'latest.yml'
foreach ($path in @($appPath, $metadataPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required release artifact is missing: $path"
    }
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8
$pathMatch = [regex]::Match($metadata, '(?m)^path: (Project-Ardor-Setup-[0-9A-Za-z.-]+-x64\.exe)\r?$')
if (-not $pathMatch.Success) {
    throw 'Update metadata does not identify an x64 installer.'
}
$installerPath = Join-Path $dist $pathMatch.Groups[1].Value
$blockmapPath = "$installerPath.blockmap"
foreach ($path in @($installerPath, $blockmapPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required release artifact is missing: $path"
    }
}

foreach ($path in @($appPath, $installerPath)) {
    $signature = Get-AuthenticodeSignature -LiteralPath $path
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate) {
        throw "Authenticode signature is not valid for $path (status: $($signature.Status))."
    }
    if ($signature.SignerCertificate.Thumbprint.ToUpperInvariant() -ne $expected) {
        throw "Unexpected Authenticode signer for $path."
    }
    if ($null -eq $signature.TimeStamperCertificate) {
        throw "Authenticode timestamp is missing for $path."
    }
}

$quotedName = [regex]::Escape([System.IO.Path]::GetFileName($installerPath))
if ($metadata -notmatch ("(?m)^path: {0}\r?$" -f $quotedName)) {
    throw 'Update metadata does not identify the signed installer.'
}
$hashMatch = [regex]::Match($metadata, '(?m)^sha512: ([A-Za-z0-9+/=]+)\r?$')
if (-not $hashMatch.Success) {
    throw 'Update metadata does not contain a top-level SHA-512 digest.'
}

$stream = [System.IO.File]::OpenRead($installerPath)
try {
    $sha512 = [System.Security.Cryptography.SHA512]::Create()
    try {
        $actualHash = [Convert]::ToBase64String($sha512.ComputeHash($stream))
    } finally {
        $sha512.Dispose()
    }
} finally {
    $stream.Dispose()
}
if ($actualHash -ne $hashMatch.Groups[1].Value) {
    throw 'Update metadata SHA-512 does not match the signed installer.'
}

Write-Output "Verified installer and application signatures, timestamp, signer, blockmap, and update digest: $([System.IO.Path]::GetFileName($installerPath))"
