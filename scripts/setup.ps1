<#
  One-command setup on Windows 10 or 11. From the repository folder, in PowerShell:

    powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
    powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 -WithGuideTools

  Installs JDK 21 with winget if it is missing, then runs a first build through the
  Gradle wrapper, which downloads Gradle, the Kotlin compiler and the test libraries.
  -WithGuideTools also installs Python and Node.js, needed only to edit the guide.
  Safe to run again: anything already installed is left alone.

  "-ExecutionPolicy Bypass" applies to this one run only. Windows blocks unsigned
  scripts by default; it does not change any system setting.
#>
param([switch]$WithGuideTools)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# Major version of the java on PATH, or 0 if there is none.
function Get-JavaMajor {
  # java -version writes to stderr; don't let PowerShell treat that as an error.
  $ErrorActionPreference = 'Continue'
  try { $line = (& java -version 2>&1 | Select-Object -First 1) | Out-String } catch { return 0 }
  if ($line -match 'version "(\d+)') { return [int]$Matches[1] } else { return 0 }
}

# After winget installs something, this session still has the old PATH; reload it.
function Update-SessionPath {
  $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
              [Environment]::GetEnvironmentVariable('Path', 'User')
}

function Install-WithWinget([string]$Id) {
  if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget was not found. Install 'App Installer' from the Microsoft Store, then run this script again."
  }
  # winget's exit codes vary between versions (e.g. "already installed" is non-zero),
  # so success is judged by checking for the tool afterwards, not by the exit code.
  winget install --id $Id --exact --silent --accept-source-agreements --accept-package-agreements
  Update-SessionPath
}

if ((Get-JavaMajor) -ge 21) {
  Write-Host '==> JDK 21 already installed'
} else {
  Write-Host '==> Installing JDK 21 (Eclipse Temurin)'
  Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK'
  $temurin = Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ((Get-JavaMajor) -lt 21 -and -not $temurin) {
    throw 'JDK 21 is still not available after installing. Open a new terminal and run this script again.'
  }
}

if ($WithGuideTools) {
  Write-Host '==> Installing Python and Node.js for the guide tools'
  Install-WithWinget 'Python.Python.3.12'
  Install-WithWinget 'OpenJS.NodeJS.LTS'
}

Write-Host '==> First build: downloads Gradle and the libraries (a few minutes the first time)'
& .\gradlew.bat --quiet assemble testClasses
if ($LASTEXITCODE -ne 0) { throw "The first build failed (exit code $LASTEXITCODE)." }

Write-Host ''
Write-Host 'Ready. Run the demo with:   .\gradlew.bat run'
Write-Host 'Run the tests with:         .\gradlew.bat test'
