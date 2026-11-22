$ErrorActionPreference = "Continue"
$adb = "C:\Users\admin\Documents\dsh\.toolchain\sdk\platform-tools\adb.exe"
$tv  = "192.168.31.147"
$log = "C:\Users\admin\Documents\dsh\.tmp\pair-output.txt"
$dir = Split-Path $log
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

function Say($text, $color = "Gray") {
  Write-Host $text -ForegroundColor $color
  Add-Content -Path $log -Value $text -Encoding UTF8
}

Set-Content -Path $log -Value "=== pair attempt $(Get-Date -Format o) ===" -Encoding UTF8

Say ""
Say "=== FnTV TV wireless-debug pairing ===" "Cyan"
Say "TV: $tv"
Say ""
Say "On the TV: Settings > System > Developer options > Wireless debugging"
Say "Tap 'Pair device with pairing code' and KEEP THAT DIALOG OPEN."
Say ""

$pport = Read-Host "Pairing port (from the pairing dialog, 5 digits)"
if ([string]::IsNullOrWhiteSpace($pport)) { Say "No port, abort." "Red"; pause; exit 1 }
$pcode = Read-Host "6-digit pairing code"

Say ""
Say "[1/3] adb pair ${tv}:$pport ..." "Yellow"
$out = & $adb pair "${tv}:$pport" $pcode 2>&1
$out | ForEach-Object { Say $_ }
if ($LASTEXITCODE -ne 0) {
  Say ""
  Say "Pairing FAILED (exit $LASTEXITCODE)." "Red"
  Say "Check: dialog still open? code not expired? try reopening the dialog."
  pause
  exit 1
}

$cport = Read-Host "Connect port (press Enter for 38869)"
if ([string]::IsNullOrWhiteSpace($cport)) { $cport = "38869" }

Say ""
Say "[2/3] adb connect ${tv}:$cport ..." "Yellow"
(& $adb connect "${tv}:$cport" 2>&1) | ForEach-Object { Say $_ }

Say ""
Say "[3/3] adb devices -l:" "Yellow"
(& $adb devices -l 2>&1) | ForEach-Object { Say $_ }

Say ""
Say "If a line ends with 'device', it worked. Tell the assistant." "Green"
Say "If 'unauthorized', tap Allow on the TV."
Say ""
pause
