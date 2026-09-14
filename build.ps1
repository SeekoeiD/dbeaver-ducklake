<#
.SYNOPSIS
  Build the DuckLake plugin jars from source (Windows).

.DESCRIPTION
  The plugin extends DBeaver's bundled ext.duckdb / ext.generic plugins, so it compiles
  directly against the bundles in an existing DBeaver install (no Maven/Tycho needed).
  Output jars are written to plugin/dist/.

.PARAMETER DBeaverPath
  DBeaver install dir (contains plugins/). Default: %LOCALAPPDATA%\DBeaver.
.PARAMETER JdkPath
  JDK 21 home. Default: C:\Program Files\Java\jdk-21.

.EXAMPLE
  .\build.ps1
#>
param(
    [string]$DBeaverPath = "$env:LOCALAPPDATA\DBeaver",
    [string]$JdkPath = "C:\Program Files\Java\jdk-21"
)

$ErrorActionPreference = "Stop"
$root  = $PSScriptRoot
$javac = Join-Path $JdkPath "bin\javac.exe"
$jar   = Join-Path $JdkPath "bin\jar.exe"
$cp    = Join-Path $DBeaverPath "plugins\*"
$model = Join-Path $root "plugin\org.jkiss.dbeaver.ext.ducklake"
$ui    = Join-Path $root "plugin\org.jkiss.dbeaver.ext.ducklake.ui"
$dist  = Join-Path $root "plugin\dist"

foreach ($p in @($javac, $jar)) { if (-not (Test-Path $p)) { throw "Not found: $p (set -JdkPath)" } }
if (-not (Test-Path (Join-Path $DBeaverPath "plugins"))) { throw "DBeaver plugins not found (set -DBeaverPath)" }
New-Item -ItemType Directory -Force $dist | Out-Null

# Jar names carry the bundle version from the model MANIFEST (both bundles share it).
$version = (Select-String -Path "$model\META-INF\MANIFEST.MF" -Pattern '^Bundle-Version:\s*(\S+)').Matches[0].Groups[1].Value
Remove-Item "$dist\*.jar" -ErrorAction SilentlyContinue

# --- model bundle ---
$modelOut = Join-Path $model "target\classes"
New-Item -ItemType Directory -Force $modelOut | Out-Null
& $javac --release 21 -encoding UTF-8 -cp $cp -d $modelOut (Get-ChildItem "$model\src" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE -ne 0) { throw "model compile failed" }
Copy-Item "$model\plugin.xml" "$modelOut\plugin.xml" -Force
New-Item -ItemType Directory -Force "$modelOut\icons" | Out-Null
Copy-Item "$model\icons\*.svg" "$modelOut\icons\" -Force
& $jar --create --file "$dist\org.jkiss.dbeaver.ext.ducklake_$version.jar" --manifest="$model\META-INF\MANIFEST.MF" -C $modelOut .

# --- ui bundle (needs model classes on classpath) ---
$uiOut = Join-Path $ui "target\classes"
New-Item -ItemType Directory -Force $uiOut | Out-Null
& $javac --release 21 -encoding UTF-8 -cp "$modelOut;$cp" -d $uiOut (Get-ChildItem "$ui\src" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE -ne 0) { throw "ui compile failed" }
Copy-Item "$ui\plugin.xml" "$uiOut\plugin.xml" -Force
& $jar --create --file "$dist\org.jkiss.dbeaver.ext.ducklake.ui_$version.jar" --manifest="$ui\META-INF\MANIFEST.MF" -C $uiOut .

Write-Host "Built:"
Get-ChildItem $dist -Filter *.jar | ForEach-Object { Write-Host ("  " + $_.FullName + "  (" + $_.Length + " bytes)") }
