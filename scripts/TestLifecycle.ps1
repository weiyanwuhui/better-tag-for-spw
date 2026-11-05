param(
    [string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll',
    [string]$NativeAppDirectory = 'E:\Steam\steamapps\common\Salt Player for Windows\app',
    [string]$BuildLog = "$PSScriptRoot/../build/build-0.6.26-verified.log",
    [string]$Package = "$PSScriptRoot/../build/libs/better-tag-for-spw-0.6.26.spmod"
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot
$tools = [IO.Path]::GetFullPath((Join-Path $workspace '../开发资料/tools'))
$jdk = Join-Path $tools 'jdk25/jdk-25.0.4.1/bin'
$firstLine = Get-Content -LiteralPath $BuildLog -TotalCount 1
if ($firstLine -notmatch '^Build stage: (.+)$') { throw 'Build stage missing.' }
$stage = $Matches[1]
$testRoot = Join-Path $workspace ('build/lifecycle-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $testRoot 'classes'
# The native Attach agent requires an ASCII path on this runtime.
$profile = Join-Path $env:TEMP ('spw-lyrics-lifecycle-' + [guid]::NewGuid().ToString('N'))
$support = Join-Path $testRoot 'support'
New-Item -ItemType Directory -Force -Path $classes,$profile,"$support/local/spw/host","$support/local/spw/lyrics" | Out-Null
Get-ChildItem -LiteralPath "$stage/classes/local/spw/host" -Filter '*.class' | Copy-Item -Destination "$support/local/spw/host"
foreach ($name in @('TestPreferencesFactory','TestPreferences')) {
    Copy-Item -LiteralPath "$stage/tests/local/spw/lyrics/$name.class" -Destination "$support/local/spw/lyrics"
}
$classpath = "$support;$tools/kotlin-metadata-jvm-2.4.20.jar;$HostJar"
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $classpath -d $classes (Join-Path $workspace 'src/testRuntime/java/local/spw/lyrics/PluginLifecycleProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Lyrics lifecycle probe compilation failed.' }
$priorAppData = $env:APPDATA
try {
    $env:APPDATA = $profile
    & "$jdk/java.exe" -ea --enable-native-access=ALL-UNNAMED '-Djdk.attach.allowAttachSelf=true' `
        '-Djava.util.prefs.PreferencesFactory=local.spw.lyrics.TestPreferencesFactory' "-Djava.io.tmpdir=$profile" `
        "-Dskiko.library.path=$NativeAppDirectory" "-Dcompose.application.resources.dir=$NativeAppDirectory/resources" `
        -cp "$classes;$classpath" local.spw.lyrics.PluginLifecycleProbe $profile $Package "$NativeAppDirectory/resources"
    if ($LASTEXITCODE -ne 0) { throw 'Lyrics package lifecycle regression failed.' }
} finally { $env:APPDATA = $priorAppData }
