[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_]{3,16}$')]
    [string]$Player,

    [string]$HostName = '127.0.0.1',

    [int]$Port = 25575
)

$ErrorActionPreference = 'Stop'

function Read-ExactBytes {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream]$Stream,
        [Parameter(Mandatory = $true)]
        [int]$Count
    )

    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) {
            throw 'RCON connection closed while reading a response.'
        }
        $offset += $read
    }
    return ,$buffer
}

function Send-RconPacket {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream]$Stream,
        [Parameter(Mandatory = $true)]
        [int]$RequestId,
        [Parameter(Mandatory = $true)]
        [int]$PacketType,
        [Parameter(Mandatory = $true)]
        [string]$Body
    )

    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($Body)
    $payload = New-Object byte[] ($bodyBytes.Length + 10)
    [Array]::Copy([BitConverter]::GetBytes([int]$RequestId), 0, $payload, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]$PacketType), 0, $payload, 4, 4)
    [Array]::Copy($bodyBytes, 0, $payload, 8, $bodyBytes.Length)
    $payload[$payload.Length - 2] = 0
    $payload[$payload.Length - 1] = 0

    $lengthBytes = [BitConverter]::GetBytes([int]$payload.Length)
    $Stream.Write($lengthBytes, 0, $lengthBytes.Length)
    $Stream.Write($payload, 0, $payload.Length)
    $Stream.Flush()
}

function Receive-RconPacket {
    param([Parameter(Mandatory = $true)][System.IO.Stream]$Stream)

    $lengthBytes = Read-ExactBytes -Stream $Stream -Count 4
    $length = [BitConverter]::ToInt32($lengthBytes, 0)
    if ($length -lt 10 -or $length -gt 65536) {
        throw "Invalid RCON packet length: $length"
    }
    $packet = Read-ExactBytes -Stream $Stream -Count $length
    $textLength = $length - 10
    $text = if ($textLength -gt 0) {
        [System.Text.Encoding]::ASCII.GetString($packet, 8, $textLength)
    }
    else {
        ''
    }

    return [pscustomobject]@{
        RequestId = [BitConverter]::ToInt32($packet, 0)
        PacketType = [BitConverter]::ToInt32($packet, 4)
        Text = $text
    }
}

$password = [Environment]::GetEnvironmentVariable('RCON_PASSWORD')
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'GitHub Actions secret SERVER_RCON_PASSWORD is missing or empty.'
}

$client = [System.Net.Sockets.TcpClient]::new()
$client.ReceiveTimeout = 5000
$client.SendTimeout = 5000
try {
    $client.Connect($HostName, $Port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 5000
    $stream.WriteTimeout = 5000

    $authId = 101
    Send-RconPacket -Stream $stream -RequestId $authId -PacketType 3 -Body $password
    $authenticated = $false
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $response = Receive-RconPacket -Stream $stream
        if ($response.RequestId -eq -1) {
            throw 'RCON authentication failed. Check the live server.properties password and GitHub Actions secret.'
        }
        if ($response.RequestId -eq $authId -and $response.PacketType -eq 2) {
            $authenticated = $true
            break
        }
    }
    if (-not $authenticated) {
        throw 'RCON did not confirm authentication.'
    }
    Write-Host 'RCON authentication succeeded.'

    $listId = 102
    Send-RconPacket -Stream $stream -RequestId $listId -PacketType 2 -Body 'list'
    $listResponse = Receive-RconPacket -Stream $stream
    if ($listResponse.RequestId -ne $listId) {
        throw 'Unexpected RCON response while checking whether the target player is online.'
    }
    $playerPattern = '(?i)(?<![A-Za-z0-9_])' + [Regex]::Escape($Player) + '(?![A-Za-z0-9_])'
    if ($listResponse.Text -notmatch $playerPattern) {
        throw "Player '$Player' is not online. Join the server, then rerun this workflow."
    }
    Write-Host "Confirmed target player '$Player' is online."

    $commands = @(
        @{ Id = 103; Text = "give $Player minecraft:diamond 10"; Label = 'Give 10 diamonds' },
        @{ Id = 104; Text = "execute at $Player run fill ~-1 ~-1 ~-1 ~1 ~-1 ~1 minecraft:oak_planks"; Label = 'Place a 3x3 oak-plank platform below the player' }
    )
    foreach ($command in $commands) {
        Send-RconPacket -Stream $stream -RequestId $command.Id -PacketType 2 -Body $command.Text
        $result = Receive-RconPacket -Stream $stream
        if ($result.RequestId -ne $command.Id) {
            throw "Unexpected RCON response while running: $($command.Label)."
        }
        Write-Host "$($command.Label): $($result.Text)"
    }

    Write-Host 'RCON test reward completed.'
}
finally {
    if ($null -ne $client) {
        $client.Dispose()
    }
    $password = $null
}
