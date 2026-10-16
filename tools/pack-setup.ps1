<#
  Packs the one-click installer for a release: BeamMod-Setup-<version>.zip with Установить.cmd, tools\setup.ps1,
  tools\restore.ps1 and this release's APKs: Beam Mod and its XGIMI settings entry (so they need no download).
    .\tools\pack-setup.ps1 -ApkDir .publish\release-1.1 -Tag v1.1
  The APKs are checked against SHA256SUMS.txt in -ApkDir. The ZIP goes to build\setup\.
#>
param(
    [Parameter(Mandatory)][string]$ApkDir,
    [Parameter(Mandatory)][string]$Tag
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$names = @("app-release.apk", "remote-entry-release.apk")
$sums = @{}
foreach ($line in Get-Content (Join-Path $ApkDir "SHA256SUMS.txt")) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$') { $sums[$Matches[2]] = $Matches[1].ToLower() }
}
$out = Join-Path $Root "build\setup"
$stage = Join-Path $out ("BeamMod-Setup-" + $Tag.TrimStart("v"))
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path (Join-Path $stage "tools\apks") | Out-Null
foreach ($name in $names) {
    $file = Join-Path $ApkDir $name
    if ((Get-FileHash -Algorithm SHA256 $file).Hash.ToLower() -ne $sums[$name]) { throw "$name does not match SHA256SUMS.txt" }
    Copy-Item $file (Join-Path $stage "tools\apks\$name")
}
Copy-Item (Join-Path $PSScriptRoot "setup.ps1"), (Join-Path $PSScriptRoot "restore.ps1") (Join-Path $stage "tools")
Copy-Item (Join-Path $PSScriptRoot "Установить.cmd") $stage
Copy-Item (Join-Path $Root "LICENSE") $stage
$zip = "$stage.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# The folder itself goes into the ZIP, so "Extract all" gives one tidy folder.
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Host "$zip  $((Get-Item $zip).Length) bytes  sha256 $((Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower())"