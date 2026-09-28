param(
    [string]$JdkHome,
    [switch]$SkipBuild,
    [string]$Config,
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$repositoryRoot = Split-Path $projectRoot -Parent
if (-not $Config) {
    $Config = Join-Path $projectRoot 'config\collector.json'
}
$Config = (Resolve-Path -LiteralPath $Config).Path
if (-not $Destination) {
    $Destination = Join-Path $projectRoot 'dist'
}
$Destination = [IO.Path]::GetFullPath($Destination)
$bundle = Join-Path $Destination 'DataBridgeCollector'
if (Test-Path -LiteralPath $bundle) {
    throw "Bundle already exists: $bundle. Move it before packaging again."
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build.ps1') -JdkHome $JdkHome
}
$tools = & (Join-Path $PSScriptRoot 'java-tools.ps1') -JdkHome $JdkHome
$java = Join-Path $tools.JavaHome 'bin\java.exe'
$jar = Join-Path $projectRoot 'target\data-collector.jar'

# JSONC는 실행 프로그램과 같은 파서로 읽습니다. 여기까지는 배포 폴더를 만들지 않습니다.
$previousOutputEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $configJson = & $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $jar export-config --config $Config
    $exportExitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousOutputEncoding
}
if ($exportExitCode -ne 0) {
    throw 'Collector configuration export failed'
}
$configData = ($configJson -join [Environment]::NewLine) | ConvertFrom-Json
$configData | Add-Member -NotePropertyName stateDir -NotePropertyValue 'state' -Force
$configDirectory = Split-Path $Config -Parent
$sourceFile = Join-Path $configDirectory $configData.sourceFile
$propertiesFile = Join-Path $configDirectory 'application.properties'
if (-not (Test-Path -LiteralPath $propertiesFile)) {
    $propertiesFile = Join-Path $projectRoot 'src\main\resources\application.properties'
}
if (-not (Test-Path -LiteralPath $propertiesFile)) {
    throw "Put application.properties beside $Config before packaging."
}

$wrapper = Join-Path $projectRoot '.tools\WinSW-x64.exe'
if (-not (Test-Path -LiteralPath $wrapper)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe' -OutFile $wrapper
}
if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ne '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA') {
    throw 'WinSW checksum mismatch'
}

$targetRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$stageRoot = Join-Path $targetRoot ('package-' + [Guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
try {
    $inputDirectory = Join-Path $stageRoot 'input'
    $preparedConfig = Join-Path $stageRoot 'config'
    New-Item -ItemType Directory -Path $inputDirectory, $preparedConfig | Out-Null
    Copy-Item -LiteralPath $jar -Destination $inputDirectory
    Copy-Item -LiteralPath $sourceFile -Destination $preparedConfig
    Copy-Item -LiteralPath $propertiesFile -Destination (Join-Path $preparedConfig 'application.properties')
    [IO.File]::WriteAllText((Join-Path $preparedConfig 'collector.json'), ($configData | ConvertTo-Json -Depth 20), $utf8)

    # 실제로 배포할 설정과 토큰을 검사한 뒤에만 jpackage를 실행합니다.
    & $java -jar $jar validate --config (Join-Path $preparedConfig 'collector.json')
    if ($LASTEXITCODE -ne 0) {
        throw 'Packaged collector configuration validation failed'
    }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $repositoryRoot 'pom.xml') -Raw
    $imageDirectory = Join-Path $stageRoot 'image'
    $arguments = @(
        '--type', 'app-image'
        '--name', 'DataBridgeCollector'
        '--app-version', $pom.project.version
        '--input', $inputDirectory
        '--main-jar', 'data-collector.jar'
        '--main-class', 'io.databridge.collector.CollectorApplication'
        '--dest', $imageDirectory
        '--win-console'
        '--java-options', '-Dfile.encoding=UTF-8'
        '--java-options', '-Dstdout.encoding=UTF-8'
        '--java-options', '-Dstderr.encoding=UTF-8'
        '--add-modules', 'java.base,java.desktop,java.sql,java.net.http,java.logging,java.naming,jdk.charsets,jdk.crypto.ec,jdk.unsupported'
    )
    & (Join-Path $tools.JavaHome 'bin\jpackage.exe') @arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'jpackage failed'
    }
    $stagedBundle = Join-Path $imageDirectory 'DataBridgeCollector'
    Copy-Item -LiteralPath $preparedConfig -Destination (Join-Path $stagedBundle 'config') -Recurse
    New-Item -ItemType Directory -Path (Join-Path $stagedBundle 'state') | Out-Null
    Copy-Item -LiteralPath $wrapper -Destination (Join-Path $stagedBundle 'DataBridgeCollectorService.exe')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\DataBridgeCollectorService.xml') -Destination $stagedBundle
    Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\service.ps1') -Destination $stagedBundle
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'README.md') -Destination $stagedBundle

    $resolvedStage = (Resolve-Path -LiteralPath $stageRoot).Path
    $resolvedBundle = (Resolve-Path -LiteralPath $stagedBundle).Path
    if (-not $resolvedBundle.StartsWith($resolvedStage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Staged bundle is outside its temporary directory'
    }
    if ([IO.Path]::GetFullPath($bundle) -ne (Join-Path $Destination 'DataBridgeCollector')) {
        throw 'Invalid bundle destination'
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    if (Test-Path -LiteralPath $bundle) {
        throw "Bundle already exists: $bundle"
    }
    Move-Item -LiteralPath $resolvedBundle -Destination $bundle
    Write-Output "Windows bundle ready: $bundle"
} finally {
    # 이 실행에서 만든 target/package-<UUID>만 정리합니다. 기존 dist는 건드리지 않습니다.
    if (Test-Path -LiteralPath $stageRoot) {
        $resolvedStage = (Resolve-Path -LiteralPath $stageRoot).Path
        $resolvedTarget = (Resolve-Path -LiteralPath $targetRoot).Path
        if (-not $resolvedStage.StartsWith($resolvedTarget + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a staging directory outside target'
        }
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
