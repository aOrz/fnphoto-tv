# 视频遥控与媒体切换

视频进入播放会话后（包括暂停、缓冲、播放结束），遥控器方向键不再直接切换媒体。

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| 视频画面 | 左 / 右 | 当前视频快退 / 快进 10 秒；按住按系统重复事件连续定位，最多每 250ms 一步 |
| 视频画面 | 确定 / Enter / 空格 | 播放 / 暂停；结束后播放从头开始 |
| 视频画面 | 上 / 下 / Menu，或触屏点击画面 | 打开操作栏，初始焦点为播放 / 暂停 |
| 操作栏 | 左 / 右、确定 | 移动焦点、执行按钮：上一项、快退、播放/暂停、快进、下一项、收起 |
| 操作栏 | 返回 / Menu / 收起 | 收起操作栏，焦点回到画面；下一次返回退出详情 |
| 视频画面 | 返回 | 退出详情，释放播放器 |
| 任意视频焦点 | 独立快进 / 快退媒体键 | 定位当前视频，操作栏是否可见不改变含义 |
| 任意视频焦点 | 独立播放 / 暂停媒体键 | 明确播放 / 暂停，连续按播放不会反向暂停 |
| 详情页 | 上一曲 / 下一曲媒体键 | 切换上一项 / 下一项媒体，一次按下只切一项 |
| 触屏操作栏 | 拖动进度条 / 点击按钮 | 精确定位 / 对应播放和媒体切换操作 |
| 图片、视频预览或错误页 | 左 / 右 | 保持切换媒体；预览确定播放，错误页确定重试 |
| 图片 | 上 / 下、确定、Info | 保持缩放、幻灯片、信息；图片快进媒体键仍可开始幻灯片 |

没有独立媒体键的遥控器可以只用方向、确定和返回完成全部视频操作。画面上显示操作提示，按钮有可读名称和无障碍描述；操作栏聚焦期间不自动隐藏。SeekBar 保留触屏和无障碍调整，遥控器焦点停留在有文字的按钮上。

短按和长按使用相同含义，长按左右始终定位，不会转换为切媒体。只有定位键接受连续重复，播放、返回、打开操作栏、独立媒体切换只在首次按下执行。接管的按键释放会一并消费，避免切换媒体或打开操作栏后旧按键再触发新页面。

定位限制在 `0..duration`，首尾不循环、不自动换片。时长未知、尚未准备好或播放器报告不可定位时，显示提示并消费按键。定位保留播放/暂停意图；平台备用播放器串行处理异步定位，把连续按键累计为最新目标。手动打开控制栏、定位、播放/暂停或切媒体会停止幻灯片自动前进，避免定位到片尾意外换片。图片的幻灯片定时和视频自然播放结束后的自动前进保留。

## 实现边界

- `MediaDetailActivity.dispatchKeyEvent` 在子 View 之前统一分发；禁用 ExoPlayer 自带控制器，避免原生控制器、Activity 和焦点三个入口互抢按键。操作栏方向键继续交给 Android 的焦点分发。
- `VideoRemoteInput` 管理按键所有权、重复节流和跨媒体序列；`VideoSeekState` 处理边界及平台播放器异步定位队列；`VideoControls` 只负责视频详情操作栏。未改全局网格、搜索、文件夹或登录焦点布局。
- 基于 main `97c370db`，保留焦点、中文 README、照片墙自适应布局与开发指引；复用其 `bg_pill_button` 焦点样式。以后若改详情页按键入口、播放器视图或该按钮背景，需要一起检查交互；不应再次为视频左右键增加媒体导航。
- 没有更换 ExoPlayer 2.11.8、最低 Android API 或媒体传输/鉴权逻辑。具体电视的按键映射、重复频率、解码与备用播放器可定位能力仍取决于设备，模拟器通过不代表所有电视兼容。

## 自动检查与模拟器场景

```bash
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m unittest discover -s test -p 'test_*.py' -v
```

单测覆盖输入路由、焦点模式、DOWN/UP、长按节流、跨媒体残余事件、PLAY/PAUSE、准备阶段意图、定位上下界和异步累计。实际 Activity/播放器验证使用 `app/src/androidTest` 的AndroidX instrumentation runner，需专用模拟器和本地合成服务：

```bash
python test/generate_qa_media.py --output-dir test/.generated/media --duration 90
python test/qa_server.py --profile video --host 127.0.0.1 --port 5666 \
  --media-dir test/.generated/media --state-dir test/.generated/state
# 在另一个终端，指定专用模拟器：
ANDROID_SERIAL=emulator-5554 bash ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.synthetic=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fnphoto.tv.VideoRemoteInstrumentationTest
```

设备测试使用 `10.0.2.2:5666`，注入合成详情列表和测试配置，不需要真实 NAS。只能在可丢弃的测试模拟器运行；测试会临时替换应用配置并在结束时恢复。设备测试检查真实事件分发、焦点、播放位置、媒体索引和暂停意图；它不能代替真机听验、无障碍服务实测、所有备用解码器、断网和长期稳定性验证。

手工补充矩阵：播放中/暂停时左右短按；长按快进后反向；首尾和重播；缓冲时定位；控制栏逐按钮移动及首尾边界；触屏打开和拖动；媒体键PLAY、PAUSE、NEXT、PREVIOUS；控制栏返回再返回；视频→图片→视频；错误页重试；图片缩放/幻灯片/信息；后台退出不再定位或播音。每次记录实际 APK hash、镜像、合成视频 hash 和 PASS/FAIL/NOT RUN，不把纯逻辑单测当成设备验收。
