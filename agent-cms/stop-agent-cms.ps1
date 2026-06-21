$ErrorActionPreference = "SilentlyContinue"
$runtime = Join-Path $PSScriptRoot ".runtime"
foreach ($name in @("api.pid", "web.pid")) {
    $file = Join-Path $runtime $name
    if (Test-Path $file) {
        $pidValue = [int](Get-Content -LiteralPath $file)
        Stop-Process -Id $pidValue -Force
        Remove-Item -LiteralPath $file -Force
    }
}
foreach ($port in @(8084, 3002)) {
    $connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($processId in ($connections | Select-Object -ExpandProperty OwningProcess -Unique)) {
        Stop-Process -Id $processId -Force
    }
}
Write-Host "Cosmic Agent CMS stopped."
