param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Archive)) { throw "Archive not found: $Archive" }
if (Test-Path $Target) {
    $existing = Get-ChildItem $Target -Force | Select-Object -First 1
    if ($null -ne $existing) { throw "Target is not empty: $Target (use a fresh directory)" }
}
New-Item -ItemType Directory -Force -Path $Target | Out-Null
& tar -xzf $Archive -C $Target
if ($LASTEXITCODE -ne 0) { throw "tar extraction failed" }

Write-Host "Restored to: $Target"
foreach ($p in @("knowledge/base.json", "data", "data/conversations", "data/memory", "data/notes", "sessions")) {
    $full = Join-Path $Target $p
    if (Test-Path $full) { Write-Host "  OK $p" } else { Write-Warning "Missing $p" }
}
$count = (Get-ChildItem $Target -Recurse -File | Measure-Object).Count
Write-Host "Files: $count"
