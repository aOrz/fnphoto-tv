# 幻灯片文档截图 / Slideshow screenshot provenance

此目录仅收录用户要求更新 README 时明确选取的两张合成公开文档图片；不导入原始QA归档、日志、私有媒体或凭据。图片原样复制，未裁剪、重绘、修饰或伪造界面。

## 对应代码与构建

- 获取/核对日期：2026-11-02。截图来自先前幻灯片交付，不声称本次重新运行模拟器。
- 当前基线 main：`aa810506740802822a275cace83868307af50184`（PR #28已合入）。
- 完整源码tree：`bcc420d0c575a781a29a55dc91df1bd307c51145`，与该交付最终tree一致；其受测代码为`c367ffb`，随后仅补文档。
- 设备：Android TV API29，1280×720；fingerprint：`google/sdk_google_atv_x86/generic_x86:10/QTU1.200805.001/6738020:user/test-keys`。
- 应用包：`com.fnphoto.tv`（不是共存side包）。APK SHA-256：`fab1f75e3f51f2a8683b5a32b244e9e75e5a7fe6550c55cbbc11e3f4ca19661b`。
- 原交付证据及验证范围：[幻灯片报告](../reports/slideshow-enhancement.md)。两张图片与原证据ZIP内对应PNG及manifest校验值逐字节核对一致。

| 文档图片 | 原场景与文件 | SHA-256 |
| --- | --- | --- |
| `01-settings.png` | 遥控设置面板，`settings-dpad.png` | `8139288d41edf8cf92006767e8150982a2986e034f3072503a2c6efd859920a0` |
| `02-video-paused.png` | 合成视频暂停操作栏，`slideshow-video-paused.png` | `254965f901d9c23dddd6fbb31fd73204ef0f6f5b7192ac6be8f702491ce2b662` |

## 隐私、内容与范围

已直接目视检查：只有应用UI、合成图形/测试文字和生成的视频时间码，无真实账号、NAS地址、私人照片或真人内容。视频由项目合成QA夹具生成，未使用第三方照片、影片或音乐；应用UI/品牌保持原样，不另作权属或新许可声明。

静态图只展示设置和暂停界面，不能单独证明帧冻结、持续播放、真实NAS兼容或实机性能。设置图中的“循环开/视频关”是演示选择，不是默认值（默认5秒、循环关、视频开）。原始测试产物仍保留在忽略目录/交付附件，不因本次精选文档图而改变其管理规则。

These two unmodified 1280×720 emulator captures use project-generated synthetic content. They document the merged slideshow UI, not a fresh device test, a new release, or real-NAS compatibility. The settings shown are demonstration choices, not defaults. No third-party photos, films or music were used; this document does not grant a new license to application branding.
