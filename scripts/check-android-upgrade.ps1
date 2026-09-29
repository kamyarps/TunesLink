param(
    [Parameter(Mandatory = $true)] [string]$PreviousApk,
    [Parameter(Mandatory = $true)] [string]$NewApk
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $root ".tools/android-sdk" }
$properties = Get-Content -Raw (Join-Path $root "android/gradle.properties")
$toolsVersion = [regex]::Match($properties, '(?m)^TunesLink\.buildToolsVersion=(\S+)').Groups[1].Value
if (-not $toolsVersion) { throw "Android build-tools version is missing." }
$tools = Join-Path $sdkRoot "build-tools/$toolsVersion"
$aapt = Join-Path $tools $(if ($IsWindows) { "aapt.exe" } else { "aapt" })
$apksigner = Join-Path $tools $(if ($IsWindows) { "apksigner.bat" } else { "apksigner" })
if (-not (Test-Path -LiteralPath $aapt) -or -not (Test-Path -LiteralPath $apksigner)) {
    throw "Pinned Android build tools are missing from $tools."
}

function Get-ApkIdentity([string]$path) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $badging = @(& $aapt dump badging $resolved)
    if ($LASTEXITCODE -ne 0) { throw "Could not read APK manifest: $resolved" }
    # aapt can append compile SDK attributes after the package fields.
    $package = [regex]::Match(($badging | Select-Object -First 1),
        "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'(?:\s|$)")
    $sdk = [regex]::Match(($badging | Where-Object { $_ -match '^sdkVersion:' } | Select-Object -First 1),
        "^sdkVersion:'(\d+)'")
    if (-not $package.Success -or -not $sdk.Success) {
        throw "Could not read package, version, or minimum SDK from $resolved."
    }
    $signatures = @(& $apksigner verify --min-sdk-version $sdk.Groups[1].Value `
        --print-certs $resolved)
    if ($LASTEXITCODE -ne 0) { throw "APK does not verify at its minimum SDK: $resolved" }
    $digests = @($signatures | ForEach-Object {
        [regex]::Match($_, '^.+ Signer: certificate SHA-256 digest: ([0-9a-fA-F]{64})$')
    } | Where-Object Success | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } |
        Select-Object -Unique)
    if ($digests.Count -ne 1) { throw "Expected one signing certificate in $resolved." }
    return [pscustomobject]@{
        Package = $package.Groups[1].Value
        VersionCode = [long]::Parse($package.Groups[2].Value)
        VersionName = $package.Groups[3].Value
        MinSdk = [int]::Parse($sdk.Groups[1].Value)
        Certificate = $digests[0]
    }
}

$previous = Get-ApkIdentity $PreviousApk
$new = Get-ApkIdentity $NewApk
if ($previous.Package -ne $new.Package) {
    throw "Android package changed from $($previous.Package) to $($new.Package); installation would create a separate app."
}
if ($previous.Certificate -ne $new.Certificate) {
    throw "Android release signing certificate changed; existing installs could not update."
}
if ($new.VersionCode -le $previous.VersionCode) {
    throw "Android versionCode $($new.VersionCode) must exceed published versionCode $($previous.VersionCode)."
}
Write-Output "Android upgrade compatible: $($previous.VersionName) ($($previous.VersionCode)) -> $($new.VersionName) ($($new.VersionCode)); package and signing certificate match."
