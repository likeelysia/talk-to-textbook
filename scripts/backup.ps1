param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Out = "",
    [switch]$IncludeDict
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Out)) { $Out = Join-Path $Root "backups" }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archive = Join-Path $Out "talk-to-textbook-$stamp.tar.gz"
$items = @("data", "knowledge", "sessions")
if ($IncludeDict) { $items += "dict" }

Write-Host "Backup root: $Root"
Write-Host "Items: $($items -join ', ')"
Push-Location $Root
try {
    & tar -czf $archive @items
} finally {
    Pop-Location
}

$hash = Get-FileHash $archive -Algorithm SHA256
"$($hash.Hash)  $archive" | Set-Content "$archive.sha256" -Encoding ASCII
Write-Host "Archive: $archive"
Get-Item $archive | Select-Object FullName, Length
