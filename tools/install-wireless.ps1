# Install the Sapphire Arctic Luxury TV fork on a Google TV over Wireless debugging (no USB, no port 5555).
#
# On the TV: Settings > System > About > press "Android TV OS build" 7 times, then
# Settings > System > Developer options > Wireless debugging > On.
#   - "Pair device with pairing code" shows an address like 192.168.4.50:37123 and a 6-digit code.
#   - The main Wireless debugging screen shows the connect address, e.g. 192.168.4.50:41235 (a different port).
#
# First time (pair + install):
#   tools\install-wireless.ps1 -Pair 192.168.4.50:37123 -Code 123456 -Connect 192.168.4.50:41235 -Latitude 40.71 -Longitude -74.01
# Later (already paired; the connect port changes whenever Wireless debugging is turned off and on):
#   tools\install-wireless.ps1 -Connect 192.168.4.50:41235
# Find the TV without reading the screen:
#   tools\install-wireless.ps1 -Discover
# Put everything back the way it was:
#   tools\install-wireless.ps1 -Connect 192.168.4.50:41235 -Undo
#
# Every step can be undone; nothing on the TV is uninstalled except this launcher itself with -Undo.
param(
    [string]$Pair,
    [string]$Code,
    [string]$Connect,
    [string]$Apk,
    [string]$Theme,
    [string]$Latitude,
    [string]$Longitude,
    [switch]$DisableStockLauncher,
    [switch]$ButtonHelper,
    [switch]$Discover,
    [switch]$Undo
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $repo 'launcher\app\build\outputs\apk\release\app-release.apk' }
if (-not $Theme) { $Theme = Join-Path $repo 'themes\sapphire-arctic' }
$pkg = 'com.esamyo.luxurytv'
$stock = 'com.google.android.apps.tv.launcherx'

# Use a modern adb (pairing needs platform-tools 30+). The old C:\Android\adb.exe on PATH can't pair.
$adb = 'C:\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
function Step($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Run([string[]]$a) { & $adb @a; if ($LASTEXITCODE -ne 0) { throw "adb $($a -join ' ') failed ($LASTEXITCODE)" } }
function Sh([string]$cmd, [switch]$Soft) {
    $out = & $adb -s $Connect shell $cmd 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $Soft) { throw "adb shell '$cmd' failed: $out" }
    return $out
}

if ($Discover) {
    Step 'Looking for TVs with Wireless debugging on (mDNS)'
    & $adb mdns check
    & $adb mdns services
    Write-Host "Use the _adb-tls-pairing entry with -Pair (and the code on the TV), the _adb-tls-connect entry with -Connect."
    return
}
if (-not $Connect) { throw 'Give -Connect <ip:port> (Settings > Developer options > Wireless debugging), or use -Discover.' }

if ($Pair) {
    if (-not $Code) { throw 'Pairing needs -Code (the 6-digit code shown on the TV).' }
    Step "Pairing with $Pair"
    Run @('pair', $Pair, $Code)
}

Step "Connecting to $Connect"
Run @('connect', $Connect)
$state = (& $adb -s $Connect get-state 2>&1)
if ("$state".Trim() -ne 'device') { throw "TV not ready ($state). Check that Wireless debugging is still on and the port is current." }
$model = (Sh 'getprop ro.product.model').Trim()
$sdk = (Sh 'getprop ro.build.version.sdk').Trim()
Write-Host "Connected: $model (Android API $sdk)"

if ($Undo) {
    Step 'Undo: re-enabling the stock Google TV launcher'
    Sh "pm enable --user 0 $stock" -Soft | Out-Host
    Step 'Undo: Google TV screensaver back'
    Sh 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop' -Soft | Out-Host
    Step 'Undo: notification access for the live background'
    Sh "cmd notification disallow_listener $pkg/$pkg.LiveArtListener" -Soft | Out-Host
    Step 'Undo: Home/YouTube button helper off'
    Sh 'settings delete secure enabled_accessibility_services' -Soft | Out-Host
    Sh 'settings put secure accessibility_enabled 0' -Soft | Out-Host
    Step "Undo: uninstalling $pkg"
    & $adb -s $Connect uninstall $pkg
    Sh 'input keyevent KEYCODE_HOME' -Soft | Out-Null
    Write-Host "`nDone. The TV is back on its stock home screen." -ForegroundColor Green
    return
}

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (build it: launcher\gradlew.bat assembleRelease)" }
Step "Installing $(Split-Path -Leaf $Apk)"
Run @('-s', $Connect, 'install', '-r', $Apk)

Step 'Allowing auto-start (TCL TclAppBoot blocks services of new apps: theme, screensaver, live art, buttons)'
Sh "appops set $pkg AUTO_START allow" -Soft | Out-Null

Step 'Pushing the Sapphire Arctic theme'
# TCL (TclAppBoot) refuses to start a background app for a content provider; open the launcher first so its process runs.
Sh "am start -n $pkg/.CoverFlowActivity" | Out-Null
Start-Sleep -Seconds 3
$cfg = Get-Content (Join-Path $Theme 'config.json') -Raw | ConvertFrom-Json
if ($Latitude) { $cfg.latitude = $Latitude }
if ($Longitude) { $cfg.longitude = $Longitude }
if (-not $cfg.latitude -or -not $cfg.longitude) {
    Write-Warning 'No latitude/longitude: the weather stays hidden. Re-run with -Latitude and -Longitude for your city.'
}
$tmp = Join-Path $env:TEMP 'lt-config.json'
[IO.File]::WriteAllText($tmp, ($cfg | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$uri = "content://$pkg.theme"
Run @('-s', $Connect, 'push', $tmp, '/data/local/tmp/lt-config.json') | Out-Null
Sh "content write --uri $uri/config.json < /data/local/tmp/lt-config.json; rm /data/local/tmp/lt-config.json" | Out-Null
$rev = [DateTimeOffset]::Now.ToUnixTimeSeconds()
Sh "echo $rev > /data/local/tmp/lt-rev; content write --uri $uri/theme_revision < /data/local/tmp/lt-rev; rm /data/local/tmp/lt-rev" | Out-Null
Remove-Item $tmp

Step 'Making it the Home screen'
Sh "cmd package set-home-activity --user 0 $pkg/.CoverFlowActivity" | Out-Host

Step 'Setting the almanac screensaver'
Sh "settings put secure screensaver_components $pkg/.AlmanacDream" | Out-Null
Sh 'settings put secure screensaver_enabled 1' | Out-Null
Sh 'settings put system screen_off_timeout 15000' | Out-Null   # screensaver after 15 s idle (videos keep the TV awake)

Step 'Live background: media-session access (video and music apps) and Watch Next'
Sh "cmd notification allow_listener $pkg/$pkg.LiveArtListener" -Soft | Out-Host
$g = Sh "pm grant $pkg com.android.providers.tv.permission.READ_EPG_DATA" -Soft
if ("$g" -match 'Exception|not a changeable|Unknown') { Write-Host '  Watch Next permission not grantable on this TV; media-session backgrounds still work.' }

if ($ButtonHelper) {
    # TCL ignores set-home-activity, and disabling Google TV's launcher loops setupwraith's RecoveryActivity.
    # This accessibility service sends Home to this launcher and the YouTube button to youtube_button_app, if set.
    Step 'Home/YouTube button helper (accessibility service)'
    $cur = "$(Sh 'settings get secure enabled_accessibility_services' -Soft)".Trim()
    if ($cur -and $cur -ne 'null' -and $cur -notmatch 'HomeKeyService') { $svc = "${cur}:$pkg/.HomeKeyService" } else { $svc = "$pkg/.HomeKeyService" }
    Sh "settings put secure enabled_accessibility_services $svc" | Out-Null
    Sh 'settings put secure accessibility_enabled 1' | Out-Null
}

if ($DisableStockLauncher) {
    Step "Disabling the stock launcher ($stock) so Home always opens this one"
    Write-Host '  Undo any time with -Undo, or: adb shell pm enable --user 0' $stock
    Sh "pm disable-user --user 0 $stock" | Out-Host
}

Step 'Opening the new Home screen'
Sh 'input keyevent KEYCODE_HOME' | Out-Null
$homeAct = Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' -Soft
Write-Host "Home resolves to: $(($homeAct | Select-Object -Last 1))"
if ("$homeAct" -notmatch 'CoverFlowActivity') {
    Write-Warning 'Google TV still sends Home to its own launcher. Re-run with -DisableStockLauncher (reversible with -Undo).'
}
Write-Host "`nInstalled. Press Home on the remote." -ForegroundColor Green
