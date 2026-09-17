$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'lib/flowtrail.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    $jarPath = Join-Path $projectRoot 'target/flowtrail.jar'
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    [Console]::Error.WriteLine('Missing flowtrail.jar. Run mvn clean verify from the project directory first.')
    exit 1
}
$javaCommand = 'java'
if ($env:JAVA_HOME) {
    $javaCommand = Join-Path $env:JAVA_HOME 'bin/java'
}
& $javaCommand '-Dfile.encoding=UTF-8' '-jar' $jarPath @args
exit $LASTEXITCODE
