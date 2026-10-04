<#
    Checks that the Java found by start.bat is new enough for the server.

    Exit codes:
      0 = Java is fine (or the version could not be parsed)
      2 = Java is installed but older than -MinMajor
      3 = Java was not found at all
#>
[CmdletBinding()]
param(
    [string] $JavaPath = 'java',
    [int]    $MinMajor = 25
)

$ErrorActionPreference = 'SilentlyContinue'

function Resolve-JavaExe([string] $Candidate) {
    if ($Candidate -and (Test-Path -LiteralPath $Candidate)) {
        return (Resolve-Path -LiteralPath $Candidate).Path
    }
    foreach ($name in @($Candidate, 'java')) {
        if (-not $name) { continue }
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) {
            if ($cmd.PSObject.Properties.Name -contains 'Source' -and $cmd.Source) { return $cmd.Source }
            if ($cmd.PSObject.Properties.Name -contains 'Path'   -and $cmd.Path)   { return $cmd.Path }
            if ($cmd.PSObject.Properties.Name -contains 'Definition')             { return $cmd.Definition }
        }
    }
    return $null
}

$exe = Resolve-JavaExe $JavaPath
if (-not $exe) {
    Write-Host ('[X] Java was not found (looked for: ' + $JavaPath + ' and "java" on PATH).')
    exit 3
}

try {
    $raw = (& $exe -version 2>&1 | Out-String)
} catch {
    Write-Host ('[X] Could not run "' + $exe + '": ' + $_.Exception.Message)
    exit 3
}

$major = $null
if ($raw -match 'version "(\d+)(?:\.(\d+))?') {
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }   # old "1.8.0_402" style
}

Write-Host ('      java.exe: ' + $exe)
foreach ($line in ($raw -split "`r?`n")) {
    if ($line.Trim().Length -gt 0) { Write-Host ('      ' + $line.Trim()) }
}

if ($null -eq $major) {
    Write-Host '      [WARN] Could not read the Java version - continuing anyway.'
    exit 0
}

if ($major -lt $MinMajor) {
    Write-Host ('[X] Java ' + $major + ' is older than the required Java ' + $MinMajor + '.')
    exit 2
}

Write-Host ('      OK: Java ' + $major + '.')
exit 0
