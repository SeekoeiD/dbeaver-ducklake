<#
.SYNOPSIS
  Install the DuckLake plugin into a DBeaver installation (Windows).

.DESCRIPTION
  This DBeaver build loads bundles from Equinox simpleconfigurator (bundles.info),
  not from loose dropins. So this script copies the two plugin jars into DBeaver's
  plugins/ folder and registers them in bundles.info. Run the two jars + this script
  from the same folder (that's how the GitHub Release is packaged).

.PARAMETER DBeaverPath
  Path to the DBeaver install (the folder containing dbeaver.exe, plugins/, configuration/).
  Defaults to %LOCALAPPDATA%\DBeaver (DBeaver Community default on Windows).

.EXAMPLE
  .\install.ps1
  .\install.ps1 -DBeaverPath "C:\Program Files\DBeaver"
#>
param(
    [string]$DBeaverPath = "$env:LOCALAPPDATA\DBeaver"
)

$ErrorActionPreference = "Stop"
$src = $PSScriptRoot
$jars = @(
    "org.jkiss.dbeaver.ext.ducklake_1.0.0.jar",
    "org.jkiss.dbeaver.ext.ducklake.ui_1.0.0.jar"
)

$plugins = Join-Path $DBeaverPath "plugins"
$bi = Join-Path $DBeaverPath "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"

if (-not (Test-Path $plugins)) { throw "DBeaver plugins folder not found: $plugins  (pass -DBeaverPath)" }
if (-not (Test-Path $bi))      { throw "bundles.info not found: $bi  (pass -DBeaverPath)" }
foreach ($j in $jars) {
    if (-not (Test-Path (Join-Path $src $j))) { throw "Missing $j next to this script." }
}

# Copy jars
foreach ($j in $jars) { Copy-Item (Join-Path $src $j) (Join-Path $plugins $j) -Force }

# Register in bundles.info (idempotent; back up original once)
if (-not (Test-Path "$bi.orig")) { Copy-Item $bi "$bi.orig" }
$lines = Get-Content $bi | Where-Object { $_ -notmatch '^org\.jkiss\.dbeaver\.ext\.ducklake' }
$lines += 'org.jkiss.dbeaver.ext.ducklake,1.0.0,plugins/org.jkiss.dbeaver.ext.ducklake_1.0.0.jar,4,false'
$lines += 'org.jkiss.dbeaver.ext.ducklake.ui,1.0.0,plugins/org.jkiss.dbeaver.ext.ducklake.ui_1.0.0.jar,4,false'
Set-Content -Path $bi -Value $lines -Encoding UTF8

Write-Host "DuckLake plugin installed into $DBeaverPath"
Write-Host "Now (re)start DBeaver ONCE with -clean so it rebuilds from bundles.info:"
Write-Host "    & `"$DBeaverPath\dbeaver.exe`" -clean"
