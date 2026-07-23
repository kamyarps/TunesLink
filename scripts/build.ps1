[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Windows")]
    [string]$Component = "All",
    [switch]$SkipChecks,
    [switch]$SkipVisualChecks
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot "BuildRequirements.psm1") -Force
Initialize-TunesLinkLocalToolchain -Root $root
$requirements = Get-TunesLinkBuildRequirements -Root $root
$artifacts = Join-Path $root "artifacts"
$buildId = [guid]::NewGuid().ToString("N")
$staging = Join-Path $root ("artifacts.tmp." + $buildId)
$backup = Join-Path $root ("artifacts.old." + $buildId)
$publishWork = Join-Path ([IO.Path]::GetTempPath()) ("TunesLink-publish-" + $buildId)
$resolvedRoot = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
$androidIsReleaseSigned = $false

foreach ($path in @($artifacts, $staging, $backup)) {
    if (-not [IO.Path]::GetFullPath($path).StartsWith(
        $resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Build output resolved outside the repository: $path"
    }
}

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

function Resolve-ApkSigner {
    $androidHome = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } else { $env:ANDROID_SDK_ROOT }
    $candidate = if ([string]::IsNullOrWhiteSpace($androidHome)) { "" } else {
        Join-Path $androidHome "build-tools\$($requirements.BuildToolsVersion)\apksigner.bat"
    }
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    $command = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw "apksigner is required to verify TunesLink.apk." }
    return $command.Source
}

function Remove-BuildDirectory {
    param([Parameter(Mandatory)] [string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return
        }
        catch {
            if ($attempt -eq 10) {
                Write-Warning "Build succeeded, but temporary files are still locked at '$Path'. They can be deleted later."
                return
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

try {
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    New-Item -ItemType Directory -Path $publishWork -Force | Out-Null

    if (-not $SkipChecks) {
        & (Join-Path $PSScriptRoot "test.ps1") -Component $Component `
            -SkipVisualChecks:$SkipVisualChecks
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    if ($Component -in @("All", "Android")) {
        $signingState = Get-TunesLinkAndroidSigningState -Root $root
        $androidIsReleaseSigned = $signingState.EnvironmentConfigured -or
            $signingState.PropertiesConfigured
        $gradle = Join-Path $root "android\gradlew.bat"

        if ($SkipChecks) {
            $tasks = New-Object System.Collections.Generic.List[string]
            $tasks.Add(":app:assembleDebug")
            if ($androidIsReleaseSigned) { $tasks.Add(":app:assembleRelease") }
            Push-Location (Join-Path $root "android")
            try {
                Invoke-Checked $gradle (@("--no-daemon", "--console=plain",
                    "--dependency-verification", "strict") + $tasks.ToArray())
            }
            finally { Pop-Location }
        }

        $apkSource = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
        if ($androidIsReleaseSigned) {
            $metadataPath = Join-Path $root `
                "android\app\build\outputs\apk\release\output-metadata.json"
            if (-not (Test-Path -LiteralPath $metadataPath)) {
                throw "Gradle did not produce signed release APK metadata at $metadataPath"
            }
            $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
            $output = @($metadata.elements | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_.outputFile)
            }) | Select-Object -First 1
            if ($null -eq $output) { throw "Release APK metadata contains no outputFile." }
            $releaseName = [string]$output.outputFile
            if ($releaseName.EndsWith("-unsigned.apk", [StringComparison]::OrdinalIgnoreCase)) {
                throw "Android signing is configured, but Gradle produced '$releaseName'."
            }
            $apkSource = Join-Path $root "android\app\build\outputs\apk\release\$releaseName"
        }

        if (-not (Test-Path -LiteralPath $apkSource)) {
            throw "Android build did not produce the expected APK: $apkSource"
        }
        $apkArtifact = Join-Path $staging "TunesLink.apk"
        Copy-Item -LiteralPath $apkSource -Destination $apkArtifact -Force
        Invoke-Checked (Resolve-ApkSigner) @("verify", "--verbose", $apkArtifact)
    }

    if ($Component -in @("All", "Windows")) {
        $project = Join-Path $root "windows\TuneLinkBridge.WinUI\TuneLinkBridge.WinUI.csproj"
        $windowsPublish = Join-Path $publishWork "windows"
        Invoke-Checked "dotnet" @(
            "publish", $project,
            "--configuration", "Release",
            "--runtime", "win-x64",
            "--self-contained", "true",
            "-p:PublishSingleFile=true",
            "-p:DebugType=None",
            "-p:DebugSymbols=false",
            "--output", $windowsPublish
        )
        $publishedExe = Join-Path $windowsPublish "TunesLink Bridge.exe"
        $windowsArtifact = Join-Path $staging "TunesLink Bridge.exe"
        Copy-Item -LiteralPath $publishedExe -Destination $windowsArtifact -Force
        Remove-BuildDirectory $publishWork
        Invoke-GuiProcessChecked $windowsArtifact @("--self-test")
        Invoke-GuiProcessChecked $windowsArtifact @(
            "--verify-layout", "--viewport", "420x640",
            "--text-scale", "2.0", "--ui-state", "runtime-unavailable")
    }

    $expected = @(Get-TunesLinkArtifactNames -Component $Component | Sort-Object)
    $actual = @(Get-ChildItem -LiteralPath $staging -File |
        Select-Object -ExpandProperty Name | Sort-Object)
    if (($expected -join '|') -cne ($actual -join '|')) {
        throw "Artifact contract mismatch. Expected '$($expected -join ', ')'; produced '$($actual -join ', ')'."
    }

    if (Test-Path -LiteralPath $artifacts) {
        Move-Item -LiteralPath $artifacts -Destination $backup
    }
    try {
        Move-Item -LiteralPath $staging -Destination $artifacts
    }
    catch {
        if (-not (Test-Path -LiteralPath $artifacts) -and
            (Test-Path -LiteralPath $backup)) {
            Move-Item -LiteralPath $backup -Destination $artifacts
        }
        throw
    }
    if (Test-Path -LiteralPath $backup) {
        Remove-BuildDirectory $backup
    }

    Write-Host ""
    Write-Host "Build complete. Ready to use:" -ForegroundColor Green
    Get-ChildItem -LiteralPath $artifacts -File | Sort-Object Name | ForEach-Object {
        Write-Host ("  " + $_.Name + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)")
    }
    if ($Component -in @("All", "Android")) {
        if ($androidIsReleaseSigned) {
            Write-Host "TunesLink.apk is a verified, release-signed APK." -ForegroundColor Green
        }
        else {
            Write-Host "TunesLink.apk is debug-signed for personal testing." -ForegroundColor Yellow
            Write-Host "Official public releases must be built with the project signing key." `
                -ForegroundColor Yellow
        }
    }
}
finally {
    Remove-BuildDirectory $staging
    Remove-BuildDirectory $publishWork
}
