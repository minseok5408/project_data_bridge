param(
    [string]$Bundle,
    [string]$MariaDbHome = $env:MARIADB_HOME,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$collectorRoot = Split-Path $PSScriptRoot -Parent
$hubRoot = Join-Path (Split-Path $collectorRoot -Parent) 'data-hub'
if (-not $Bundle) {
    $Bundle = Join-Path $collectorRoot 'dist\DataBridgeCollector'
}
$Bundle = (Resolve-Path -LiteralPath $Bundle).Path
$executable = Join-Path $Bundle 'DataBridgeCollector.exe'
$hubJar = Join-Path $hubRoot 'target\data-hub.jar'
if (-not (Test-Path -LiteralPath $executable) -or -not (Test-Path -LiteralPath $hubJar)) {
    throw 'Build both modules and create the Windows bundle before running this test.'
}
$tools = & (Join-Path $PSScriptRoot 'java-tools.ps1') -JdkHome $JdkHome

# 주석이 있는 개발 설정도 Java 파서로 읽습니다. 실제 토큰과 장비 연결은 필요 없습니다.
$previousOutputEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $configJson = & $executable export-config --config (Join-Path $collectorRoot 'config\collector.json')
    $exportExitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousOutputEncoding
}
if ($exportExitCode -ne 0) {
    throw 'Packaged configuration export failed'
}
$config = ($configJson -join [Environment]::NewLine) | ConvertFrom-Json
& (Join-Path $hubRoot 'scripts\with-test-database.ps1') -MariaDbHome $MariaDbHome -Action {
    param($database)

    $smokeRoot = Join-Path $database.Directory 'smoke'
    New-Item -ItemType Directory -Path $smokeRoot, (Join-Path $smokeRoot 'input') | Out-Null
    & $database.Client "--defaults-file=$($database.ClientConfig)" --execute='CREATE DATABASE databridge_smoke CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'
    if ($LASTEXITCODE -ne 0) {
        throw 'Smoke database creation failed'
    }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $token = 'package-smoke-test-token-only'
    $utf8 = [Text.UTF8Encoding]::new($false)
    $hubConfig = Join-Path $smokeRoot 'hub.properties'
    $hubSettings = @(
        'server.host=127.0.0.1'
        "server.port=$port"
        "ims.token=$token"
        "database.url=$($database.Url)databridge_smoke"
        "database.username=$($database.User)"
        "database.password=$($database.Password)"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($hubConfig, $hubSettings, $utf8)
    [IO.File]::WriteAllText((Join-Path $smokeRoot 'application.properties'), "ims.token=$token", $utf8)
    $config | Add-Member -NotePropertyName rootDir -NotePropertyValue $smokeRoot -Force
    $config | Add-Member -NotePropertyName stateDir -NotePropertyValue 'state' -Force
    $config | Add-Member -NotePropertyName endpoint -NotePropertyValue "http://127.0.0.1:$port/api/v1/events" -Force
    $config.sourceFile = 'package-test.json'
    $source = @{
        id = 'text_test'
        com_cd = 'company-test'
        type = 'text'
        equipmentId = 'EQ-TEST'
        directory = 'input'
        glob = '*.txt'
        settleSeconds = 0
        batchLines = 1
        fields = @{
            measured_at = @{ column = 1; type = 'datetime'; target = 'observed_at' }
        }
        payload = @{
            temperature = @{ column = 2; type = 'float'; unit = 'degC' }
            production_count = @{ column = 3; type = 'int'; kind = 'counter'; unit = 'ea' }
            running = @{ column = 4; type = 'bool'; kind = 'status' }
        }
    }
    $testProfile = @{ collectionType = 'text'; sources = @($source) }
    [IO.File]::WriteAllText((Join-Path $smokeRoot $config.sourceFile), ($testProfile | ConvertTo-Json -Depth 12), $utf8)
    [IO.File]::WriteAllText((Join-Path $smokeRoot 'ignored.json'), 'Unselected JSON must not be read', $utf8)
    $collectorFile = Join-Path $smokeRoot 'collector.json'
    [IO.File]::WriteAllText($collectorFile, ($config | ConvertTo-Json -Depth 12), $utf8)
    $rows = @(
        (@('time', 'temperature', 'production_count', 'running') -join [char]9)
        (@('2026-09-22T09:00:00+09:00', '25', '100', 'true') -join [char]9)
        (@('2026-09-22T09:00:01+09:00', '26', '105', 'false') -join [char]9)
        ''
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $smokeRoot 'input\measurements.txt'), $rows, $utf8)

    $hubProcess = $null
    try {
        $processArguments = @{
            FilePath = Join-Path $tools.JavaHome 'bin\java.exe'
            WorkingDirectory = $smokeRoot
            ArgumentList = @(
                '-Dfile.encoding=UTF-8'
                '-Djava.io.tmpdir="' + (Join-Path $hubRoot 'target') + '"'
                '-Djdk.net.unixdomain.tmpdir="' + (Join-Path $hubRoot 'target') + '"'
                '-jar', ('"' + $hubJar + '"')
                'run', '--config', ('"' + $hubConfig + '"')
            )
            WindowStyle = 'Hidden'
            PassThru = $true
            RedirectStandardOutput = Join-Path $smokeRoot 'hub.log'
            RedirectStandardError = Join-Path $smokeRoot 'hub-error.log'
        }
        $hubProcess = Start-Process @processArguments
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            try {
                Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 2 | Out-Null
                $ready = $true
                break
            } catch {
                if ($hubProcess.HasExited) {
                    throw "Hub exited; see $smokeRoot\hub-error.log"
                }
                Start-Sleep -Milliseconds 100
            }
        }
        if (-not $ready) {
            throw 'Hub did not start'
        }

        & $executable once --config $collectorFile
        if ($LASTEXITCODE -ne 0) {
            throw 'Collector EXE failed'
        }
        if (Test-Path -LiteralPath (Join-Path $smokeRoot 'input\measurements.txt')) {
            throw 'Completed input was not archived'
        }
        if (-not (Test-Path -LiteralPath (Join-Path $smokeRoot 'input\complete\measurements.txt'))) {
            throw 'Complete archive missing'
        }
        & $executable once --config $collectorFile
        if ($LASTEXITCODE -ne 0) {
            throw 'Collector restart failed'
        }

        $headers = @{ Authorization = 'Bearer ' + $token }
        $base = "http://127.0.0.1:$port/api/v1"
        $events = Invoke-RestMethod "$base/data-json" -Headers $headers
        if ($events.items.Count -ne 2) {
            throw 'Expected two file rows after collector restart'
        }
        if ($events.items[0].com_cd -ne 'company-test' -or $events.items[0].collection_type -ne 'text') {
            throw 'Stored company or collection type differs from the test profile'
        }
        $last = $events.items[1]
        if ($last.file_name -ne 'measurements.txt' -or $last.file_extension -ne 'txt') {
            throw 'Original file metadata was not stored'
        }
        if ($last.payload.temperature -ne '26' -or $last.payload.production_count -ne '105' -or $last.payload.running -ne 'false') {
            throw 'Stored payload differs from the second input row'
        }
        if (@($last.payload.PSObject.Properties).Count -ne 3 -or $last.id -le $events.items[0].id) {
            throw 'Unexpected payload fields or row order'
        }
        $tables = @(& $database.Client "--defaults-file=$($database.ClientConfig)" --batch --skip-column-names --execute="SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='databridge_smoke'")
        if ($LASTEXITCODE -ne 0 -or (($tables | Sort-Object) -join ',') -ne 'data_json,data_modbus,equipment,file_import,modbus_state') {
            throw 'Expected the five hub tables including file_import'
        }
        $runs = Invoke-RestMethod "$base/modbus/runs?com_cd=company-test" -Headers $headers
        if ($runs.items.Count -ne 0) {
            throw 'File data must not create Modbus production periods'
        }
        $filtered = Invoke-RestMethod "$base/events?com_cd=company-test&collectionType=text" -Headers $headers
        if ($filtered.items.Count -ne 2) {
            throw 'Company and method filtering failed'
        }
        $receipts = @(& $database.Client "--defaults-file=$($database.ClientConfig)" --batch --skip-column-names --execute='SELECT row_count FROM databridge_smoke.file_import')
        if ($LASTEXITCODE -ne 0 -or $receipts.Count -ne 1 -or $receipts[0] -ne '2') {
            throw 'Expected one file receipt for both rows after collector restart'
        }

        # batchLines=1이어도 마지막 행 오류가 앞의 정상 10개 행을 저장하지 않아야 합니다.
        $badFile = Join-Path $smokeRoot 'input\bad.txt'
        $invalidRows = [Collections.Generic.List[string]]::new()
        $invalidRows.Add((@('time', 'temperature', 'production_count', 'running') -join [char]9))
        for ($index = 0; $index -lt 10; $index++) {
            $invalidRows.Add((@('2026-09-22T09:00:00+09:00', '25', '100', 'true') -join [char]9))
        }
        $invalidRows.Add((@('2026-09-22T09:00:01+09:00', 'bad', '105', 'false') -join [char]9))
        [IO.File]::WriteAllText($badFile, ($invalidRows -join [Environment]::NewLine), $utf8)
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'SilentlyContinue'
            & $executable once --config $collectorFile *> (Join-Path $smokeRoot 'invalid-file.log')
            $invalidExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($invalidExitCode -ne 1) {
            throw 'Expected collector failure for the invalid eleventh data row'
        }
        $countSql = "SELECT CONCAT((SELECT COUNT(*) FROM databridge_smoke.data_json), ',', (SELECT COUNT(*) FROM databridge_smoke.file_import))"
        $counts = @(& $database.Client "--defaults-file=$($database.ClientConfig)" --batch --skip-column-names "--execute=$countSql")
        if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 1 -or $counts[0] -ne '2,1') {
            throw 'The invalid file changed the existing two rows or file receipt'
        }
        $errorFile = Join-Path $smokeRoot 'input\error\bad.txt'
        if (-not (Test-Path -LiteralPath $errorFile)) {
            throw 'Invalid original file was not preserved in error'
        }
        if (Test-Path -LiteralPath ($errorFile + '.retry.json')) {
            throw 'A file rejected before sending must not need a delivery retry sidecar'
        }

        # 오류 행을 고친 원본만 돌려놓으면 11개 행 전체가 새 요청 하나로 저장됩니다.
        $invalidRows[$invalidRows.Count - 1] = @('2026-09-22T09:00:01+09:00', '26', '105', 'false') -join [char]9
        [IO.File]::WriteAllText($errorFile, ($invalidRows -join [Environment]::NewLine), $utf8)
        $resolvedSmoke = (Resolve-Path -LiteralPath $smokeRoot).Path
        $resolvedError = (Resolve-Path -LiteralPath $errorFile).Path
        $resolvedInput = [IO.Path]::GetFullPath($badFile)
        foreach ($path in @($resolvedError, $resolvedInput)) {
            if (-not $path.StartsWith($resolvedSmoke + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Smoke file path is outside the isolated test directory'
            }
        }
        Move-Item -LiteralPath $resolvedError -Destination $resolvedInput
        & $executable once --config $collectorFile
        if ($LASTEXITCODE -ne 0) {
            throw 'Corrected file failed to import'
        }
        $counts = @(& $database.Client "--defaults-file=$($database.ClientConfig)" --batch --skip-column-names "--execute=$countSql")
        if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 1 -or $counts[0] -ne '13,2') {
            throw 'Corrected file must add all eleven rows and exactly one receipt'
        }
        if (-not (Test-Path -LiteralPath (Join-Path $smokeRoot 'input\complete\bad.txt'))) {
            throw 'Corrected file was not archived in complete'
        }
        & $executable once --config $collectorFile
        if ($LASTEXITCODE -ne 0) {
            throw 'Collector restart after corrected file failed'
        }
        $counts = @(& $database.Client "--defaults-file=$($database.ClientConfig)" --batch --skip-column-names "--execute=$countSql")
        if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 1 -or $counts[0] -ne '13,2') {
            throw 'Collector restart duplicated the corrected file'
        }
        Write-Output 'PASS: packaged collector -> hub -> isolated MariaDB; invalid eleventh row saves nothing, corrected file saves all rows once, total 13 rows and 2 receipts after restart.'
    } finally {
        if ($hubProcess -and -not $hubProcess.HasExited) {
            Stop-Process -Id $hubProcess.Id -Force
            $hubProcess.WaitForExit()
        }
    }
}
