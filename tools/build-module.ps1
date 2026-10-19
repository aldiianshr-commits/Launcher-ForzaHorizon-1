<#
.SYNOPSIS
Builds the FHorizon Mobile backend module on a Windows PC from your own
Forza Horizon disc image (USA retail, MS-2505).

.DESCRIPTION
Uses Pinyon Shift's own tools at a pinned commit:
  1. clone Pinyon Shift and its ShiftGlue SDK submodule;
  2. setup-preview.ps1: verify the ISO, extract it, run ReXGlue/ShiftGlue
     codegen (PowerPC -> C++) and build the PC game (needed by the Android
     build, which reuses the generated code);
  3. pinyon.py android doctor/build: download the pinned Android SDK/NDK
     (after you accept the license) and cross-compile for arm64;
  4. tools/fhorizon_module.py: repack as fhorizon-module-*.zip.
About 45 GB free and 30-90 minutes. Nothing is uploaded.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools\build-module.ps1 -IsoPath D:\forza-horizon.iso
#>
param(
    [Parameter(Mandatory)] [string]$IsoPath,
    [string]$PinyonCommit = '9331e453e9850d0fcc1c1606dee95db834a9d317',
    [string]$PinyonRepo = 'https://github.com/arcanite24/pinyon-shift',
    [string]$Work = (Join-Path (Split-Path $PSScriptRoot -Parent) '.work'),
    [switch]$AcceptLicenses
)
$ErrorActionPreference = 'Stop'
$here = Split-Path $PSScriptRoot -Parent
$iso = (Resolve-Path -LiteralPath $IsoPath).Path
foreach ($tool in 'git', 'python') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required" }
}
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$pinyon = Join-Path $Work 'pinyon-shift'
if (-not (Test-Path (Join-Path $pinyon '.git'))) {
    Write-Host '== cloning Pinyon Shift'
    git clone --filter=blob:none $PinyonRepo $pinyon
}
Write-Host "== checking out Pinyon Shift $PinyonCommit"
git -C $pinyon fetch --depth 1 origin $PinyonCommit
git -C $pinyon checkout --quiet $PinyonCommit
git -C $pinyon submodule update --init --recursive --depth 1

Push-Location $pinyon
try {
    Write-Host '== verifying, extracting and recompiling the game (setup-preview.ps1)'
    & (Join-Path $pinyon 'tools\setup-preview.ps1') -IsoPath $iso
    Write-Host '== Android toolchain'
    python tools\pinyon.py android doctor
    if ($LASTEXITCODE -ne 0) {
        if ($AcceptLicenses) { python tools\pinyon.py android doctor --install --accept-licenses }
        else { python tools\pinyon.py android doctor --install }
        if ($LASTEXITCODE -ne 0) { throw 'Android toolchain incomplete' }
    }
    Write-Host '== cross-compiling for Android arm64'
    python tools\pinyon.py android build
    if ($LASTEXITCODE -ne 0) { throw 'android build failed' }
} finally {
    Pop-Location
}
Write-Host '== packing the FHorizon module'
python -I (Join-Path $here 'tools\fhorizon_module.py') from-apk (Join-Path $pinyon '.local\android\pinyon-shift.apk') -o (Join-Path $here 'module-out')
if ($LASTEXITCODE -ne 0) { throw 'packing failed' }
$gameRoot = Join-Path $pinyon '.local\game\base'
Write-Host ""
Write-Host "Done."
Write-Host "  Module:     $here\module-out\  -> copy the newest fhorizon-module-*.zip to /storage/emulated/0/FHorizon/Module/"
Write-Host "  Game files: $gameRoot  -> copy this folder to /storage/emulated/0/FHorizon/Game/"
Write-Host "Keep both private: they come from your own disc."
