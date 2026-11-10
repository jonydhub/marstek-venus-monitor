<#
.SYNOPSIS
  Build Marstek Venus Monitor for Windows: tests -> PyInstaller onedir -> portable zip -> Inno Setup installer.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File windows\build.ps1            # uses `python` on PATH
  powershell -ExecutionPolicy Bypass -File windows\build.ps1 -Python C:\path\to\venv\Scripts\python.exe
#>
param(
  [string]$Python = "python",
  [switch]$SkipTests,
  [switch]$SkipInstaller,
  [string]$Iscc = ""   # path to ISCC.exe if Inno Setup is not on PATH / in Program Files
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$version = (& $Python -c "import sys; sys.path.insert(0, 'windows'); import mvm; print(mvm.VERSION)").Trim()
Write-Host "Marstek Venus Monitor $version"

if (-not $SkipTests) {
  Push-Location server; & $Python -B -m unittest discover -s tests -q; if ($LASTEXITCODE) { throw "server tests failed" }; Pop-Location
  Push-Location windows; & $Python -B -m unittest discover -s tests -t . -q; if ($LASTEXITCODE) { throw "windows tests failed" }; Pop-Location
}

& $Python -m PyInstaller --noconfirm --clean --distpath windows\dist --workpath windows\build windows\packaging\MarstekVenusMonitor.spec
if ($LASTEXITCODE) { throw "PyInstaller failed" }

$zip = "windows\dist\MarstekVenusMonitor-$version-portable.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path windows\dist\MarstekVenusMonitor -DestinationPath $zip
Write-Host "portable: $zip"

if (-not $SkipInstaller) {
  $iscc = if ($Iscc) { $Iscc } else { (Get-Command iscc.exe -ErrorAction SilentlyContinue).Source }
  if (-not $iscc) {
    foreach ($p in @("$env:ProgramFiles(x86)\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
                     "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe")) { if (Test-Path $p) { $iscc = $p; break } }
  }
  if (-not $iscc) { Write-Warning "Inno Setup (ISCC.exe) not found: installer skipped, portable zip only" }
  else {
    & $iscc "/DAppVersion=$version" windows\packaging\installer.iss
    if ($LASTEXITCODE) { throw "Inno Setup failed" }
  }
}
Get-ChildItem windows\dist -File | ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.Name }
