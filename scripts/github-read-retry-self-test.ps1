$ErrorActionPreference = "Stop"
$WarningPreference = "SilentlyContinue"
$PSNativeCommandUseErrorActionPreference = $true
Import-Module (Join-Path $PSScriptRoot "GitHubReadRetry.psm1") -Force

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "GitHub read retry self-test failed: $Message" }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern)
    try { & $Action | Out-Null }
    catch {
        Assert-True ($_.Exception.Message -match $Pattern) "unexpected failure: $($_.Exception.Message)"
        return
    }
    throw "GitHub read retry self-test failed: expected failure matching $Pattern"
}

function Set-Responses {
    param([object[]]$Responses)
    $script:responses = $Responses
    $script:calls = @()
    $script:delays = @()
}

# Mock only in this standalone test process; never contact GitHub or wait for backoff.
function global:gh {
    $response = $script:responses[$script:calls.Count]
    $script:calls += ,@($args)
    $global:LASTEXITCODE = $response.Code
    if ($response.Error) { Write-Error $response.Error -ErrorAction Continue }
    if ($response.Output) { Write-Output $response.Output }
}

function global:Start-Sleep {
    param([int]$Seconds)
    $script:delays += $Seconds
}

try {
    Set-Responses @(
        @{ Code = 1; Error = 'HTTP 503 Service Unavailable' },
        @{ Code = 1; Error = 'non-200 OK status code: 504 Gateway Timeout' },
        @{ Code = 0; Output = '[{"tagName":"v1.2.3"}]' }
    )
    $release = Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'list') `
        -FailureMessage 'Could not list previous releases' | ConvertFrom-Json
    Assert-True ($release.tagName -eq 'v1.2.3' -and $script:calls.Count -eq 3) "recover from 503 and 504"
    Assert-True (($script:delays -join ',') -eq '5,10') "exponential backoff"

    Set-Responses @(@{ Code = 1; Error = 'HTTP 401 Unauthorized' })
    Assert-Throws { Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'list') `
        -FailureMessage 'Could not list previous releases' } '401 Unauthorized'
    Assert-True ($script:calls.Count -eq 1 -and $script:delays.Count -eq 0) "authentication errors fail immediately"

    Set-Responses @(1..4 | ForEach-Object { @{ Code = 1; Error = 'HTTP 504 Gateway Timeout' } })
    Assert-Throws { Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'list') `
        -FailureMessage 'Could not list previous releases' } 'attempt 4/4'
    Assert-True ($script:calls.Count -eq 4 -and ($script:delays -join ',') -eq '5,10,20') "persistent outages still fail validation"

    Set-Responses @(
        @{ Code = 1; Error = 'unexpected EOF' },
        @{ Code = 0; Output = 'download complete' }
    )
    Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'download', 'v1.2.3', '--clobber') `
        -FailureMessage 'Could not download previous APK' | Out-Null
    Assert-True ($script:calls.Count -eq 2 -and $script:calls[1][-1] -eq '--clobber') "download retry preserves partial-file replacement"

    Set-Responses @(@{ Code = 0; Error = 'A CLI warning'; Output = '[]' })
    $result = Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'list') `
        -FailureMessage 'Could not list previous releases'
    Assert-True ($result -eq '[]' -and $script:calls.Count -eq 1) "stderr cannot corrupt successful JSON"

    Set-Responses @()
    Assert-Throws { Invoke-TunesLinkGitHubRead -ArgumentList @('release', 'upload') `
        -FailureMessage 'Should not run' } 'Only GitHub release listing and downloads'
    Assert-True ($script:calls.Count -eq 0) "release writes cannot be retried"
}
finally {
    Remove-Item Function:\gh, Function:\Start-Sleep
}

Write-Host "GitHub read retry self-test passed." -ForegroundColor Green
