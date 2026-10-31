# AI 开发指引

本仓库是 Java Android TV 客户端，单模块 `:app`，包名 `com.fnphoto.tv`。
先读 [开发上下文与代码地图](docs/development/README.md)，再按任务阅读
[实现约束/API 边界](docs/development/constraints.md) 和
[构建、测试与模拟器流程](docs/development/workflow.md)。

## 开始修改

- 先看 `git status --short --branch`、目标文件及其测试；保留用户和并行任务的改动。
  若存在更近层级的 `AGENTS.md` 或 `.agents/skills`，先检查适用说明。
- 以当前源码、Gradle 配置和 CI 为当前行为依据。旧文档的测试次数、APK 和会话记录
  不是本次验证证据；文档冲突及适用范围见开发上下文。
- 保持 Java 8 源码、API 19 兼容性和现有依赖约束；新 Android API 必须有版本保护。
  不要为修复局部问题顺带升级 AGP、Gradle、OkHttp、Leanback 或 ExoPlayer。
- 优先扩展已有状态类和对应测试，不要重写整份 `MainFragment` 或绕过现有分页/取消逻辑。

## 必须保留的行为

- TV 所有操作必须支持 DPAD、确定和 Back；焦点环应在图片上方，实际 focus 与 selection
  分开。抽屉、输入框、重试和下一批入口不能丢失焦点或被异步回调抢焦点。
- 分页 offset 按服务端原始行数推进；显示去重、单请求在途、失败原 offset 重试。
  缺少 `hasNext` 时短非空页不代表结束。保留 1,200 项窗口、5 页稀疏扫描预算和
  8 MiB 元数据限制；暂停/不完整不能伪装成结束。
- 导航/查询变化先使旧代数失效，再取消请求；图片、详情、播放器和计时器必须随生命周期清理。
  使用现有 Glide 管线，不在 UI 线程解码/扫描磁盘，不手工回收 Glide bitmap。
- 不猜 NAS 路径、参数或权限。共享目前仅支持接收方只读浏览；标签、智能分类、地点续页
  缺少可验证合同。不要把 mock 成功当真实 NAS 兼容证据。
- 保留真实请求体签名、受限重试和认证重定向约束；普通视频与共享视频的传输路径不同。
  当前 TLS/凭据存储限制见详细文档，不要宣称已具备严格证书校验或加密存储。
- 不把真实账号、密码、令牌、签名密钥、私人媒体或完整日志写进文档/fixture/提交。
  合成 QA 产物放在已忽略的 `test/.generated/`。

## 验证与交付

从仓库根目录、JDK 11 + Android SDK 29 + Build Tools 30.0.3 环境运行：

```bash
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
# 先按 workflow.md 安装 Python 依赖及 FFmpeg/ffprobe。
python -m unittest discover -s test -p 'test_*.py' -v
git diff --check
```

按变更选择验证：文档检查链接/命令；状态/API 改动运行相关单测；Android 改动完成上述
构建/lint，焦点/播放/生命周期还需 [设备验收](test/QA_CHECKLIST.md)。不要把 JVM
状态测试称作 UI 测试。报告实际执行、跳过、失败和未执行项；设备证据必须对应本次 APK。
行为或合同改变时同步更新对应专题文档和开发索引，保持本文件简短。
