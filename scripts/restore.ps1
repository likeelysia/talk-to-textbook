param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Archive)) { throw "Archive not found: $Archive" }
if (-not (Test-Path "$Archive.sha256")) { throw "Checksum file missing: $Archive.sha256" }
if (Test-Path $Target) {
    $existing = Get-ChildItem $Target -Force | Select-Object -First 1
    if ($null -ne $existing) { throw "Target is not empty: $Target (use a fresh directory)" }
}

$expected = ((Get-Content "$Archive.sha256" -TotalCount 1) -split '\s+')[0]
$actual = (Get-FileHash $Archive -Algorithm SHA256).Hash
if ($expected -ne $actual) { throw "Checksum mismatch: expected $expected, actual $actual" }

New-Item -ItemType Directory -Force -Path $Target | Out-Null
& tar -xzf $Archive -C $Target
if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "tar extraction failed: $LASTEXITCODE" }

Write-Host "Restored to: $Target"
foreach ($p in @("knowledge/base.json", "data", "data/conversations", "data/memory", "data/notes", "sessions")) {
    $full = Join-Path $Target $p
    if (Test-Path $full) { Write-Host "  OK $p" } else { Write-Warning "Missing $p" }
}
$count = (Get-ChildItem $Target -Recurse -File | Measure-Object).Count
Write-Host "Files: $count"
