param([string]$RepositoryPath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$licenseProject = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = Join-Path $licenseProject '.cache/m2'
}
$licenseDirectory = Join-Path $licenseProject 'licenses'
$licenseManifest = Get-Content -LiteralPath (Join-Path $licenseDirectory 'RESOURCE_MANIFEST.json') -Raw | ConvertFrom-Json
if ($licenseManifest.formatVersion -ne 1) { throw 'Unsupported license manifest version.' }

foreach ($licenseRecord in $licenseManifest.resources) {
    $licenseArtifact = Join-Path $RepositoryPath $licenseRecord.sourceArtifact
    $licenseResource = Join-Path $licenseDirectory $licenseRecord.resource
    if ((Get-FileHash -LiteralPath $licenseArtifact -Algorithm SHA256).Hash -ne $licenseRecord.artifactSha256) {
        throw "Source artifact hash mismatch: $($licenseRecord.sourceArtifact)"
    }
    if ((Get-FileHash -LiteralPath $licenseResource -Algorithm SHA256).Hash -ne $licenseRecord.resourceSha256) {
        throw "License resource hash mismatch: $($licenseRecord.resource)"
    }
    $licenseArchive = [System.IO.Compression.ZipFile]::OpenRead($licenseArtifact)
    try {
        $licenseEntry = $licenseArchive.GetEntry($licenseRecord.sourceEntry)
        if ($null -eq $licenseEntry -or $licenseEntry.Length -ne $licenseRecord.bytes) {
            throw "Source entry missing or length mismatch: $($licenseRecord.sourceEntry)"
        }
        $licenseStream = $licenseEntry.Open()
        $licenseHasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            $licenseEntryHash = [System.BitConverter]::ToString($licenseHasher.ComputeHash($licenseStream)).Replace('-', '')
            if ($licenseEntryHash -ne $licenseRecord.resourceSha256) {
                throw "Source entry and preserved resource differ: $($licenseRecord.resource)"
            }
        } finally {
            $licenseHasher.Dispose()
            $licenseStream.Dispose()
        }
    } finally {
        $licenseArchive.Dispose()
    }
    Write-Output "Verified: $($licenseRecord.component) / $($licenseRecord.resource)"
}
Write-Output "Verified $($licenseManifest.resources.Count) preserved license/metadata resources."
