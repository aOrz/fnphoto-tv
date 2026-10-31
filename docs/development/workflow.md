# 开发与验证流程

所有命令从仓库根目录运行。完整 CI 定义在
[android.yml](../../.github/workflows/android.yml)，设备用例在
[QA_CHECKLIST.md](../../test/QA_CHECKLIST.md)。按变更选择检查，并记录实际未覆盖范围。

## 工具链与构建

| 项目 | 仓库当前配置 |
| --- | --- |
| Gradle / Android Gradle Plugin | wrapper 7.4 / AGP 4.2.2 |
| 构建 JDK / Java 源码 | JDK 11 / Java 8 |
| Android SDK | compile/target 29，min 19，Build Tools 30.0.3 |
| 主要固定依赖 | OkHttp 3.12.12、Retrofit 2.6.4、Leanback 1.0.0、Glide 4.12.0、ExoPlayer 2.11.8、Conscrypt 2.5.2 |

依据为 [根 build.gradle](../../build.gradle)、[app/build.gradle](../../app/build.gradle)、
[wrapper properties](../../gradle/wrapper/gradle-wrapper.properties)。先安装 SDK 包
`platforms;android-29`、`build-tools;30.0.3`，让 `ANDROID_HOME` 或本地
`local.properties` 指向 SDK，`JAVA_HOME` 指向 JDK 11。
现代 `sdkmanager` 安装包时可能需 JDK 17（CI 先用 17 安装，再切回 11 构建）。
不要将 `local.properties` 或本机工具链路径提交。

```bash
java -version
bash ./gradlew --version
bash ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest
bash ./gradlew --no-daemon --stacktrace :app:assembleDebug
bash ./gradlew --no-daemon --stacktrace :app:lintDebug
```

wrapper 无 Git 执行位，因此用 `bash ./gradlew`，不需要全局安装 Gradle。
默认检查 debug；release 签名涉及本地 keystore，不是通用验证前提。
依赖通过 Google/Maven Central 下载，仅 `com.google.android.exoplayer` 使用
`https://maven.aliyun.com/repository/public` 的 HTTPS 归档镜像。
受限网络缺少此主机时记录依赖下载受阻，不通过 HTTP、关闭校验或任意升级依赖绕过。

产物：

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- JUnit：`app/build/test-results/testDebugUnitTest/` 和 `app/build/reports/tests/testDebugUnitTest/`。
- Lint：`app/build/reports/lint-results-debug.html`、`lint-results-debug.xml`。

CI 运行上述三项及下方 Python discovery；没有模拟器/instrumentation/真实 NAS 步骤。
本地缺少 JDK/SDK 或网络依赖时，报告原因和未运行检查，不能沿用历史 APK 的通过记录。

## Python 合同与合成媒体检查

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r test/requirements-contract.txt
ffmpeg -version
ffprobe -version
python -m unittest discover -s test -p 'test_*.py' -v
```

需要 Python 3.10+、aiohttp、cryptography、Pillow。全量 discovery 还包含
FFmpeg/ffprobe 的 H.264/AAC 生成检查；工具须预先安装。缺少媒体工具可能导致测试跳过，
检查输出中的 skips，不能把“OK (skipped=...)”描述为全部覆盖。
`requirements-contract.txt` 的“无需 ffmpeg”注释只适用于基础合同部分。

局部验证示例（不替代变更需要的其他检查）：

```bash
bash ./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.fnphoto.tv.MainPagingStateTest'
python -m unittest discover -s test -p 'test_shared_fixtures.py' -v
```

JVM 测试使用 JUnit 和少量 Android stub；Python 验证 handler/HTTP/fixture/媒体生成。
两者都不证明 TV 上的焦点、视频帧推进、运行时 API19 兼容或真实 NAS 鉴权。
不要将手动 HTTP `test_mock.py` 当作 UI 自动化。

## 启动合成服务

仅用合成账号 `test` / `test`，不连接私人照片目录，不把服务开放到公网。
同一端口一次运行一个 profile；结束用 Ctrl+C。输出放已忽略的 `test/.generated/`。
更多参数、故障模式和来源说明见 [test/README.md](../../test/README.md)。

常规分页/焦点（固定 360 项）：

```bash
python test/qa_server.py --profile large --host 127.0.0.1 --port 5666
```

视频先生成合成媒体，再启动服务：

```bash
python test/generate_qa_media.py --output-dir test/.generated/media --duration 30
python test/qa_server.py --profile video --host 127.0.0.1 --port 5666 \
  --media-dir test/.generated/media --state-dir test/.generated/state
```

另一终端控制同一状态目录，`124` 是故障目标、`151/314` 是正常 A/B 视频：

```bash
python test/qa_server.py control --state-dir test/.generated/state --video-mode missing
python test/qa_server.py control --state-dir test/.generated/state --video-mode restored
python test/qa_server.py control --state-dir test/.generated/state --video-mode invalid
```

控制修改本地文件，没有 HTTP 控制接口；`restored` 后在 TV 按确定重试。
不能把静态缩略图当播放成功，要观察烧录时间码/运动帧、暂停和恢复。

共享和大图库边界入口：

```bash
python test/qa_server.py --profile shared --host 127.0.0.1 --port 5667 \
  --state-dir test/.generated/shared-state --trace-file test/.generated/shared-requests.jsonl
python test/large_library_server.py --profile sparse-video --count 200
```

最后两条是各自独立的场景。共享视频也需生成媒体。
共享失败/撤权控制见 [共享 fixture](../../test/README.md#6-分享给我合同与-ui-场景)；
更多地点、超大响应、短页、延迟与单次失败见 [边界 fixture](../large-library-api-contract.md)。
这些注入的状态码和字段只证明客户端对合成情形的处理。

## 模拟器和设备验收

- 官方本机 Android 模拟器用 `http://10.0.2.2:5666` 连接宿主服务，共享场景为 5667；
  宿主自身用 `127.0.0.1`。真机不使用 `10.0.2.2`。默认 loopback，特殊隔离网络方案
  按已有 QA 说明处理，不为联调方便直接开放公网监听。
- 使用专用合成数据设备，所有 adb 命令指定 serial。API29 TV 遥控与 API19 普通
  Android 兼容性是不同覆盖范围，不互相替代。

```bash
adb devices
# 将 emulator-5554 换成上面确认的专用设备 serial。
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm path com.fnphoto.tv
adb -s emulator-5554 shell input keyevent 23
adb -s emulator-5554 shell input keyevent 4
```

两条 keyevent 分别是确定和 Back。安装后经正常 launcher 登录/导航，执行
[QA 清单](../../test/QA_CHECKLIST.md) 中对应项。记录 commit、APK hash、实际安装包身份、
镜像/API、profile、媒体 hash 和 PASS/FAIL/NOT RUN/BLOCKED；同 versionName 不代表同 APK。
截图/日志只保存在本地合成设备证据目录，可能含测试令牌，不提交完整日志。

最低关注：不透明照片上的焦点、抽屉/空态/重试可达、分页/下一批、快速 DPAD 与 Back、
旧响应取消、共享返回位置、照片/视频混合切换、错误页重试、后台及进程死亡恢复。
进程死亡必须证明进程确实重建，不能用普通后台往返代替；详细步骤以 QA 清单为准。
`video_smoke.py` 只在人工确认合成视频已播放的模拟器上截图/暂停恢复，是启发式辅助，
不替代人工判定或真实硬件性能测量。

## 修改完成前

运行 `git diff --check`，检查新增 Markdown 相对链接能在仓库内解析，核对示例参数与
对应 CLI `--help`/Gradle 配置。文档变更无需凭空增加实现镜像测试；行为变更补充能够
暴露回归的状态/合同测试，并更新受影响的已有专题及 [开发上下文](README.md)。
报告只列本次执行及范围，不复制历史测试总数或旧候选 APK 的设备结论。
