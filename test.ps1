<#
.SYNOPSIS
  Run the plugin's unit tests (Windows).

.DESCRIPTION
  Compiles the model bundle plus plugin\org.jkiss.dbeaver.ext.ducklake\test against the jars of a
  DBeaver install (like build.ps1) and runs the query guard and default data filter tests. No
  JUnit, no Maven: each test class is a plain main() that exits non-zero on the first failed check.

.PARAMETER DBeaverPath
  DBeaver install dir (contains plugins/). Default: %LOCALAPPDATA%\DBeaver.
.PARAMETER JdkPath
  JDK 21 home. Default: C:\Program Files\Java\jdk-21.
#>
param(
    [string]$DBeaverPath = "$env:LOCALAPPDATA\DBeaver",
    [string]$JdkPath = "C:\Program Files\Java\jdk-21"
)

$ErrorActionPreference = "Stop"
$root  = $PSScriptRoot
$javac = Join-Path $JdkPath "bin\javac.exe"
$java  = Join-Path $JdkPath "bin\java.exe"
$cp    = Join-Path $DBeaverPath "plugins\*"
$model = Join-Path $root "plugin\org.jkiss.dbeaver.ext.ducklake"
$out   = Join-Path $model "target\test-classes"

if (-not (Test-Path (Join-Path $DBeaverPath "plugins"))) { throw "DBeaver plugins not found (set -DBeaverPath)" }
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

$sources = @(Get-ChildItem "$model\src", "$model\test\src" -Recurse -Filter *.java).FullName
& $javac --release 21 -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

& $java -cp "$out;$cp" org.jkiss.dbeaver.ext.ducklake.model.guard.QueryGuardTest "$model\test\resources"
if ($LASTEXITCODE -ne 0) { throw "tests failed" }

& $java -cp "$out;$cp" org.jkiss.dbeaver.ext.ducklake.model.guard.NewestFileFilterTest
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
