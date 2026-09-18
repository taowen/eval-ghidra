# Launch a second, independent Ghidra instance for the eval-ghidra tutorial.
#
# Layout
#   install    C:\tools\ghidra-tutorial          (copy of Ghidra 12.1.2 with
#                                                 GhidraMCP 6.0.0 installed)
#   settings   C:\games\eval-ghidra\ghidra-tutorial\user
#   cache      C:\games\eval-ghidra\ghidra-tutorial\cache
#   project    C:\games\eval-ghidra\ghidra-tutorial\projects\tutorial.gpr
#
# Ports
#   This instance owns 127.0.0.1:8090. The option was pinned once with
#   ghidra_scripts/PinMcpPort.java (Tools > GhidraMCP HTTP Server > Server
#   Port). The ARLauncher instance keeps 127.0.0.1:8090's sibling, 8089.
#   Because the settings dir is separate, the two instances never share Tool
#   Options or tool layout.
#
# Point the eval-ghidra client at this instance with:
#   $env:GHIDRA_MCP_URL = "http://127.0.0.1:8090"
#
# Usage:  pwsh -File start-tutorial-ghidra.ps1

$ErrorActionPreference = "Stop"

$Install     = "C:\tools\ghidra-tutorial"
$SettingsDir = "C:\games\eval-ghidra\ghidra-tutorial\user"
$CacheDir    = "C:\games\eval-ghidra\ghidra-tutorial\cache"
$ProjectDir  = "C:\games\eval-ghidra\ghidra-tutorial\projects"
$ProjectName = "tutorial"
$Port        = 8090

foreach ($d in @($SettingsDir, $CacheDir, $ProjectDir)) {
    if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

if (-not (Test-Path -LiteralPath "$Install\ghidraRun.bat")) {
    throw "tutorial Ghidra install missing: $Install\ghidraRun.bat"
}
if (-not (Test-Path -LiteralPath "$Install\Ghidra\Extensions\GhidraMCP\extension.properties")) {
    throw "GhidraMCP extension not installed in $Install."
}

# Warn if the expected port is already taken by something else.
$busy = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($busy) {
    Write-Warning "port $Port is already in use (pid $($busy.OwningProcess)); GhidraMCP will pick a fallback port"
}

# System properties that give this instance its own settings/cache directories.
$env:JAVA_USER_HOME_DIR_OVERRIDE =
    "-Dapplication.settingsdir=$SettingsDir -Dapplication.cachedir=$CacheDir"
$env:GHIDRA_MCP_ALLOW_SCRIPTS = "1"

& "$Install\ghidraRun.bat" "$ProjectDir\$ProjectName.gpr"
