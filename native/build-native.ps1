[CmdletBinding()]
param(
    [string]$StreamlineInclude = $env:STREAMLINE_INCLUDE,
    [string]$VulkanInclude = $env:VULKAN_INCLUDE,
    [string]$JniInclude = $env:JNI_INCLUDE,
    [string]$ZigExecutable = $env:ZIG_EXE,
    [string]$GlslcExecutable = $env:GLSLC_EXE,
    [string]$PrecompiledShaderCompiler =
        $env:MOTION_SHADER_COMPILER_PROVENANCE,
    [switch]$SkipShaderBuild
)

$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$OutputDirectory = Join-Path $Project 'src\main\resources\assets\nvidia_dlss\native\win-x64'
$Output = Join-Path $OutputDirectory 'nvidia_dlss_bridge.dll'
$SourceStamp = Join-Path $OutputDirectory 'native-source-v1.properties'
$BridgeSource = Join-Path $PSScriptRoot 'nvidia_dlss_bridge.cpp'
$ShaderSource = Join-Path $PSScriptRoot 'shaders\motion_vectors.comp'
$ShaderOutput = Join-Path $OutputDirectory 'motion_vectors.comp.spv'
$DiagnosticShaderOutput = Join-Path $OutputDirectory 'motion_vectors.debug.comp.spv'

if ([string]::IsNullOrWhiteSpace($VulkanInclude) -and -not [string]::IsNullOrWhiteSpace($env:VULKAN_SDK)) {
    $VulkanInclude = Join-Path $env:VULKAN_SDK 'Include'
}
if ([string]::IsNullOrWhiteSpace($JniInclude) -and -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $JniInclude = Join-Path $env:JAVA_HOME 'include'
}
if ([string]::IsNullOrWhiteSpace($ZigExecutable)) {
    $zigCommand = Get-Command 'zig.exe' -ErrorAction SilentlyContinue
    if ($zigCommand) { $ZigExecutable = $zigCommand.Source }
}
if ([string]::IsNullOrWhiteSpace($GlslcExecutable)) {
    $glslcCommand = Get-Command 'glslc.exe' -ErrorAction SilentlyContinue
    if ($glslcCommand) {
        $GlslcExecutable = $glslcCommand.Source
    } elseif (-not [string]::IsNullOrWhiteSpace($env:VULKAN_SDK)) {
        $GlslcExecutable = Join-Path $env:VULKAN_SDK 'Bin\glslc.exe'
    }
}

$requiredDirectories = @{
    StreamlineInclude = $StreamlineInclude
    VulkanInclude = $VulkanInclude
    JniInclude = $JniInclude
}
foreach ($entry in $requiredDirectories.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($entry.Value) -or -not (Test-Path -LiteralPath $entry.Value -PathType Container)) {
        throw "$($entry.Key) is missing. Pass it as a parameter or set the matching environment variable."
    }
}

$requiredTools = @{ ZigExecutable = $ZigExecutable }
if (-not $SkipShaderBuild) {
    $requiredTools.GlslcExecutable = $GlslcExecutable
}
foreach ($entry in $requiredTools.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($entry.Value) -or -not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) {
        throw "$($entry.Key) is missing. Pass it as a parameter or set the matching environment variable."
    }
}

$zigVersion = (& $ZigExecutable version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($zigVersion)) {
    throw 'Zig compiler version could not be determined.'
}
$BridgeCompiler = "zig $zigVersion"

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

if ($SkipShaderBuild) {
    if (-not (Test-Path -LiteralPath $ShaderOutput -PathType Leaf)) {
        throw "SkipShaderBuild requires a precompiled shader at $ShaderOutput"
    }
    if (-not (Test-Path -LiteralPath $DiagnosticShaderOutput -PathType Leaf)) {
        throw "SkipShaderBuild requires a precompiled diagnostic shader at $DiagnosticShaderOutput"
    }
    if ([string]::IsNullOrWhiteSpace($PrecompiledShaderCompiler)) {
        throw 'SkipShaderBuild requires -PrecompiledShaderCompiler provenance.'
    }
    $ShaderCompiler = $PrecompiledShaderCompiler.Trim()
    Write-Host "Motion shader (precompiled release): $ShaderOutput"
    Write-Host "Motion shader (precompiled diagnostics): $DiagnosticShaderOutput"
} else {
    $glslcVersion = (& $GlslcExecutable '--version' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($glslcVersion)) {
        throw 'glslc compiler version could not be determined.'
    }
    $ShaderCompiler = 'glslc ' + (
        $glslcVersion -replace '(\r?\n)+', ' | '
    )
    & $GlslcExecutable '--target-env=vulkan1.2' '-O' '-DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=0' $ShaderSource '-o' $ShaderOutput
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $GlslcExecutable '--target-env=vulkan1.2' '-O' '-DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=1' $ShaderSource '-o' $DiagnosticShaderOutput
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Motion shader (release): $ShaderOutput"
    Write-Host "Motion shader (diagnostics): $DiagnosticShaderOutput"
}

& $ZigExecutable c++ -target x86_64-windows-gnu -shared -O3 `
    "-I$StreamlineInclude" `
    "-I$VulkanInclude" `
    "-I$JniInclude" `
    "-I$(Join-Path $JniInclude 'win32')" `
    $BridgeSource `
    -o $Output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Native bridge: $Output"

$stampLines = @(
    'schema=1'
    "bridgeSourceSha256=$((Get-FileHash -LiteralPath $BridgeSource -Algorithm SHA256).Hash.ToLowerInvariant())"
    "motionShaderSourceSha256=$((Get-FileHash -LiteralPath $ShaderSource -Algorithm SHA256).Hash.ToLowerInvariant())"
    "bridgeBinarySha256=$((Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash.ToLowerInvariant())"
    "motionShaderBinarySha256=$((Get-FileHash -LiteralPath $ShaderOutput -Algorithm SHA256).Hash.ToLowerInvariant())"
    "motionShaderDiagnosticBinarySha256=$((Get-FileHash -LiteralPath $DiagnosticShaderOutput -Algorithm SHA256).Hash.ToLowerInvariant())"
    "bridgeCompiler=$BridgeCompiler"
    "motionShaderCompiler=$ShaderCompiler"
    'motionShaderReleaseDefine=BLOCKFRAME_DEVELOPER_DIAGNOSTICS=0'
    'motionShaderDiagnosticDefine=BLOCKFRAME_DEVELOPER_DIAGNOSTICS=1'
)
[IO.File]::WriteAllLines($SourceStamp, $stampLines, [Text.UTF8Encoding]::new($false))
Write-Host "Native source provenance: $SourceStamp"
