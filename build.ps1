param(
    [string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll',
    [string]$ToolsRoot = "$PSScriptRoot/../开发资料/tools",
    [string]$JdkHome = "$ToolsRoot/jdk25/jdk-25.0.4.1"
)
$ErrorActionPreference = 'Stop'
$HostJar = (Resolve-Path -LiteralPath $HostJar).Path
$ToolsRoot = (Resolve-Path -LiteralPath $ToolsRoot).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
Push-Location -LiteralPath $PSScriptRoot
try {
$workspace = $PSScriptRoot
$java = Join-Path $JdkHome 'bin\java.exe'
$stage = Join-Path $PSScriptRoot ('build\staging-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
$tests = Join-Path $stage 'tests'
New-Item -ItemType Directory -Path $classes,$tests -Force | Out-Null
Write-Output "Build stage: $stage"
$reference = (Resolve-Path -LiteralPath $HostJar).Path
$runtimeRelease = Join-Path (Split-Path (Split-Path $HostJar)) 'runtime\release'
$moduleLine = Get-Content -LiteralPath $runtimeRelease | Where-Object { $_ -match '^MODULES="([^"]+)"$' }
if (!$moduleLine) { throw 'Cannot determine the modules available in the installed SPW runtime.' }
$runtimeModules = ($moduleLine -replace '^MODULES="|"$', '') -replace ' ', ','
$compiler = Join-Path $ToolsRoot 'kotlin-compiler-embeddable.jar'
$compose = Join-Path $ToolsRoot 'kotlin-compose-compiler-plugin-embeddable.jar'
$annotations = Join-Path $ToolsRoot 'annotations.jar'
$stdlib = Join-Path $ToolsRoot 'kotlin-stdlib-2.4.20.jar'
$yaml = Join-Path $ToolsRoot 'snakeyaml-2.7.jar'
if ((Get-FileHash -LiteralPath $yaml).Hash -ne '2E194EBA45A67DEE19A4E272F4A04B18DE8054E9F598B094382F6DAE0B0E4B5E') { throw 'Unexpected SnakeYAML dependency.' }
if ((Get-FileHash -LiteralPath $stdlib).Hash -ne '2226DE463D309D4A5500A481320B3DEA515A6981DCAE1DEF531FBC158884E25F') { throw 'Unexpected Kotlin stdlib build dependency.' }
# Inline against the original stdlib; SPW's optimized inline bodies are unsafe compiler input.
$compileReference = Join-Path $ToolsRoot 'spw-host.jar'
$compileClasspath = "$yaml;$stdlib;$compileReference"
& "$workspace/shared/build-host-resolver.ps1" -ToolsRoot $ToolsRoot -Classes $classes -JdkHome $JdkHome
$compileClasspath = "$classes;$compileClasspath"
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src\main\kotlin\local\spw\lyrics" -Filter '*.kt' -File | ForEach-Object FullName)
& $java -cp "$compiler;$stdlib;$reference;$annotations" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $compileClasspath "-Xplugin=$compose" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Lyrics plugin compilation failed.' }
Copy-Item -Path "$PSScriptRoot\src\main\resources\*" -Destination $classes -Recurse -Force
$yamlArchive = [IO.Compression.ZipFile]::OpenRead($yaml)
try {
    foreach ($entry in $yamlArchive.Entries) {
        if ($entry.FullName.StartsWith('org/yaml/snakeyaml/') -and $entry.FullName.EndsWith('.class')) {
            $target = Join-Path $classes $entry.FullName
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    }
} finally { $yamlArchive.Dispose() }
if (!(Test-Path -LiteralPath (Join-Path $classes 'licenses/SnakeYAML-LICENSE.txt'))) { throw 'SnakeYAML license missing.' }
$testSources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src\test\kotlin\local\spw\lyrics" -Filter '*.kt' -File -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($testSources.Count) {
    & "$workspace/shared/test-host-resolver.ps1" -ToolsRoot $ToolsRoot -Classes $classes -Tests $tests -JdkHome $JdkHome -HostJar $reference
    & $java -cp "$compiler;$stdlib;$reference;$annotations" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$tests;$classes;$compileClasspath" "-Xplugin=$compose" -d $tests @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Lyrics self-check compilation failed.' }
    # Thousands of tag-reader stderr lines through PowerShell can stall the timed worker; drain both pipes directly.
    $testInfo = [Diagnostics.ProcessStartInfo]::new($java)
    $testInfo.WorkingDirectory = $workspace
    $testInfo.UseShellExecute = $false; $testInfo.CreateNoWindow = $true
    $testInfo.RedirectStandardOutput = $true; $testInfo.RedirectStandardError = $true
    foreach ($argument in @('-ea','--limit-modules',$runtimeModules,'--enable-native-access=ALL-UNNAMED',
        '-Djava.util.prefs.PreferencesFactory=local.spw.lyrics.TestPreferencesFactory',"-Dspw.tools.path=$ToolsRoot",
        "-Dskiko.library.path=$(Split-Path $HostJar)",'-cp',"$tests;$classes;$reference",'local.spw.lyrics.LyricsSelfTestKt')) {
        $testInfo.ArgumentList.Add($argument)
    }
    $testProcess = [Diagnostics.Process]::Start($testInfo)
    try {
        $testOutput = $testProcess.StandardOutput.ReadToEndAsync()
        $testErrors = $testProcess.StandardError.ReadToEndAsync()
        $testProcess.WaitForExit()
        Write-Output $testOutput.GetAwaiter().GetResult()
        Write-Output $testErrors.GetAwaiter().GetResult()
        if ($testProcess.ExitCode -ne 0) { throw 'Lyrics self-check failed; package not updated.' }
    } finally { $testProcess.Dispose() }
}
$meta = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Path $meta -Force | Out-Null
$manifest = @(
    'Manifest-Version: 1.0'
    'Plugin-Class: local.spw.lyrics.LyricsPlugin'
    'Plugin-Id: local.spw.lyrics'
    'Plugin-Version: 0.6.26'
    'Plugin-Provider: Local'
    'Plugin-Name: Better Tag for SPW'
    'Plugin-Description: 歌词匹配、逐字时间轴、原生排版、音乐标签与封面写入'
    'Plugin-Has-Config: false'
    'Plugin-Permissions: library-read,library-write,class-transform'
    ''
    ''
) -join "`r`n"
[IO.File]::WriteAllText((Join-Path $meta 'MANIFEST.MF'), $manifest, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $meta 'extensions.idx'), "local.spw.lyrics.LyricsPlayback`n", [Text.UTF8Encoding]::new($false))
$output = Join-Path $PSScriptRoot 'build\libs\better-tag-for-spw-0.6.26.spmod'
New-Item -ItemType Directory -Path (Split-Path $output) -Force | Out-Null
$archive = [IO.Compression.ZipArchive]::new([IO.File]::Open($output, [IO.FileMode]::Create), [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $classes -File -Recurse) {
        $relative = [IO.Path]::GetRelativePath($classes, $file.FullName).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "classes/$relative") | Out-Null
    }
} finally { $archive.Dispose() }
Get-Item -LiteralPath $output | Select-Object FullName,Length
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Hash



} finally { Pop-Location }
