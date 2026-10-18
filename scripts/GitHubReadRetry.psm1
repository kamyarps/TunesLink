Set-StrictMode -Version 2

function Invoke-TunesLinkGitHubRead {
    param(
        [Parameter(Mandatory)] [string[]]$ArgumentList,
        [Parameter(Mandatory)] [string]$FailureMessage,
        [ValidateRange(1, 4)] [int]$Attempts = 4,
        [ValidateRange(0, 15)] [int]$RetryDelaySeconds = 5
    )

    # Retrying a read or download cannot publish or replace a GitHub release.
    if ($ArgumentList.Count -lt 2 -or
        ($ArgumentList[0..1] -join ' ') -notin @('release list', 'release download', 'run download')) {
        throw "Only GitHub release listing and downloads can be retried."
    }

    # Inspect native failures ourselves, even when the caller opts into native errors.
    $PSNativeCommandUseErrorActionPreference = $false
    $stderrPath = Join-Path ([IO.Path]::GetTempPath()) ("TunesLink-gh-" + [guid]::NewGuid() + ".stderr")
    try {
        for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
            $output = @(& gh @ArgumentList 2> $stderrPath)
            $exitCode = $LASTEXITCODE
            if ($exitCode -eq 0) { return ($output -join [Environment]::NewLine) }

            $diagnostic = [IO.File]::ReadAllText($stderrPath).Trim()
            if ([string]::IsNullOrWhiteSpace($diagnostic)) { $diagnostic = $output -join ' ' }
            $transient = $diagnostic -match
                '(?i)\b(?:429|5\d{2})\b|timeout|timed out|unexpected EOF|connection (?:reset|refused)|temporary failure|TLS handshake'
            # GitHub's gateway errors can include an entire HTML error page.
            $summary = $diagnostic.Substring(0, [Math]::Min(400, $diagnostic.Length))
            if (-not $transient -or $attempt -eq $Attempts) {
                throw "$FailureMessage (exit $exitCode, attempt $attempt/$Attempts): $summary"
            }

            $delay = $RetryDelaySeconds * [int][Math]::Pow(2, $attempt - 1)
            Write-Warning "$FailureMessage; retrying in ${delay}s ($attempt/$Attempts): $summary"
            Start-Sleep -Seconds $delay
        }
    }
    finally {
        if (Test-Path -LiteralPath $stderrPath) { Remove-Item -LiteralPath $stderrPath -Force }
    }
}

Export-ModuleMember -Function Invoke-TunesLinkGitHubRead
