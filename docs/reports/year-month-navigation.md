# 年月快速跳转交付与测试报告

## 范围与版本

本次为新需求及验收期间发现的焦点/生命周期缺陷修复。需求文档
[year-month-navigation](../requirements/year-month-navigation.md) 在生产实现前建立；
用户已确认按所选月展示当前来源结果、可返回原位置，以及普通相册可取消地分批扫描。
实现说明见 [年月快速跳转](../year-month-navigation.md)。

- 重新克隆并核对的 main 基线：`593e368b3f8c16608aa67d0c165c2ae2f968c71f`。
- 当前受测及独立 review 候选：`f864cc798c7d763a19bf2c3b16eca7fa07deedc6`。
- 本报告在生产候选后单独提交，除报告外无源码差异；最终 SHA 由 PR 正文和交付摘要关联。
- 当前交付状态：用户于 2026-11-02 在完整设备回归进行中明确要求“好了提交一下 PR 吧”。
  按 AGENTS 的先开 PR 例外创建明确标注待验的 Draft；不宣称全部门槛通过，不合并。

照片墙顶栏和 MENU 抽屉均可进入年份、月份选择；深层滑动窗口无需先向上重取全部旧页。
图库日期/收藏/照片视频沿用 `gallery/getList` 的 snake_case 时间范围；人物保留 personId、
album_id 和现有大小写别名；共享沿用 camelCase 时间参数和只读权限，年份限 2000–2099。
普通相册仅用原 `album/photos` 参数分批扫描，严格匹配 `photoDateTime`；不猜日期过滤参数，
不把上传时间、标题或排序当作拍摄日期，不按日期早停或二分。

来源、类型和收藏约束保留；单日是定位入口，跳月替换单日条件。地点、最近添加、目录、
独立文件夹/搜索、标签及智能分类不扩展年月支持。真实 NAS 时间语义认证、全库索引和依赖升级不在范围。
五页扫描预算、单请求、原始 offset、原 offset 重试、短非空页继续、560 原始行/16 页窗口、
561 屏幕/详情上限、每响应 8 MiB 均沿用。返回只保留一个来源/坐标/ID，有限重取失败明确提示。

## 复现与验收

下表只记录当前候选结果；旧版本失败及修复另列，不能与当前版本通过数量相加。

| 需求 ID | 验收范围 | 当前状态 | 证据 |
| --- | --- | --- | --- |
| AC-01 | 年→月、DPAD/确定/Back、深层 MENU 入口、抽屉不抢焦点 | NOT RUN | 当前候选完整设备批次待汇总；关键返回/后台子集已通过 |
| AC-02 | 单日跨月、人物/共享 ID、类型/收藏保留、闰年边界 | NOT RUN | JVM/HTTP 合同 PASS；当前设备待汇总 |
| AC-03 | 乱序拍摄日期、第六页及以后目标、五页暂停/显式继续、短页 | NOT RUN | JVM/HTTP 合同 PASS；当前设备待汇总 |
| AC-04 | 空月终止证据、未知日期有/无匹配均提示、重复页/失败 | NOT RUN | JVM/HTTP 合同 PASS；当前设备待汇总 |
| AC-05 | 快速重选、取消、后台、旧回调和等待反向页 | NOT RUN | 当前候选完整设备批次待汇总；关键返回/后台子集已通过 |
| AC-06 | 来源/原坐标/ID 恢复、删除/变化失败、月 offset 隔离 | NOT RUN | MonthReturnPoint/HTTP 合同 PASS；当前设备待汇总 |
| AC-07 | 详情翻页/返回、窗口淘汰、既有功能回归 | NOT RUN | 当前候选完整设备批次待汇总；关键返回/后台子集已通过 |
| AC-08 | 不扩大不支持来源 | NOT RUN | 两轮独立源码审查 PASS；当前设备待汇总 |
| AC-09 | Gradle、Python、diff、固定依赖、API19 | NOT RUN | 构建/Python PASS；最终 diff/API19 设备待汇总 |

## 测试记录

环境：Temurin 11.0.29+7，Gradle 7.4，AGP 4.2.2，compile/target 29、Build Tools 30.0.3，
Java 8/min19；Python 3.12、FFmpeg/ffprobe 7.1.5。未升级项目依赖。
设备为专用合成数据官方模拟器：API19 x86 普通 Android、API29 x86 Android TV；无 KVM，软件执行。
API19 兼容性与 API29 TV 遥控覆盖分别记录。

| 范围 | 命令/步骤 | 状态 | 当前候选实际结果 |
| --- | --- | --- | --- |
| JVM / APK / lint / test APK | `bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | PASS | 33 秒；537 tests，0 failure/error/skip；lint 0 error、99 warning |
| Python 全量 | `.venv/bin/python -m unittest discover -s test -p 'test_*.py'` | PASS | 93 tests，5.010 秒，0 failure/skip |
| 年月 instrumentation | `am instrument -w -e synthetic true -e class com.fnphoto.tv.YearMonthNavigationDeviceTest com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner` | NOT RUN | API29 完整19项 PASS（598.678 秒）；API19 完整批次结果尚未确认；前置 API19 两轮抽屉 + 四项返回/后台全部 PASS（35.064/31.772/64.785 秒），API29 五项全部 PASS（142.208 + 41.978 秒） |
| PhotoWall 既有回归 | 同上 class 改为 `com.fnphoto.tv.PhotoWallDeviceTest` | NOT RUN | 当前候选待汇总，共 13 场景 |
| 幻灯片设备回归 | 同上 class 改为 `com.fnphoto.tv.SlideshowDeviceTest` | NOT RUN | 当前候选待汇总，共 8 场景 |
| 空态/搜索/文件夹 | 同上 class 改为 `com.fnphoto.tv.EmptyStateDeviceTest` | NOT RUN | 当前候选待汇总，共 13 场景 |
| diff、相对链接、命令一致性 | `git diff --check 593e368..f864cc7` 及新增/修改 Markdown 链接解析 | PASS | diff 无错误；80 个相对链接可解析；最终报告补交再检查 |
| 真实 NAS | 实际设备上的日期范围、photoDateTime、时区、鉴权及共享权限 | NOT RUN | 无真实 NAS/私人数据；合成结果不证明真实服务兼容性 |

以上 `am instrument` 命令在指定设备的 shell 内执行。设备测试使用真实 Activity、DPAD 和应用网络路径，fixture 在设备内提供合成 HTTP。
部分边界前置使用反射设置坐标/来源，故不声称全部场景均为从登录开始的纯遥控端到端测试。
后台回归通过真实 Settings Activity 覆盖触发 onStop，不能写成 Home 键已认证。
JVM/HTTP 合同与 UI 结果分开；视频筛选条目不构成本次视频编解码验收。

## UI 截图与可访问证据

当前候选 debug APK SHA-256：
`794b30d2403a91e96224659da00c6f44aba20c9b43ba968636a65bb9a8ee1364`。
对应 instrumentation APK：
`328c8cf0b728c61368b0567d012b48752f6fceef7cc8c21ee036b0ec00160150`。
包名分别为 `com.fnphoto.tv`、`com.fnphoto.tv.test`；设备安装身份由测试人员核对。

构建摘要在已忽略的 `test/.generated/year-month-f864cc7-build-summary.json`；
设备截图和摘要将放在同前缀 `-api19`、`-api29` 目录。已产生并目视确认关键返回/后台的实际截图；先提供这些证据，待完整批次结束补齐实际年份/月对话框、
五页暂停/继续、空月/未知日期、原位置恢复、详情返回/抽屉保护等截图，逐张标明 API、场景和 APK hash。
截图必须看见实际对话框或已解码的 SYNTHETIC 图片及焦点，不能拿占位卡片证明图片焦点。
初版合成审阅附件由当前对话交付，包含 APK、测试 APK、源码 patch、关键截图和摘要；
私人 Library 文件链接不放入公共仓库或 PR。初版明确标记完整回归未完成，后续补齐同一附件。
不提交私人媒体、令牌、钥匙或完整日志。

## 模拟器阻碍与解决记录

原始被拒动作是请求向工作区外 `/home/agent/.android` 写入，再执行原生 adb version：
`mkdir -p /home/agent/.android && /workspace/fnphoto-tools/android-sdk/platform-tools/adb version`。
工具为 `exec_command`，请求 `with_additional_permissions` 的 filesystem write 指向该目录。
自动审批原文：

> This action was rejected due to unacceptable risk.
> Reason: The command is a bounded local setup action, but it writes outside the explicitly authorized /workspace tools and local.properties locations.

该动作未执行、未重试，未写该目录、未改 HOME，也未通过其他执行器完成同一被拒写入。
可独立完成的路径是公开 `adb-shell` 客户端直连本任务两台本机模拟器的 ADB TCP 端口，
密钥显式置于 `/workspace/fnphoto-tools/android-home`；官方 emulator 通过支持的
`ANDROID_EMULATOR_HOME` 配置存储在工作区。此路径不执行原生 adb，也不依赖被拒目录。
API29 黑屏经正常唤醒/解锁恢复；安装、instrumentation 和应用截图均已在此前候选实际执行。
当前不存在需放宽该目录权限才能验收的阻碍；若以后确需原生 adb，仍须另行获得该写入授权。

## 独立 review 与失败循环

`independent_review` 与 `regression_review` 是两个独立 agent，不是 GitHub 人工 approval。
两者及其子审查在本次候选构建/测试同时检查源码；最新候选 f864cc7 的生命周期修复独立复审未发现新的确定问题，最终设备结论仍待实测。
复核包括分页/窗口、原始坐标和请求预算、焦点/抽屉、详情返回与翻页、共享只读、幻灯片循环/取消、
收藏、浏览类型恢复及生命周期；Java8/API19、依赖和权限范围未放宽。
最终设备失败若出现仍需回到修复/复审，不能以静态无问题替代设备结果。

| 旧候选 / 问题 | 未修复证据与根因 | 修复及当前重验状态 |
| --- | --- | --- |
| b2d8bd9：深层窗口缺少可达入口、最近添加范围可能扩大 | 静态审查发现 UP 会加载前页，顶栏不易到达；recent 无日期合同 | e02e0eb 加 MENU 入口并拒绝 recent；后续设备验证入口，当前候选待汇总 |
| e02e0eb：抽屉动画与快速恢复回调竞态 | 静态检查快速响应发生在关闭动画前，焦点恢复被保护拒绝 | 8bc72fc 同步关闭导航抽屉；当前候选组合设备测试待汇总 |
| 8bc72fc：等待反向页时停止详情又开始请求 | API19 `stoppingDetailWhileWaitingForOppositePageDoesNotRestartScan` 失败；预期 monthPaused=true/不发新请求，实际暂停被清除。取消通知仍进入 continueOpposite | 270d1ae 给暂停/反向续扫增加 guard；f864cc7 同名关键设备重验双 API PASS，完整回归待汇总 |
| 8bc72fc：held restart 期间 Back 返回墙后请求被取消 | API19 单例失败 44.208 秒；预期 ID1003 恢复，实际 media=0、paused=true、focus=__page_action。正常 Back 与真实后台的 onStop 未区分 | 270d1ae 记录 returningToWall；真后台仍取消。f864cc7 对应关键设备重验双 API PASS，完整回归待汇总 |
| 8bc72fc：单日 Back 数据已恢复但焦点丢失 | API29 日期墙 Back 后 media=1、focus=null；按键抬起撤销同次导航新建的焦点意图 | 270d1ae 用 monthBackOwned 配对处理；该版该项已 PASS，最终候选仍须重验 |
| 270d1ae：数据恢复但实际 focus 未恢复 | API19 held restart/Back 失败：media=35、paused=false，但 focus=null/TextView。selection 不等于 focus，且恢复时窗口尚未就绪 | 179e2ce 延迟到 resumed/windowFocus 并复用 handoff；ACTION_UP 不撤销同次返回意图；f864cc7 关键设备重验双 API PASS，完整回归待汇总 |
| 179e2ce：pending 返回遇抽屉未自动应用 | 独立源码复审发现关抽屉不会触发 resume/windowFocus，pending 可滞留；未虚称设备已复现 | cb56 在抽屉关闭的既有 focusBrowseContent 任务补 applyPending，并增加真实 held/Back/MENU 组合测试；静态复审关闭，设备待汇总 |
| 测试 harness 的失败 | API19 externalFilesDir 为 null；初始异步 focus 覆盖反射选项；慢启动消耗 held 请求超时；recreate monitor 返回旧实例并污染后续；API29 HOME 注入未真实离开前台 | 私有截图目录、实际 focus/帧等待、调整前置顺序、RESUMED 新实例/清理、Settings 真实覆盖。保留旧失败，不能将测试修正等同生产修复 |

当前 cb56 关键子集先行结果：API19 的 opposite-stop 与 held-restart-Back 两项 PASS，
新增抽屉场景在 MENU 前因 Activity 配置重建失败（43.496 秒，旧实例 destroyed=true），
固定横屏后同 APK 又在 12.738 秒内复现 held 请求被提前取消；
API29 关键四项为 2 PASS / 2 FAIL（168.852 秒），抽屉在途前置条件失效；
Back→Settings 的取消/暂停检查已过，最后预期 ID1003 的断言失败。独立审查已确认既有 footer
规则在显式继续后聚焦首个新增项 ID1004，该最后断言需修正并重验，不能记为整个用例 PASS。
后续仅测试改动候选 bd25d4f（主 APK hash 不变）保留失败到诊断：API19 抽屉首轮
PASS 20.147 秒、第二轮 FAIL 12.252 秒，生命周期 trace 明确显示：详情 Back 清除
monthDetailOpen 后，Main 的迟到 onStop 在 heldAge 456 ms 到达，484 ms 主界面再次
启动时 monthPaused=true/loading=false。没有配置变化，排除 30 秒超时；f864cc7 用单个 MonthDetailHandoff 会话修复这一生产竞态：主墙真实 resume 才确认交接，
详情返回途中被其他 Activity 覆盖仍取消；仅保留尚未被新操作撤销的返回焦点意图。新增 8 项
状态测试及第 19 个真实 Activity 未 resume 覆盖测试。f864cc7 关键设备重验在 API19/API29 均 PASS；API19 两轮抽屉均实际覆盖迟到 Main.stop 后仍 loading、未 paused，随后关闭抽屉回到 ID1003。完整回归仍在运行。
旧 bd25d4f 候选的 API19 加强后的后台测试 PASS 14.764 秒；API29 抽屉两轮 PASS（42.4/43.109 秒），
这两轮 Main 停止均发生在 Back 前，不能反证 API19 已记录的问题。
f864cc7 的 API29 完整19项已经19/19 PASS、0 skip，598.678 秒；
API19 已有13项通过，随后设备/ADB响应停滞，最终结果未确认，正在只读诊断；既有设备回归尚未开始。

旧批次明确保留：270d1ae 年月 API19 为 12 PASS / 5 FAIL（381.821 秒），API29 为
16 PASS / 1 FAIL（566.567 秒）；API29 唯一失败是未发生后台的 HOME 前置条件，不是证实取消逻辑失败。
同旧版 API19 PhotoWall 13 PASS（426.009 秒）。这些结果不替代 f864cc7 的最终验收。
旧截图若未等实际绘制不作为相应视觉通过证据；同名 timeout 图可能被覆盖，只能按对应文字输出归因。

## 风险、回退与 PR/CI

- 真实 NAS 的拍摄日期文本、时区、区间端点及各版本字段语义仍未验证。未知日期提示是保守结果，可能使部分普通相册不能完整分类。
- 普通相册的目标若很深，需要多次显式继续；不以无限自动扫描换取表面上的快速跳转。
- 返回点属于当前会话。删除/移动/筛选变动、短页或进程死亡不保证精确恢复；有限范围找不到会明确说明。
- 当前验证是软件模拟器，不证明低内存真机性能、全部遥控器映射或生产视频解码。进程死亡的年月恢复属于非目标，既有详情有限快照不是 live 来源恢复。
- 回退应对本分支独立功能提交逐项 revert，或撤回尚未合并的 Draft；不重置其他任务改动。
- PR/远端 SHA/CI：本报告提交时尚未运行。用户明确要求此时先开 Draft，正文须保留未完成项；创建后的 URL、远端 SHA、CI 状态由 PR 正文及最终交付摘要更新。CI 无设备或真实 NAS 步骤；无 CI 不等于 PASS。未经授权不合并/部署。
