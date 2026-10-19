# Local Windows builds (26.2 / 26.3)

The four 0.1.9 client jars for Minecraft 26.2 and 26.3 were built locally with
Temurin 25.0.4.1 and cached Gradle 9.8.1. All four passed `validate_jar.py` with
Java 25, and none contains the separate runtime test mod. These are actual
Gradle builds of generated platform sources and the shared client sources,
not repackaged CI jars. The normal release workflow still builds/publishes its
own artifacts in CI.

## Selector-pipe workaround on this machine

Gradle initially failed while connecting to its daemon with
`IOException: Unable to establish loopback connection`. The nested cause was
`SocketException: Invalid argument: connect` in
`sun.nio.ch.UnixDomainSockets.connect`, reached through `PipeImpl` and
`WEPollSelectorImpl`. Ordinary TCP sockets worked; forcing IPv4 did not help.

Inspection of the installed JDK showed that its selector pipe prefers a Unix
socket, and falls back to TCP if the Unix listener cannot bind. The local
build processes used a deliberately nonexistent socket-directory path through
`jdk.net.unixdomain.tmpdir`, causing that existing fallback. This setting was
passed only to the build processes via `JAVA_TOOL_OPTIONS`. No Java installation,
system setting, game configuration or client source was changed.

For the same observed failure, the build-process setting is:

```powershell
# Choose a path that does not exist; do not create it.
$socketFallbackPath = Join-Path (Get-Location) 'release-output/gradle-no-unix-socket-dir'
if (Test-Path -LiteralPath $socketFallbackPath) { throw 'Choose a nonexistent path' }
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = ($previousJavaToolOptions + ' "-Djdk.net.unixdomain.tmpdir=' + $socketFallbackPath + '"').Trim()
    # Generate the desired target, then run its Gradle build normally.
    python tools/port-version/port.py 26.3
    Push-Location minecraft/fabric
    try { .\gradlew.bat --no-daemon build } finally { Pop-Location }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
```

Use a Java 25 `JAVA_HOME` for these versions. Do not apply this workaround to
unrelated machines without the same nested Unix-domain socket failure.

The simultaneous local builds used isolated roots under ignored
`release-output/local-builds/<minecraft>/`, with `client` junctions pointing
back into this workspace and the repository LICENSE copied into each root.
The generator's `repoRoot = ../..` source/resource layout was preserved.
The generated build files were not edited. Each Forge build also rebuilt its
Minecraft Mavenizer dependencies before compiling the mod.

The user-delivered local jars and `checksums-sha256.txt` are in ignored
`release-output/local-jars-0.1.9/`; `build-info.json` records their provenance.
Jar class totals are Fabric/Forge 385/384 for 26.3 and 384/383 for 26.2.
