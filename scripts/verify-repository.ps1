[CmdletBinding()]
param([switch]$RequireCleanWorktree)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot "BuildRequirements.psm1") -Force
$git = Get-Command git -ErrorAction Stop
$tracked = @(& $git.Source -C $root ls-files)
if ($LASTEXITCODE -ne 0) { throw "Could not inspect tracked repository files." }
$presentTracked = @($tracked | Where-Object {
    Test-Path -LiteralPath (Join-Path $root ($_ -replace '/', [IO.Path]::DirectorySeparatorChar))
})

$forbidden = @($presentTracked | Where-Object {
    $_ -match '(^|/)(bin|obj|audit-evidence|plans)/' -or
    $_ -match '(?i)\.(apk|aab|apks|idsig|exe|dll|pdb|dmp|log|trx|coverage|coveragexml|pfx|p12|jks|keystore|key|pem)$' -or
    $_ -match '(^|/)(local\.properties|keystore\.properties|signing\.properties|\.env(?:\..+)?)$'
})
if ($forbidden.Count -gt 0) {
    throw "Generated, internal, or sensitive files are tracked:`n$($forbidden -join "`n")"
}

$textExtensions = @('.bat', '.cs', '.csproj', '.gradle', '.java', '.json', '.kt', '.md', '.props',
    '.ps1', '.py', '.resw', '.sh', '.sln', '.txt', '.xml', '.yaml', '.yml')
$localPaths = foreach ($relative in $presentTracked) {
    if ($textExtensions -notcontains [IO.Path]::GetExtension($relative).ToLowerInvariant()) { continue }
    $path = Join-Path $root ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    $line = Select-String -LiteralPath $path -Pattern '[A-Za-z]:[\\/]+(?:Users|Documents and Settings)[\\/]' | Select-Object -First 1
    if ($line) { "$relative`:$($line.LineNumber)" }
}
if (@($localPaths).Count -gt 0) {
    throw "Personal absolute paths are present:`n$($localPaths -join "`n")"
}

$brokenLinks = foreach ($relative in $presentTracked | Where-Object { $_ -like '*.md' }) {
    $path = Join-Path $root ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    $content = Get-Content -Raw -LiteralPath $path
    foreach ($match in [regex]::Matches($content, '!?(?:\[[^\]]*\])\((?<target>[^)]+)\)')) {
        $target = $match.Groups['target'].Value.Trim().Trim('<', '>')
        if ($target -match '^(?:https?://|mailto:|#)' -or $target -match '^\{') { continue }
        $target = $target.Split('#')[0]
        if ([string]::IsNullOrWhiteSpace($target)) { continue }
        $target = [Uri]::UnescapeDataString($target)
        $resolved = Join-Path (Split-Path -Parent $path) ($target -replace '/', [IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $resolved)) { "$relative -> $target" }
    }
}
if (@($brokenLinks).Count -gt 0) {
    throw "Broken relative Markdown links were found:`n$($brokenLinks -join "`n")"
}

$androidBuild = Get-Content -Raw -LiteralPath (Join-Path $root "android\app\build.gradle")
$androidMatch = [regex]::Match($androidBuild, 'versionName\s*=\s*"([^"]+)"')
if (-not $androidMatch.Success) { throw "Could not read the Android version." }
$versions = @(
    $androidMatch.Groups[1].Value,
    (Get-TunesLinkProjectVersion -Path `
        (Join-Path $root "windows\TuneLinkBridge.WinUI\TuneLinkBridge.WinUI.csproj")),
    (Get-TunesLinkProjectVersion -Path `
        (Join-Path $root "test-support\TuneLinkDemoBridge\TuneLinkDemoBridge.csproj"))
)
if (@($versions | Select-Object -Unique).Count -ne 1) {
    throw "Application versions must match: $($versions -join ', ')"
}
$windowsManifest = Get-Content -Raw -LiteralPath `
    (Join-Path $root "windows\TuneLinkBridge.WinUI\app.manifest")
if ($windowsManifest -notmatch '<dpiAwareness[^>]*>\s*PerMonitorV2(?:,\s*PerMonitor)?\s*</dpiAwareness>') {
    throw "The WinUI application manifest must declare PerMonitorV2 DPI awareness."
}
if ($RequireCleanWorktree) {
    $status = @(& $git.Source -C $root status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect repository status." }
    if ($status.Count -gt 0) {
        throw "The build changed the repository worktree:`n$($status -join "`n")"
    }
}

Write-Host "Repository hygiene check passed." -ForegroundColor Green
