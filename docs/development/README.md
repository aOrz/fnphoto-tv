# 开发上下文

这里是维护入口，记录可从仓库核对的实现、约束和验证方法，不存放会话纪要或凭据。
初始化核对基线为 `main` 提交 `521cbab8487a596099c5b0d88dd6a4f4875c6b1b`；
后续改动以当前源码为准，并同步更新受影响的章节。这个基线不是构建或设备通过声明。

- [实现约束与 API 边界](constraints.md)：TV 焦点、分页、缓存、鉴权和未实现能力。
- [开发与验证流程](workflow.md)：真实构建命令、合成服务、模拟器和证据范围。
  其中 [ExoPlayer 来源与兼容边界](workflow.md#exoplayer-来源与兼容边界) 说明官方 Maven 依赖与 API 19 约束。
- [根 AGENTS.md](../../AGENTS.md)：AI 每次修改需要遵守的简明规则。

## 项目与目录

这是 FN NAS/fnOS 相册服务的 Android TV 浏览客户端，没有仓库内生产服务端。
应用使用 Java、AndroidX Leanback/AppCompat、Retrofit/OkHttp、Glide 和 ExoPlayer；
不是 Compose/Kotlin 或 Web 项目。生产包名为 `com.fnphoto.tv`，仅有 Gradle 模块 `:app`。

| 位置 | 用途及阅读重点 |
| --- | --- |
| [app/src/main/java/com/fnphoto/tv](../../app/src/main/java/com/fnphoto/tv) | Activity、Leanback Presenter、媒体模型和可独立测试的状态类 |
| [api/](../../app/src/main/java/com/fnphoto/tv/api) | NAS/FN Connect、WebSocket 登录、签名、刷新、响应/重定向限制 |
| [paging/](../../app/src/main/java/com/fnphoto/tv/paging) | 网格窗口、目录历史、元数据预算 |
| [cache/](../../app/src/main/java/com/fnphoto/tv/cache) | Glide 加载/清理、异步缓存管理 |
| [player/](../../app/src/main/java/com/fnphoto/tv/player) | 视频认证数据源、错误分类和重试策略 |
| [res/](../../app/src/main/res) / [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) | 布局、焦点资源、API21 专用样式、横屏与 TV launcher |
| [app/src/test/java](../../app/src/test/java) | JVM JUnit；包含少量 Android 类 stub，没有设备 View 测试 |
| [test/](../../test) | Python 合同测试、合成 NAS、视频生成及手工设备 QA |
| [构建配置](../../app/build.gradle) / [CI workflow](../../.github/workflows/android.yml) | 工具链、依赖、真实检查与产物 |

## 运行链路与代码地图

1. [FnPhotoApplication](../../app/src/main/java/com/fnphoto/tv/FnPhotoApplication.java)
   初始化 MultiDex，并将 Glide 的 `GlideUrl` 网络加载接到共用 `HttpClientProvider`。
2. [LoginActivity](../../app/src/main/java/com/fnphoto/tv/LoginActivity.java) 是 launcher；
   输入 NAS 地址或 FN ID，解析目标并通过 `FnWebSocketClient` 登录，保存会话后进入主界面。
   `RememberedNasTarget` 区分原始 FN ID 与当前解析出的地址，不能用地址覆盖原始身份。
3. [MainActivity](../../app/src/main/java/com/fnphoto/tv/MainActivity.java) 管理导航抽屉、
   浏览类型、启动恢复和返回行为；[MainFragment](../../app/src/main/java/com/fnphoto/tv/MainFragment.java)
   管理时间线、相册、共享、人物、地点等 Leanback 行和网络请求。
   版本/统计请求是辅助请求，不能阻塞或覆盖用户选择。
4. [FolderBrowseActivity](../../app/src/main/java/com/fnphoto/tv/FolderBrowseActivity.java)
   与 [SearchActivity](../../app/src/main/java/com/fnphoto/tv/SearchActivity.java)
   使用独立网格；前者在同一 Activity 内下钻目录，后者按当前查询取消过时结果。
5. 列表用 [MediaListHolder](../../app/src/main/java/com/fnphoto/tv/MediaListHolder.java)
   做进程内交接，向 [MediaDetailActivity](../../app/src/main/java/com/fnphoto/tv/MediaDetailActivity.java)
   提供当前窗口与 `PageSource`。`MediaPageController` 管理详情分页交付，列表仍拥有网络分页。
   `DetailSessionStore` 负责进程死亡后的有限快照恢复。
   [PhotoDetailActivity](../../app/src/main/java/com/fnphoto/tv/PhotoDetailActivity.java)
   是仍注册的简单单图查看器，不具备完整媒体详情功能。
6. [SettingsActivity](../../app/src/main/java/com/fnphoto/tv/SettingsActivity.java)
   提供设置和图片缓存管理；不要将图片清缓存与登录/浏览偏好重置混为一谈。

| 修改主题 | 优先阅读的实现/现有测试 |
| --- | --- |
| 焦点、卡片复用 | `FocusImageCardView`、`CardPresenter`、`MainFragment.focusBrowseContent`、`item_photo_grid.xml`；另做设备 QA |
| 空/加载/错误状态 | `TvStateView`、`BrowseStatus`、`GridScreenState` 及同名状态测试；设备 `EmptyStateDeviceTest`，见 [TV 列表状态](../empty-states.md) |
| 浏览类型恢复 | `BrowseMode`、`BrowseSelection`、`BrowseSelectionTest` |
| 主列表分页/取消 | `MainPagingState`、`MainMetadataWindow`、`MainRequestScope`、`SparseScanBudget`、`PhotoPageDemand` 及同名 Test |
| 文件夹/搜索 | `paging/GridPageState`、`paging/FolderNavigationState` 及同名 Test |
| 详情/播放 | `MediaPageController`、`DetailLoadState`、`DetailPreloadScope`、`DetailPlaybackState`、`DetailSessionStore`；`VideoRemoteInput`、`VideoSeekState`、`VideoControls`；`MediaDetailLifecycleTest`、`DetailPreloadScopeTest`、`player/*Test`，设备测试 `VideoRemoteInstrumentationTest` |
| 图片生命周期 | `cache/CachedImageLoader`、`ImageRequestScope`、`ImageCacheManager`；`ImagePipelineTest` |
| 日期预览调度 | `PreviewLoadThrottle`、`MainFragment.schedulePreviewLoad`、`CardPresenter.loadPreviewImages`；`PreviewLoadThrottleTest`，设备场景见 QA 清单 A11–A14 |
| 共享 | `SharedAlbumSupport`、`player/SharedVideoStream`；`SharedAlbumSupportTest`、`test_shared_fixtures.py` |
| 网络/鉴权 | `api/FnHttpApi`、`ApiInterceptor`、`FnWebSocketClient`、`RequestAuthentication`、`TokenRefreshGate`；`api/*Test` |
| 地点/响应上限 | `GeoPhotoResponseReader`、`MetadataResponseInterceptor` 及同名 Test；`test_large_library_contract.py` |

Java 文件位于上述生产或测试包；Python 文件位于 `test/`。不要仅凭测试类名推断覆盖层级，
例如 `MediaDetailLifecycleTest` 验证状态辅助类，不启动真实 Activity。

日期预览持续滚动时按最新焦点节流派发，拼图逐张显示；详情的缩略图可见状态与主图完成
分开，预取成为当前图时保留到主图请求结束。实现边界见
[图片、缓存与视频](constraints.md#图片缓存与视频)，行为说明见 [加载说明](../performance.md)。
分页照片墙在靠近页尾或实际聚焦底部状态卡时请求下一页，完成后重新检查需求；
底部交接到新增照片前重新核对实际焦点和页面代数。一次自动需求最多请求五个原始页，
失败、稀疏暂停和窗口边界仍需明确操作，设备场景见 QA 清单 A15–A18。

## 已有文档的用途与冲突处理

保留已有专题作为详细资料，不在这里复制历史报告。遇到冲突先读当前实现与测试，
把“代码行为”“客户端参考合同”“真实 NAS 联调证据”分开记录。

| 文档 | 当前用途与注意事项 |
| --- | --- |
| [.github/CI.md](../../.github/CI.md) | 构建/检查说明；实际任务以 workflow 为准 |
| [test/README.md](../../test/README.md)、[QA_CHECKLIST.md](../../test/QA_CHECKLIST.md) | 合成数据命令与设备判定标准；清单不是已经执行的结果 |
| [large-library-boundaries.md](../large-library-boundaries.md) | 当前有限窗口、扫描、目录历史和恢复边界 |
| [large-library-api-contract.md](../large-library-api-contract.md) | 地点/日期合同的证据范围及边界 fixture |
| [video-remote-controls.md](../video-remote-controls.md) | 视频方向键定位、显式切媒体、触屏、媒体键与设备验证方法 |
| [shared-albums.md](../shared-albums.md) | 已实现的接收方共享浏览；来源是客户端交接/参考实现，真实 NAS 仍需联调 |
| [remember-browse-type.md](../remember-browse-type.md) | 按账号记住成功浏览类型；共享已接入 |
| [performance.md](../performance.md)、[functional-completion.md](../functional-completion.md) | 历史改动/验证报告，不能当当前功能全集或当前测试结果 |
| [API.md](../../API.md) | 旧协议说明；路径/参数必须与当前源码交叉核对 |
| [中文 README](../../README.md)、[英文 README](../../README_EN.md)、[中文兼容入口](../../README_CN.md) | 项目介绍、截图与安装；截图不是当前 APK 的验证证据 |

已确认的差异：

- `functional-completion.md` 的旧段落称共享未实现；当前共享已支持只读接收方浏览。
  标签与智能分类仍只有未接入提示，不能把三者合并判断。
- `performance.md` 的早期父列表恢复描述不适用于当前父目录：目录返回会重新加载，
  不保留已丢弃窗口/父目录滚动位置；当前规则见大图库边界文档。
- `API.md` 的简化 WebSocket 登录示例不等于实际协议；当前路径是
  `/websocket?type=main`，包含 `util.crypto.getRSAPub` 和加密的 `user.login`。

## 如何维护这份上下文

改动某项行为时，修改对应专题与这里的导航/边界，引用具体文件或符号；避免只写
“已支持”“已通过”。未确认的服务端语义放进 [待确认列表](constraints.md#未实现与待确认)，
补充脱敏的版本、请求/响应合同和对应测试后再升级为事实。
测试结果放本次交付报告，必要的设备记录按 QA 流程存本地；不要把新会话日志、
本机绝对路径、账号或未经复核的性能数字积累进本目录。
