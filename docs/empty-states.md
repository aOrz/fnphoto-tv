# TV 列表状态

应用的启动器标签、登录标题、主界面标题和设置中的应用名称统一使用
`@string/app_name`（飞牛相册）。包名、applicationId、签名配置、偏好键和媒体数据不变。

## 展示与操作

`TvStateView` 提供 24sp 标题、18sp 说明和最小 52dp 的操作按钮，按钮保留可见边框和
实际焦点环。绑定状态本身不请求焦点。主界面由 `focusBrowseContent` 管理焦点，
导航抽屉打开（包括动画期间）或窗口失焦时不会抢回焦点；搜索保留输入框焦点，
按下可到状态按钮；文件夹仅在没有有效焦点时聚焦状态或内容。

搜索框在自身的按键监听器中处理 `DPAD_DOWN`，先于 EditText 的光标移动处理；
不能仅依赖 Activity 的 `onKeyDown`，否则输入框可能消费按键，让错误页重试不可达。
显式下移优先进入可见状态按钮、已有结果的重试/下一批状态行，最后是可见结果网格。
加载中无动作时保留输入焦点；异步成功、失败和取消不主动把焦点从输入框移走。

部分 TV 输入法即使隐藏也会吞掉/延迟遥控键，单靠控件监听器仍不足。搜索页在浏览模式
通过根布局的 `dispatchKeyEventPreIme` 将方向、确定和 Back 交给现有 Activity/View 分发；
未处理的方向键沿实际焦点树移动。键盘的可见 frame 还在全局布局变化时更新，不仅依赖全屏根布局的尺寸变化或下一次按键。
同一个设备/按键/downTime 的 DOWN、重复和 UP
固定归同一端，避免键盘恰好显隐时丢失确认键或拆分 Back。窗口失焦清除未完成键序列。
此逻辑仅作用于搜索页，不改变结果加载、offset 或重试次数。

输入框用于打开键盘的确定/Enter 完整消费 DOWN/重复/UP，只在匹配且未取消的 UP
打开，避免只消费 UP、让另一半继续进入其他编辑/输入法分发路径；不猜测特定 IME 的内部实现。
用户明确按确定/点击时以 `showSoftInput(..., 0)` 正常请求键盘，不用可被忽略的
`SHOW_IMPLICIT`，也不用可能跨窗口保留的 `SHOW_FORCED`；合同见
[InputMethodManager](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager#SHOW_IMPLICIT)。
在输入框按确定/点击后，显式键盘请求（包括尚未显示或浮动键盘）及观测到的可见键盘
仍拥有按键，不抢走键盘中的方向选择。Back 先收键盘，IME 的 Search/Done 动作收键盘，
之后方向键恢复浏览；显示请求被拒绝/报告保持隐藏时也恢复，不用固定短延时猜测失败。
原有启动收键盘回调不会关闭用户刚主动打开的键盘。可见高度观测采用 API19 兼容方式，
不能据此保证所有厂商输入法；仍需设备验收显式打开、输入、收起及重新浏览。

| 情形 | 展示与操作 |
| --- | --- |
| 图库、文件夹目录、收藏、最近、相册、共享、人物、地点、照片、视频为空 | 按当前分类解释；“浏览其他内容”打开导航 |
| 人物时间线或地点查询返回零项 | 返回人物/地点；地点保留响应完整性说明，不能保证整个地点无照片 |
| 尚未输入搜索词 / 搜索成功零结果 | 分别提示输入 / 修改关键词，不混同加载或失败 |
| 文件夹的子目录和媒体都成功返回零项 | 提示空文件夹，可返回上级 |
| 请求在途 / 搜索输入防抖 | 进度圈和加载说明，不提前显示空状态 |
| HTTP 401 / 403 | 登录失效 / 无权限，提供确定键重试；未知业务错误不猜成权限问题 |
| 网络、业务错误或无效数据 | 持续的失败状态和重试，不写成“暂无” |
| 已有内容时追加失败 | 保留已加载内容和原位置，通过状态入口重试原 offset |
| 稀疏扫描暂停、重复页、窗口上限、未知结束边界 | 保留继续查找、下一批或不完整提示，不冒充空或结束 |
| 已翻过一个窗口后的终止空页 | 提示本批没有更多内容，不声称整个搜索或目录为空 |

`BrowseStatus`、`GridScreenState` 只决定展示，不修改 `MainPagingState`、`GridPageState`、
原始行 offset、去重、在途请求、窗口预算或自动预取规则。主界面零内容面板保留底层
分页 footer，重试仍调用原加载入口。导航使旧请求及旧状态动作失效。

列表响应必须包含明确成功的业务状态码。`TimelineResponse`、`AlbumListResponse`、
`FolderListResponse`、`SubFolderListResponse`、`FolderFileListResponse`、`GeoListResponse`、
`PersonListResponse`、`PersonTimelineResponse` 与原有 Gallery/Shared 响应一样，缺失或 null
`code` 不会默认为 0。此约束不代表其他未涉及 DTO 已全部修复。

## 验证入口

按 [开发流程](development/workflow.md) 执行单测、debug 构建和 lint。
`BrowseStatusTest`、`GridScreenStateTest` 检查加载、失败、暂停、边界与真空的优先级；
`api/ListResponseStatusTest` 用 Gson 检查成功、失败、缺失和 null 状态码。
`SearchKeyboardStateTest` 检查显式编辑/可见键盘、失败请求代数、键对跨显隐及窗口失焦；
这些纯状态测试不代表真实 IME 已通过。

`EmptyStateDeviceTest` 使用进程内 loopback 合成服务，覆盖真实 Activity、遥控重试、
抽屉/输入焦点和页面恢复，并保存截图。搜索回归覆盖提示/无结果/503/401/403 的下移和
确定重试、上移返回输入、加载时隐藏按钮不可达、取消旧失败、正常结果及追加失败原 offset
重试；使用系统 `sendKeySync` 注入真实按键分发，不直接调用 Activity 的 `onKeyDown`。
同一手势 DOWN/UP 显式共用一次 `downTime` 与设备 ID，UP 的事件时刻单独更新。
不能用 API29 的 `sendKeyDownUpSync` 验证严格成对手势：它会分别给两个零时间事件
补时间，可能变成不同 `downTime`，从而被生产代码正确拒绝为孤立 UP。依据见
[Android10 Instrumentation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-10.0.0_r1/core/java/android/app/Instrumentation.java#L984-L1012)。
显式键盘用例（API21+）同时检查 `dumpsys input_method` 和专用 TV 停靠键盘带来的
窗口可见 frame 收缩/复原，并保存截图人工核实；不能把服务 shown 标志当成已绘制键盘。
该 TV 输入法的 accessibility root 不可靠，不将其作为必须通过的窗口可见性条件。
这不是浮动/overlay IME 的覆盖；不修改设备输入法设置，也不在测试中重置键盘状态。验证
确定打开时查询不变、键盘方向选择、Back 收起和恢复重试焦点；隐藏验证同时等待
系统状态、窗口可见 frame 和浏览按键归属恢复，不将隐藏动画开始当作动画结束。
Search 动作部分调用实际 editor
回调验证收起接线，不声称覆盖各厂商键盘的 Search 按钮布局。
仅在专用合成模拟器运行：

```bash
bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e synthetic true \
  -e class com.fnphoto.tv.EmptyStateDeviceTest \
  com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Android/data/com.fnphoto.tv/files/empty-state-evidence \
  test/.generated/empty-state-evidence
```

将 serial 换成确认过的专用模拟器。记录本次 APK hash 和实际执行结果；没有生成和安装
本次 APK 时，设备用例与截图验证必须记为未执行。组件预览和 JVM 状态测试不能替代
整页焦点、API19 兼容性或真实 NAS 验收。
