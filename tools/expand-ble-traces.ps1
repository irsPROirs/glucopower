[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BugreportPath,
    [string]$OutputDirectory
)

# Offline extraction: OEMs may store standard btsnoop data under .cfa names.
# Never use an archive entry name as a destination path.
$ErrorActionPreference = 'Stop'
$BugreportPath = (Resolve-Path -LiteralPath $BugreportPath).Path
if (-not $OutputDirectory) { $OutputDirectory = Split-Path -Parent $BugreportPath }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($BugreportPath)
$traceNumber = 0
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '(?i)(^|/)(btsnoop[^/]*|bt_hci_[^/]+)$|/bluetooth/logs/[^/]+$') { continue }
        if ($entry.Length -lt 16) { continue }
        $inputStream = $entry.Open()
        try {
            $header = New-Object byte[] 16
            $read = 0
            while ($read -lt $header.Length) {
                $count = $inputStream.Read($header, $read, $header.Length - $read)
                if ($count -eq 0) { break }
                $read += $count
            }
            # Magic "btsnoop\0", version 1 (network byte order).
            if ($read -ne 16 -or [BitConverter]::ToString($header, 0, 12) -ne '62-74-73-6E-6F-6F-70-00-00-00-00-01') { continue }
            $dataLink = ([uint32]$header[12] * 16777216) + ([uint32]$header[13] * 65536) + ([uint32]$header[14] * 256) + $header[15]
            do {
                $traceNumber++
                $tracePath = Join-Path $OutputDirectory ("hci-$traceNumber-btsnoop.log")
            } while (Test-Path -LiteralPath $tracePath)
            $outputStream = [System.IO.File]::Open($tracePath, [System.IO.FileMode]::CreateNew)
            try {
                $outputStream.Write($header, 0, $header.Length)
                $inputStream.CopyTo($outputStream)
            } finally { $outputStream.Dispose() }
            "$([System.IO.Path]::GetFileName($tracePath)) <- $($entry.FullName) (datalink=$dataLink)" |
                Add-Content -LiteralPath (Join-Path $OutputDirectory 'hci-files.txt') -Encoding UTF8
            [PSCustomObject]@{ Path = $tracePath; Source = $entry.FullName; Bytes = $entry.Length; DataLink = $dataLink }
        } finally { $inputStream.Dispose() }
    }
} finally { $archive.Dispose() }
