$ErrorActionPreference = "Stop"

$databaseCmsRoot = $PSScriptRoot
$projectRoot = Split-Path $databaseCmsRoot -Parent
$envFile = Join-Path $databaseCmsRoot ".env"
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue

if (-not $nodeCommand) {
    $bundledNode = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
    if (Test-Path -LiteralPath $bundledNode) {
        $nodeExecutable = $bundledNode
    } else {
        throw "Node.js 22+ is required to build and run the Database CMS web application."
    }
} else {
    $nodeExecutable = $nodeCommand.Source
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $databaseCmsRoot ".env.example") -Destination $envFile
    throw "Created database-cms/.env. Add the MySQL password, then run this script again."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], "Process")
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:DATABASE_CMS_DB_PASSWORD) -or
    [string]::IsNullOrWhiteSpace($env:GAME_DB_PASSWORD)) {
    throw "DATABASE_CMS_DB_PASSWORD and GAME_DB_PASSWORD must be set in database-cms/.env."
}

& (Join-Path $projectRoot "mvnw.cmd") -q -f (Join-Path $databaseCmsRoot "api/pom.xml") package
if ($LASTEXITCODE -ne 0) {
    throw "Database CMS API build failed."
}

Push-Location (Join-Path $databaseCmsRoot "web")
try {
    if ($npmCommand) {
        & $npmCommand.Source install
        if ($LASTEXITCODE -ne 0) {
            throw "Database CMS web dependency installation failed."
        }
        & $npmCommand.Source run build
    } elseif (Test-Path -LiteralPath "node_modules\next\dist\bin\next") {
        & $nodeExecutable "node_modules\next\dist\bin\next" build
    } else {
        throw "npm is unavailable and database-cms/web/node_modules has not been installed."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Database CMS web build failed."
    }
} finally {
    Pop-Location
}

$logRoot = Join-Path $databaseCmsRoot ".runtime"
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

$api = Start-Process -FilePath "java" `
    -ArgumentList "-jar", (Join-Path $databaseCmsRoot "api/target/cosmic-database-cms-api-0.1.0-SNAPSHOT.jar") `
    -WorkingDirectory (Join-Path $databaseCmsRoot "api") -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logRoot "api.log") `
    -RedirectStandardError (Join-Path $logRoot "api-error.log")

$webRoot = Join-Path $databaseCmsRoot "web"
$standaloneRoot = Join-Path $webRoot ".next/standalone"
Copy-Item -Path (Join-Path $webRoot ".next/static") -Destination (Join-Path $standaloneRoot ".next") `
    -Recurse -Force

$web = Start-Process -FilePath $nodeExecutable -ArgumentList (Join-Path $standaloneRoot "server.js") `
    -WorkingDirectory $webRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logRoot "web.log") `
    -RedirectStandardError (Join-Path $logRoot "web-error.log")

Set-Content -LiteralPath (Join-Path $logRoot "api.pid") -Value $api.Id
Set-Content -LiteralPath (Join-Path $logRoot "web.pid") -Value $web.Id

Write-Host "Cosmic Database CMS is starting at http://localhost:3000"
Write-Host "Runtime logs are stored in database-cms/.runtime."
