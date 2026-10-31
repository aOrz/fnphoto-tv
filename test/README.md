# 可复现的本地 QA

这些工具用**合成账号、合成图片和合成视频**模拟 NAS，服务于分页、遥控导航和失败恢复回归。它们不是生产 NAS，也不验证真实 NAS 的协议兼容性、认证安全或真实电视性能。完整设备操作与判定标准见 [QA_CHECKLIST.md](QA_CHECKLIST.md)。

所有命令从仓库根目录运行。不要提交生成的媒体、状态、APK、截图或日志；本地输出放在 `test/.generated/` 下。不要输入真实账号、挂载私人照片目录或把测试服务暴露到公网。

## 1. 环境准备

需要 Python 3.10+、可用的 Android SDK/adb 和测试设备或官方模拟器。Android 构建环境按 [CI 说明](../.github/CI.md)配置（JDK 11、SDK 29、Build Tools 30.0.3）。

```bash
python -m venv .venv
# Linux/macOS；Windows 使用 .venv\Scripts\activate
. .venv/bin/activate
python -m pip install -r test/requirements-contract.txt
```

媒体生成另需环境中已有的 `ffmpeg`、`ffprobe`，包含 H.264/AAC 编码支持；先运行 `ffmpeg -version`、`ffprobe -version` 检查。生成器不自动安装或下载二进制。缺少这些工具时记录“未执行媒体生成/解码验证”，不要把跳过当通过。合成图片依赖 Pillow。

## 2. 大数据分页场景

```bash
python test/qa_server.py --profile large --host 127.0.0.1 --port 5666
```

`large` 提供时钟固定为 `2026-10-31T12:00:00Z`、seed 固定为 `42` 的 360 条混合媒体单相册；图片带合成标识，便于核对顺序、复用错图和返回焦点。日期不随运行当天变化；photo29 是 10 月 30 日唯一收藏图，搜索 `test_photo` 可匹配多条。使用原 WebSocket 登录流程，测试用户名和密码均为 `test`。`large` 主要用于浏览/分页；视频播放与错误恢复使用下节 `video` profile，不把一个静态缩略图当作播放成功。

### 设备连接

- 默认仅监听 `127.0.0.1`。同机 HTTP 检查使用 `http://127.0.0.1:5666`。
- 本机官方 Android 模拟器内，宿主地址为 `http://10.0.2.2:5666`，不是模拟器自己的 `127.0.0.1`。先尝试默认 loopback 绑定。
- 仅当本机模拟器连接 `10.0.2.2` 的环境确实要求非 loopback 绑定时，才在**隔离测试网络**明确改为 `--host 0.0.0.0`；限制宿主防火墙访问，测试完停止服务。不要为公网/其他设备便利而开放该配置。
- 真机不使用 `10.0.2.2`；本说明不提供公开监听的真机服务。另行建立经过授权的隔离连接方案。

`--trace-file` 默认写入 `test/.generated/requests.jsonl`，采用追加模式；建议每轮指定独立路径。trace 只记录路由模板、数字 ID、分页参数和状态，不记录认证信息或 WebSocket query；它是请求合同证据，不是 UI 通过证明。

服务在前台运行，结束用 Ctrl+C。切换 profile 前停止旧服务，避免端口占用和连接到错误的数据集。

## 3. 可控视频失败场景

先生成两段带运动/时间码、可区分的本地 MP4：

```bash
python test/generate_qa_media.py --output-dir test/.generated/media --duration 30
```

输出 `clip_a.mp4`、`clip_b.mp4` 和 `manifest.json`。视频内容是程序生成，不含下载素材或私人媒体。编码参数和探测结果以生成器实际输出为准；不同 ffmpeg 版本不保证逐字节相同，复现应核对内容/编码/时长和本轮文件 hash，不依赖跨机器 MP4 hash 相同。

```bash
python test/qa_server.py --profile video --host 127.0.0.1 --port 5666 \
  --media-dir test/.generated/media --state-dir test/.generated/state
```

| 媒体 ID | 用途 |
| --- | --- |
| 124 | 可控失败目标；干净状态默认 HTTP 404 |
| 151 | 正常视频 A，来自 `clip_a.mp4` |
| 314 | 正常视频 B，来自 `clip_b.mp4` |

在另一个终端修改同一个状态目录，**不重启服务、不离开当前详情页**：

```bash
# 每轮显式设定初态，避免复用上轮状态。
python test/qa_server.py control --state-dir test/.generated/state --video-mode missing
# 让 124 原地恢复成正常 A MP4；随后在 TV 错误页按确定重试。
python test/qa_server.py control --state-dir test/.generated/state --video-mode restored
# 让 124 返回 HTTP 200 但内容不是有效 MP4。
python test/qa_server.py control --state-dir test/.generated/state --video-mode invalid
```

控制通过本机状态文件完成，**没有控制 HTTP API**。服务与 control 必须使用同一 `--state-dir`。`restored` 后仍应由用户按重试，不应因后台往返偷偷重新请求。状态文件与生成媒体属于可丢弃本地测试产物，不要放入 Git。

### 可选：模拟器视频帧 smoke 辅助

先由人工在专用模拟器中打开并播放合成 A/B 视频，确保剩余时长超过 15 秒，再执行：

```bash
python test/video_smoke.py --serial emulator-5554 --confirm-synthetic-video-playing \
  --output-dir test/.generated/evidence
```

可用 `--adb PATH` 指定 adb。确认参数表示你已核对屏幕为正在播放的合成视频，而不是授权脚本查找或播放私人媒体。工具核对 `ro.kernel.qemu`，拒绝真机；只截图并发送按键85暂停/恢复，不安装应用、登录或修改设备设置。每次输出独立子目录的 PNG 和 JSON。颜色/运动 ROI 判断只是启发式辅助，仍需人工确认真实帧推进、暂停及恢复；不是完整 UI 自动化，也不测错误页/导航。离线单测通过不表示在新设备上实际跑过此脚本。

## 4. 自动检查与手工检查的分工

```bash
python -m unittest discover -s test -p 'test_*.py' -v
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Python discovery 包含已有 mock 合同和新增 QA fixture/HTTP/媒体生成检查；查看每项的通过、失败、跳过状态。依赖外部媒体工具的测试须核实本机工具是否存在。CI 的实际任务和依赖以 [workflow](../.github/workflows/android.yml) 为准。测试计数会随代码变化，不以历史固定数量充当当前运行证据。

- 自动测试可以验证分页数据合同、状态切换、HTTP 响应、合成媒体可生成/探测；它们不能证明 Android 上真实解码、遥控焦点、UI 提示或生命周期恢复。
- `test_mock.py` 是对**已启动**服务的显式 HTTP smoke 工具，不是设备 UI 自动化。不要把其通用 stream 期望直接套用到故意制造 404 的视频场景。
- `test_server.py` 是底层/原有 mock，直接启动也默认 loopback；媒体同样需要先运行生成器。旧 TLS key/cert 和 MP4 已从 Git 移除，生成产物位于忽略的 `.generated` 目录。可复现回归优先用 `qa_server.py` 的固定 profile，不依赖旧入口的动态日期或旧视频素材。

## 5. 安装与留证

```bash
adb devices
# 多设备时每条 adb 命令都加 -s <serial>，只操作专用测试设备。
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p test/.generated/evidence
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell pm path com.fnphoto.tv
adb -s <serial> logcat -v threadtime > test/.generated/evidence/logcat.txt
```

记录源码 commit、APK SHA-256、实际安装 APK 身份、Android API/镜像、fixture profile、媒体 hash、执行时间。`versionName` 相同不代表 APK 相同。若采集安装包核对 hash，使用 `pm path` 实际返回的路径，不硬编码设备路径。

截图示例：`adb -s <serial> exec-out screencap -p > test/.generated/evidence/01-case.png`。只在合成数据专用设备采证；日志可能含测试令牌，也不应提交。记录每项 **PASS / FAIL / NOT RUN / BLOCKED**，附必要证据及范围限制。

## 历史设备验证边界

2026-10-31 的既有设备验证使用官方模拟器与本地合成数据，而不是本目录新增 CLI 已经全量重跑的证明：

| 历史候选 APK hash 前缀 | 已有设备证据 |
| --- | --- |
| `1eaebb91` | API29 TV：相册 35 条按需分页、详情切换/返回焦点、人物多级 Back、媒体分类取消、照片详情进程恢复；API19 普通 Android：文件夹、搜索、设置/清缓存等兼容性 |
| `4dab7e53` | API29：取消收藏后日期列表为空，再 Back 重取收藏时间线，过时日期卡消失；未重跑上述完整矩阵 |
| `9bf8247d` | API29 TV 视频专项：404 持久提示、同页重试、真实帧推进、暂停/恢复、A/B 切换、坏文件错误、过期回调取消、退出释放；未重跑 API19 |

历史 API19 镜像是 Android 4.4.2 普通 Android，不是 TV OS；视频专项使用无音频输出的软件模拟器，因此未听验音频。历史结果不替代当前 commit/APK 的检查表；未验证真实 NAS/电视、4K/HEVC/HDR、长时间稳定性、弱网全矩阵或真实硬件 FPS。
