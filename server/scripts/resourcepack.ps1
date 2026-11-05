# =====================================================================
#  ServerMMO - writes resource-pack settings into server.properties
#
#  Computes the real SHA-1 of resourcepack\servermmo-hud.zip and sets:
#     resource-pack=
#     resource-pack-sha1=
#     resource-pack-prompt=
#     require-resource-pack=
#
#  Called by setup-resourcepack.bat. Can also be used directly:
#     powershell -File scripts\resourcepack.ps1 -Url http://1.2.3.4:8080/servermmo-hud.zip
#
#  The file stays ASCII on purpose (Windows code pages).
# =====================================================================
[CmdletBinding()]
param(
    [string]$Url = "",
    [int]$Port = 8080,
    [switch]$Require
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$pack = Join-Path $root 'resourcepack\servermmo-hud.zip'
$propsPath = Join-Path $root 'server.properties'

if (-not (Test-Path -LiteralPath $pack)) {
    Write-Host "[ERROR] Resource pack not found: $pack"
    exit 2
}
if (-not (Test-Path -LiteralPath $propsPath)) {
    Write-Host "[ERROR] server.properties not found: $propsPath"
    exit 2
}

$hash = (Get-FileHash -LiteralPath $pack -Algorithm SHA1).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $pack).Length

function Get-LanAddress {
    try {
        $found = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.PrefixOrigin -ne 'WellKnown'
            } |
            Sort-Object -Property InterfaceMetric
        if ($found) { return ($found | Select-Object -First 1).IPAddress }
    } catch {
        # older Windows without the NetTCPIP module - fall back to DNS
    }
    try {
        $addresses = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName())
        $v4 = $addresses |
            Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
            Select-Object -First 1
        if ($v4) { return $v4.IPAddressToString }
    } catch {
        # offline machine
    }
    return '127.0.0.1'
}

if ([string]::IsNullOrWhiteSpace($Url)) {
    $lan = Get-LanAddress
    $suggested = "http://${lan}:${Port}/servermmo-hud.zip"

    Write-Host "=============================================================="
    Write-Host "  ServerMMO HUD - resource pack URL"
    Write-Host "=============================================================="
    Write-Host "  file: $pack"
    Write-Host "  size: $size bytes"
    Write-Host "  sha1: $hash"
    Write-Host ""
    Write-Host "  The link must be reachable from the players' computers:"
    Write-Host "    LAN (same Wi-Fi):  $suggested"
    Write-Host "    Internet:          http://<public-ip>:$Port/servermmo-hud.zip"
    Write-Host "                       (needs port $Port forwarded on the router)"
    Write-Host "    Web hosting:       https://your-site.example/servermmo-hud.zip"
    Write-Host ""
    $answer = Read-Host "URL (press Enter to use $suggested)"
    if ([string]::IsNullOrWhiteSpace($answer)) {
        $Url = $suggested
    } else {
        $Url = $answer.Trim()
    }
}

if ($Url -notmatch '^https?://') {
    Write-Host "[ERROR] The URL must start with http:// or https://"
    Write-Host "        Got: $Url"
    exit 3
}
if ($Url -notmatch '\.zip$') {
    Write-Host "[WARN] The URL does not end with .zip - Minecraft may refuse it."
}

$requireValue = if ($Require) { 'true' } else { 'false' }
$promptValue = '{"text":"ServerMMO HUD resource pack","color":"gold"}'

# ISO-8859-1 (28591) round-trips every byte, so the rest of the file
# (including any non-ASCII motd) is never damaged.
$encoding = [System.Text.Encoding]::GetEncoding(28591)
$text = [System.IO.File]::ReadAllText($propsPath, $encoding)

function Set-Property {
    param(
        [string]$Content,
        [string]$Key,
        [string]$Value,
        [switch]$OnlyIfEmpty
    )

    $pattern = '(?m)^' + [regex]::Escape($Key) + '=.*$'
    $line = $Key + '=' + $Value
    $hasKey = [regex]::IsMatch($Content, $pattern)

    if ($hasKey) {
        if ($OnlyIfEmpty) {
            $current = [regex]::Match($Content, $pattern).Value
            $currentValue = $current.Substring($Key.Length + 1).Trim()
            if (-not [string]::IsNullOrWhiteSpace($currentValue)) {
                return $Content
            }
        }
        return [regex]::Replace($Content, $pattern, { param($match) $line }, 1)
    }

    $newline = if ($Content -match "`r`n") { "`r`n" } else { "`n" }
    if (-not $Content.EndsWith($newline)) { $Content += $newline }
    return $Content + $line + $newline
}

$updated = $text
$updated = Set-Property -Content $updated -Key 'resource-pack' -Value $Url
$updated = Set-Property -Content $updated -Key 'resource-pack-sha1' -Value $hash
$updated = Set-Property -Content $updated -Key 'resource-pack-prompt' -Value $promptValue -OnlyIfEmpty
$updated = Set-Property -Content $updated -Key 'require-resource-pack' -Value $requireValue

$backup = $propsPath + '.bak'
Copy-Item -LiteralPath $propsPath -Destination $backup -Force
[System.IO.File]::WriteAllText($propsPath, $updated, $encoding)

Write-Host ""
Write-Host "=============================================================="
Write-Host "  server.properties updated (backup: server.properties.bak)"
Write-Host "=============================================================="
Write-Host "  resource-pack=$Url"
Write-Host "  resource-pack-sha1=$hash"
Write-Host "  require-resource-pack=$requireValue"
Write-Host ""
if ($Url -match '://(127\.|localhost)') {
    Write-Host "  [WARN] This address only works on your own PC."
    Write-Host "         Players need your LAN IP or a public address."
    Write-Host ""
}
Write-Host "  Next:"
Write-Host "    1) restart the Minecraft server (settings are read at start)"
Write-Host "    2) join and accept the pack when the game offers it"
Write-Host "    3) if nothing happens, open the URL in a browser on another"
Write-Host "       device - it must download servermmo-hud.zip"
Write-Host ""
Write-Host "  require-resource-pack=true kicks players who decline the pack."
Write-Host "  Keep it false if you want them to be able to play without the HUD."
Write-Host ""

exit 0
