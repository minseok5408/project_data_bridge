param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$collectorRoot = Split-Path $PSScriptRoot -Parent
$repositoryRoot = Split-Path $collectorRoot -Parent
[xml]$pom = Get-Content -LiteralPath (Join-Path $repositoryRoot 'pom.xml') -Raw
$versions = $pom.project.properties
$jdkVersion = $versions.'jdk.version'
$jdkBuild = $versions.'jdk.build'
$javaRelease = $versions.'maven.compiler.release'
$mavenVersion = $versions.'maven.version'
$toolRoot = Join-Path $collectorRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null

$customJdk = -not [string]::IsNullOrWhiteSpace($JdkHome)
if (-not $customJdk) {
    $JdkHome = Join-Path $toolRoot "jdk-$jdkVersion+$jdkBuild"
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    if ($customJdk) {
        throw "JDK compiler not found: $JdkHome"
    }
    $zip = Join-Path $toolRoot "openjdk-$jdkVersion.zip"
    if (-not (Test-Path -LiteralPath $zip)) {
        $url = "https://github.com/adoptium/temurin$javaRelease-binaries/releases/download/jdk-$jdkVersion%2B$jdkBuild/OpenJDK$($javaRelease)U-jdk_x64_windows_hotspot_$($jdkVersion)_$jdkBuild.zip"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    }
    if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $versions.'jdk.windows.sha256') {
        throw 'OpenJDK checksum mismatch'
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $toolRoot -Force
}
$release = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch ('JAVA_VERSION="' + [regex]::Escape($jdkVersion) + '"')) {
    throw "OpenJDK $jdkVersion is required; the version is managed in the root pom.xml."
}
$env:JAVA_HOME = $JdkHome

$maven = Join-Path $toolRoot "apache-maven-$mavenVersion\bin\mvn.cmd"
if (-not (Test-Path -LiteralPath $maven)) {
    $zip = Join-Path $toolRoot "maven-$mavenVersion.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" -OutFile $zip
    if ((Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash -ne $versions.'maven.zip.sha512') {
        throw 'Maven checksum mismatch'
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $toolRoot -Force
}
[PSCustomObject]@{
    JavaHome = $JdkHome
    Maven = $maven
    Repository = Join-Path $toolRoot 'm2'
}
