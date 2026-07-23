[CmdletBinding()]
param(
    [switch]$Sync,
    [switch]$SkipChecks,
    [switch]$PreflightOnly,
    [switch]$IncludeVisualChecks
)

Set-StrictMode -Version 2
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$localTools = Join-Path $root ".tools"
Import-Module (Join-Path $PSScriptRoot "BuildRequirements.psm1") -Force
$requirements = Get-TunesLinkBuildRequirements -Root $root

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host (">> " + $Message) -ForegroundColor Cyan
}

function Test-DotNetSdk {
    param([string]$Executable)
    if ([string]::IsNullOrWhiteSpace($Executable) -or -not (Test-Path -LiteralPath $Executable)) {
        return $false
    }
    $installed = & $Executable --list-sdks 2>$null
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-TunesLinkDotNetSdkList -SdkList $installed -RequiredVersion $requirements.DotNetSdk)) {
        return $false
    }

    Push-Location $root
    try {
        $resolvedVersion = (& $Executable --version 2>$null | Select-Object -First 1).Trim()
        return $LASTEXITCODE -eq 0 -and $resolvedVersion -eq $requirements.DotNetSdk
    }
    finally {
        Pop-Location
    }
}

function Resolve-DotNet {
    $candidates = New-Object System.Collections.Generic.List[string]
    $diagnostics = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $localTools "dotnet\dotnet.exe"))
    $command = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($null -ne $command) { $candidates.Add($command.Source) }

    foreach ($candidate in $candidates) {
        if (Test-DotNetSdk $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        if (Test-Path -LiteralPath $candidate) {
            $versions = @(& $candidate --list-sdks 2>$null)
            $diagnostics.Add("$candidate -> installed: $($versions -join '; ')")
        }
        else { $diagnostics.Add("$candidate -> not found") }
    }

    throw @"
The exact .NET SDK $($requirements.DotNetSdk) required by global.json was not resolved.
Install that SDK from https://dotnet.microsoft.com/download/dotnet/10.0 or place it at
.tools\dotnet\dotnet.exe. A newer .NET 10 feature band is not accepted by this build.
Candidates checked:
$($diagnostics -join "`n")
"@
}

function Get-JavaVersionMajor {
    param([string]$CandidateHome)
    if ([string]::IsNullOrWhiteSpace($CandidateHome)) { return 0 }
    $java = Join-Path $CandidateHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) { return 0 }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $versionText = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { return 0 }
    return Get-TunesLinkJavaMajor -VersionText $versionText
}

function Test-JavaVersion {
    param([string]$CandidateHome)
    return (Get-JavaVersionMajor $CandidateHome) -eq $requirements.JavaMajor
}

function Resolve-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    $diagnostics = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $localTools "jdk"))
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates.Add($env:JAVA_HOME) }
    $candidates.Add((Join-Path $env:ProgramFiles "Android\Android Studio\jbr"))

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source)))
    }

    foreach ($candidate in $candidates) {
        if (Test-JavaVersion $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        $major = Get-JavaVersionMajor $candidate
        $diagnostics.Add("$candidate -> " + $(if ($major -gt 0) { "detected JDK $major" } else { "not found" }))
    }

    throw @"
JDK $($requirements.JavaMajor) was not found. Install Microsoft OpenJDK $($requirements.JavaMajor) from
https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.msi
or place that exact supported major at .tools\jdk. Newer JDK majors are not supported.
Candidates checked:
$($diagnostics -join "`n")
"@
}

function Test-AndroidSdk {
    param([string]$CandidateHome)
    if ([string]::IsNullOrWhiteSpace($CandidateHome) -or -not (Test-Path -LiteralPath $CandidateHome)) {
        return $false
    }
    return @(Get-TunesLinkAndroidSdkProblems -SdkRoot $CandidateHome -Requirements $requirements).Count -eq 0
}

function Resolve-AndroidSdk {
    $candidates = New-Object System.Collections.Generic.List[string]
    $diagnostics = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $localTools "android-sdk"))
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { $candidates.Add($env:ANDROID_HOME) }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk"))

    foreach ($candidate in $candidates) {
        if (Test-AndroidSdk $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        $problems = @(Get-TunesLinkAndroidSdkProblems -SdkRoot $candidate -Requirements $requirements)
        $diagnostics.Add("$candidate -> $($problems -join '; ')")
    }

    throw @"
Android platform $($requirements.AndroidPlatformPackage) with Build Tools
$($requirements.BuildToolsVersion) was not found or is incomplete. The build requires
android.jar, aapt2, d8, and apksigner. Install the exact packages through Android Studio
or place a complete SDK at .tools\android-sdk.
Candidates checked:
$($diagnostics -join "`n")
"@
}

if ($Sync) {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $git -and (Test-Path -LiteralPath (Join-Path $root ".git"))) {
        $changes = & $git.Source -C $root status --porcelain
        if ($LASTEXITCODE -eq 0 -and [string]::IsNullOrWhiteSpace(($changes | Out-String))) {
            $upstream = & $git.Source -C $root rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>$null
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($upstream | Out-String))) {
                Write-Step "Updating the clean repository with a fast-forward-only pull"
                & $git.Source -C $root pull --ff-only
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning "The update failed; continuing with the latest local commit."
                }
            }
        }
        else {
            Write-Warning "Local source changes are present, so synchronization was skipped."
        }
    }
}

Write-Step "Checking build tools"
$dotnet = Resolve-DotNet
$javaHome = Resolve-JavaHome
$androidHome = Resolve-AndroidSdk

$env:DOTNET_ROOT = Split-Path -Parent $dotnet
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:PATH = (Split-Path -Parent $dotnet) + ";" + (Join-Path $javaHome "bin") + ";" + $env:PATH

Write-Host ("   .NET:    " + $dotnet)
Write-Host ("   Java:    " + $javaHome)
Write-Host ("   Android: " + $androidHome)

Write-Step "Exercising Gradle and global.json resolution"
Push-Location $root
try {
    $resolvedDotNet = (& $dotnet --version).Trim()
    if ($LASTEXITCODE -ne 0 -or $resolvedDotNet -ne $requirements.DotNetSdk) {
        throw "global.json requires $($requirements.DotNetSdk), but '$dotnet --version' resolved '$resolvedDotNet'."
    }
}
finally {
    Pop-Location
}

$gradle = Join-Path $root "android\gradlew.bat"
Push-Location (Join-Path $root "android")
try {
    $gradleOutput = @(& $gradle --no-daemon --console=plain `
        --dependency-verification strict :app:tasks --quiet 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $gradleOutput | ForEach-Object { Write-Host $_ }
        throw "Gradle configuration preflight failed."
    }
}
finally {
    Pop-Location
}

Write-Host ("   Required .NET SDK:       " + $requirements.DotNetSdk)
Write-Host ("   Required JDK major:      " + $requirements.JavaMajor)
Write-Host ("   Required Android API:    " + $requirements.CompileSdk)
Write-Host ("   Required Build Tools:    " + $requirements.BuildToolsVersion)

if ($PreflightOnly) {
    Write-Step "Environment is ready"
    return
}

Write-Step "Building and verifying Android plus Windows"
$arguments = @{
    Component = "All"
    SkipVisualChecks = -not $IncludeVisualChecks
}
if ($SkipChecks) { $arguments.SkipChecks = $true }
& (Join-Path $PSScriptRoot "build.ps1") @arguments
if (-not $?) { throw "The TunesLink build script failed." }

$artifacts = Join-Path $root "artifacts"
Write-Step "Build complete"
Get-ChildItem -LiteralPath $artifacts -File -Recurse |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($artifacts.Length).TrimStart('\')
        Write-Host ("   " + $relative + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)")
    }

if ($Host.Name -notmatch 'ServerRemoteHost') {
    Start-Process explorer.exe -ArgumentList ('"' + $artifacts + '"')
}
