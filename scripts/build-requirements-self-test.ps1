$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot "BuildRequirements.psm1") -Force

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Build-requirements self-test failed: $Message" }
}

$requirements = Get-TunesLinkBuildRequirements -Root $root
Assert-True ($requirements.DotNetSdk -eq "10.0.302") "global.json SDK"
Assert-True ($requirements.JavaMajor -eq 17) "JDK major"
Assert-True ($requirements.CompileSdk -eq 37 -and $requirements.TargetSdk -eq 37) "Android API"
Assert-True (Test-TunesLinkDotNetSdkList @("10.0.302 [C:\dotnet\sdk]") "10.0.302") "exact SDK accepted"
Assert-True (-not (Test-TunesLinkDotNetSdkList @("10.0.301 [C:\dotnet\sdk]") "10.0.302")) "wrong SDK rejected"
Assert-True ((Get-TunesLinkJavaMajor 'openjdk version "17.0.19"') -eq 17) "JDK 17 parsed"
Assert-True ((Get-TunesLinkJavaMajor 'openjdk version "21.0.1"') -ne 17) "wrong JDK distinguished"
Assert-True ((Get-TunesLinkArtifactNames All) -join '|' -eq
    'TunesLink.apk|TunesLink Bridge.exe') "all-build artifact contract"
Assert-True ((Get-TunesLinkArtifactNames Android) -join '|' -eq
    'TunesLink.apk') "Android artifact contract"
Assert-True ((Get-TunesLinkArtifactNames Windows) -join '|' -eq
    'TunesLink Bridge.exe') "Windows artifact contract"

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("TunesLink-requirements-" + [guid]::NewGuid().ToString("N"))
try {
    $api36 = Join-Path $temporary "platforms\android-36"
    [IO.Directory]::CreateDirectory($api36) | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $api36 "android.jar"), [byte[]]@())
    Assert-True (@(Get-TunesLinkAndroidSdkProblems $temporary $requirements).Count -gt 0) "API-36-only SDK rejected"

    $platform = Join-Path $temporary ("platforms\" + $requirements.AndroidPlatformPackage)
    $tools = Join-Path $temporary ("build-tools\" + $requirements.BuildToolsVersion)
    [IO.Directory]::CreateDirectory($platform) | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $platform "android.jar"), [byte[]]@())
    Assert-True (@(Get-TunesLinkAndroidSdkProblems $temporary $requirements).Count -ge 3) "missing Build Tools rejected"
    [IO.Directory]::CreateDirectory($tools) | Out-Null
    foreach ($path in @(
        (Join-Path $platform "android.jar"),
        (Join-Path $tools "aapt2.exe"),
        (Join-Path $tools "d8.bat"),
        (Join-Path $tools "apksigner.bat")
    )) { [IO.File]::WriteAllBytes($path, [byte[]]@()) }
    Assert-True (@(Get-TunesLinkAndroidSdkProblems $temporary $requirements).Count -eq 0) "valid SDK layout"
    [IO.File]::Delete((Join-Path $tools "d8.bat"))
    Assert-True (@(Get-TunesLinkAndroidSdkProblems $temporary $requirements).Count -eq 1) "missing tool rejected"

    $multiGroupProject = Join-Path $temporary "multi-group.csproj"
    [IO.File]::WriteAllText($multiGroupProject, @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <Version>3.0.0</Version>
  </PropertyGroup>
  <PropertyGroup Condition="'$(Configuration)' == 'UIQA'">
    <DefineConstants>UIQA</DefineConstants>
  </PropertyGroup>
</Project>
'@)
    Assert-True ((Get-TunesLinkProjectVersion $multiGroupProject) -eq "3.0.0") `
        "version parsed from project with conditional property group"

    $conflictingProject = Join-Path $temporary "conflicting-version.csproj"
    [IO.File]::WriteAllText($conflictingProject, @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><Version>3.0.0</Version></PropertyGroup>
  <PropertyGroup><Version>4.0.0</Version></PropertyGroup>
</Project>
'@)
    $conflictingVersionRejected = $false
    try { Get-TunesLinkProjectVersion $conflictingProject | Out-Null }
    catch { $conflictingVersionRejected = $true }
    Assert-True $conflictingVersionRejected "conflicting project versions rejected"
}
finally {
    if (Test-Path -LiteralPath $temporary) {
        [IO.Directory]::Delete($temporary, $true)
    }
}

$signingNames = @(
    "TunesLink_ANDROID_KEYSTORE",
    "TunesLink_ANDROID_STORE_PASSWORD",
    "TunesLink_ANDROID_KEY_ALIAS",
    "TunesLink_ANDROID_KEY_PASSWORD"
)
$savedSigning = @{}
foreach ($name in $signingNames) {
    $savedSigning[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $null)
}
try {
    $unsigned = Get-TunesLinkAndroidSigningState $root
    Assert-True (-not $unsigned.EnvironmentConfigured) "unsigned environment accepted"
    [Environment]::SetEnvironmentVariable($signingNames[0], "test.jks")
    $partialRejected = $false
    try { Get-TunesLinkAndroidSigningState $root | Out-Null } catch { $partialRejected = $true }
    Assert-True $partialRejected "partial signing environment rejected"
    foreach ($name in $signingNames) { [Environment]::SetEnvironmentVariable($name, "test") }
    $environmentSigning = Get-TunesLinkAndroidSigningState $root
    Assert-True $environmentSigning.EnvironmentConfigured "environment-only signing accepted"
}
finally {
    foreach ($name in $signingNames) {
        [Environment]::SetEnvironmentVariable($name, $savedSigning[$name])
    }
}
Write-Host "Build-requirements self-test passed." -ForegroundColor Green
