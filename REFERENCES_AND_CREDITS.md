# 歌词增强：参考项目与致谢

整理日期：2026-10-26，依据本项目开发记录、生产代码和随包第三方说明。

本插件开发过程中参考了以下项目的界面、交互、歌词协议及表现方式。最终界面以 SPW 原生控件和使用习惯为准。设计参考、数据来源与实际随包依赖分别列出，避免混淆。

## 界面与操作流程参考

| 项目 | 本项目研究或借鉴的内容 |
| --- | --- |
| [SaltUI](https://github.com/Moriafly/SaltUI) | 设置项、菜单、输入框、按钮与整体视觉一致性；最终实现复用 SPW 宿主现有控件。 |
| [Lyrico](https://github.com/Replica0110/Lyrico) | 标签编辑、歌词搜索的独立入口、字段选择及歌词偏好复用。 |
| [Feishin](https://github.com/jeffvli/feishin) | 从当前歌曲直达搜索，点选结果后直接预览，明确选中状态与应用操作。 |
| [LRCGET](https://github.com/tranxuanthang/lrcget) | 通过歌名、歌手、专辑和时长区分同名歌曲及不同录音版本。 |
| [LDDC](https://github.com/chenmozhijin/LDDC) | 多源候选、结果字段布局，以及 QQ QRC、酷狗等歌词请求和解析协议研究。 |
| [OpenLyrics / foo_openlyrics](https://github.com/jacquesh/foo_openlyrics) | 手动搜索结果、来源信息与选中预览的组织方式。 |
| [fooyin](https://github.com/fooyin/fooyin) | 歌词区域右键直达搜索、编辑等操作的入口设计。 |
| [Tagger](https://github.com/NickvisionApps/Tagger) | 标签字段直接编辑，操作紧邻被操作内容。 |
| [MusicBrainz Picard](https://picard-docs.musicbrainz.org/en/v2.13/getting_started/screen_main.html) | 原标签与待写入标签对照，便于在写入前确认变化。 |
| [AMLL TTML Tool](https://github.com/amll-dev/amll-ttml-tool) | 围绕当前行、当前词组织编辑操作，保留逐词时间及时间平移。 |

## 歌词表现与协议参考

| 项目 | 本项目研究或借鉴的内容 |
| --- | --- |
| [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics) | 间奏圆点生命周期、播放时钟、暂停与恢复等表现行为；未引入其渲染器。 |
| [lyric-dom](https://github.com/SPlayer-Dev/lyric-dom) | 最短间奏间隔和提示显示参数；未引入其渲染器。 |
| [N501 Verse](https://github.com/Nombah501/n501-verse) | 倒计时节奏与短计算的历史移植参考；后续随本插件要求调整。包内保留 MIT 版权与许可全文。 |
| [qq_music_api](https://github.com/tidain/qq_music_api) | QQ 音乐搜索与歌曲详情请求参数研究。 |
| [Lyricsfile](https://github.com/tranxuanthang/lyricsfile) | LRCLIB 对应歌词文档的 YAML 格式解析依据。 |

## 数据来源与实际使用的基础组件

- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)：插件开发接口、生命周期及 Hook 契约。
- [LRCLIB](https://github.com/tranxuanthang/lrclib)、[AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db)：在线歌词数据来源；AMLL 播放组件、编辑器和词库是不同项目。
- [OpenCC](https://github.com/BYVoid/OpenCC)：包内使用其繁简转换词典，转换逻辑为独立 JVM 实现；保留 Apache-2.0 许可与 NOTICE。
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml)：随包用于安全解析 Lyricsfile，保留 Apache-2.0 许可。
- [Kotlin Metadata JVM](https://github.com/JetBrains/kotlin)：随包用于解析宿主 Kotlin 元数据，动态定位接口，保留对应许可与 NOTICE。
- [jaudiotagger](https://www.jthink.net/jaudiotagger/)：复用 SPW 宿主自带库读写音乐标签和内嵌封面，没有额外打入一份。

网易云、QQ 音乐、酷狗及 iTunes 等平台是在线内容服务来源，与上述界面参考项目的性质不同。

## 仅评估，未接入

[SPW Media Runtime](https://github.com/GaBoron/SPW-Media-Runtime) 曾按需求评估，当前插件没有接入或依赖它。详见本目录 `SPW-Media-Runtime-评估.md`。

## 核对依据

- `design-preview/GITHUB-UX-RESEARCH-20261023.md`
- `design-preview/REDESIGN-CONTRACT-20261023.md`
- `src/test/fixtures/README.md` 的 QRC 协议参考记录
- `VALIDATION-0.6.4.md`、`VALIDATION-0.6.5.md`、`VALIDATION-0.6.6.md`
- `src/main/resources/THIRD-PARTY-NOTICES.txt`、`src/main/resources/opencc/NOTICE`
- `build.ps1` 与 `../shared/build-host-resolver.ps1` 的实际依赖打包清单

这份列表说明开发参考和使用范围，不表示上游作者参与开发、官方合作或背书；也不将“参考设计”统称为“移植代码”。本次只整理说明，没有变更插件代码或重打安装包。
