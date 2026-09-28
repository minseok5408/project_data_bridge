param(
    [Parameter(Mandatory = $true)]
    [scriptblock]$Action,
    [string]$MariaDbHome = $env:MARIADB_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $MariaDbHome) {
    $installed = Get-ChildItem -LiteralPath $env:ProgramFiles -Directory -Filter 'MariaDB *' |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($installed) {
        $MariaDbHome = $installed.FullName
    }
}
if (-not $MariaDbHome -or -not (Test-Path -LiteralPath (Join-Path $MariaDbHome 'bin\mariadbd.exe'))) {
    throw 'Set MARIADB_HOME or -MariaDbHome to a MariaDB server installation for isolated integration tests.'
}
$admin = Join-Path $MariaDbHome 'bin\mariadb-admin.exe'
if (-not (Test-Path -LiteralPath $admin)) {
    throw "MariaDB admin client not found: $admin"
}

function Invoke-TestDatabaseAdmin([string]$Command) {
    # PowerShell 5.1은 준비 전의 native stderr도 예외로 올릴 수 있습니다.
    # 이 두 관리 명령은 종료 코드로 판단하고 함수 밖의 오류 정책은 유지합니다.
    $ErrorActionPreference = 'SilentlyContinue'
    & $admin "--defaults-file=$clientConfig" --connect-timeout=1 $Command *> $null
    return $LASTEXITCODE
}

$toolRoot = Join-Path $projectRoot '.tools'
$testRoot = Join-Path $toolRoot ('test-db-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$dataDirectory = Join-Path $testRoot 'data'
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$testPassword = [Guid]::NewGuid().ToString('N')
$template = Join-Path $testRoot 'template.ini'
$clientConfig = Join-Path $testRoot 'client.ini'
$utf8 = [Text.UTF8Encoding]::new($false)
$previous = @{}
foreach ($key in @('IMS_TEST_DB_URL', 'IMS_TEST_DB_USER', 'IMS_TEST_DB_PASSWORD')) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$process = $null
$succeeded = $false
try {
    $serverSettings = @(
        '[mysqld]'
        'bind-address=127.0.0.1'
        'innodb-buffer-pool-size=32M'
        'innodb-log-file-size=16M'
        ''
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($template, $serverSettings, $utf8)
    & (Join-Path $MariaDbHome 'bin\mariadb-install-db.exe') "--datadir=$dataDirectory" "--password=$testPassword" "--port=$port" "--config=$template" --silent *> (Join-Path $testRoot 'initialize.log')
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB test initialization failed; see $testRoot\initialize.log"
    }
    $clientSettings = @(
        '[client]'
        'host=127.0.0.1'
        "port=$port"
        'user=root'
        "password=$testPassword"
        'protocol=tcp'
        'skip-ssl'
        ''
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($clientConfig, $clientSettings, $utf8)
    $startArguments = @{
        FilePath = Join-Path $MariaDbHome 'bin\mariadbd.exe'
        ArgumentList = @(('--defaults-file="' + (Join-Path $dataDirectory 'my.ini') + '"'), '--console')
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = Join-Path $testRoot 'server.log'
        RedirectStandardError = Join-Path $testRoot 'server-error.log'
    }
    $process = Start-Process @startArguments
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($process.HasExited) {
            throw "MariaDB test process exited; see $testRoot\server-error.log"
        }
        if ((Invoke-TestDatabaseAdmin 'ping') -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) {
        throw 'MariaDB test server did not start'
    }

    $env:IMS_TEST_DB_URL = "jdbc:mariadb://127.0.0.1:$port/"
    $env:IMS_TEST_DB_USER = 'root'
    $env:IMS_TEST_DB_PASSWORD = $testPassword
    & $Action ([PSCustomObject]@{
        Url = $env:IMS_TEST_DB_URL
        User = 'root'
        Password = $testPassword
        Client = Join-Path $MariaDbHome 'bin\mariadb.exe'
        ClientConfig = $clientConfig
        Directory = $testRoot
    })
    $succeeded = $true
} finally {
    try {
        if ($process -and -not $process.HasExited) {
            try {
                $null = Invoke-TestDatabaseAdmin 'shutdown'
            } finally {
                # shutdown 명령이 실패해도 이 스크립트가 시작한 프로세스는 종료합니다.
                if (-not $process.WaitForExit(10000)) {
                    Stop-Process -InputObject $process -Force
                    if (-not $process.WaitForExit(5000)) {
                        throw "MariaDB test process did not stop; files preserved at $testRoot"
                    }
                }
            }
        }
    } finally {
        foreach ($key in $previous.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
        }
    }
    if ($succeeded) {
        $resolvedTest = (Resolve-Path -LiteralPath $testRoot).Path
        $resolvedTools = (Resolve-Path -LiteralPath $toolRoot).Path
        if (-not $resolvedTest.StartsWith($resolvedTools + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a test database outside .tools'
        }
        Remove-Item -LiteralPath $resolvedTest -Recurse -Force
    } else {
        Write-Warning "Test failed; database files and logs are preserved at $testRoot"
    }
}
