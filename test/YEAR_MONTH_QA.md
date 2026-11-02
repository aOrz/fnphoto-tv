# 年月导航合成 QA

本服务只提供确定的合成场景，不证明真实 NAS 的日期、时区、排序或错误码语义。
原有 `test_server.py`、`qa_server.py` 默认行为未改变。仅 loopback 监听，登录 `test` / `test`。

```bash
python test/year_month_server.py control
python test/year_month_server.py --port 5668
```

专用官方 Android 模拟器访问 `http://10.0.2.2:5668`。本机合同测试访问 loopback。
所有媒体为有字样的合成图片；视频条目用于筛选，播放仍需按既有视频 QA 准备媒体。
默认状态为 `test/.generated/year-month-state/year-month.json`，请求证据为
`test/.generated/year-month-requests.jsonl`。证据只记录受控元数据接口、状态、数字 ID/offset/limit
和 NAS 格式日期边界，不记录账号、请求头、令牌、签名、媒体或 WebSocket 查询。

## 来源和场景

| 来源 | 数据与预期 |
| --- | --- |
| 普通相册 7001 | 245 原始行。首 ID 1001；前 210 行拍摄日期在 2026-10/2026-08 间交替，上传时间 `dateTime` 按原始行递减。2026-09 从 offset 210、ID 1211 开始，共 35 项。每页 35：五页后只查了 175 条，必须暂停/继续；下一次显式继续才可到目标。不能因看到 8 月就提前停止 |
| 普通相册 7002 | ID 2001 缺拍摄日期，2002 拍摄日期为非法 2026-02-30，2003 为 2026-09。前两项上传时间也为 9 月，不得用作拍摄日期回退；即使有匹配，仍应说明日期不完整 |
| 普通相册 7003 | 空相册；空页且 `hasNext=false` |
| 普通相册 7004 | 只有 ID 2001/2002 未知日期；扫描结束也不能据此断言该月没有照片 |
| 图库、照片/视频、收藏 | 图库与 7001 同一合成数据；日期请求先过滤再按请求 offset 分页。照片视频/收藏都有混合值，客户端继续本地判定，无服务端 category 参数 |
| 人物 71 | 7001 每隔三项取一项；保留 personId/album_id 与两组时间别名，参数不一致拒绝 |
| 共享 9001 | 独立 ID 11001–11085；10 月 60 项、9 月 25 项（首 ID 11061）；无 ordinary additional 缩略图，走共享 UUID 路径；保留 camelCase 参数和 2000–2099 边界 |
| 无匹配月份 | 2026-07 在上述有效日期来源均无匹配。普通 7001 仍须扫完，7004 仍为未知 |

`album/photos` 严格拒绝日期参数；图库、人物、共享真正按请求日期区间过滤，再使用各自
查询中的 offset。普通 offset 5 是 ID 1006，9 月图库 offset 5 是 ID 1216，便于检查坐标混用。
接口返回的 `count` 仅为本页项数，不能推断真实 NAS 语义。

## 失败、中断与变化

控制命令替换全部控制状态；未给出的选项重置。可组合延迟和失败。

```bash
# 请求延迟两秒，检查取消、快速重选、Back、后台后旧响应隔离。
python test/year_month_server.py control --delay-ms 2000
# 普通相册 offset 175 第一次返回503；下一次同offset恢复。
python test/year_month_server.py control --fail-once-offset 175
# 普通相册 offset 35 故意重复首个原始页，仍称有后续，检查不完整提示。
python test/year_month_server.py control --repeat-album-offset 35
# 删除首次选中的ID，下一次原坐标重新读取从1002开始；不可宣称精确恢复1001。
python test/year_month_server.py control --delete-id 1001
# 复原合成列表和所有控制。
python test/year_month_server.py control
```

删除控制作用于列表结果，保持其他合成媒体服务可用；它验证来源变化下的位置恢复，
不模拟完整 NAS 文件删除/媒体缓存一致性。

另启独立端口测试短非空页且无 `hasNext/count`，不得把每个 7 项页当结束：

```bash
python test/year_month_server.py --port 5669 --album-page-cap 7 --omit-album-paging \
  --state-dir test/.generated/year-month-short-state \
  --trace-file test/.generated/year-month-short-requests.jsonl
```

该模式以原始 7 行前进，目标首 offset 仍为 210，最终原始 offset 245 的空响应才是终止证据。
所有自动扫描仍必须遵守五页预算，不因服务端短页偷偷增加请求数。

## 合同检查与设备证据

```bash
python -m unittest discover -s test -p 'test_year_month_contract.py' -v
```

HTTP 合同用例验证数据分布、参数/来源隔离、月份先过滤、原始 offset、短页、未知日期、
空月、失败重试、延迟、重复页和删除。它们不启动 Android UI，也不验证真实 NAS。
设备截图与操作结果必须另附本次 APK SHA-256、API/镜像、场景、合成 profile、实测状态和
可访问证据链接；仅生成 fixture 不能记作设备 PASS。
