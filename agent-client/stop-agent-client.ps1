$ErrorActionPreference = "SilentlyContinue"
$runtime = Join-Path $PSScriptRoot ".runtime"
$pidFile = Join-Path $runtime "api.pid"
if (Test-Path $pidFile) {
    $pidValue = [int](Get-Content -LiteralPath $pidFile)
    Stop-Process -Id $pidValue -Force
    Remove-Item -LiteralPath $pidFile -Force
    Write-Host "Stopped agent-client process $pidValue"
}

$port = 8094
$connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
foreach ($processId in ($connections | Select-Object -ExpandProperty OwningProcess -Unique)) {
    Stop-Process -Id $processId -Force
    Write-Host "Stopped agent-client process $processId on port $port"
}

if (-not (Test-Path $pidFile) -and -not $connections) {
    Write-Host "agent-client is not running."
}
