param(
  [Parameter(Mandatory=$true)][string]$Project,
  [Parameter(Mandatory=$true)][string]$Questions,
  [Parameter(Mandatory=$true)][string]$Report,
  [string]$Jar = 'target/flowtrail.jar',
  [string]$Java = 'java',
  [ValidateRange(1,100)][int]$WarmRepeats = 3,
  [ValidateSet('mock','openai','ollama')][string]$Provider = 'mock',
  [string]$Model = ''
)
$ErrorActionPreference = 'Stop'
$arguments = @('-Xmx512m', '-cp', $Jar, 'dev.flowtrail.rag.RetrievalEvaluation', $Project, $Questions, $Report, "$WarmRepeats", $Provider)
if ($Model) { $arguments += $Model }
& $Java @arguments
if ($LASTEXITCODE -ne 0) { throw "Retrieval evaluation exited with $LASTEXITCODE" }
