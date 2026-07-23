[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Windows")]
    [string]$Component = "All",
    [switch]$SkipVisualChecks
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot "BuildRequirements.psm1") -Force
Initialize-TunesLinkLocalToolchain -Root $root
$initialStatus = & git -C $root status --porcelain
$startedClean = $LASTEXITCODE -eq 0 -and [string]::IsNullOrWhiteSpace(($initialStatus | Out-String))
$initialTrackedDiff = (& git -C $root diff --binary HEAD | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Could not capture the initial tracked-file state." }

function Invoke-Checked {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($ArgumentList -join ' ')"
    }
}

function Invoke-GuiProcessChecked {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        throw "Command failed with exit code $($process.ExitCode): $FilePath $($ArgumentList -join ' ')"
    }
}

if ($Component -in @("All", "Android")) {
    $gradle = Join-Path $root "android\gradlew.bat"
    Push-Location (Join-Path $root "android")
    try {
        Invoke-Checked $gradle @("--no-daemon", "--console=plain", "--warning-mode", "all",
            "--dependency-verification", "strict",
            ":app:lintDebug", ":app:lintRelease", `
            ":app:testDebugUnitTest", ":app:assembleDebug", ":app:assembleRelease")
    }
    finally {
        Pop-Location
    }
}

if ($Component -in @("All", "Windows")) {
    $winUiProject = Join-Path $root "windows\TuneLinkBridge.WinUI\TuneLinkBridge.WinUI.csproj"
    $integrationProject = Join-Path $root "test-support\TuneLinkDemoBridge\TuneLinkDemoBridge.csproj"
    Invoke-Checked "dotnet" @("build", $winUiProject, "--configuration", "Release")
    Invoke-Checked "dotnet" @("build", $integrationProject, "--configuration", "Release")
    if (-not $SkipVisualChecks) {
        Invoke-Checked "dotnet" @("build", $winUiProject, "--configuration", "UIQA")
        $winUiUiqaExecutable = Join-Path (Split-Path -Parent $winUiProject) `
            "bin\UIQA\net10.0-windows10.0.17763.0\win-x64\TunesLink.Bridge.exe"
        $layoutCases = @(
            @("480x580", "1.0", "two-phones"),
            @("500x840", "1.0", "paired"),
            @("420x640", "2.0", "long-name"),
            @("500x840", "1.5", "both-errors"),
            @("420x640", "2.0", "runtime-unavailable")
        )
        foreach ($layoutCase in $layoutCases) {
            Invoke-GuiProcessChecked $winUiUiqaExecutable @(
                "--verify-layout", "--viewport", $layoutCase[0],
                "--text-scale", $layoutCase[1], "--ui-state", $layoutCase[2])
        }
    }
    else {
        Write-Host "Windows visual UIQA previews skipped for this non-interactive build." `
            -ForegroundColor DarkGray
    }
    Invoke-Checked "dotnet" @("format", (Join-Path $root "windows\TuneLink.sln"),
        "--verify-no-changes", "--severity", "warn", "--no-restore")
    $winUiReleaseExecutable = Join-Path (Split-Path -Parent $winUiProject) `
        "bin\Release\net10.0-windows10.0.17763.0\win-x64\TunesLink.Bridge.exe"
    Invoke-GuiProcessChecked $winUiReleaseExecutable @("--self-test")
}

& (Join-Path $PSScriptRoot "build-requirements-self-test.ps1")
if (-not $?) { throw "Build-requirements self-tests failed." }
& (Join-Path $PSScriptRoot "verify-repository.ps1") -RequireCleanWorktree:$startedClean
if (-not $?) { throw "Repository hygiene verification failed." }
$finalTrackedDiff = (& git -C $root diff --binary HEAD | Out-String)
if ($LASTEXITCODE -ne 0 -or $finalTrackedDiff -cne $initialTrackedDiff) {
    throw "The test/build workflow modified a tracked repository file."
}

Write-Host "TunesLink checks passed." -ForegroundColor Green
