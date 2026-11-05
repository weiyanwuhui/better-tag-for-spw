# 构建歌词增强

需要 PowerShell 7、JDK 25、Kotlin／Compose compiler 2.4.20、Kotlin stdlib／Metadata JVM 2.4.20、annotations、SnakeYAML 2.7 和本地安装的 SPW。工具当前在 `../../开发资料/tools/`，也可指定外部目录。

从本 Mod 根目录执行：

```powershell
New-Item -ItemType Directory -Path build -Force | Out-Null
& ./build.ps1 -HostJar 'E:/Steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll' -ToolsRoot '../开发资料/tools' *> build/build.log
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& ./scripts/TestLifecycle.ps1 -BuildLog build/build.log -Package build/libs/better-tag-for-spw-0.6.26.spmod
& ./scripts/AuditRelease.ps1 -Version 0.6.26 -Package build/libs/better-tag-for-spw-0.6.26.spmod -BuildLog build/build.log -SourceRoot src/main
```

`TestLifecycle.ps1` 在临时 APPDATA 和内存 Preferences 中测试真实安装包的导入、六个 Hook 注册、启用中更新和停用，生产插件类只由 PF4J 加载；不会使用真实曲库。默认从已安装的 SPW `app/resources` 配置测试进程的六个音频运行库，其他安装位置使用 `-NativeAppDirectory` 指定。可用 `-HostJar` 检查旧宿主归档。测试进程会加载音频引擎，但不播放歌曲。

完整自检直接并行读取 JVM stdout/stderr，防止数万行音频标签日志通过 PowerShell 管道反压影响 200 首模拟测试；不修改 30 秒门槛。编译仍使用原始 `spw-host.jar` 与 stdlib 的内联实现，实际运行和 Hook 验证使用 `-HostJar` 对应宿主；不能用优化过的安装宿主直接替代编译依赖。

从工作区根也可调用 `& './歌词增强/build.ps1'`，脚本在测试期间切换到本工程目录，结束后恢复。JDK 可用 -JdkHome 指定。

脚本先编译并执行完整隔离回归，失败不生成新包，输出位于 build/libs/。裁剪模块列表读取自所选宿主对应的 runtime/release。release/ 交付副本不会自动替换，审计通过后才更新。

shared/ 在本工程内，不引用另一个 Mod。音频测试需要 ToolsRoot/ffmpeg-test/ffmpeg.exe，脚本通过 spw.tools.path 传给测试；音频及其他夹具位于 src/test/。

编译链仍使用工具目录中的 spw-host.jar（旧参考宿主）与原始 stdlib，运行验证使用 -HostJar 指定的实际宿主。宿主由开发者合法本地安装提供，不随开源源码或安装包分发。

依赖获取说明的进一步完善、许可确认后的测试素材替换和公开 CI 方案列为正式开源前待办，当前不宣称干净环境克隆后无需准备即可一键构建。
