# Build one tutorial chapter into an AArch64 Android shared object.
#
#   pwsh -File tutorial/build.ps1 01-missing-information
#
# Output: tutorial/build/<chapter>.so
#
# Keep -O2 so the compiler performs the same stack-slot reuse, inlining and SSA
# merging the real target shows; that is what the refine chapters fix. Chapter
# 05 needs exceptions/LSDA, so it opts out of -fno-exceptions.

param(
    [Parameter(Mandatory = $true)][string]$Chapter
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root "tutorial"))) {
    $root = $PSScriptRoot
    $root = Split-Path -Parent $root
}

$src = Join-Path $root "tutorial\$Chapter\src"
$out = Join-Path $root "tutorial\build"
if (-not (Test-Path -LiteralPath $src)) { throw "missing chapter source: $src" }
New-Item -ItemType Directory -Path $out -Force | Out-Null

if (-not $env:ANDROID_NDK_HOME) {
    $candidates = @(
        "C:\tools\android-sdk\ndk\29.0.14206865",
        "$env:ANDROID_HOME\ndk\29.0.14206865"
    )
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c) { $env:ANDROID_NDK_HOME = $c; break } }
}
if (-not $env:ANDROID_NDK_HOME) { throw "set ANDROID_NDK_HOME to an r27+ NDK" }

$bin = Join-Path $env:ANDROID_NDK_HOME "toolchains\llvm\prebuilt\windows-x86_64\bin"
# Use clang++.exe directly with an explicit --target. The *-clang++.cmd wrapper
# is a batch file whose %* expansion breaks under PowerShell argument passing.
$clangxx = Join-Path $bin "clang++.exe"
if (-not (Test-Path -LiteralPath $clangxx)) { throw "missing compiler: $clangxx" }

$flags = @("--target=aarch64-linux-android29", "-O2", "-shared", "-fPIC", "-fno-rtti", "-fvisibility=default")
if ($Chapter -eq "05-lost-boundary") { $flags += "-fexceptions" } else { $flags += "-fno-exceptions" }

$sources = Get-ChildItem -LiteralPath $src -Filter *.cpp | ForEach-Object { $_.FullName }
if (-not $sources) { throw "no .cpp under $src" }

$target = Join-Path $out "$Chapter.so"

# Pass everything through a clang response file. PowerShell's native-command
# argument binder mishandles the '+' in "clang++.exe" in some host contexts and
# splits paths into single characters; a response file avoids that entirely.
$rsp = Join-Path $out "$Chapter.rsp"
$lines = @()
$lines += $flags
$lines += "-o"
$lines += '"' + ($target -replace '\\', '/') + '"'
foreach ($s in $sources) { $lines += '"' + ($s -replace '\\', '/') + '"' }
Set-Content -LiteralPath $rsp -Value $lines -Encoding ascii

& $clangxx "@$rsp"
if ($LASTEXITCODE -ne 0) { throw "compile failed with exit code $LASTEXITCODE" }
Write-Output "wrote $target"
