[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$StreamlineBin
)

$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$SourceDirectory = (Resolve-Path -LiteralPath $StreamlineBin).Path
$TargetDirectory = Join-Path $Project 'src\main\resources\assets\nvidia_dlss\native\win-x64'

# Exact signed NVIDIA production files used by BlockFrame DLSS 0.3.18.
$ExpectedSha256 = [ordered]@{
    'NvLowLatencyVk.dll' = '2a77dc3e1c724b7eea5755be0ae7423752e79a2459fae72181a9f00e3507e5d6'
    'nvngx_dlss.dll' = 'be6e434a94ca32499515eb62ca0e6c274526055d568d0426e4c652dcdfb6ee6e'
    'sl.common.dll' = 'c57930ef5a8a3fe9be85efdf71a61d8107c1148e8a6aed456464547128f7f4ae'
    'sl.dlss.dll' = 'a997022d2b93601e0eefc3ddb3067c36df386dd3163ae71e11095191fb14f8e4'
    'sl.interposer.dll' = '2a79db6857ae8c75bbd871a9489c48bc6a39f7fcc88b9b02afd53d0376cbec66'
    'sl.nis.dll' = '82ac2d3936ad24856b2219ce43016e7428539bf9dfc217a690a3cc8b05adaf63'
}

if ((Split-Path -Leaf $SourceDirectory) -ieq 'development') {
    throw 'Use the signed production bin\x64 directory, not the development directory.'
}

$missing = @($ExpectedSha256.Keys | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $SourceDirectory $_) -PathType Leaf)
})
if ($missing.Count -gt 0) {
    throw "The selected directory is missing: $($missing -join ', ')"
}

foreach ($file in $ExpectedSha256.Keys) {
    $source = Join-Path $SourceDirectory $file
    $signature = Get-AuthenticodeSignature -LiteralPath $source
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'NVIDIA Corporation') {
        throw "NVIDIA Authenticode validation failed for ${file}: $($signature.Status) / $($signature.SignerCertificate.Subject)"
    }
    $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256[$file]) {
        throw "SHA-256 mismatch for ${file}: expected $($ExpectedSha256[$file]), got $actual"
    }
}

New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
foreach ($file in $ExpectedSha256.Keys) {
    Copy-Item -LiteralPath (Join-Path $SourceDirectory $file) -Destination (Join-Path $TargetDirectory $file) -Force
}

Write-Host "Staged $($ExpectedSha256.Count) signed, hash-pinned NVIDIA production binaries in $TargetDirectory"

