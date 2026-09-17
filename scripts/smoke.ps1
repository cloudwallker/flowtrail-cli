$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot ('target/smoke-' + [Guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath (Join-Path $projectRoot 'target/flowtrail-dist.zip') -DestinationPath $destination
$distribution = (Get-ChildItem -LiteralPath $destination -Directory | Select-Object -First 1).FullName
$launcher = Join-Path $distribution 'bin/flowtrail.ps1'
$example = Join-Path $distribution 'examples/hello.json'
$result = & $launcher run $example --json
if ($LASTEXITCODE -ne 0) { throw 'Distribution run failed.' }
$parsed = $result | ConvertFrom-Json
if (-not $parsed.ok -or $parsed.steps.Count -ne 2 -or $parsed.steps[1].output -notmatch 'FlowTrail') {
    throw 'Unexpected distribution result.'
}
$created = Join-Path $destination 'file with spaces.json'
& $launcher init $created
if ($LASTEXITCODE -ne 0) { throw 'Distribution init failed.' }
& $launcher validate $created
if ($LASTEXITCODE -ne 0) { throw 'Distribution validate failed.' }
& $launcher doctor --json
if ($LASTEXITCODE -ne 0) { throw 'Distribution doctor failed.' }
Write-Output 'Distribution smoke checks passed.'
