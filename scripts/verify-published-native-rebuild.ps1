[CmdletBinding()]
param(
    [string]$WorkDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot

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
    'nvidia_dlss_bridge.dll' = @{
        Length = 558592
        Sha256 = 'f1f1f4ec4b7ecc7c85d0f824b3552468e92600ceca76141c8441965a54a407c6'
    }
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
