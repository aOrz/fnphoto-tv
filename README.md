# fnPhoto TV

**简体中文** · [English](README_EN.md)

在 Android TV 上浏览飞牛 NAS（fnOS）中的照片和视频，用遥控器在大屏上查看时间线、文件夹和相册。

## 应用效果图

以下复用仓库已有的应用运行截图，展示合成测试内容，不含真实用户照片或账号。图片来自 [UI 重设计提交 `4133a81`](https://github.com/aOrz/fnphoto-tv/commit/4133a81ffac85ae14860f6da9f4493e713fd753b)，早于当前 main 的后续更新，焦点样式等细节可能有所不同；这些截图不代表发布版本或真实 NAS 兼容性验证。

**图库时间线**

![图库时间线：按日期分组展示合成图片](docs/ui-redesign/01-gallery-timeline.png)

| 遥控器侧边导航 | 设置与缓存管理 |
| --- | --- |
| ![侧边导航：切换图库、文件夹、相册等栏目](docs/ui-redesign/02-navigation-drawer.png) | ![设置页面：本地测试连接与图片缓存管理](docs/ui-redesign/03-settings.png) |

| 文件名搜索 | NAS 登录 |
| --- | --- |
| ![搜索页面：输入文件名查找媒体](docs/ui-redesign/04-search.png) | ![登录页面：NAS 地址、账号、密码与记住登录选项](docs/ui-redesign/05-login.png) |

设置图中的 `10.0.2.2` 是模拟器访问宿主机的测试地址；实际使用时请填写自己的 NAS 地址。

## 功能特点

- **FN Connect 支持**：通过 FN ID（云端）、局域网 IP 或域名登录，自动发现可达的 NAS
- **时间线视图**：按日期组织的时间线形式浏览照片
- **文件夹视图**：浏览受管理的文件夹
- **相册视图**：浏览用户创建的相册
- **共享相册**：只读浏览“分享给我”的相册，支持照片/视频分页、返回定位与失败重试（[接口依据和限制](docs/shared-albums.md)）
- **媒体播放**：查看照片和播放视频，支持全屏显示
- **无缝导航**：使用电视遥控器左右键切换照片
- **全屏体验**：无系统 UI 干扰的沉浸式查看
- **TLS 1.2 支持**：内置 Conscrypt + Mozilla CA 证书包，为 API 19 旧设备提供 TLS 1.2 HTTPS 连接支持

## 系统要求

- Android TV 或 API 级别 19+（Android 4.4）的 Android 设备
- 运行相册服务的飞牛 NAS（fnOS）
- 设备能够访问 NAS：可使用同一局域网的 IP，也可通过可达域名或 FN ID 连接

## 安装步骤

构建需要 JDK 11、Android SDK Platform 29 和 Build Tools 30.0.3。配置 `JAVA_HOME` 和 `ANDROID_HOME`，完整环境说明见 [CI 文档](.github/CI.md)。

1. 克隆仓库：

```bash
git clone https://github.com/aOrz/fnphoto-tv.git
cd fnphoto-tv
```

2. 使用 Android Studio 或 Gradle 构建项目：

```bash
bash ./gradlew :app:assembleDebug
```

3. 让 `adb` 连接到已开启调试的 Android TV，再安装 APK：

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

连接多个设备时，用 `adb -s <serial> install -r ...` 指定目标。上述命令生成调试 APK；CI 调试产物的获取方式见 [CI 文档](.github/CI.md)。

## 认证方式

本应用使用 [fnnas-api](https://github.com/FNOSP/fnnas-api)（由 FNOSP 开发）的认证机制，流程如下：

1. 基于 WebSocket 登录获取访问令牌
2. 使用 API key 和 secret 对请求签名
3. 在签名中加入随机 nonce 和时间戳

详见 [API.md](API.md) 的详细认证文档。

## 使用说明

1. **首次启动**：输入 FN ID、局域网 IP 或域名，然后输入用户名和密码
2. **时间线导航**：按年、月分组浏览照片
3. **侧边菜单**：按遥控器 MENU 键或选择右上角“浏览”，访问文件夹、相册和设置
4. **查看照片**：点击日期查看当天的所有照片
5. **媒体详情**：点击照片或视频进入全屏查看
6. **照片切换**：在详情页使用左右键切换照片
7. **返回与退出**：返回键先关闭菜单或返回上一级；回到主浏览层级后，在两秒内连按两次返回键退出

## 应用架构

- **LoginActivity**：登录页面，作为启动 Activity
- **MainActivity**：主入口，带侧边抽屉导航
- **MainFragment**：基于 BrowseSupportFragment 的相册网格展示
- **MediaDetailActivity**：全屏照片/视频查看器，视频播放使用 ExoPlayer
- **PhotoDetailActivity**：独立的全屏图片查看器
- **CardPresenter**：自定义 Presenter 用于照片缩略图展示
- **FnAuthUtils**：认证和请求签名工具类
- **FnHttpApi**：基于 Retrofit 的 NAS API 通信接口
- **FnConnectApi**：FN Connect 云端 API 客户端 — FN ID 查询、authx 签名、基于优先级并行延迟的 TCP 探测
- **TlsUtils**：Conscrypt SSL 提供者 + 内置 Mozilla CA 证书包，为 API 19 提供 TLS 1.2 支持
- **FnWebSocketClient / FnWebSocketManager**：基于 WebSocket 的登录流程
- **ApiInterceptor / Reauthenticator**：处理认证失败后的重新登录、令牌更新和请求重签名

## API 接口

应用与飞牛 NAS 相册 API 通信，主要接口如下：

- `/p/api/v1/gallery/timeline` — 获取时间线数据
- `/p/api/v1/gallery/getList` — 获取指定日期范围的照片
- `/p/api/v1/photo/folder/list` — 获取受管理的文件夹
- `/p/api/v1/folder_view/getFolderList`、`/p/api/v1/folder_view/getFileList` — 获取子文件夹和文件
- `/p/api/v1/album/list` — 获取相册列表
- `/p/api/v1/album_grant/list_to_me`、`/p/api/v1/album/normal/getList` — 获取“分享给我”的相册及内容
- `/p/api/v1/stream/p/t/{id}/{variant}/{uuid}` — 加载图片；使用接口返回的 URL 或按场景选择尺寸路径
- `/p/api/v1/stream/v/{id}` — 播放视频

相册 API 请求使用 `accesstoken` 与 `authx` 认证头。接口定义以 [FnHttpApi.java](app/src/main/java/com/fnphoto/tv/api/FnHttpApi.java) 为准，共享内容的接口依据与限制见 [共享相册说明](docs/shared-albums.md)。

## 配置说明

日常使用只需在登录页填写 NAS 地址、用户名和密码。开发者如需调整请求签名，可在 [FnAuthUtils.java](app/src/main/java/com/fnphoto/tv/api/FnAuthUtils.java) 中修改以下常量（下列值仅为占位示例，无需普通用户填写）：

```java
private static final String API_KEY = "YOUR_API_KEY";
private static final String API_SECRET = "YOUR_API_SECRET";
```

## 技术栈

- **语言**：Java
- **最低 SDK**：19（Android 4.4 KitKat）
- **目标 SDK**：29（Android 10）
- **TV UI 框架**：Android Leanback
- **图片加载**：Glide
- **视频播放**：ExoPlayer
- **网络请求**：OkHttp 3.12.x + Retrofit

## 致谢

- 认证实现基于 [fnnas-api](https://github.com/FNOSP/fnnas-api)（由 FNOSP 开发）
- 使用 Android Leanback 库构建电视优化界面
- 图片加载使用 Glide
- 视频播放使用 ExoPlayer

## 开源协议

项目原有说明声明使用 MIT 协议；当前仓库尚未包含独立的 `LICENSE` 文件。

## 贡献指南

欢迎提交 Pull Request。重大变更请先提交 issue 讨论。

## 测试与合成数据

可复现 mock、按需生成的 H264/AAC 视频及遥控回归步骤见 [test/README.md](test/README.md)，设备用例见 [验收清单](test/QA_CHECKLIST.md)，构建检查见 [CI 说明](.github/CI.md)。无需真实 NAS 账号或私人媒体。新生成的 QA 媒体、密钥、截图和日志放在忽略的 `test/.generated/` 中，不提交仓库；上方效果图复用已收录的公开文档图片。
