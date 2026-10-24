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
$archiveError = $null
$recoveryError = $null
$status = 0

try {
    Write-Host "Stop writes: $StopCommand"
    $global:LASTEXITCODE = 0
    Invoke-Expression $StopCommand
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "stop command failed: $LASTEXITCODE" }
    $stopped = $true
    Start-Sleep -Seconds ([Math]::Max(0, $StopWaitSeconds))

    Write-Host "Archive: $Root -> $archive"
    Push-Location $Root
    try {
        $global:LASTEXITCODE = 0
        & tar -czf $archive @items
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "tar failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    $global:LASTEXITCODE = 0
    & tar -tzf $archive | Set-Content "$archive.manifest.txt" -Encoding UTF8
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "manifest failed: $LASTEXITCODE" }

    $hash = Get-FileHash $archive -Algorithm SHA256
    "$($hash.Hash)  $(Split-Path -Leaf $archive)" | Set-Content "$archive.sha256" -Encoding ASCII
    Write-Host "Archive: $archive"
    Get-Item $archive | Select-Object FullName, Length
} catch {
    $message = $_.Exception.Message
    if ($stopped) {
        $archiveError = "archive failed - $message"
        $status = 5
    } else {
        $archiveError = "stop failed - $message"
        $status = 3
    }
} finally {
    if ($stopped) {
        Write-Host "Start service: $StartCommand"
        try {
            $global:LASTEXITCODE = 0
            Invoke-Expression $StartCommand
            if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
                throw "start command failed: $LASTEXITCODE"
            }
            Write-Host "Service restore: OK"
        } catch {
            $recoveryError = $_.Exception.Message
            if ($status -eq 0) { $status = 6 }
        }
    }
}

# 两类错误都输出,不让恢复错误覆盖归档错误
if ($null -ne $archiveError) {
    [Console]::Error.WriteLine("ERROR: $archiveError")
}
if ($null -ne $recoveryError) {
    [Console]::Error.WriteLine("ERROR: service restore failed - $recoveryError")
    [Console]::Error.WriteLine("MANUAL: check service status; restore command: $StartCommand")
}
if ($status -ne 0) {
    [Console]::Error.WriteLine("BACKUP NOT FULLY SUCCESSFUL. Archive (if created) is kept: $archive")
    exit $status
}
