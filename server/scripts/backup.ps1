<#
    Packs the worlds, plugins and config files of the server into
    a timestamped archive inside .\backups  (or copies them, -Mode copy).

    Used by backup.bat - you normally do not run it directly.

    Exit codes: 0 = ok, 1 = nothing to back up / failure.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Source,

    [Parameter(Mandatory = $true)]
    [string] $Destination,

    # zip  = Compress-Archive (handy, 2 GB limit per zip)
    # copy = plain folder copy (no size limit, a bit larger)
    [ValidateSet('zip', 'copy')]
    [string] $Mode = 'zip'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$sourceRoot = (Resolve-Path -LiteralPath $Source).Path
if (-not (Test-Path -LiteralPath $Destination)) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
}
$Destination = (Resolve-Path -LiteralPath $Destination).Path
$stamp       = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'

# --- What goes into the backup ------------------------------------------
$items = @()

# worlds: "world", "world_nether", "world_the_end", or a custom level-name
$items += @(Get-ChildItem -LiteralPath $sourceRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'world*' })

# plugins (including their data folders) and the generated Paper configs
foreach ($name in @('plugins', 'config')) {
    $p = Join-Path $sourceRoot $name
    if (Test-Path -LiteralPath $p) { $items += Get-Item -LiteralPath $p }
}

# server settings and access lists
foreach ($name in @('server.properties', 'bukkit.yml', 'spigot.yml', 'permissions.yml',
                    'ops.json', 'whitelist.json', 'banned-players.json', 'banned-ips.json')) {
    $p = Join-Path $sourceRoot $name
    if (Test-Path -LiteralPath $p) { $items += Get-Item -LiteralPath $p }
}

if ($items.Count -eq 0) {
    Write-Host ('[X] Nothing to back up in ' + $sourceRoot)
    Write-Host '    Expected at least a "world" folder - start the server once.'
    exit 1
}

$fileBytes = ($items | Where-Object { -not $_.PSIsContainer } |
              Measure-Object -Property Length -Sum).Sum
if (-not $fileBytes) { $fileBytes = 0 }
$fileSizeMb = [math]::Round($fileBytes / 1MB, 1)
Write-Host ('[backup] Source : ' + $sourceRoot + ' (' + $fileSizeMb + ' MB of loose files)')
Write-Host ('[backup] Items  : ' + $items.Count + ' (' + ($items.Name -join ', ') + ')')

# --- zip or copy --------------------------------------------------------
if ($Mode -eq 'zip') {
    $target = Join-Path $Destination ('backup-' + $stamp + '.zip')
    Write-Host ('[backup] Creating ' + $target)
    try {
        Compress-Archive -Path $items.FullName -DestinationPath $target -CompressionLevel Optimal -Force
    } catch {
        Write-Host ('[X] Archiving failed: ' + $_.Exception.Message)
        Write-Host '    A world bigger than ~2 GB cannot be put into one zip with Compress-Archive.'
        Write-Host '    Run backup.bat again and choose the copy mode, or use -Mode copy:'
        Write-Host '      powershell -File scripts\backup.ps1 -Source . -Destination backups -Mode copy'
        exit 1
    }
} else {
    $target = Join-Path $Destination ('backup-' + $stamp)
    Write-Host ('[backup] Copying into ' + $target)
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    foreach ($item in $items) {
        Copy-Item -LiteralPath $item.FullName -Destination $target -Recurse -Force
    }
}

$sizeMb = [math]::Round(((Get-ChildItem -LiteralPath $target -Recurse -File |
                          Measure-Object -Property Length -Sum).Sum) / 1MB, 1)

Write-Host ('[backup] Done: ' + (Split-Path -Leaf $target) + ' (' + $sizeMb + ' MB)')
Write-Host '[backup] Tip: a backup taken while the server is running may be inconsistent -'
Write-Host '[backup]      run "save-all" (or stop the server) before backing up.'
exit 0
