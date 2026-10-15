# Validates the production launcher with a fully suspended native host and a backed-up stdin pipe.
param(
    [string]$RiderHome = (Join-Path $env:LOCALAPPDATA 'Programs/Rider')
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
Push-Location $root
try {
    & "$root/gradlew.bat" classes --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    dotnet build "$root/spike/MushHost" -c Debug --nologo -v q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $classpath = "$root/build/classes/kotlin/main;$RiderHome/lib/*"
    & "$RiderHome/jbr/bin/java.exe" --enable-native-access=ALL-UNNAMED `
        "-Djna.boot.library.path=$RiderHome/lib/jna/amd64" --class-path $classpath `
        "$PSScriptRoot/P1Probe.java" "$root/spike/MushHost/bin/Debug/net10.0/MushHost.exe"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
