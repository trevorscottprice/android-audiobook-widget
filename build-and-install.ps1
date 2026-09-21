# Builds the debug APK and installs it on the connected device.
#   .\build-and-install.ps1
#
# Point JAVA_HOME and ANDROID_HOME at your own JDK 17 and Android SDK, or let
# them fall back to whatever is already on PATH.

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($env:ANDROID_HOME) { $env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH" }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }

$gradle = if (Get-Command gradle -ErrorAction SilentlyContinue) { 'gradle' }
          elseif (Test-Path '.\gradlew.bat') { '.\gradlew.bat' }
          else { throw 'Gradle not found. Install Gradle 8.x or add a wrapper.' }

Write-Host 'Building...' -ForegroundColor Cyan
& $gradle assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

$apk = 'app\build\outputs\apk\debug\app-debug.apk'

$devices = (adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    Write-Host ''
    Write-Host 'No device detected. On the phone:' -ForegroundColor Yellow
    Write-Host '  Settings > About phone > tap Build number 7x'
    Write-Host '  Settings > Developer options > USB debugging = on'
    Write-Host '  Replug the cable and accept the "Allow USB debugging?" prompt'
    Write-Host ''
    Write-Host 'Samsung devices: turn off Auto Blocker first, or USB debugging'
    Write-Host 'stays greyed out (Settings > Security and privacy > Auto Blocker).'
    Write-Host ''
    Write-Host "APK is ready at $apk"
    exit 1
}

Write-Host 'Installing...' -ForegroundColor Cyan
adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }

# Open the setup screen so notification access can be granted.
adb shell am start -n com.trevorprice.audiobookwidget/.MainActivity | Out-Null
Write-Host 'Installed. The setup screen should be open on your phone.' -ForegroundColor Green
