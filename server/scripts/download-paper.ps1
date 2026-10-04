<#
    Downloads the newest Paper build for a given Minecraft version from
    the official PaperMC API (fill.papermc.io, API v3).

    Used by start.bat and update.bat - you normally do not run it directly.

    Example:
      powershell -NoProfile -ExecutionPolicy Bypass -File download-paper.ps1 `
          -Version 26.3 -Destination C:\server\paper.jar

    Exit codes: 0 = ok (downloaded or already up to date), 1 = failed.
#>
[CmdletBinding()]
param(
    [string] $Version = '26.3',

    [Parameter(Mandatory = $true)]
    [string] $Destination,

    # Re-download even when the installed core is already the latest build.
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$UserAgent    = 'servermmo-windows-server-pack/1.0 (+https://github.com/oshee-g/servermmo)'
$ApiBase      = 'https://fill.papermc.io/v3/projects/paper/versions'
$DownloadPage = 'https://papermc.io/downloads/paper'

function Write-Log([string] $Message) { Write-Host ('[core] ' + $Message) }

$Destination    = [System.IO.Path]::GetFullPath($Destination)
$DestinationDir = Split-Path -Parent $Destination
if (-not (Test-Path -LiteralPath $DestinationDir)) {
    New-Item -ItemType Directory -Path $DestinationDir -Force | Out-Null
}

# --- 1. Ask PaperMC for the latest build --------------------------------
try {
    Write-Log ('Requesting the latest Paper {0} build from PaperMC ...' -f $Version)
    $build = Invoke-RestMethod -Uri ("$ApiBase/$Version/builds/latest") `
                               -Headers @{ 'User-Agent' = $UserAgent } `
                               -TimeoutSec 60
} catch {
    Write-Host ('[X] Could not reach the PaperMC API: ' + $_.Exception.Message)
    Write-Host ('    Manual download: ' + $DownloadPage)
    exit 1
}

$download = $null
if ($build.downloads) {
    $names = @($build.downloads.PSObject.Properties.Name)
    if ($names -contains 'server:default') {
        $download = $build.downloads.'server:default'
    } else {
        foreach ($entry in $build.downloads.PSObject.Properties) {
            if ($entry.Value.name -like '*.jar') { $download = $entry.Value; break }
        }
    }
}
if (-not $download -or -not $download.url) {
    Write-Host ('[X] The API returned no server jar for Paper ' + $Version + '.')
    Write-Host ('    Manual download: ' + $DownloadPage)
    exit 1
}

$buildId = $build.id
if ($build.PSObject.Properties.Name -contains 'build') { $buildId = $build.build }

$sha = ''
if ($download.checksums -and $download.checksums.sha256) { $sha = ([string]$download.checksums.sha256).ToLower() }

$sizeMb = ''
if ($download.size) { $sizeMb = [math]::Round(([double]$download.size) / 1MB, 1) }

Write-Log ('Latest build: {0} [{1}] - {2} ({3} MB)' -f $buildId, $build.channel, $download.name, $sizeMb)

# --- 2. Is the installed core already that build? -----------------------
if ((Test-Path -LiteralPath $Destination) -and -not $Force) {
    $current = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLower()
    if ($sha -and ($current -eq $sha)) {
        Write-Log 'The installed core is already the latest build. Nothing to do.'
        exit 0
    }
    Write-Log 'A different core build is installed - replacing it.'
}

# --- 3. Download to a temporary file ------------------------------------
$temp = "$Destination.download"
if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }

try {
    Write-Log ('Downloading ' + $download.url)
    Invoke-WebRequest -Uri $download.url -OutFile $temp `
                      -Headers @{ 'User-Agent' = $UserAgent } `
                      -TimeoutSec 900 -UseBasicParsing
} catch {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    Write-Host ('[X] Download failed: ' + $_.Exception.Message)
    Write-Host ('    Manual download: ' + $DownloadPage)
    exit 1
}

# --- 4. Verify the checksum --------------------------------------------
if ($sha) {
    $newHash = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLower()
    if ($newHash -ne $sha) {
        Remove-Item -LiteralPath $temp -Force
        Write-Host ('[X] SHA-256 mismatch: expected ' + $sha + ', got ' + $newHash + '. The file was discarded.')
        exit 1
    }
    Write-Log 'SHA-256 verified.'
} else {
    Write-Log '[WARN] The API returned no checksum - skipping verification.'
}

# --- 5. Install it, keeping the previous core --------------------------
if (Test-Path -LiteralPath $Destination) {
    $backup = "$Destination.old"
    if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    Move-Item -LiteralPath $Destination -Destination $backup -Force
    Write-Log ('Previous core kept as ' + (Split-Path -Leaf $backup))
}

Move-Item -LiteralPath $temp -Destination $Destination -Force
Write-Log ('Done: {0} = Paper {1} build {2}.' -f (Split-Path -Leaf $Destination), $Version, $buildId)
exit 0
