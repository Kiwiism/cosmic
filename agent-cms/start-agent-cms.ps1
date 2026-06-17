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

& (Join-Path $root "mvnw.cmd") -q -f (Join-Path $api "pom.xml") package
if ($LASTEXITCODE -ne 0) {
    throw "Agent CMS API build failed."
}

$apiProcess = Start-Process -FilePath "java" -WindowStyle Hidden -WorkingDirectory $api -PassThru `
    -ArgumentList "-jar", (Join-Path $api "target\cosmic-agent-cms-api-0.1.0-SNAPSHOT.jar") `
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
Write-Host "  Logs: $runtime"
