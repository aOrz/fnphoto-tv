# 实现约束与 API 边界

以下“当前”指 [上下文基线](README.md) 的源码行为，不是服务端保证。
源码根目录为 [com/fnphoto/tv](../../app/src/main/java/com/fnphoto/tv)，测试位于
[同名 JVM 包](../../app/src/test/java/com/fnphoto/tv)。修改前先核对当前分支。

## TV 焦点与生命周期

- `FocusImageCardView.dispatchDraw` 在子视图绘制后画焦点环，按 `isFocused()` 判断；
  Leanback selection 可在抽屉打开时留在内容区，不能把 selected 当实际焦点。
  网格 `item_photo_grid.xml` 用可聚焦 FrameLayout 的 foreground 显示焦点，兼容 API 19。
- `MainActivity.canFocusBrowseContent` 和 `MainFragment.focusBrowseContent` 防止在
  抽屉打开、窗口失焦、过时请求时抢焦点。有效空列表仍应能回到“浏览”按钮。
  文件夹/搜索需等待子视图实际生成，并保证输入框、错误、重试、继续扫描、下一批可达。
- Presenter bind/unbind 要完整清理图像、文字、旧回调；仅测试占位图不足以确认
  焦点环未被不透明照片遮挡。焦点改动必须在真实图片加载后用 DPAD 检查。
- `MainRequestScope.next()` 先使 generation 失效，再 cancel；取消本身可能产生回调。
  搜索/目录也有请求代数。导航、清空查询、Back、销毁后，旧响应不得覆盖新页面或启动续页。
- `MediaDetailActivity.onStop` 取消图片和收藏请求、释放播放器/计时器，使图片/导航旧回调失效。
  切换媒体也会清图片、播放器和幻灯片等待，但当前不会取消收藏请求，收藏回调没有同样的
  媒体代数保护；修改收藏/快速切换时须单独检查这个缺口。停止的幻灯片不能由旧加载回调重启。
  API21+ 字体/样式调用需保护，API19 普通 Android 和 TV 镜像分别验证。

## 分页、窗口与恢复

完整设计见 [大图库边界](../large-library-boundaries.md)。核心预算由
[`MetadataLimits`](../../app/src/main/java/com/fnphoto/tv/paging/MetadataLimits.java) 定义。

| 路径 | 当前页大小/限制 |
| --- | --- |
| 自有相册目录 | 60 项/页，显示窗口上限 1,200 |
| 普通日期/相册/人物媒体 | 35 项/页；请求前要求整页放得下，满页窗口为 1,190 |
| 文件夹/关键词搜索 | 100 项/页，`GridPageState` 按原始行数限制每窗口 1,200 |
| 共享目录/内容 | 1,000 / 50 项/页；整页窗口检查，共享目录不能静默截掉下一页尾部 |
| 未分页的时间线/人物/地点/子目录 | 最多保留 1,200 项，超限说明不完整，不猜续页参数 |
| 本地照片/视频/收藏筛选 | 一次显式动作最多扫描五个 35 行页，暂停后必须显式继续 |
| 元数据响应 | 8 MiB 解压后字节上限，覆盖已知/未知 Content-Length；不截断媒体字节流 |

- offset 按原始响应行数增长，与过滤后显示数、去重数不同。只允许一个在途请求；
  失败重试原 offset；超大页/null 行拒绝后不能部分提交状态。
- 显式 `hasNext=false` 是结束依据；没有该字段时，短的非空页仍继续，后续空页才可能结束。
  重复页、空页却称 `hasNext=true`、整数 offset 边界都应提示协议/不完整边界。
  字段含义因接口不同，不把所有 `count` 当总量。
- `MainPagingState` 只保留最近两页原始 ID，显示窗口单独去重。不得重新引入随全图库
  无界增长的 ID 集合。下一批由用户明确触发，保留 raw offset、释放旧窗口和旧详情源。
- 稀疏扫描暂停、窗口已满、失败与真正结束是不同状态。暂停后焦点预取不能自动补充预算；
  详情释放等待并停止幻灯片，图片/预览页显式右键可继续；播放中的视频通过操作栏“下一项”继续。不要把暂停传作空的终止页。
- 照片墙的 `PhotoPageDemand` 在请求期间保留焦点需求，并在追加后重新检查。底部卡片
  使用首次进入时的数量作为锚点，不能随新页不断追尾；自动焦点交接不刷新预算。
  一次自动需求最多五个原始页，短页/稀疏匹配不能通过完成回调无限补充预算；预算耗尽
  显式继续。图片加载状态不参与元数据分页条件。异步交接仅在同页面、界面前台、
  抽屉关闭且实际焦点仍属于底部卡片时进行，失败不自动重试，窗口不自动替换。
- `FolderNavigationState` 最多 32 次下钻，路径 4,096 字符、显示名 256 字符。
  同一 Activity 返回父目录会重新加载；不能承诺父目录滚动/已丢弃窗口恢复。
- 详情最多保留 1,200 项；列表 `PageSource` 使用 `MainPagingState.DeliveryCursor` 或
  `GridPageState.Delivery` 保证已预取的末页也能交给详情，`MediaPageController` 接收并去重。
  不通过 Intent/Bundle 传完整媒体列表。
- `DetailSessionStore` 存 app 私有缓存，使用随机 session ID 与索引恢复；正常登录绑定
  `login_session`，旧会话缺少该字段时仍回退 token。上限为 1,200 项、8 MiB、24 小时、
  8 个文件。配置重建可保留控制器；
  进程死亡后只恢复已加载窗口，没有原 live `PageSource`，需返回列表继续加载。
- `BrowseSelection` 只保存当前账号最新成功的稳定类型 ID，合法空列表也算成功。
  账号来源是原始 NAS/FN ID + 用户名摘要；不以 token/password 或菜单位置作键。
  失败、取消、旧响应不能覆盖偏好；启动恢复失败只回退图库一次并保留旧选择。

这些是客户端结构上限，不代表没有 OOM、全局去重或服务端快照一致性。变化中的数据集
使用 offset 分页仍可能移动；已丢弃窗口不能无缝向前恢复。

## 图片、缓存与视频

- 复用 `cache/CachedImageLoader` 的 Glide 异步管线；不在 bind/UI 线程解码、重压 JPEG
  或扫描磁盘。ImageView 请求替换旧目标；custom target 要 clear，`onLoadCleared`
  释放引用，不能手动 recycle Glide-owned bitmap。`ImageRequestScope` 同样先失效再取消。
- full photo 和 thumbnail 在同一 Glide 请求链，不能拆成两个可互相覆盖的请求。
  未测量 custom target 使用有限尺寸；详情只低优先级预取相邻一张照片，最多 1920×1080。
  预取成为当前图时保留句柄，直到当前主图成功/失败后释放；最多持有当前交接和下一张
  两个句柄，停止/退出仍全部清理。缩略图成功只隐藏加载圈，不提前设置主图就绪或开始
  幻灯片间隔；旧回调必须通过当前媒体 generation 与目标校验。
  视频 URL 不送图片解码器；缩小解码尺寸不等于网络只传缩略图字节。
- 日期预览最多两个并行元数据请求、512 日期 URL LRU；收藏封面最多四 URL 和五页扫描预算。
  选中变化使用 300ms leading/trailing 节流，首次立即排入主线程任务，持续移动更新最新
  焦点而不推迟已有任务；页面切换重置。元数据完成可立即补空槽，仍受两个在途请求限制。
  拼图首张成功即显示，后续逐张更新；失败保留已显示内容，复用与清理仍受图片代数保护。
  `ImageCacheManager` 在后台做磁盘统计/清理，Glide 内存清理回主线程；旧 URL-only JPEG
  缓存不再作为加载来源。设置清图片缓存不应清登录/浏览偏好。
- 视频会话（含暂停、缓冲和结束）左右键只定位当前视频；上下/Menu 打开带文字的操作栏，
  “上一项/下一项”才切换媒体。操作栏方向键交给系统焦点分发，触屏后也保留焦点所有者。
  图片/预览/错误页保留左右浏览，详见 [视频遥控说明](../video-remote-controls.md)。
- 普通视频使用 ExoPlayer，符合格式/解码兼容条件时最多一次平台 MediaPlayer fallback；
  HTTP、网络、TLS、超时或未知故障不能盲目换播放器。错误为持久可重试 UI，启动/缓冲
  等待上限 15 秒，退出/切换/重试使旧回调失效。后台返回不能偷偷重试已有错误。
- 共享视频走 `SharedAlbumHttpDataSourceFactory` 的 OkHttp 流式数据源，支持 Range，
  不整文件缓存，不进入无法约束认证重定向的平台 fallback。不能把共享路径的安全属性
  外推到普通视频的 `DefaultHttpDataSource`/MediaPlayer 路径。

## 客户端 API 合同

以 [`FnHttpApi`](../../app/src/main/java/com/fnphoto/tv/api/FnHttpApi.java) 的注解、实际调用点和
测试为依据，注释/DTO 名称本身不证明功能存在。以下列出容易改错的已使用形状，
不是生产 NAS 的完整规格。

| 场景 | 当前请求与注意点 |
| --- | --- |
| 时间线/日期媒体 | GET `/p/api/v1/gallery/timeline`；收藏时间线 `is_collect=1`。GET `/p/api/v1/gallery/getList` 使用 `start_time/end_time/limit/offset/mode=index`；无已证实的请求 `category` 参数 |
| 本人相册 | GET `/p/api/v1/album/list`；内容 `/p/api/v1/album/photos` 使用 `album_id/sort_by/sort_direction/offset/limit` |
| 文件夹/关键词 | `/p/api/v1/photo/folder/list`、`/p/api/v1/folder_view/getFolderList`、`getFileList`；目录参数是 `folderPath`。关键词为 GET `/p/api/v1/photo/search?keyword=...&limit=...&offset=...` |
| 人物 | `ai-person/list`、`ai-person/photoLibrary/timeLine`、`ai-person/photoLibrary/list`；保留 Retrofit 中的 `personId`、时间参数大小写/别名，不统一重命名 |
| 收藏写入 | POST `/p/api/v1/photo/collect`，form 字段 `id`、`collect`；先读真实当前状态再写目标状态，检查 HTTP 和业务结果；共享详情禁用该写入 |
| 地点 | POST `/p/api/v1/search/results`，既有 JSON 包含 `keyword/filters/antiFilters` 与地点子过滤；用 `GeoPhotoResponseReader` 序列化/流式读取，不拼未转义字符串，不猜 offset/cursor |
| 分享给我 | GET `/p/api/v1/album_grant/list_to_me`，`offset/limit/sort_by=share_mod_time/sort_direction=desc`；内容 GET `/p/api/v1/album/normal/getList`，严格为 `albumId/startTime/endTime/offset/limit/sortDirection` |

共享固定时间范围为 `2000:01:01 00:00:00` 至 `2099:12:31 23:59:59`；保留参考合同，
时区/范围外媒体行为待确认。共享内容 `hasNext` 优先，不用未证实的 `count` 推导总量；
目录使用可选 `total`，缺失/矛盾时按需继续至空页或明确边界。
图片路径 `/p/api/v1/stream/p/t/{id}/s/{UUID}` 与 `/m/{UUID}`，视频 `/p/api/v1/stream/v/{id}`；
UUID 按一个路径段编码，拒绝 `.`/`..`，缺失时不拼 `/null`。
共享封面初始地址只接受相对或 NAS 同源 URL。详细来源/fixture 见 [共享说明](../shared-albums.md)。

`BaseResponse` 使用 nullable `code`、`errno` 缺失哨兵和 `result` 判定，防止全缺失默认成功。
该保护尚未覆盖所有 DTO，`AlbumListResponse`、`FolderListResponse` 等仍有默认 `int code`；
新增或修改响应模型时检查缺字段行为，不能宣称所有接口都已拒绝缺失业务码。
地点的 `SERVER_REPORTED_END` 仅表示该响应声称结束，不能独立证明结果完整；
`UNKNOWN/SERVER_HAS_MORE/CLIENT_LIMIT` 必须在 UI 暴露。参见 [地点合同](../large-library-api-contract.md)。

## 登录、签名与传输限制

- `FnWebSocketClient` 使用 `/websocket?type=main`、`util.crypto.getRSAPub` 和加密
  `user.login`，成功后维护会话；不要照搬 `API.md` 的简化 action/login 示例。
  `FnConnectApi` 负责 FN ID 发现；保留原始 FN ID，以便重新发现地址。
- `FnAuthUtils` 负责 HTTP `authx`，`FnProtocolUtils` 负责 WebSocket reqid/加密辅助；
  HTTP 使用 `accesstoken` 与 `authx`。
  `RequestAuthentication` 对 GET 签实际 query；重放 POST 时签实际可重放 form/JSON body，
  大小限 1 MiB，不能读取未知上传流来重签。不要在 UI 新增一套签名算法或抄出源码密钥。
- `ApiInterceptor` 仅对当前 NAS 同源、带 token 的 `/p/api/` 请求处理 HTTP 401；
  `TokenRefreshGate` 合并同会话并发刷新并保留失败退避，刷新失败时原 401 仍可读取。
  上述范围的 GET 仅在原始目标是 FN ID 时，传输故障可尝试一次重发现/登录；
  结果不确定的写请求不因传输失败重放。`NasEndpointRouter` 仅限已知 NAS `/p/api/`
  路径、同登录身份重路由。
- `CredentialRedirectInterceptor` 在每次网络跳转拒绝携带凭据的跨 scheme/host/port
  重定向；这种拒绝不是过期 token，不触发重新登录。不能为未知 CDN 转发 NAS 凭据。
- **现有限制：** `TlsUtils` 的 Conscrypt 路径允许任意 hostname，且组合 trust manager
  会吞掉系统与自带 CA 都不信任的证书错误。Manifest/网络配置还允许明文 HTTP。
  这不是严格 TLS 身份验证，不应以“内置 CA”宣称安全问题已解决。
- **现有限制：** 登录信息/记住的凭据写入普通私有 `SharedPreferences`，不是加密存储；
  Manifest 当前允许备份。签名/存储安全加固需要专门实现与回归，此文档没有修改运行行为。

## 未实现与待确认

| 边界 | 开发前需要的证据 |
| --- | --- |
| 标签、智能分类 | 对应 NAS/相册版本的列表/媒体接口、字段与权限；当前菜单只提示未接入。人物浏览不等于智能分类功能 |
| 地点后续页、服务端照片/视频筛选 | 明确分页/cursor/category 请求与响应合同；当前本地筛选或有限单响应不能冒充完整检索 |
| 创建共享、邀请、成员/权限管理、共享收藏 | 正式接口与权限语义；当前仅接收方只读，不从 `permission` 数值或 DTO 猜行为 |
| 共享真实服务端兼容 | 脱敏成功/失败样本、nullable 字段、ID 范围、分页上限、`total/count/hasNext`、撤权、媒体401及时间边界 |
| 跨域媒体/CDN | 明确允许的认证来源和跳转规则；目前同源策略不自动放宽 |
| 上传/管理 UI | `FnHttpApi` 有相关声明不代表已有用户流程；需先确认需求及实际调用方 |
| API19/真实 TV 解码与性能 | 对应 APK 的设备运行、帧推进、焦点、内存/帧率证据；编译、lint、mock不能证明这些结论 |

需要样本时只记录协议必要字段，去掉账号、token、cookie、签名、个人路径与媒体内容。
合成错误码/`count` 语义只定义 fixture 行为，不升级为真实 NAS 的已证实规则。
