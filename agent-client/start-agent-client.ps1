$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFiles = @(
    (Join-Path $root "agent-cms\.env"),
    (Join-Path $PSScriptRoot ".env")
)

foreach ($envFile in $envFiles) {
    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#")) {
                $name, $value = $line -split "=", 2
                if ($name -and $null -ne $value) {
                    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
                }
            }
        }
    }
}

if (-not $env:AGENT_CLIENT_API_PORT) {
    $env:AGENT_CLIENT_API_PORT = "8094"
}
if (-not $env:COSMIC_GAME_HOST) {
    $env:COSMIC_GAME_HOST = "127.0.0.1"
}
if (-not $env:COSMIC_LOGIN_PORT) {
    $env:COSMIC_LOGIN_PORT = "8484"
}
if (-not $env:COSMIC_CHANNEL_PORT_BASE) {
    $env:COSMIC_CHANNEL_PORT_BASE = "7575"
}
if (-not $env:COSMIC_WORLD_PORT_STRIDE) {
    $env:COSMIC_WORLD_PORT_STRIDE = "100"
}
if (-not $env:COSMIC_GAME_CONNECT_TIMEOUT_MILLIS) {
    $env:COSMIC_GAME_CONNECT_TIMEOUT_MILLIS = "1500"
}
foreach ($name in @(
    "AGENT_CLIENT_SEND_LOGIN_PACKETS",
    "AGENT_CLIENT_SEND_MOVEMENT_PACKETS",
    "AGENT_CLIENT_SEND_NPC_PACKETS",
    "AGENT_CLIENT_SEND_LOOT_PACKETS",
    "AGENT_CLIENT_SEND_SOCIAL_PACKETS",
    "AGENT_CLIENT_SEND_INVENTORY_PACKETS",
    "AGENT_CLIENT_SEND_COMBAT_PACKETS"
)) {
    if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
        [Environment]::SetEnvironmentVariable($name, "true", "Process")
    }
}
if (-not $env:AGENT_CLIENT_DB_URL -and $env:AGENT_CMS_DB_URL) {
    $env:AGENT_CLIENT_DB_URL = $env:AGENT_CMS_DB_URL
}
if (-not $env:AGENT_CLIENT_DB_URL) {
    $env:AGENT_CLIENT_DB_URL = "jdbc:mysql://localhost:3306/cosmic_agent_cms?createDatabaseIfNotExist=true"
}
if (-not $env:AGENT_CLIENT_DB_USER -and $env:AGENT_CMS_DB_USER) {
    $env:AGENT_CLIENT_DB_USER = $env:AGENT_CMS_DB_USER
}
if (-not $env:AGENT_CLIENT_DB_USER) {
    $env:AGENT_CLIENT_DB_USER = "root"
}
if (-not $env:AGENT_CLIENT_DB_PASSWORD -and $env:AGENT_CMS_DB_PASSWORD) {
    $env:AGENT_CLIENT_DB_PASSWORD = $env:AGENT_CMS_DB_PASSWORD
}

$runtime = Join-Path $PSScriptRoot ".runtime"
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

$api = Join-Path $PSScriptRoot "api"
& (Join-Path $root "mvnw.cmd") -q -f (Join-Path $api "pom.xml") package
if ($LASTEXITCODE -ne 0) {
    throw "Agent Client API build failed."
}

$javaArgs = @(
    "-Dserver.port=$env:AGENT_CLIENT_API_PORT",
    "-Dspring.datasource.url=$env:AGENT_CLIENT_DB_URL",
    "-Dspring.datasource.username=$env:AGENT_CLIENT_DB_USER",
    "-Dspring.datasource.password=$env:AGENT_CLIENT_DB_PASSWORD",
    "-Dcosmic.game.host=$env:COSMIC_GAME_HOST",
    "-Dcosmic.game.login-port=$env:COSMIC_LOGIN_PORT",
    "-Dcosmic.game.channel-port-base=$env:COSMIC_CHANNEL_PORT_BASE",
    "-Dcosmic.game.world-port-stride=$env:COSMIC_WORLD_PORT_STRIDE",
    "-Dcosmic.game.connect-timeout-millis=$env:COSMIC_GAME_CONNECT_TIMEOUT_MILLIS",
    "-Dcosmic.agent-runtime.send-login-packets=$env:AGENT_CLIENT_SEND_LOGIN_PACKETS",
    "-Dcosmic.agent-runtime.send-movement-packets=$env:AGENT_CLIENT_SEND_MOVEMENT_PACKETS",
    "-Dcosmic.agent-runtime.send-npc-packets=$env:AGENT_CLIENT_SEND_NPC_PACKETS",
    "-Dcosmic.agent-runtime.send-loot-packets=$env:AGENT_CLIENT_SEND_LOOT_PACKETS",
    "-Dcosmic.agent-runtime.send-social-packets=$env:AGENT_CLIENT_SEND_SOCIAL_PACKETS",
    "-Dcosmic.agent-runtime.send-inventory-packets=$env:AGENT_CLIENT_SEND_INVENTORY_PACKETS",
    "-Dcosmic.agent-runtime.send-combat-packets=$env:AGENT_CLIENT_SEND_COMBAT_PACKETS",
    "-jar",
    (Join-Path $api "target\cosmic-agent-client-api-0.1.0-SNAPSHOT.jar")
)

$apiProcess = Start-Process -FilePath "java" -WindowStyle Hidden -WorkingDirectory $api -PassThru `
    -ArgumentList $javaArgs `
    -RedirectStandardOutput (Join-Path $runtime "api.log") `
    -RedirectStandardError (Join-Path $runtime "api-error.log")
Set-Content -LiteralPath (Join-Path $runtime "api.pid") -Value $apiProcess.Id

Write-Host "Cosmic Agent Client is starting:"
Write-Host "  API: http://localhost:$env:AGENT_CLIENT_API_PORT"
Write-Host "  Logs: $runtime"
