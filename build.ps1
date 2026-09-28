param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$tools = & (Join-Path $PSScriptRoot 'data-collector\scripts\java-tools.ps1') -JdkHome $JdkHome
Push-Location -LiteralPath $PSScriptRoot
try {
    & $tools.Maven "-Dmaven.repo.local=$($tools.Repository)" -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw 'Build or unit tests failed'
    }
} finally {
    Pop-Location
}
