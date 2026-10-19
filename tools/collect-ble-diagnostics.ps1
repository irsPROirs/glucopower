[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial
)

# Read-only collection. Enable HCI snoop and reproduce the issue on the phone
# before running this script. It does not change Bluetooth or pairing settings.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $bundledAdb = Join-Path $projectDirectory '.tools/android-sdk/platform-tools/adb.exe'
    if (Test-Path -LiteralPath $bundledAdb) {
        $AdbPath = $bundledAdb
    } else {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if (-not $adbCommand) { throw 'ADB not found. Supply -AdbPath with the path to adb.exe.' }
        $AdbPath = $adbCommand.Source
    }
}

try {
    # Windows PowerShell can treat harmless native stderr as a terminating error.
    $ErrorActionPreference = 'Continue'
    $deviceLines = @(& $AdbPath devices 2>&1)
    $listExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = 'Stop' }
if ($listExitCode -ne 0) { throw 'ADB could not list devices.' }
$readyDevices = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if ($Serial) {
    if ($readyDevices -notcontains $Serial) {
        throw 'Selected device is not authorized or connected. Check USB debugging and the phone authorization dialog.'
    }
} elseif ($readyDevices.Count -eq 1) {
    $Serial = $readyDevices[0]
} else {
    throw 'Connect one phone with USB debugging enabled and authorize this computer. For multiple phones use -Serial.'
}

$outputDirectory = Join-Path $projectDirectory ('diagnostics/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $outputDirectory | Out-Null

function Save-AdbText {
    param([string]$Name, [string[]]$AdbArguments)
    $destination = Join-Path $outputDirectory $Name
    # Keep stderr separate: it can contain benign daemon messages or a failed
    # OEM shell command. Do not let a failure erase the rest of the collection.
    $previousConsoleEncoding = [Console]::OutputEncoding
    try {
        # ADB writes UTF-8. Windows PowerShell otherwise decodes Russian logcat
        # using the console OEM code page before Set-Content can save it.
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $ErrorActionPreference = 'Continue'
        $commandOutput = & $AdbPath -s $Serial @AdbArguments 2> ($destination + '.stderr.txt')
        $commandExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = $previousConsoleEncoding
    }
    $commandOutput | Set-Content -LiteralPath $destination -Encoding UTF8
    if ($commandExitCode -ne 0) {
        Write-Warning "$Name failed (exit $commandExitCode); continuing with bugreport."
    }
}

@(
    'Captured: ' + (Get-Date -Format o)
    'HCI logging must have been enabled before reproducing the issue.'
    'This folder is local. Nothing is uploaded automatically.'
    'The bugreport may include unrelated device and application data.'
) | Set-Content -LiteralPath (Join-Path $outputDirectory 'README.txt') -Encoding UTF8

Save-AdbText -Name 'bluetooth-manager.txt' -AdbArguments @('shell', 'dumpsys', 'bluetooth_manager')
Save-AdbText -Name 'glucopower-service.txt' -AdbArguments @('shell', 'dumpsys', 'activity', 'services', 'com.example.glucoble')
Save-AdbText -Name 'glucopower-logcat.txt' -AdbArguments @('logcat', '-d', '-v', 'threadtime', '-s', 'GlucoBleBridge:I', '*:S')
Save-AdbText -Name 'hci-log-mode.txt' -AdbArguments @('shell', 'getprop', 'persist.bluetooth.btsnooplogmode')
Save-AdbText -Name 'phone-model.txt' -AdbArguments @('shell', 'getprop', 'ro.product.model')
Save-AdbText -Name 'phone-firmware.txt' -AdbArguments @('shell', 'getprop', 'ro.build.fingerprint')
Save-AdbText -Name 'android-release.txt' -AdbArguments @('shell', 'getprop', 'ro.build.version.release')
Save-AdbText -Name 'android-sdk.txt' -AdbArguments @('shell', 'getprop', 'ro.build.version.sdk')
Save-AdbText -Name 'android-sdk-full.txt' -AdbArguments @('shell', 'getprop', 'ro.build.version.sdk_full')
Save-AdbText -Name 'phone-display.txt' -AdbArguments @('shell', 'getprop', 'ro.build.display.id')
Save-AdbText -Name 'lineage-version.txt' -AdbArguments @('shell', 'getprop', 'ro.lineage.version')

$bugreportPath = Join-Path $outputDirectory 'bugreport.zip'
Write-Host 'Collecting Android bugreport; this can take several minutes...'
try {
    $ErrorActionPreference = 'Continue'
    & $AdbPath -s $Serial bugreport $bugreportPath
    $bugreportExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = 'Stop' }
if ($bugreportExitCode -ne 0 -or -not (Test-Path -LiteralPath $bugreportPath)) {
    throw "Bugreport capture failed. Partial text reports are in $outputDirectory"
}

# Recognize the btsnoop header, including vivo's bt_hci_*.cfa filenames.
$traces = @(& (Join-Path $PSScriptRoot 'expand-ble-traces.ps1') -BugreportPath $bugreportPath -OutputDirectory $outputDirectory)

Write-Host "Saved locally: $outputDirectory"
if ($traces.Count -gt 0) {
    Write-Host "Extracted $($traces.Count) HCI file(s). Record the attempt time and share only the required trace."
} else {
    Write-Warning 'No btsnoop header found in Bluetooth trace files. This does not prove there was no BLE traffic; the report may still contain a limited BTSNOOZ dump.'
}
