param(
    [string]$JdkHome,
    [string]$MariaDbHome = $env:MARIADB_HOME
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$tools = & (Join-Path $repositoryRoot 'data-collector\scripts\java-tools.ps1') -JdkHome $JdkHome
& (Join-Path $PSScriptRoot 'with-test-database.ps1') -MariaDbHome $MariaDbHome -Action {
    param($database)

    Push-Location -LiteralPath $repositoryRoot
    try {
        & $tools.Maven "-Dmaven.repo.local=$($tools.Repository)" -B -ntp -Pintegration-tests clean verify
        if ($LASTEXITCODE -ne 0) {
            throw 'Build or MariaDB integration tests failed'
        }
    } finally {
        Pop-Location
    }
}
