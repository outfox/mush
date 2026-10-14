# Builds the spike host and opens the embedding harness on Rider's JetBrains Runtime.
#   ./run.ps1 -Mode a            owned popup (Godot's own --wid embedding)
#   ./run.ps1 -Mode b [-Detach]  SetParent as WS_CHILD, optionally detaching the input queues
#   ./run.ps1 -Mode b -Auto out  scripted measurement with screenshots into ./out
param(
    [ValidateSet('a', 'b')][string]$Mode = 'b',
    [switch]$Detach,
    [switch]$Sync,
    [string]$Auto
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot/../.."
$java = Join-Path $env:LOCALAPPDATA 'Programs/Rider/jbr/bin/java.exe'

dotnet build "$root/spike/MushHost" -c Debug --nologo -v q
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$harnessArgs = @(
    '--mode', $Mode,
    '--host', "$root/spike/MushHost/bin/Debug/net10.0/MushHost.exe",
    '--file', "$root/samples/MushSample/scenes/sample_2d.tscn"
)
if ($Detach) { $harnessArgs += '--detach' }
if ($Sync) { $harnessArgs += '--sync' }
if ($Auto) { $harnessArgs += @('--auto', $Auto) }

& $java --enable-native-access=ALL-UNNAMED `
    --add-opens java.desktop/java.awt=ALL-UNNAMED `
    --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED `
    "$PSScriptRoot/Harness.java" @harnessArgs
