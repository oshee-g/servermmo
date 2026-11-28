[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerRoot,

    [Parameter(Mandatory = $true)]
    [string]$ServiceName,

    [Parameter(Mandatory = $true)]
    [string]$SourceRoot
)

$ErrorActionPreference = 'Stop'
$Latin1Encoding = [System.Text.Encoding]::GetEncoding(28591)

$ServerRoot = [System.IO.Path]::GetFullPath($ServerRoot)
$SourceRoot = [System.IO.Path]::GetFullPath($SourceRoot)
$SourcePlugins = Join-Path $SourceRoot 'server\plugins'
$SourcePack = Join-Path $SourceRoot 'server\resourcepack\servermmo-hud.zip'
$SourceProperties = Join-Path $SourceRoot 'server\server.properties'
$TargetPlugins = Join-Path $ServerRoot 'plugins'
$TargetPackDirectory = Join-Path $ServerRoot 'resourcepack'
$TargetPack = Join-Path $TargetPackDirectory 'servermmo-hud.zip'
$TargetProperties = Join-Path $ServerRoot 'server.properties'
$PluginNames = @(
    'servermmo-core-1.0.0.jar',
    'servermmo-mobs-1.0.0.jar',
    'servermmo-players-1.0.0.jar',
    'servermmo-hud-1.0.0.jar'
)
$ManagedPropertyNames = @(
    'resource-pack',
    'resource-pack-sha1',
    'resource-pack-id',
    'resource-pack-prompt',
    'require-resource-pack'
)

foreach ($path in @($SourcePlugins, $SourcePack, $SourceProperties, $TargetProperties)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -and
        -not (Test-Path -LiteralPath $path -PathType Container)) {
        throw "Required deployment path not found: $path"
    }
}

$SourcePropertiesLines = [System.IO.File]::ReadAllLines(
    $SourceProperties, $Latin1Encoding)
$SourcePropertiesMap = @{}
foreach ($line in $SourcePropertiesLines) {
    if ($line -match '^\s*([^#\s][^=]*)=(.*)$') {
        $SourcePropertiesMap[$Matches[1].Trim()] = $Matches[2]
    }
}
foreach ($name in $ManagedPropertyNames) {
    if (-not $SourcePropertiesMap.ContainsKey($name)) {
        throw "server/server.properties is missing managed key '$name'."
    }
}

$ExpectedSha1 = $SourcePropertiesMap['resource-pack-sha1'].ToLowerInvariant()
$ActualSha1 = (Get-FileHash -LiteralPath $SourcePack -Algorithm SHA1).Hash.ToLowerInvariant()
if ($ActualSha1 -ne $ExpectedSha1) {
    throw "Resource-pack SHA-1 mismatch: server.properties has $ExpectedSha1, archive is $ActualSha1."
}

foreach ($name in $PluginNames) {
    $sourceJar = Join-Path $SourcePlugins $name
    if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
        throw "Built plugin JAR is missing: $sourceJar"
    }
}

$service = Get-Service -Name $ServiceName -ErrorAction Stop
Write-Host "Deploying ServerMMO files to '$ServerRoot' (service '$ServiceName')."
Write-Host "Verified resource-pack SHA-1: $ActualSha1"

try {
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $ServiceName -ErrorAction Stop
        $service.WaitForStatus(
            [System.ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(90))
    }

    New-Item -ItemType Directory -Force -Path $TargetPlugins, $TargetPackDirectory | Out-Null
    foreach ($name in $PluginNames) {
        Copy-Item -LiteralPath (Join-Path $SourcePlugins $name) `
            -Destination (Join-Path $TargetPlugins $name) -Force
    }
    Copy-Item -LiteralPath $SourcePack -Destination $TargetPack -Force

    # Merge only resource-pack settings; retain the live server's port,
    # online-mode, world, whitelist, and other machine-specific settings.
    $targetLines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $TargetProperties -PathType Leaf) {
        foreach ($line in [System.IO.File]::ReadAllLines(
                $TargetProperties, $Latin1Encoding)) {
            $targetLines.Add($line)
        }
    }
    foreach ($name in $ManagedPropertyNames) {
        $escapedName = [Regex]::Escape($name)
        $replacement = "$name=$($SourcePropertiesMap[$name])"
        $found = $false
        for ($i = 0; $i -lt $targetLines.Count; $i++) {
            if ($targetLines[$i] -match "^\s*$escapedName\s*=") {
                $targetLines[$i] = $replacement
                $found = $true
            }
        }
        if (-not $found) {
            $targetLines.Add($replacement)
        }
    }
    [System.IO.File]::WriteAllLines(
        $TargetProperties, $targetLines.ToArray(), $Latin1Encoding)

    Start-Service -Name $ServiceName -ErrorAction Stop
    $service = Get-Service -Name $ServiceName
    $service.WaitForStatus(
        [System.ServiceProcess.ServiceControllerStatus]::Running,
        [TimeSpan]::FromSeconds(60))
    Write-Host 'Deployment complete; the Paper service is running.'
}
catch {
    # Do not leave an existing server offline if a copy or config step fails.
    try {
        $service = Get-Service -Name $ServiceName -ErrorAction Stop
        if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
            Start-Service -Name $ServiceName -ErrorAction Stop
            $service = Get-Service -Name $ServiceName
            $service.WaitForStatus(
                [System.ServiceProcess.ServiceControllerStatus]::Running,
                [TimeSpan]::FromSeconds(60))
        }
    }
    catch {
        Write-Warning "Automatic recovery could not start service '$ServiceName': $_"
    }
    throw
}
