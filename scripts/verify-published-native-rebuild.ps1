[CmdletBinding()]
param(
    [string]$WorkDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-Sha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Expected,
        [long]$ExpectedLength = -1
    )

    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($ExpectedLength -ge 0 -and $item.Length -ne $ExpectedLength) {
        throw "Length mismatch for ${Path}: expected $ExpectedLength, got $($item.Length)"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for ${Path}: expected $Expected, got $actual"
    }
    Write-Host "SHA-256 OK: $($item.Name) = $actual"
}

function Copy-ZipEntry {
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)][string]$EntryName,
        [Parameter(Mandatory = $true)][string]$DestinationPath
    )

    $archive = [IO.Compression.ZipFile]::OpenRead(
        (Resolve-Path -LiteralPath $ArchivePath).Path
    )
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) {
            throw "Archive is missing $EntryName"
        }
        $parent = Split-Path -Parent $DestinationPath
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
        $input = $entry.Open()
        $output = [IO.File]::Create($DestinationPath)
        try {
            $input.CopyTo($output)
        } finally {
            $output.Dispose()
            $input.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

function Assert-ByteRange {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [Parameter(Mandatory = $true)][long]$Offset,
        [Parameter(Mandatory = $true)][long]$Length,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Offset -lt 0 -or $Length -lt 0 -or
        $Offset -gt $Bytes.LongLength -or
        $Length -gt ($Bytes.LongLength - $Offset)) {
        throw "Invalid $Label range: offset=$Offset length=$Length fileLength=$($Bytes.LongLength)"
    }
}

function Get-ByteArraySha256 {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-NormalizedBridgeImage {
    param([Parameter(Mandatory = $true)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $bytes = [IO.File]::ReadAllBytes($resolved)
    if ($bytes.LongLength -ne 558592) {
        throw "Unexpected bridge length for ${Path}: expected 558592, got $($bytes.LongLength)"
    }

    Assert-ByteRange -Bytes $bytes -Offset 0x3c -Length 4 -Label 'DOS e_lfanew'
    $peOffset = [long][BitConverter]::ToUInt32($bytes, 0x3c)
    Assert-ByteRange -Bytes $bytes -Offset $peOffset -Length 24 -Label 'PE/COFF header'
    if ($bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45 -or
        $bytes[$peOffset + 2] -ne 0 -or $bytes[$peOffset + 3] -ne 0) {
        throw "Missing PE signature in $Path"
    }

    $sectionCount = [int][BitConverter]::ToUInt16($bytes, [int]($peOffset + 6))
    $optionalHeaderSize = [int][BitConverter]::ToUInt16($bytes, [int]($peOffset + 20))
    if ($sectionCount -le 0 -or $optionalHeaderSize -le 0) {
        throw "Invalid PE section/optional-header counts in $Path"
    }
    $optionalHeaderOffset = $peOffset + 24
    Assert-ByteRange -Bytes $bytes -Offset $optionalHeaderOffset -Length $optionalHeaderSize -Label 'PE optional header'
    $magic = [BitConverter]::ToUInt16($bytes, [int]$optionalHeaderOffset)
    if ($magic -eq 0x20b) {
        $numberOfRvaAndSizesOffset = $optionalHeaderOffset + 108
        $dataDirectoryOffset = $optionalHeaderOffset + 112
    } elseif ($magic -eq 0x10b) {
        $numberOfRvaAndSizesOffset = $optionalHeaderOffset + 92
        $dataDirectoryOffset = $optionalHeaderOffset + 96
    } else {
        throw ('Unsupported PE optional-header magic 0x{0:x} in {1}' -f $magic, $Path)
    }

    Assert-ByteRange -Bytes $bytes -Offset $numberOfRvaAndSizesOffset -Length 4 -Label 'PE data-directory count'
    $directoryCount = [BitConverter]::ToUInt32($bytes, [int]$numberOfRvaAndSizesOffset)
    if ($directoryCount -le 6) {
        throw "PE debug data directory is absent in $Path"
    }
    $debugDataDirectoryOffset = $dataDirectoryOffset + (6 * 8)
    if (($debugDataDirectoryOffset + 8) -gt ($optionalHeaderOffset + $optionalHeaderSize)) {
        throw "PE debug data directory extends past the optional header in $Path"
    }
    $debugRva = [long][BitConverter]::ToUInt32($bytes, [int]$debugDataDirectoryOffset)
    $debugSize = [long][BitConverter]::ToUInt32($bytes, [int]($debugDataDirectoryOffset + 4))
    if ($debugRva -eq 0 -or $debugSize -eq 0 -or ($debugSize % 28) -ne 0) {
        throw "Invalid PE debug directory RVA/size in $Path"
    }

    $sectionTableOffset = $optionalHeaderOffset + $optionalHeaderSize
    Assert-ByteRange -Bytes $bytes -Offset $sectionTableOffset -Length (40L * $sectionCount) -Label 'PE section table'
    $debugDirectoryOffset = $null
    for ($index = 0; $index -lt $sectionCount; $index++) {
        $sectionOffset = $sectionTableOffset + (40L * $index)
        $virtualAddress = [long][BitConverter]::ToUInt32($bytes, [int]($sectionOffset + 12))
        $rawSize = [long][BitConverter]::ToUInt32($bytes, [int]($sectionOffset + 16))
        $rawPointer = [long][BitConverter]::ToUInt32($bytes, [int]($sectionOffset + 20))
        $delta = $debugRva - $virtualAddress
        if ($delta -ge 0 -and $delta -le $rawSize -and
            $debugSize -le ($rawSize - $delta)) {
            if ($null -ne $debugDirectoryOffset) {
                throw "PE debug directory maps to multiple sections in $Path"
            }
            $debugDirectoryOffset = $rawPointer + $delta
        }
    }
    if ($null -eq $debugDirectoryOffset) {
        throw "PE debug directory cannot be mapped to raw file data in $Path"
    }
    Assert-ByteRange -Bytes $bytes -Offset $debugDirectoryOffset -Length $debugSize -Label 'IMAGE_DEBUG_DIRECTORY array'

    $ranges = [Collections.Generic.List[object]]::new()
    $ranges.Add([pscustomobject]@{
        Kind = 'COFF TimeDateStamp'
        Offset = $peOffset + 8
        Length = 4
    })
    $debugEntryCount = [int]($debugSize / 28)
    $rsdsCount = 0
    for ($index = 0; $index -lt $debugEntryCount; $index++) {
        $entryOffset = $debugDirectoryOffset + (28L * $index)
        $ranges.Add([pscustomobject]@{
            Kind = "IMAGE_DEBUG_DIRECTORY[$index] TimeDateStamp"
            Offset = $entryOffset + 4
            Length = 4
        })

        $type = [BitConverter]::ToUInt32($bytes, [int]($entryOffset + 12))
        if ($type -eq 2) {
            $dataSize = [long][BitConverter]::ToUInt32($bytes, [int]($entryOffset + 16))
            $dataPointer = [long][BitConverter]::ToUInt32($bytes, [int]($entryOffset + 24))
            Assert-ByteRange -Bytes $bytes -Offset $dataPointer -Length $dataSize -Label 'CodeView debug data'
            if ($dataSize -lt 24 -or
                [Text.Encoding]::ASCII.GetString($bytes, [int]$dataPointer, 4) -ne 'RSDS') {
                throw "Unsupported CodeView debug record in $Path"
            }
            $ranges.Add([pscustomobject]@{
                Kind = 'RSDS GUID volatile prefix'
                Offset = $dataPointer + 4
                Length = 8
            })
            $rsdsCount++
        }
    }

    $normalizedByteCount = [long](($ranges | Measure-Object -Property Length -Sum).Sum)
    if ($debugEntryCount -ne 2 -or $rsdsCount -ne 1 -or $normalizedByteCount -ne 20) {
        throw "Unexpected volatile PE metadata layout in ${Path}: debugEntries=$debugEntryCount rsdsRecords=$rsdsCount normalizedBytes=$normalizedByteCount"
    }

    $normalized = [byte[]]$bytes.Clone()
    foreach ($range in $ranges) {
        Assert-ByteRange -Bytes $normalized -Offset $range.Offset -Length $range.Length -Label $range.Kind
        [Array]::Clear($normalized, [int]$range.Offset, [int]$range.Length)
    }

    return [pscustomobject]@{
        Path = $resolved
        RawBytes = $bytes
        NormalizedBytes = $normalized
        NormalizedSha256 = Get-ByteArraySha256 -Bytes $normalized
        RangeSignature = (($ranges | Sort-Object Offset | ForEach-Object {
            "$($_.Offset):$($_.Length):$($_.Kind)"
        }) -join '|')
        NormalizedByteCount = $normalizedByteCount
    }
}

function Assert-NormalizedBridgeMatch {
    param(
        [Parameter(Mandatory = $true)][string]$PublishedPath,
        [Parameter(Mandatory = $true)][string]$RebuiltPath,
        [Parameter(Mandatory = $true)][string]$ExpectedNormalizedSha256
    )

    $published = Get-NormalizedBridgeImage -Path $PublishedPath
    $rebuilt = Get-NormalizedBridgeImage -Path $RebuiltPath
    $expected = $ExpectedNormalizedSha256.ToLowerInvariant()
    if ($published.RangeSignature -ne $rebuilt.RangeSignature) {
        throw 'Published and rebuilt bridge metadata layouts differ.'
    }
    if ($published.NormalizedSha256 -ne $expected) {
        throw "Published bridge normalized SHA-256 mismatch: expected $expected, got $($published.NormalizedSha256)"
    }
    if ($rebuilt.NormalizedSha256 -ne $expected) {
        throw "Rebuilt bridge normalized SHA-256 mismatch: expected $expected, got $($rebuilt.NormalizedSha256)"
    }

    $rawDifferenceCount = 0
    for ($index = 0; $index -lt $published.NormalizedBytes.Length; $index++) {
        if ($published.NormalizedBytes[$index] -ne $rebuilt.NormalizedBytes[$index]) {
            throw "Executable bridge content mismatch after metadata normalization at file offset $index"
        }
        if ($published.RawBytes[$index] -ne $rebuilt.RawBytes[$index]) {
            $rawDifferenceCount++
        }
    }
    if ($rawDifferenceCount -gt $published.NormalizedByteCount) {
        throw "Bridge differs in $rawDifferenceCount raw bytes, beyond the $($published.NormalizedByteCount) explicitly normalized metadata bytes."
    }

    $rebuiltRawSha256 = (Get-FileHash -LiteralPath $RebuiltPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Normalized bridge SHA-256 OK: $expected"
    Write-Host "Normalized PE metadata bytes: $($published.NormalizedByteCount)"
    Write-Host "Raw differing byte count: $rawDifferenceCount"
    Write-Host "Raw rebuilt bridge SHA-256 (informational): $rebuiltRawSha256"
}

function Get-PinnedFile {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [long]$Length = -1
    )

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-Host "Downloading $Uri"
        & curl.exe '--fail' '--location' '--silent' '--show-error' $Uri '--output' $Path
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed with exit code ${LASTEXITCODE}: $Uri"
        }
    }
    Assert-Sha256 -Path $Path -Expected $Sha256 -ExpectedLength $Length
}

function Assert-OutputSet {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Expected
    )

    foreach ($entry in $Expected.GetEnumerator()) {
        Assert-Sha256 `
            -Path (Join-Path $Directory $entry.Key) `
            -Expected $entry.Value.Sha256 `
            -ExpectedLength $entry.Value.Length
    }
}

if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    $temporaryRoot = if (-not [string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        $env:RUNNER_TEMP
    } else {
        [IO.Path]::GetTempPath()
    }
    $WorkDirectory = Join-Path $temporaryRoot (
        'blockframe-native-repro-' + [Guid]::NewGuid().ToString('N')
    )
}
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null
$WorkDirectory = (Resolve-Path -LiteralPath $WorkDirectory).Path
Write-Host "Ephemeral verification directory: $WorkDirectory"

# The rejected project is intentionally hidden from Modrinth's public API.
# These immutable CDN URLs are the exact files uploaded to project Pvg0flfG.
$jar0318 = Join-Path $WorkDirectory 'published-0.3.18.jar'
$jar0316 = Join-Path $WorkDirectory 'published-0.3.16.jar'
Get-PinnedFile `
    -Uri 'https://cdn.modrinth.com/data/Pvg0flfG/versions/ymDbhdmv/blockframe-dlss-0.3.18-unapproved-bricks-farlod-history-candidate-neoforge-26.2.jar' `
    -Path $jar0318 `
    -Sha256 '32fa02e499476ca25066efaf2ef1485c743c2938ba8eff0c7572b823618cc6f1' `
    -Length 34003950
Get-PinnedFile `
    -Uri 'https://cdn.modrinth.com/data/Pvg0flfG/versions/d2TCEgJ0/blockframe-dlss-0.3.16-neoforge-26.2.jar' `
    -Path $jar0316 `
    -Sha256 '70e53af0f3c3f505122438d792ef0568808409f05eee8cd26288ab2c2db1b5a4' `
    -Length 33953776

& (Join-Path $PSScriptRoot 'verify-native-provenance.ps1') -JarPath $jar0318
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $PSScriptRoot 'verify-native-provenance.ps1') -JarPath $jar0316
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$publishedBridge = Join-Path $WorkDirectory 'published-0.3.18-nvidia_dlss_bridge.dll'
Copy-ZipEntry `
    -ArchivePath $jar0318 `
    -EntryName 'assets/nvidia_dlss/native/win-x64/nvidia_dlss_bridge.dll' `
    -DestinationPath $publishedBridge
Assert-Sha256 `
    -Path $publishedBridge `
    -Expected 'f1f1f4ec4b7ecc7c85d0f824b3552468e92600ceca76141c8441965a54a407c6' `
    -ExpectedLength 558592

# Official NVIDIA Streamline SDK 2.12.0 release asset. The staging script also
# validates every production DLL's NVIDIA Authenticode signature and SHA-256.
$streamlineArchive = Join-Path $WorkDirectory 'streamline-sdk-v2.12.0.zip'
$streamlineRoot = Join-Path $WorkDirectory 'streamline-sdk-v2.12.0'
Get-PinnedFile `
    -Uri 'https://github.com/NVIDIA-RTX/Streamline/releases/download/v2.12.0/streamline-sdk-v2.12.0.zip' `
    -Path $streamlineArchive `
    -Sha256 'f5c0a3d870707dddc3570fb4bcd3655cf48a8a68c3a9d342910cfa21b77dcf48' `
    -Length 231958617
if (-not (Test-Path -LiteralPath (Join-Path $streamlineRoot 'bin\x64\sl.common.dll'))) {
    Expand-Archive -LiteralPath $streamlineArchive -DestinationPath $streamlineRoot
}

# Pin every tool/header input that affects the current project-owned outputs.
$zigArchive = Join-Path $WorkDirectory 'zig-x86_64-windows-0.15.2.zip'
$zigRoot = Join-Path $WorkDirectory 'zig-x86_64-windows-0.15.2'
Get-PinnedFile `
    -Uri 'https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip' `
    -Path $zigArchive `
    -Sha256 '3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c' `
    -Length 92614574
if (-not (Test-Path -LiteralPath (Join-Path $zigRoot 'zig.exe'))) {
    Expand-Archive -LiteralPath $zigArchive -DestinationPath $WorkDirectory
}
$zig = Join-Path $zigRoot 'zig.exe'
$zigVersion = (& $zig version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $zigVersion -ne '0.15.2') {
    throw "Unexpected Zig version: $zigVersion"
}

$vulkanInstaller = Join-Path $WorkDirectory 'vulkan-sdk-1.4.350.0.exe'
$vulkanRoot = Join-Path $WorkDirectory 'vulkan-sdk-1.4.350.0'
Get-PinnedFile `
    -Uri 'https://sdk.lunarg.com/sdk/download/1.4.350.0/windows/vulkan_sdk.exe' `
    -Path $vulkanInstaller `
    -Sha256 '855b27ba05d2d8119c5114c5d4ff870ca38f2c632b11e1bb9923b9b7e6ecfe7b' `
    -Length 324012984
if (-not (Test-Path -LiteralPath (Join-Path $vulkanRoot 'Bin\glslc.exe'))) {
    $installer = Start-Process `
        -FilePath $vulkanInstaller `
        -ArgumentList @(
            '--root', $vulkanRoot,
            '--accept-licenses',
            '--default-answer',
            '--confirm-command',
            'install',
            'copy_only=1'
        ) `
        -Wait `
        -PassThru `
        -WindowStyle Hidden
    if ($installer.ExitCode -ne 0) {
        throw "Vulkan SDK installer exited with code $($installer.ExitCode)"
    }
}
$glslc = Join-Path $vulkanRoot 'Bin\glslc.exe'
Assert-Sha256 `
    -Path $glslc `
    -Expected '93898530ea4f2e809733ea8a839d09d4b947bd6e853c84670923ac4414f41eb3' `
    -ExpectedLength 5129144
$glslcVersion = (& $glslc '--version' | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $glslcVersion -notmatch '^shaderc v2026\.2 v2026\.2') {
    throw "Unexpected glslc version: $glslcVersion"
}
Write-Host $glslcVersion

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME is missing. The workflow pins Temurin 25.0.3+9.'
}
$jniInclude = Join-Path $env:JAVA_HOME 'include'
Assert-Sha256 `
    -Path (Join-Path $jniInclude 'jni.h') `
    -Expected '1e03bd83d1662ac00647f7a06c11d32333767ee537803ec1d153e89888414520' `
    -ExpectedLength 76300
Assert-Sha256 `
    -Path (Join-Path $jniInclude 'win32\jni_md.h') `
    -Expected 'c989b8c6f03c26c6f170836df8531379eb5cb3f5891f5d53f3bd17c1fdb28938' `
    -ExpectedLength 1484

# Build 0.3.18 in an isolated source copy so verification never modifies the
# checkout. This invokes the same checked-in scripts used for local releases.
$currentRoot = Join-Path $WorkDirectory 'rebuild-0.3.18'
New-Item -ItemType Directory -Path $currentRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $Project 'native') -Destination $currentRoot -Recurse -Force
New-Item -ItemType Directory -Path (Join-Path $currentRoot 'scripts') -Force | Out-Null
Copy-Item `
    -LiteralPath (Join-Path $Project 'scripts\stage-nvidia-runtime.ps1') `
    -Destination (Join-Path $currentRoot 'scripts\stage-nvidia-runtime.ps1') `
    -Force
$currentOutput = Join-Path $currentRoot 'src\main\resources\assets\nvidia_dlss\native\win-x64'
New-Item -ItemType Directory -Path $currentOutput -Force | Out-Null

& (Join-Path $currentRoot 'scripts\stage-nvidia-runtime.ps1') `
    -StreamlineBin (Join-Path $streamlineRoot 'bin\x64')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $currentRoot 'native\build-native.ps1') `
    -StreamlineInclude (Join-Path $streamlineRoot 'include') `
    -VulkanInclude (Join-Path $vulkanRoot 'Include') `
    -JniInclude $jniInclude `
    -ZigExecutable $zig `
    -GlslcExecutable $glslc
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$currentExpected = [ordered]@{
    'motion_vectors.comp.spv' = @{
        Length = 15188
        Sha256 = '940687072284efc94f9e3fd4e55c38f7f7593e6ed5e8ebc5bbffb54a73e47551'
    }
    'motion_vectors.debug.comp.spv' = @{
        Length = 19080
        Sha256 = '7807fea40ecf2f6e9c74f651f340715dd611365d6775881cb62f16c328820b0a'
    }
}
Assert-OutputSet -Directory $currentOutput -Expected $currentExpected
Assert-NormalizedBridgeMatch `
    -PublishedPath $publishedBridge `
    -RebuiltPath (Join-Path $currentOutput 'nvidia_dlss_bridge.dll') `
    -ExpectedNormalizedSha256 '34d11bab0460283842e4321a670ddd4da154c4d332236c15ff48aecfd3510d32'

# 0.3.16 used LWJGL's shaderc 3.4.1 binding. Its four Maven Central inputs
# are pinned below; CompileMotionShaders.java recreates both historical SPVs.
$lwjglRoot = Join-Path $WorkDirectory 'lwjgl-3.4.1'
$lwjglFiles = [ordered]@{
    'lwjgl-3.4.1.jar' = @{
        Uri = 'https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar'
        Length = 1157957
        Sha256 = '9b1c3a3a078c2377219ecb8a2662730b3dece07c10592cf1d12f957286037b69'
    }
    'lwjgl-3.4.1-natives-windows.jar' = @{
        Uri = 'https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-windows.jar'
        Length = 176521
        Sha256 = '21872776b9be0a93c746d6f28797c12ac47a75e0f0188e5a4fad0c20c9a6a07e'
    }
    'lwjgl-shaderc-3.4.1.jar' = @{
        Uri = 'https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-shaderc/3.4.1/lwjgl-shaderc-3.4.1.jar'
        Length = 153051
        Sha256 = '72293c0d58aa07205f67233efed3769111e8312c73bd8e1f4c13a44ab255f27f'
    }
    'lwjgl-shaderc-3.4.1-natives-windows.jar' = @{
        Uri = 'https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-shaderc/3.4.1/lwjgl-shaderc-3.4.1-natives-windows.jar'
        Length = 2448578
        Sha256 = 'd1449f6e4969797230545ec77c2f7478862379e586b35530c3d3651c926cc9ca'
    }
}
$lwjglPaths = @()
foreach ($entry in $lwjglFiles.GetEnumerator()) {
    $path = Join-Path $lwjglRoot $entry.Key
    Get-PinnedFile `
        -Uri $entry.Value.Uri `
        -Path $path `
        -Sha256 $entry.Value.Sha256 `
        -Length $entry.Value.Length
    $lwjglPaths += $path
}

$legacyClasses = Join-Path $WorkDirectory 'legacy-shader-classes'
$legacyOutput = Join-Path $WorkDirectory 'legacy-shader-output'
New-Item -ItemType Directory -Path $legacyClasses, $legacyOutput -Force | Out-Null
$compileClasspath = $lwjglPaths -join [IO.Path]::PathSeparator
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
& $javac `
    '-encoding' 'UTF-8' `
    '-cp' $compileClasspath `
    '-d' $legacyClasses `
    (Join-Path $Project 'provenance\0.3.16\CompileMotionShaders.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$runtimeClasspath = (@($legacyClasses) + $lwjglPaths) -join [IO.Path]::PathSeparator
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $java `
    '--enable-native-access=ALL-UNNAMED' `
    '-cp' $runtimeClasspath `
    'CompileMotionShaders' `
    (Join-Path $Project 'provenance\0.3.16\native\shaders\motion_vectors.comp') `
    $legacyOutput
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$legacyShaderExpected = [ordered]@{
    'motion_vectors.comp.spv' = @{
        Length = 12272
        Sha256 = 'fc89b518ffbfd39b1a2d8d37957534663b10a922a25629d116d72fe25810ea19'
    }
    'motion_vectors.debug.comp.spv' = @{
        Length = 16224
        Sha256 = 'a505c8cedd93575a852c5d277bf873447f962bc43016146404562b42f7c053b6'
    }
}
Assert-OutputSet -Directory $legacyOutput -Expected $legacyShaderExpected

Write-Host '0.3.16 bridge archival verification:'
Write-Host '  source SHA and recorded output SHA were checked against the source stamp;'
Write-Host '  the published DLL was checked byte-for-byte inside the downloaded JAR.'
Write-Host '  An exact bridge rebuild is not claimed because its historical Vulkan/JNI'
Write-Host '  header inputs were not recorded with archive-level identities in 0.3.16.'
Write-Host 'Native binary verification completed successfully.'
Write-Host 'No downloaded or rebuilt binary is uploaded as a workflow artifact.'

