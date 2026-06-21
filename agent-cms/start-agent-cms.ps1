$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot ".env"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $name, $value = $line -split "=", 2
            [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
        }
    }
}

$runtime = Join-Path $PSScriptRoot ".runtime"
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

$api = Join-Path $PSScriptRoot "api"
$web = Join-Path $PSScriptRoot "web"
$agentNodeModules = Join-Path $web "node_modules"
$serverNodeModules = Join-Path $root "server-cms\web\node_modules"
if (-not (Test-Path -LiteralPath $agentNodeModules) -and (Test-Path -LiteralPath $serverNodeModules)) {
    New-Item -ItemType Junction -Path $agentNodeModules -Target $serverNodeModules | Out-Null
}

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    $nodeExecutable = $node.Source
} else {
    $nodeExecutable = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
}
if (-not (Test-Path -LiteralPath $nodeExecutable)) {
    throw "Node.js is required to run the Agent CMS web application."
}
$next = Join-Path $web "node_modules\next\dist\bin\next"
if (-not (Test-Path -LiteralPath $next)) {
    throw "Agent CMS web dependencies are missing. Install them in agent-cms/web first, or install Server CMS web dependencies so this script can reuse them."
}
if (-not $env:AGENT_CLIENT_API_URL) {
    $env:AGENT_CLIENT_API_URL = "http://127.0.0.1:8094"
}

& (Join-Path $root "mvnw.cmd") -q -f (Join-Path $api "pom.xml") package
if ($LASTEXITCODE -ne 0) {
    throw "Agent CMS API build failed."
}

$javaArgs = @(
    "-Dspring.datasource.url=$env:AGENT_CMS_DB_URL",
    "-Dspring.datasource.username=$env:AGENT_CMS_DB_USER",
    "-Dspring.datasource.password=$env:AGENT_CMS_DB_PASSWORD",
    "-Dcosmic.game-database.url=$env:COSMIC_DB_URL",
    "-Dcosmic.game-database.username=$env:COSMIC_DB_USER",
    "-Dcosmic.game-database.password=$env:COSMIC_DB_PASSWORD",
    "-Dcosmic.agent-client.url=$env:AGENT_CLIENT_API_URL",
    "-Dcosmic.allowed-origin=$env:AGENT_CMS_ALLOWED_ORIGIN",
    "-jar",
    (Join-Path $api "target\cosmic-agent-cms-api-0.1.0-SNAPSHOT.jar")
)
$apiProcess = Start-Process -FilePath "java" -WindowStyle Hidden -WorkingDirectory $api -PassThru `
    -ArgumentList $javaArgs `
    -RedirectStandardOutput (Join-Path $runtime "api.log") `
    -RedirectStandardError (Join-Path $runtime "api-error.log")
$webProcess = Start-Process -FilePath $nodeExecutable -WindowStyle Hidden -WorkingDirectory $web -PassThru `
    -ArgumentList $next, "dev", "-p", "3002", "--webpack" `
    -RedirectStandardOutput (Join-Path $runtime "web.log") `
    -RedirectStandardError (Join-Path $runtime "web-error.log")
Set-Content -LiteralPath (Join-Path $runtime "api.pid") -Value $apiProcess.Id
Set-Content -LiteralPath (Join-Path $runtime "web.pid") -Value $webProcess.Id

Write-Host "Cosmic Agent CMS is starting:"
Write-Host "  Web: http://localhost:3002"
Write-Host "  API: http://localhost:8084"
Write-Host "  Agent Client API: $env:AGENT_CLIENT_API_URL"
Write-Host "  Logs: $runtime"
