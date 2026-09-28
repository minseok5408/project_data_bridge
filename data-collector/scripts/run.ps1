param(
    [ValidateSet('run', 'once', 'read', 'read-once', 'validate', 'status', 'export-config')]
    [string]$Command = 'run',
    [string]$Config,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $Config) {
    $Config = Join-Path $projectRoot 'config\collector.json'
}
$tools = & (Join-Path $PSScriptRoot 'java-tools.ps1') -JdkHome $JdkHome
& (Join-Path $tools.JavaHome 'bin\java.exe') -jar (Join-Path $projectRoot 'target\data-collector.jar') $Command --config $Config
exit $LASTEXITCODE
