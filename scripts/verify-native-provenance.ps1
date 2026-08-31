[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$ExpectedJarSha256
)

$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot

function Get-StreamSha256([IO.Stream]$Stream) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Read-Properties([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) { $result[$parts[0]] = $parts[1] }
    }
    return $result
}

$sourceSets = @(
    [ordered]@{
        Version = '0.3.18-neoforge-26.2'
        Bridge = Join-Path $Project 'native\nvidia_dlss_bridge.cpp'
        BridgeSha256 = '268381373b40822996272ed4bcaa7af234464937e4c3c9ddabed6cbba161ffaa'
        Shader = Join-Path $Project 'native\shaders\motion_vectors.comp'
        ShaderSha256 = '0ff5614444f5adf20ac17d863a2b3e3563c709d8bb7e0c8cf91318b735b14b68'
        Stamp = Join-Path $Project 'src\main\resources\assets\nvidia_dlss\native\win-x64\native-source-v1.properties'
    }
    [ordered]@{
        Version = '0.3.16-neoforge-26.2'
        Bridge = Join-Path $Project 'provenance\0.3.16\native\nvidia_dlss_bridge.cpp'
        BridgeSha256 = 'b7a47af8d0c5fe689dbd5aea39dcc43274918a0de1c570bf57d1e455cc14ae96'
        Shader = Join-Path $Project 'provenance\0.3.16\native\shaders\motion_vectors.comp'
        ShaderSha256 = '3aba5ea79bc3f6bfde8dd38da1e75050ea6f1527fa624830de1153ead74c458f'
        Stamp = Join-Path $Project 'provenance\0.3.16\native-source-v1.properties'
    }
)

foreach ($set in $sourceSets) {
    $bridgeActual = (Get-FileHash -LiteralPath $set.Bridge -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($bridgeActual -ne $set.BridgeSha256) {
        throw "Bridge source SHA-256 mismatch for $($set.Version): expected $($set.BridgeSha256), got $bridgeActual"
    }
    $shaderActual = (Get-FileHash -LiteralPath $set.Shader -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($shaderActual -ne $set.ShaderSha256) {
        throw "Shader source SHA-256 mismatch for $($set.Version): expected $($set.ShaderSha256), got $shaderActual"
    }
    $stamp = Read-Properties $set.Stamp
    if ($stamp.bridgeSourceSha256 -ne $set.BridgeSha256) {
        throw "Source stamp does not match the bridge for $($set.Version)."
    }
    if ($stamp.motionShaderSourceSha256 -ne $set.ShaderSha256) {
        throw "Source stamp does not match the shader for $($set.Version)."
    }
    Write-Host "Source provenance OK: $($set.Version)"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    Write-Host "Source-only verification complete for $($sourceSets.Count) published versions."
    exit 0
}

$vendorBinary = [ordered]@{
    'assets/nvidia_dlss/native/win-x64/NvLowLatencyVk.dll' = '2a77dc3e1c724b7eea5755be0ae7423752e79a2459fae72181a9f00e3507e5d6'
    'assets/nvidia_dlss/native/win-x64/nvngx_dlss.dll' = 'be6e434a94ca32499515eb62ca0e6c274526055d568d0426e4c652dcdfb6ee6e'
    'assets/nvidia_dlss/native/win-x64/sl.common.dll' = 'c57930ef5a8a3fe9be85efdf71a61d8107c1148e8a6aed456464547128f7f4ae'
    'assets/nvidia_dlss/native/win-x64/sl.dlss.dll' = 'a997022d2b93601e0eefc3ddb3067c36df386dd3163ae71e11095191fb14f8e4'
    'assets/nvidia_dlss/native/win-x64/sl.interposer.dll' = '2a79db6857ae8c75bbd871a9489c48bc6a39f7fcc88b9b02afd53d0376cbec66'
    'assets/nvidia_dlss/native/win-x64/sl.nis.dll' = '82ac2d3936ad24856b2219ce43016e7428539bf9dfc217a690a3cc8b05adaf63'
}

$knownArtifacts = @{
    '32fa02e499476ca25066efaf2ef1485c743c2938ba8eff0c7572b823618cc6f1' = [ordered]@{
        Version = '0.3.18-neoforge-26.2'
        ProjectBinary = [ordered]@{
            'assets/nvidia_dlss/native/win-x64/nvidia_dlss_bridge.dll' = 'f1f1f4ec4b7ecc7c85d0f824b3552468e92600ceca76141c8441965a54a407c6'
            'assets/nvidia_dlss/native/win-x64/motion_vectors.comp.spv' = '940687072284efc94f9e3fd4e55c38f7f7593e6ed5e8ebc5bbffb54a73e47551'
            'assets/nvidia_dlss/native/win-x64/motion_vectors.debug.comp.spv' = '7807fea40ecf2f6e9c74f651f340715dd611365d6775881cb62f16c328820b0a'
        }
    }
    '70e53af0f3c3f505122438d792ef0568808409f05eee8cd26288ab2c2db1b5a4' = [ordered]@{
        Version = '0.3.16-neoforge-26.2'
        ProjectBinary = [ordered]@{
            'assets/nvidia_dlss/native/win-x64/nvidia_dlss_bridge.dll' = 'c251c0702df49bbfc2caffad0e2be74d525e62d07ceab9d56043d3a3a41dd327'
            'assets/nvidia_dlss/native/win-x64/motion_vectors.comp.spv' = 'fc89b518ffbfd39b1a2d8d37957534663b10a922a25629d116d72fe25810ea19'
            'assets/nvidia_dlss/native/win-x64/motion_vectors.debug.comp.spv' = 'a505c8cedd93575a852c5d277bf873447f962bc43016146404562b42f7c053b6'
        }
    }
}

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$jarHash = (Get-FileHash -LiteralPath $resolvedJar -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not [string]::IsNullOrWhiteSpace($ExpectedJarSha256) -and $jarHash -ne $ExpectedJarSha256.ToLowerInvariant()) {
    throw "JAR SHA-256 mismatch: expected $ExpectedJarSha256, got $jarHash"
}
if (-not $knownArtifacts.ContainsKey($jarHash)) {
    throw "No checked-in native provenance manifest exists for JAR SHA-256 $jarHash"
}

$artifact = $knownArtifacts[$jarHash]
$expectedBinary = [ordered]@{}
foreach ($item in $vendorBinary.GetEnumerator()) { $expectedBinary[$item.Key] = $item.Value }
foreach ($item in $artifact.ProjectBinary.GetEnumerator()) { $expectedBinary[$item.Key] = $item.Value }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedJar)
try {
    $actualNativeBinaryEntries = @($archive.Entries | Where-Object {
        $_.FullName.StartsWith('assets/nvidia_dlss/native/win-x64/') -and $_.Name -match '\.(dll|spv)$'
    })
    if ($actualNativeBinaryEntries.Count -ne $expectedBinary.Count) {
        throw "Unexpected native binary count: expected $($expectedBinary.Count), got $($actualNativeBinaryEntries.Count)"
    }
    foreach ($item in $expectedBinary.GetEnumerator()) {
        $entry = $archive.GetEntry($item.Key)
        if ($null -eq $entry) { throw "JAR is missing $($item.Key)" }
        $stream = $entry.Open()
        try { $actual = Get-StreamSha256 $stream } finally { $stream.Dispose() }
        if ($actual -ne $item.Value) {
            throw "JAR entry SHA-256 mismatch for $($item.Key): expected $($item.Value), got $actual"
        }
    }
} finally {
    $archive.Dispose()
}

Write-Host "JAR provenance OK: $($artifact.Version)"
Write-Host "JAR path: $resolvedJar"
Write-Host "JAR SHA-256: $jarHash"
Write-Host "Verified native binary entries: $($expectedBinary.Count)"
