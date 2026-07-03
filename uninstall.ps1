<#
.SYNOPSIS
  Remove the DuckLake plugin from a DBeaver installation (Windows).
#>
param(
    [string]$DBeaverPath = "$env:LOCALAPPDATA\DBeaver"
)

$ErrorActionPreference = "Stop"
$plugins = Join-Path $DBeaverPath "plugins"
$bi = Join-Path $DBeaverPath "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"

if (Test-Path $bi) {
    $lines = Get-Content $bi | Where-Object { $_ -notmatch '^org\.jkiss\.dbeaver\.ext\.ducklake' }
    Set-Content -Path $bi -Value $lines -Encoding UTF8
}
Get-ChildItem $plugins -Filter 'org.jkiss.dbeaver.ext.ducklake*.jar' -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "DuckLake plugin removed. Restart DBeaver with -clean:"
Write-Host "    & `"$DBeaverPath\dbeaver.exe`" -clean"
