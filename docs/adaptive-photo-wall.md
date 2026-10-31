# 图片墙自适应宽度

本改动基于 `main` 的 `4e38a953`，包括已合并的焦点修复 PR #10。代码仅在本地
`fix/adaptive-photo-wall` 分支保存，没有推送或发布。仓库与工作区检查未发现
`AGENTS.md` 或 `.agents/skills`。

## 原因和行为

原主图库每行硬编码 6 项，普通卡片宽 320 **px**，日期卡片宽 240 **px**；
Leanback 还要占用左右边距和项间距。系统显示密度改变后，这些尺寸与可用宽度
并不对应。时间线把整个月的所有日期放进一个横向 `ListRow`，也必然需要横滑。

现在从实际 `HorizontalGridView` 宽度计算列数和卡片宽度：

- 主图库左右各 72dp、间距 12dp；最小目标卡宽 144dp、最多 6 列。
- 在扣除边距和间距后向下取整分配像素，卡片最大 320dp。极窄窗口降为一列，
  卡片可以小于目标最小宽，不用最小宽反过来撑大父容器。
- 普通媒体为 16:9，日期预览为 4:3。日期按列数换行，同一个月只在第一行显示标题。
- Leanback 保留原来的遥控焦点放大，使用两端边界对齐，完整行不向焦点关键线横移；
  左右键移动卡片焦点，上下键继续纵向浏览。
- 文件夹与搜索沿用 PR #10 的共同卡片、焦点前景、视频角标和焦点恢复；
  `AdaptiveGridLayoutManager` 按实际 RecyclerView 宽度调整列数，
  `PhotoGridItemView` 在同一次测量中把媒体区设为 16:9。
- 子文件夹使用 240dp 的目标最小宽、最多 4 列，保留独立且高度受限的滚动区域。

主图库示例（真实布局还应以容器测量值为准）：

| 物理宽度 / 密度 | 逻辑宽度 | 列数 | 卡片宽度 px |
| --- | ---: | ---: | ---: |
| 1280 / 160dpi | 1280dp | 6 | 179 |
| 1280 / 213dpi | 约 961.5dp | 5 | 204 |
| 1920 / 240dpi | 1280dp | 6 | 269 |
| 1920 / 320dpi | 960dp | 5 | 307 |
| 1920 / 400dpi | 768dp | 4 | 367 |
| 3840 / 320dpi | 1920dp | 6 | 572 |
| 3840 / 640dpi | 960dp | 5 | 614 |

## 分页、内存和焦点

尺寸改变时仅重排已加载的窗口，不调用重新加载页面的方法。`MediaItem` 对象、
请求世代、分页对象、原始 offset、预览缓存和页尾操作均保留；预览条目到行的映射
随重排更新，下一页继续填入重排后的最后一行。选择恢复按对象和当前列数定位。

35/50/60/100 的既有接口分页大小、1,200 项窗口、12 项预取距离、同时最多两个
日期预览请求没有改变。主图库普通缩略图明确使用原来的 320×180px 请求尺寸，
避免 4K 下视图变大自动提高 Glide 解码目标；视频和日期合成图预算保持不变。
搜索/文件夹仍按已测量视图请求缩略图，未对其实际内存或帧率作实机保证。
保留 Leanback/RecyclerView 回收机制，
没有改成一次加载全部图片的布局。重排的线性工作只发生在宽度导致列数变化时；
列数不变仅更新卡片尺寸。

## 自动检查与设备复现

新增 `PhotoWallLayoutTest` 和 `PhotoWallRowsTest` 共 16 项 JVM 测试，验证常见电视
分辨率、显示缩放、非整数密度、像素舍入、焦点边界、31 天换行、月份标题、空态、
页尾和 1,200 项多次重排后顺序及对象身份保持。

新增 `PhotoWallDeviceTest`：只在专用 Android 模拟器执行，内部使用 loopback 合成
HTTP 数据，临时保存并恢复应用测试偏好。验证真实卡片边界、遥控左右不横移、
上下滚动、实时改变容器宽度后的选择和分页、搜索和文件夹的图片比例。包含超长
中英文字段。截图只来自合成数据。

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m unittest discover -s test -p 'test_*.py' -v
bash ./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  -e class com.fnphoto.tv.PhotoWallDeviceTest \
  com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner
```

对专用模拟器分别设置 `wm size` / `wm density` 后重复仪器测试；至少覆盖
1280×720/160、1920×1080/320、1920×1080/400 和 3840×2160/640。
截图目录为设备 `/sdcard/Android/data/com.fnphoto.tv/files/wall-evidence/`。
测试完成后用 `wm size reset`、`wm density reset` 恢复模拟器配置。

本次云端标准构建受现有 `maven.aliyun.com` ExoPlayer 2.11.8 依赖的网络 403 限制。
独立验证使用官方 ExoPlayer `r2.11.8` 源码
`e822196336b64208ba7caa557e4c6ee02b03335e` 编译同版本模块，通过临时 Gradle init
脚本替换验证环境中的依赖；仓库的产品依赖与仓库地址没有更改。该结果应与原配置
能否直接联网构建区分。

### 本次云端执行结果（2026-10-31）

产品代码提交：`2390bfe`；main 在最终核对时仍为
`4e38a953fdb54ea7ec7e15470c218f4c02182180`。

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | **PASS**：310 项，31 suites，0 失败、0 错误、0 跳过；包含新增 16 项 |
| Python fixture / 合同测试 | **PASS**：69 项，0 失败 |
| Android lint | **PASS**：0 Error/Fatal；仍有 90 项 Warning |
| Debug APK / AndroidTest APK | **PASS**：使用上述官方同版本源码验证配置成功生成 |
| 原仓库 Maven 配置构建 | **BLOCKED**：现有 ExoPlayer 镜像网络 403，没有修改产品仓库地址 |
| 3 项 Android 设备测试 | **NOT RUN**：已编译打包，ADB AUTH 后无法建立连接 |
| 图片墙实测截图 | **NOT RUN**：只有系统开机/黑屏画面，不是应用验证证据 |

实际尝试官方 API29 Android TV x86 镜像，软件模拟运行（环境没有 `/dev/kvm`），
日志确认 `Boot completed`。宿主 HOME 为只读，标准 ADB/console 状态文件无法
创建；通过仅监听 loopback、带令牌的官方 gRPC 通道确认系统画面后，仍未得到
可用 ADB 授权连接，系统停留黑屏。因此没有把“模拟器启动”或“测试 APK 编译成功”
计为设备测试通过，也没有确认不同分辨率、字体缩放、焦点动画或长文本的实际画面。
没有待审批操作；需在可正常连接的 TV 模拟器或实机上完成上述设备矩阵。

本次工作区证据（不提交生成产物）：

- `/workspace/tooling/android-validation-result.json`：构建来源、包 hash 和设备状态。
- `/workspace/tooling/gradle-2390bfe-validation.log`：最后产品候选的四项 Gradle 任务。
- `/workspace/tooling/python-contract-tests.log`：69 项 Python 检查。
- `app/build/reports/tests/testDebugUnitTest/index.html` 与 `app/build/reports/lint-results-debug.html`。

## 仍有的边界

- 卡片长文件名、相册名仍使用单行省略。人物/地点等 Leanback 行标题可能截断，
  本改动不承诺所有超长文字完整显示；它们不用于决定媒体列宽。
- 主图库 72dp 边距减去现有 1.14 倍焦点放大的半宽，至少留下约 48dp。
  这是应用坐标中的安全区，不等于每台电视的物理 5% 过扫描区。其他顶栏/菜单
  的原有边距未统一改造，真实电视 overscan、异常厂商缩放仍需实机检查。
- 模拟器和单元测试不能替代真实电视的 GPU 性能、大图库长期运行、真实 NAS、
  API 19 设备、所有字体缩放或厂商固件验证。

## 与焦点修复的整合

已经纳入 PR #10 的 `FocusImageCardView`、`fg_photo_focus`、统一网格卡片、
搜索向下键/重试焦点和文件夹首次焦点。搜索卡片曾发生文本冲突，最终保留 main
中的卡片实现，并在共同 XML 根布局加入尺寸测量；没有恢复旧版背景/透明度监听。
今后若另有尚未合并的焦点改动涉及 `CardPresenter`、两个 Activity 或
`item_photo_grid.xml`，应逐段整合，不能直接用任一旧文件覆盖。
