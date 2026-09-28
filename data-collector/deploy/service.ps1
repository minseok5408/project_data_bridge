param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'start', 'stop', 'restart', 'status', 'uninstall')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$wrapper = Join-Path $PSScriptRoot 'DataBridgeCollectorService.exe'
if ($Action -eq 'install') {
    # 실행 프로그램과 같은 설정 파서로 서비스 등록 전에 검사합니다.
    & (Join-Path $PSScriptRoot 'DataBridgeCollector.exe') validate --config (Join-Path $PSScriptRoot 'config\collector.json')
    if ($LASTEXITCODE -ne 0) {
        throw 'Collector configuration validation failed'
    }
}
& $wrapper $Action
if ($LASTEXITCODE -ne 0) {
    throw "Service action failed: $Action"
}
