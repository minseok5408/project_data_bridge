param(
    [ValidateSet('run', 'validate', 'export-schema')]
    [string]$Command = 'run',
    [string]$Config,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$collectorRoot = Join-Path (Split-Path $projectRoot -Parent) 'data-collector'
if (-not $Config) {
    $Config = Join-Path $projectRoot 'src\main\resources\application.properties'
}
$tools = & (Join-Path $collectorRoot 'scripts\java-tools.ps1') -JdkHome $JdkHome
& (Join-Path $tools.JavaHome 'bin\java.exe') -jar (Join-Path $projectRoot 'target\data-hub.jar') $Command --config $Config
exit $LASTEXITCODE
