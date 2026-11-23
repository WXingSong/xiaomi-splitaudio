# Build route/AppRoute.java into app/src/main/assets/approute.dex on Windows.
# Requires JDK 17 and Android SDK (platforms;android-36 and build-tools with d8.bat).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { throw "Please set ANDROID_HOME or ANDROID_SDK_ROOT" }
$aj = Join-Path $sdk "platforms\android-36\android.jar"
if (-not (Test-Path $aj)) {
    throw "Missing $aj (install platforms;android-36)"
}

$btRoot = Join-Path $sdk "build-tools"
$btVer = if ($env:BUILD_TOOLS_VERSION) { $env:BUILD_TOOLS_VERSION } else { $null }
$d8 = $null
if ($btVer) {
    $cand = Join-Path $btRoot "$btVer\d8.bat"
    if (Test-Path $cand) { $d8 = $cand }
}
if (-not $d8) {
    $dirs = Get-ChildItem $btRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($d in $dirs) {
        $cand = Join-Path $d.FullName "d8.bat"
        if (Test-Path $cand) { $d8 = $cand; $btVer = $d.Name; break }
    }
}
if (-not $d8) {
    throw "No build-tools with d8.bat found under $btRoot"
}

Write-Host "Using platforms/android-36 and build-tools/$btVer"
$outDir = Join-Path $PSScriptRoot "out"
$clsDir = Join-Path $outDir "classes"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force $clsDir | Out-Null

& javac --release 8 -encoding UTF-8 -nowarn -cp $aj -d $clsDir AppRoute.java
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classFiles = Get-ChildItem $clsDir -Filter "*.class" | ForEach-Object { $_.FullName }
& $d8 --release --min-api 31 --lib $aj --output $outDir @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item -Force (Join-Path $outDir "classes.dex") (Join-Path $PSScriptRoot "..\app\src\main\assets\approute.dex")
Remove-Item -Recurse -Force $outDir
Write-Host "Built app/src/main/assets/approute.dex"
