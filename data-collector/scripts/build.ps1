param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$tools = & (Join-Path $PSScriptRoot 'java-tools.ps1') -JdkHome $JdkHome
Push-Location -LiteralPath $repositoryRoot
try {
    & $tools.Maven "-Dmaven.repo.local=$($tools.Repository)" -B -ntp -pl data-collector -am clean verify
    if ($LASTEXITCODE -ne 0) {
        throw 'Collector build or unit tests failed'
    }
} finally {
    Pop-Location
}
