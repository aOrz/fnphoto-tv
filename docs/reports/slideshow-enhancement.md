# 幻灯片完善：交付与测试报告

> **本地交付验证通过。** 代码/文档独立复审通过；JVM 507、Python 77、API19 21、TV29 21项通过。
> 本报告区分客户端合成验收与未运行的真实NAS/实机/进程死亡等验证；不宣称可合并。
> 推送后的Draft PR及CI按下节流程另行记录，不提前写成通过。

## 范围与版本

- 类型：新需求；开发中发现的候选缺陷另按缺陷流程记录复现、最小修复和回归。
- 依据：用户于 2026-11-02 确认“按照你的建议来”，实现范围见
  [需求文档](../requirements/slideshow-enhancement.md)。
- 开发前基线：`a82f14cb5d1cc8412121c734ef74c5162ca8fac5`。
- 代码候选：`c367ffb15721bee8ed8041819c6c1d27ebfd48d6`；此后的最终交付提交仅有报告/需求说明/QA文档差异，无代码、资源或测试修改。
- 交付范围：照片间隔 3/5/10/15/30 秒（默认 5 秒）、当前完整相册/查询范围循环
  （默认关）、暂停/继续、是否播放视频（默认开）；三个选项持久保存，不恢复自动播放。
  从当前媒体起播，循环回范围真正首项；不做随机、跨相册汇总、后台播放或全库缓存。
- 实现涉及 `MediaDetailActivity`、`SlideshowState`、`VideoControls`、`MediaPageController`、
  `MediaListHolder.PageSource` 与主照片墙/搜索/文件夹来源，以及相应状态和设备测试。
- 主照片墙复用既有查询及受控 offset=0 重启；重启结果先暂存，确认导航意图后提交。
  不完整来源/后续批/缺 live 分页源快照明确拒绝完整循环，不能把最后窗口当整个列表。
- 保留 Java 8、API19、原依赖/工具链和传输安全边界；没有新增猜测的 NAS 参数，
  没有放宽共享视频认证重定向或平台 fallback 条件。

## 复现与验收

### 候选缺陷 P2：视频结束等待分页时暂停，继续后重播旧视频

- 未修复候选：`e6215d9`；应用 APK SHA-256：
  `c9ccd05850202c12f407e33a237b8134d2f95e48310da13f70f3d688b7528435`。
- 环境：官方 TV29 x86 与 API19 x86 模拟器、无 KVM；合成视频与可控延迟分页来源，不是真实 NAS。
- 最小步骤：幻灯片播放视频至 ended → 下一页尚未交付时暂停 → 交付下一页 → 明确继续。
- 预期：暂停期间保留当前项；继续后进入下一张照片，不重新播放已经结束的视频。
  实际：恢复路径按普通视频继续处理，使已结束视频重播。
- 已保存复现：TV29 定向运行 1 项/1失败；API19旧APK运行11项视频测试/1失败，
  均为同一回归断言，不是两个独立产品缺陷。失败断言为
  `Resuming completed video must advance, never replay the ended clip`。
- 失败证据：`test/.generated/slideshow/ended-page-red/instrumentation.txt` 与同目录
  `apk-sha256.txt`；API19失败见 `device-api19-complete/video-instrumentation.txt` 及同目录hash。
  两轮 test APK 均包含新增回归，而应用 APK 仍为上述未修复候选。
- 根因与最小修复：仅以播放器当前状态/暂停意图恢复会丢失“本媒体已结束、等待推进”
  的会话意图。`SlideshowState` 保存 completion pending；ExoPlayer 与平台播放器完成
  回调均记录它；暂停保留该意图，继续优先推进，换媒体/停止/新会话清除。
- 回归：新增 2 项 JVM 状态测试及
  `VideoRemoteInstrumentationTest#testSlideshowEndedVideoPausedWhilePagingResumesIntoNextPhoto`。
  修复后 JVM 与独立代码复审已通过；两平台最终11项视频测试均PASS，新增设备回归红→绿，P2已关闭。

### 逐项验收（最终候选分层）

下表 PASS 仅表示列明的本轮客户端验收通过，不能外推为最后一列未覆盖项也通过。
设备均为同一最终 APK、合成数据；不把 JVM 或 mock 当真实 NAS 验收。

| ID | 本轮验收与证据 | 状态 | 明确未覆盖/边界 |
| --- | --- | --- | --- |
| AC-01 | 五档/默认值状态校验；两设备设置及照片暂停后完整间隔推进 | PASS | 未逐档测量真实TV时序，A13延迟主图全组合未专项重跑 |
| AC-02 | 两设备照片暂停、迟到分页不推进、范围重启延迟交付；状态/分页单测 | PASS | 无穷异常组合不在本轮覆盖 |
| AC-03 | 两设备实际视频时钟/输出帧冻结与恢复、播完推进；P2各平台红→绿 | PASS | 强制平台fallback设备路径NOT RUN，接线仅静态review与状态覆盖 |
| AC-04 | 两设备630项主照片墙跨560行窗口后重启offset0、回真正首项；缓存上限断言 | PASS | 真实NAS排序/变化数据集NOT RUN |
| AC-05 | 两设备禁视频无流请求、全视频结束、175项预算暂停及重新启视频 | PASS | 请求验证仅合成服务，不等同真实NAS |
| AC-06 | JVM/Python分页/预算/边界合同；两设备迟到分页与重启边界 | PASS | 所有异常组合设备笛卡尔积NOT RUN |
| AC-07 | player/API/分页单测及合成合同；两设备HTTP404持久错误及停止自动播放 | PASS | 真实NAS 401/共享撤权/Range及全部C01–C10故障专项未完整重跑 |
| AC-08 | 两设备视频11项覆盖DPAD/确定/Back、触屏后焦点、定位不切项；截图确认短标签 | PASS | 无真实遥控硬件测试 |
| AC-09 | 两设备Home/返回停止会话、迟到交付不复活；状态/会话单测 | PASS | 真实后台进程死亡B01 NOT RUN，Home不能替代 |
| AC-10 | 空/单图/全视频/受限来源状态与重启单测；两设备单图、回首及返回焦点 | PASS | 搜索/文件夹后续批仍明确不支持完整循环 |
| AC-11 | 两设备设置选项更新及持久化值；状态不恢复播放会话 | PASS | 三项设置重开Activity读取未专项验证；设备本地偏好非账号隔离 |

## 测试记录

遵循 [开发流程](../development/workflow.md) 与 [设备 QA](../../test/QA_CHECKLIST.md)。
状态仅用 PASS / FAIL / SKIPPED / NOT RUN / BLOCKED；历史 FAIL 保留，不覆盖为 PASS。

| 检查层级/范围 | 精确命令/选择器 | 环境 | 状态 | 实际结果与证据 | 解释/未覆盖 |
| --- | --- | --- | --- | --- | --- |
| 修复后 JVM | `bash ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | JDK11、Gradle7.4、SDK29、Build Tools30.0.3 | PASS | `gradle-complete-fixed.log` 为 BUILD SUCCESSFUL；46套507项，失败/错误/跳过均0，见 `app/build/test-results/testDebugUnitTest/` | 包含新增2项 completion pending 回归；不是 UI 测试 |
| 修复后 debug/test APK 构建 | 同上 | 同上 | PASS | debug 与 androidTest assemble 已完成；两个hash见下文 | 构建不能证明已安装/运行同一 APK |
| 修复后 lint | 同上 | 同上 | PASS | `app/build/reports/lint-results-debug.xml`：0错误、100 warnings | 并非无警告；含现有 TLS/备份/静态引用等警告，未在本需求全面整改 |
| 修复后 Python 合同/合成媒体 | `python -m unittest discover -s test -p 'test_*.py' -v` | Python3.12、FFmpeg/ffprobe、合成 fixture | PASS | `python-complete.log`：77项、0失败/跳过，3.410秒 | 最终代码候选验证轮次；不是真实 NAS |
| P2 未修复设备复现 | `am instrument -w -e synthetic true -e class com.fnphoto.tv.VideoRemoteInstrumentationTest#testSlideshowEndedVideoPausedWhilePagingResumesIntoNextPhoto com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner` | TV29，`e6215d9`/上述 c9 APK | FAIL | `ended-page-red/instrumentation.txt`：1项、1失败 | 必须保留为失败到通过的起点，不能以旧10项视频测试绿代替新增回归 |
| 最终 API19 专项 | SlideshowDeviceTest 全类8项；VideoRemoteInstrumentationTest 全类11项；下列 PhotoWall 两项 | 官方 API19 x86、普通 Android、无 KVM | PASS | `device-api19-verified/`：8+11+2，共21项通过；20张PNG逐文件解码有效，关键场景目视核对 | 不是 Android TV OS，不证明真实设备解码性能 |
| 最终 TV29 专项 | 同上 | 官方 Android TV29 x86、无 KVM | PASS | `device-tv29-verified/`：8+11+2，共21项通过；20张PNG逐文件解码有效，关键场景目视核对 | 不证明真实 TV 性能、长时间内存稳定或全部视频格式 |
| 平台播放器 fallback 设备路径 | 强制兼容解码失败进入受限 MediaPlayer fallback，再验证完成/暂停/继续 | 本轮设备运行走正常 ExoPlayer 路径 | NOT RUN | 两播放器 completion 接线已独立静态review；迟到 completion 有 JVM 状态覆盖 | 未强制平台fallback解码回归，不能将 ExoPlayer 设备绿外推为fallback设备通过 |
| 进程死亡 B01 | Home 后杀后台进程，再由正常 launcher 恢复，核对新 PID/媒体 | 需最终 APK 的独立设备运行 | NOT RUN | 当前 Home/返回用例没有证明进程重建 | 不从 `DetailSessionStoreTest` 或普通后台往返推导设备通过 |
| 真实 NAS | 相册/查询重启、排序、末页、共享撤权/401/Range | 本轮仅合成服务 | NOT RUN | 未使用真实 NAS | 需获授权的 NAS/相册版本、脱敏合同和真实设备，mock 不作兼容结论 |
| 真实 TV 性能/解码矩阵 | 实机持续播放、内存/帧率、多编码验证 | 未开展实机运行 | NOT RUN | 无测量结果 | 模拟器无 KVM 的耗时不可作为用户 TV 性能指标 |
| 报告 diff/相对链接 | `git diff --check` 与 Markdown 相对目标检查 | 当前报告工作树 | PASS | 本轮diff无空白错误，4个Markdown相对目标均存在 | 最终数据回填后再次核对；独立文档复审通过 |

设备执行通过 `adb -s <serial> shell am instrument -w -e synthetic true -e class <选择器>`；
测试包/runner 为 `com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner`。
PhotoWall 选择器为：

```text
com.fnphoto.tv.PhotoWallDeviceTest#slideshowLoopsWholeAlbumAfterWindowEvictionAndReturnsFirstFocus,
com.fnphoto.tv.PhotoWallDeviceTest#slideshowPauseDuringWholeAlbumRestartKeepsTailUntilExplicitResume
```

实际命令中两项以逗号连接成一个 `-e class` 参数，不含换行。它们经过真实 Activity/
MainFragment 的合成网络分页，不是只用内存列表；仍不能证明生产 NAS 合同。
工具链从官方源恢复，编译使用 JDK11；工具安装没有升级产品依赖或关闭证书校验。

## UI 截图与设备证据

- 候选应用 APK SHA-256：`fab1f75e3f51f2a8683b5a32b244e9e75e5a7fe6550c55cbbc11e3f4ca19661b`。
- 候选 test APK SHA-256：`66edd89306abd6683f0b88c3f839ca7543e9ae4fac580fe88936f1e4fc3f03b2`。
- 实际安装身份已核对：两设备安装前记录的两个 hash均与上述相同，对应受测代码 `c367ffb`。
  不按相同 versionName 推断同一个 APK。
- API19 fingerprint：`generic_x86/sdk_x86/generic_x86:4.4.2/KK/4174703:eng/test-keys`。
- TV29 fingerprint：`google/sdk_google_atv_x86/generic_x86:10/QTU1.200805.001/6738020:user/test-keys`。
  已与最终运行各自 `device-fingerprint.txt` 核对。
- 最终候选目录：`test/.generated/slideshow/device-api19-verified/` 与
  `test/.generated/slideshow/device-tv29-verified/`。每台实际20张PNG，共40张，全部非零且PIL解码校验通过；
  设置焦点、短标签、P2推进到下一照片、相册回首/返回焦点等关键场景已目视核对。
- 关键截图：设置DPAD、照片暂停/继续、单图/循环、无合格媒体状态、视频跳过预算、
  API19短标签视频操作栏、视频暂停/恢复/结束、P2 ended等待分页恢复
  `slideshow-ended-paging-resume-photo.png`，以及大相册真正
  回首/暂停重启/返回列表焦点。暂停截图须配帧计数/时钟断言，静态截图本身不证明解码或暂停。
- 仅合成图片与 `clip_a/clip_b` 45秒 H.264/AAC 视频；生成参数见
  `test/.generated/slideshow/media/manifest.json`。附件包含媒体生成manifest（含hash/工具信息）和每证据文件SHA-256清单。
- **历史 `device-*-complete` 目录曾误启动旧 APK，不作为最终验证或最终附件来源。**
  `final-ui` 等旧目录同样只能按其自身 hash 说明历史覆盖，不能替代修复后的证据。
- 对外证据附件：`fnphoto-tv-slideshow-c367ffb-evidence.zip`，随本次交付消息提供于请求者
  ChatGPT Library（私人附件，不扩大分享权限；其他仓库审阅者需向请求者索取）。
  大小14,116,517字节，SHA-256 `3bdd070fd3efba40310b639523bc9bebfdf2588afd652dd7c45a70065b24d447`。
  包含40张最终截图、两设备结果/安装hash/指纹、JVM XML、lint/构建/Python结果、P2旧版失败证据、
  合成媒体manifest、逐文件hash及实测debug APK；不是发布版。
  不包含真实NAS日志、私人媒体、凭据、工具缓存或旧轮次截图。本地忽略目录不冒充公共下载链接。

## 独立 review 与失败循环

- 另一独立 agent 参与只读审查，与自动化/设备验证并行；关注正确性、分页窗口上限、
  生命周期/过时回调、焦点、视频传输安全、Java8/API19和测试覆盖。
- 独立代码 reviewer 已复审修复候选 `c367ffb15721bee8ed8041819c6c1d27ebfd48d6`，
  当前代码审查结论通过；独立reviewer为协作任务 `slideshow_review`。文档复审亦已通过（最终数据回填后重核），
  独立代码review不替代设备验证，也不冒充GitHub人工approval。

| 轮次/候选 | 问题与证据 | 处置 | 重验/关闭状态 |
| --- | --- | --- | --- |
| 初期实现审查 | 全视频末尾加载圈不退、暂停跳过后启视频死等、提示层遮挡、后台返回可能播放已跳过媒体 | 增加有限状态提示、正确恢复视频项/可见层级及后台保护 | 已补状态/设备场景；历史修正不豁免最终候选重验，最终复审通过；两平台相关场景通过 |
| 首轮设备 fixture | 首项普通视频没有缩略图，基线普通浏览会自动请求，导致“禁用视频零请求”在进入幻灯片前已失败 | 首项提供合成缩略图以隔离幻灯片行为；保留末项无缩略图来测后台保护 | 不把fixture失败写成产品修复成功；历史失败保留，最终用修订夹具 |
| 设备生命周期/时序测试 | API19从竖屏launcher进入横屏详情会重建，旧Activity引用断言失效；视频暂停后固定500ms等待不够稳定 | 跟踪实际RESUMED横屏Activity；最多5秒等待帧/时钟稳定750ms且持续断言暂停意图，再验证暂停期间冻结 | 测试修订 `d4a7e51`；不无限放宽超时或删掉帧冻结断言；最终两平台专项已通过 |
| API19视觉检查 | 七按钮中的“幻灯片设置”在窄dp布局换行裁切 | 短标签“幻灯片”，保留可访问描述与操作语义 | `e6215d9`旧候选已截图检查；最终APK截图已核对 |
| 确定 P2（本节前文） | `e6215d9`/c9 APK：ended等待分页中暂停，继续重播旧视频；独立定向TV29 1/1 FAIL | 两播放器记录completion pending；会话暂停保留、继续优先推进；新增2 JVM+1设备回归 | 修复后507 JVM PASS；独立代码复审已通过；最终两设备11项视频均PASS，新增回归红→绿，P2已关闭 |
| 验证批次标识错误 | `device-*-complete`启动旧APK | 更换为verified目录，重新安装并记录hash后执行 | 旧结果不得充当最终绿；最终verified目录21+21项均PASS、hash已核对 |

任何最终设备测试或 review 失败都返回修复；新代码候选重跑受影响测试及必要全量检查，
并重新独立 review。不能用未受影响的历史绿掩盖新回归，也不能把所有失败归为环境问题。

## 风险、回退与 PR/CI

- 保留窗口与扫描预算不变，不承诺全局去重、NAS快照一致性或离线完整循环。offset重读
  时服务端新增/删除媒体可能改变本轮内容；不使用无界ID/书签列表规避这一边界。
- 搜索/文件夹非首批、超限/不完整来源、地点无已知续页合同、缺live源恢复均明确限制；
  如需扩展须另确认合同，不能默默把有限列表当全部。
- 真实 NAS、真实 TV 性能/格式矩阵、后台进程死亡B01未运行，按上表如实保留；现有TLS/
  凭据存储限制未在此需求解决。详见[实现约束](../development/constraints.md)。
- 回退：若远端采用等价树单提交，整体revert该功能提交；本地审计实现/测试提交为47040bb、4895b37、cdbae5c、0481759、d4a7e51、e6215d9、c367ffb，另有文档交付提交。不要reset/revert其他并行工作；
  三个新增 `slideshow_*` 偏好键可留存，旧版本忽略，不清用户账号、缓存或其他浏览偏好。
  回退后需重验原详情播放/分页/遥控流程。
- 本轮适用客户端验证与独立review已通过，已关闭确定P2；未运行项保持上述NOT RUN，不宣称真实NAS兼容或可合并。
- 命令行push缺少GitHub凭据，改用已连接GitHub工具发布；远端commit可能与本地审计commit不同，
  必须以完整git tree SHA及diff等价校验关联受测代码，不能只比较文件计数。
- 按用户确认的项目规范创建 **Draft PR**，不合并/部署。PR链接、推送后远端head/diff核对与该提交CI
  结果在PR正文/后续评论及交付消息记录；本报告写于创建PR前，不能预填CI成功。
- 没有CI不等于通过，CI不替代设备或真实NAS；CI失败须在授权范围内修复和重验。
