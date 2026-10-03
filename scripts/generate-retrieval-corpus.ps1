param(
  [Parameter(Mandatory=$true)][string]$Destination,
  [ValidateSet(1000,5000)][int]$Chunks = 1000
)
$ErrorActionPreference = 'Stop'
$target = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $target) { throw 'Destination must be new; existing corpora are never overwritten.' }
New-Item -ItemType Directory -Path (Join-Path $target 'src') -Force | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
for ($i = 0; $i -lt ($Chunks / 2); $i++) {
  $name = 'Item{0:D4}' -f $i
  $code = "package fixture;`npublic final class $name {`n  public String handle$name() { return `"value-$i`"; }`n}`n"
  [System.IO.File]::WriteAllText((Join-Path $target "src/$name.java"), $code, $utf8)
}
$questions = for ($i = 0; $i -lt 50; $i++) {
  $name = 'Item{0:D4}' -f ($i * 7)
  @{ id = ('q{0:D2}' -f $i); query = "handle$name"; relevantPaths = @("src/$name.java") }
}
# Labels live outside the indexed corpus, preventing query/answer leakage into the index.
[System.IO.File]::WriteAllText(($target + '-questions.json'), (ConvertTo-Json -InputObject @($questions) -Depth 5), $utf8)
Write-Output "Created $Chunks JavaParser chunks and 50 automatically labelled identifier queries (synthetic corpus)."
