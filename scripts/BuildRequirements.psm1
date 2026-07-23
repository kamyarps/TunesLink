Set-StrictMode -Version 2

function Initialize-TunesLinkLocalToolchain {
    param([Parameter(Mandatory)] [string]$Root)

    $localTools = Join-Path $Root ".tools"
    $localDotNet = Join-Path $localTools "dotnet\dotnet.exe"
    $localJava = Join-Path $localTools "jdk"
    $localAndroid = Join-Path $localTools "android-sdk"
    if (Test-Path -LiteralPath $localDotNet) {
        $env:DOTNET_ROOT = Split-Path -Parent $localDotNet
        $env:PATH = $env:DOTNET_ROOT + ";" + $env:PATH
    }
    if (Test-Path -LiteralPath (Join-Path $localJava "bin\java.exe")) {
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $localJava).Path
        $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
    }
    if (Test-Path -LiteralPath $localAndroid) {
        $env:ANDROID_HOME = (Resolve-Path -LiteralPath $localAndroid).Path
        $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    }
}

function Get-TunesLinkBuildRequirements {
    param([Parameter(Mandatory)] [string]$Root)

    $globalJsonPath = Join-Path $Root "global.json"
    $gradlePropertiesPath = Join-Path $Root "android\gradle.properties"
    if (-not (Test-Path -LiteralPath $globalJsonPath)) {
        throw "Build requirements are missing: $globalJsonPath"
    }
    if (-not (Test-Path -LiteralPath $gradlePropertiesPath)) {
        throw "Build requirements are missing: $gradlePropertiesPath"
    }

    $globalJson = Get-Content -LiteralPath $globalJsonPath -Raw | ConvertFrom-Json
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $gradlePropertiesPath) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) { continue }
        $properties[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }

    $required = @(
        "TunesLink.javaVersion",
        "TunesLink.compileSdk",
        "TunesLink.targetSdk",
        "TunesLink.androidPlatformPackage",
        "TunesLink.buildToolsVersion"
    )
    foreach ($name in $required) {
        if (-not $properties.ContainsKey($name) -or
            [string]::IsNullOrWhiteSpace([string]$properties[$name])) {
            throw "Build requirement '$name' is missing from $gradlePropertiesPath"
        }
    }

    [pscustomobject]@{
        DotNetSdk = [string]$globalJson.sdk.version
        JavaMajor = [int]$properties["TunesLink.javaVersion"]
        CompileSdk = [int]$properties["TunesLink.compileSdk"]
        TargetSdk = [int]$properties["TunesLink.targetSdk"]
        AndroidPlatformPackage = [string]$properties["TunesLink.androidPlatformPackage"]
        BuildToolsVersion = [string]$properties["TunesLink.buildToolsVersion"]
    }
}

function Test-TunesLinkDotNetSdkList {
    param(
        [Parameter(Mandatory)] [string[]]$SdkList,
        [Parameter(Mandatory)] [string]$RequiredVersion
    )
    $pattern = '^' + [regex]::Escape($RequiredVersion) + '\s+\['
    return $null -ne ($SdkList | Where-Object { $_ -match $pattern } | Select-Object -First 1)
}

function Get-TunesLinkJavaMajor {
    param([Parameter(Mandatory)] [string]$VersionText)
    if ($VersionText -match 'version\s+"(\d+)') { return [int]$Matches[1] }
    if ($VersionText -match 'openjdk\s+(\d+)') { return [int]$Matches[1] }
    return 0
}

function Get-TunesLinkProjectVersion {
    param([Parameter(Mandatory)] [string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Project file does not exist: $Path"
    }

    try {
        $project = [xml](Get-Content -LiteralPath $Path -Raw)
    }
    catch {
        throw "Could not parse project file '$Path': $($_.Exception.Message)"
    }

    $versions = @($project.SelectNodes(
        "/*[local-name()='Project']/*[local-name()='PropertyGroup']/*[local-name()='Version']") |
        ForEach-Object { ([string]$_.InnerText).Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique)
    if ($versions.Count -eq 0) {
        throw "Project file does not declare a Version: $Path"
    }
    if ($versions.Count -gt 1) {
        throw "Project file declares conflicting versions '$($versions -join ', ')': $Path"
    }
    return $versions[0]
}

function Get-TunesLinkAndroidSdkProblems {
    param(
        [Parameter(Mandatory)] [string]$SdkRoot,
        [Parameter(Mandatory)] $Requirements
    )

    $problems = New-Object System.Collections.Generic.List[string]
    if (-not (Test-Path -LiteralPath $SdkRoot)) {
        $problems.Add("SDK root does not exist")
        return $problems.ToArray()
    }

    $platform = Join-Path $SdkRoot ("platforms\" + $Requirements.AndroidPlatformPackage)
    $tools = Join-Path $SdkRoot ("build-tools\" + $Requirements.BuildToolsVersion)
    $checks = @(
        @{ Paths = @((Join-Path $platform "android.jar")); Name = "Android platform android.jar" },
        @{ Paths = @((Join-Path $tools "aapt2.exe"), (Join-Path $tools "aapt2")); Name = "aapt2" },
        @{ Paths = @((Join-Path $tools "d8.bat"), (Join-Path $tools "d8")); Name = "d8" },
        @{ Paths = @((Join-Path $tools "apksigner.bat"), (Join-Path $tools "apksigner")); Name = "apksigner" }
    )
    foreach ($check in $checks) {
        if (-not ($check.Paths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1)) {
            $problems.Add("$($check.Name) is missing; checked $($check.Paths -join ', ')")
        }
    }
    return $problems.ToArray()
}

function Get-TunesLinkAndroidSigningState {
    param([Parameter(Mandatory)] [string]$Root)

    $names = @(
        "TunesLink_ANDROID_KEYSTORE",
        "TunesLink_ANDROID_STORE_PASSWORD",
        "TunesLink_ANDROID_KEY_ALIAS",
        "TunesLink_ANDROID_KEY_PASSWORD"
    )
    $present = @($names | Where-Object {
        -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
    })
    if ($present.Count -gt 0 -and $present.Count -lt $names.Count) {
        $missing = @($names | Where-Object {
            [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
        })
        throw "Android signing environment is incomplete. Missing: $($missing -join ', ')"
    }

    [pscustomobject]@{
        EnvironmentConfigured = $present.Count -eq $names.Count
        PropertiesConfigured = Test-Path -LiteralPath (Join-Path $Root "android\keystore.properties")
    }
}

function Get-TunesLinkArtifactNames {
    param(
        [Parameter(Mandatory)]
        [ValidateSet("All", "Android", "Windows")]
        [string]$Component
    )

    $names = New-Object System.Collections.Generic.List[string]
    if ($Component -in @("All", "Android")) { $names.Add("TunesLink.apk") }
    if ($Component -in @("All", "Windows")) { $names.Add("TunesLink Bridge.exe") }
    return $names.ToArray()
}

Export-ModuleMember -Function Initialize-TunesLinkLocalToolchain,
    Get-TunesLinkBuildRequirements,
    Test-TunesLinkDotNetSdkList,
    Get-TunesLinkJavaMajor,
    Get-TunesLinkProjectVersion,
    Get-TunesLinkAndroidSdkProblems,
    Get-TunesLinkAndroidSigningState,
    Get-TunesLinkArtifactNames
