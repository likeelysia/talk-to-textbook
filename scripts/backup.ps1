param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Out = "",
    [Parameter(Mandatory = $true)][string]$StopCommand,
    [Parameter(Mandatory = $true)][string]$StartCommand,
    [int]$StopWaitSeconds = 3,
    [switch]$IncludeDict
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Out)) { $Out = Join-Path $Root "backups" }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$stamp = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$PID"
$archive = Join-Path $Out "talk-to-textbook-$stamp.tar.gz"
$items = @("data", "knowledge", "sessions")
if ($IncludeDict) { $items += "dict" }

$stopped = $false
try {
    Write-Host "Stop writes: $StopCommand"
    Invoke-Expression $StopCommand
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "stop command failed: $LASTEXITCODE" }
    $stopped = $true
    Start-Sleep -Seconds ([Math]::Max(0, $StopWaitSeconds))

    Write-Host "Archive: $Root -> $archive"
    Push-Location $Root
    try {
        & tar -czf $archive @items
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "tar failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    & tar -tzf $archive | Set-Content "$archive.manifest.txt" -Encoding UTF8
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "manifest failed: $LASTEXITCODE" }

    $hash = Get-FileHash $archive -Algorithm SHA256
    "$($hash.Hash)  $archive" | Set-Content "$archive.sha256" -Encoding ASCII
    Write-Host "Archive: $archive"
    Get-Item $archive | Select-Object FullName, Length
} finally {
    if ($stopped) {
        Write-Host "Start service: $StartCommand"
        try { Invoke-Expression $StartCommand } catch { Write-Warning "start command failed: $($_.Exception.Message)" }
    }
}
