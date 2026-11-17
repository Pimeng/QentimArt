# QentimArt HTTP 协议 (v1)

所有动态接口统一挂载在 `/api/v1/projects/{project}` 下，`project` 名称需匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`

鉴权采用请求头 `Authorization: Bearer <token>`。权限等级依次为 `read` < `publish` < `admin`，高权限包含低权限操作；每个 token 可绑定允许访问的项目列表（`"*"` 代表全部项目）。

错误响应格式统一为 `{"error": "..."}`，常见状态码包括 400、401、404、409、500、503

## 版本接口

| 方法 | 路径 | 所需角色 | 说明 |
|---|---|---|---|
| GET | `/versions` | read | 返回按 SemVer 降序排列的版本列表，以及 `latest` / `latestPrerelease` |
| GET | `/versions/{v}` | read | 获取版本元数据；`v` 可填 `latest`（加 `?prerelease=1` 时包含预发布版） |
| GET | `/versions/{v}/manifest` | read | 下载 gzip 压缩清单；响应头 `X-QentimArt-Version` 返回解析后的具体版本号 |
| GET | `/versions/{v}/diff?from={u}` | read | 计算两个版本之间的文件增删改列表与需新下载的字节数 |
| PUT | `/versions/{v}` | publish | 提交新版本，请求体为 gzip 清单；版本不可覆盖，重复提交返回 409 |
| DELETE | `/versions/{v}` | admin | 删除指定版本元数据与清单（实际释放块空间需随后调 GC） |
| POST | `/gc?dryRun=1` | admin | 清理未被任何版本引用的孤立块，受宽限期保护 |

`latest` 解析规则：优先取版本号最高的正式版；若当前没有任何正式版，则退回取最高的预发布版；带上 `prerelease=1` 时直接在全部版本中取最高版本

版本元数据（VersionInfo）示例：

```json
{
  "version": "1.1.0", "createdAt": "2026-11-16T10:00:00Z", "prerelease": false,
  "fileCount": 1493, "totalSize": 315621376, "chunkCount": 1493, "manifestSize": 98765,
  "baseVersion": "1.0.0", "deltaBytes": 7444480
}
```

其中 `deltaBytes` 表示相比 `baseVersion`（发布时已有的最接近低版本）新增数据块的未压缩字节总和，反映服务端为该版本多占用的原始数据量

## 数据块接口

| 方法 | 路径 | 所需角色 | 请求体 | 响应 |
|---|---|---|---|---|
| POST | `/chunks/fetch` | read | N × 32 字节原始哈希（N ≤ 4096） | 二进制帧流，顺序与请求一致 |
| GET | `/chunks/{hex}` | read | 无 | 解压后的原始数据块（适合挂 CDN 缓存） |
| POST | `/chunks/missing` | publish | N × 32 字节原始哈希（N ≤ 65536） | 服务端尚不存在的哈希列表（同为 32 字节拼接） |
| POST | `/chunks` | publish | 二进制帧流 | `{"stored":n,"skipped":n,"bytes":n}` |

### 二进制帧格式

批量上传（`POST /chunks`）与批量拉取（`POST /chunks/fetch`）使用相同的流式帧编码：

```
hash      32 字节   原始数据块的 SHA-256
enc       u8        0 = 未压缩原文, 1 = raw DEFLATE (RFC 1951), 0xFF = 服务端不存在该块
rawLen    u32 BE    解压后的原始字节长度（上限 16 MiB）
storedLen u32 BE    紧随其后的 payload 字节长度
payload   storedLen 字节
```

客户端收到每帧并解压后，必须重新计算 SHA-256 并与 `hash` 比对。若服务端读取底层存储时出错会直接断开连接，客户端重试时只需请求尚未收齐的剩余哈希。

## 版本清单 (manifest)

清单是经 gzip 压缩的 UTF-8 JSON，其中 `files` 按 `path` 字典序升序排列：

```json
{
  "formatVersion": 1,
  "version": "1.1.0",
  "createdAt": "2026-11-16T10:00:00Z",
  "files": [
    {"path": "ui/a.png", "size": 1234, "hash": "<sha256>"},
    {"path": "bundles/pack0.pak", "size": 9000000, "hash": "<sha256>",
     "chunks": [["<sha256>", 4194304], ["<sha256>", 4805696]]}
  ]
}
```

- `hash`：完整文件的 SHA-256 十六进制字符串
- `chunks`：按顺序排列的 `[块哈希, 块原始长度]` 数组，各块在文件中的写入偏移由前序块长度累加得出。空文件，或整个文件只切出一个块（此时块哈希即文件哈希）时省略该字段。
- `path`：统一以 `/` 分隔的相对路径，长度不超过 1024 字节；禁止出现空段、`.`、`..`、前导 `/`、反斜杠、控制字符、Windows 保留字符（`<>:"|?*`）与设备名、段尾空格或句点、保留顶层目录 `.qentimart`，且同一份清单内不允许存在仅大小写不同的两个路径

## 切块参数

发布端采用 FastCDC（固定 gear 表，normalized chunking level 2），默认参数为最小 256 KiB、平均 1 MiB、最大 4 MiB，协议允许的单块硬上限为 16 MiB。切块只在发布时执行，下游 SDK 按清单记录的块列表直接校验拼装，不需要实现 FastCDC。

## 下游本地状态目录

```
<asset dir>/.qentimart/state.json        {"formatVersion":1,"project","version","syncedAt","files":{path: mtimeMs}}
<asset dir>/.qentimart/manifest.json.gz  当前已同步版本的原始压缩清单，供本地块复用与离线 status 读取
<asset dir>/.qentimart/tmp/              同步期间存放拼装中的临时文件，同步结束后清空
<asset dir>/.qentimart/lock              并发同步互斥锁文件
```

`mtimeMs` 存文件修改时间的整数毫秒值（向下取整）。Node.js、Python、Java 三个 SDK 读写完全一致的状态结构，可以在同一个资源目录上混用或交替执行。

### 锁与中断恢复

- **并发互斥**：`.qentimart/lock` 写入持锁进程的 `{pid, at}`。跨进程调用时，若发现锁文件且对应 PID 仍在运行则报 `ELOCKED` 退出；若对应进程已不存在，则判定为上次崩溃遗留的陈旧锁并直接接管。同时各 SDK 在进程内还维护了一份活动目录集合，防止同一进程内的多个线程或异步任务因 PID 相同而误判抢锁。
- **异常中断恢复**：待更新文件全部先写入 `.qentimart/tmp/`，校验无误后再 `rename` 覆盖目标路径，中途崩溃不会破坏原文件；`state.json` 在所有文件替换及清理全部完成后最后落盘。下次启动同步时会先清空残留的 `tmp/`，再结合已有状态和磁盘文件的 `size + mtime` 继续完成同步。（注：`rename` 仅保证进程层面的原子替换，不包含抗断电的 `fsync`。）

### 下游路径校验

下游 SDK 将远端清单视作不可信输入，解析时同样执行完整的路径合法性检查。落盘创建父目录时逐级检查且不跟随符号链接：如果本地某一级父目录是指向资源目录外部的符号链接，SDK 会以 `unsafe path ... is a symlink` 拒绝写入，防止 `rename` 越界写到工作目录之外。

## 静态分发目录结构

静态模式把单个项目导出为纯静态文件目录，可直接托管在 Cloudflare Pages、S3 静态站点、Nginx 或任意 CDN 后，客户端只通过 HTTP `GET` 访问：

```
<root>/
  _headers                        Cloudflare Pages 缓存规则（index.json 短缓存，其余 immutable）
  index.json                      版本索引文件（整棵目录中唯一会随新版本更新的文件）
  versions/<v>/manifest.json.gz   版本清单，格式与动态接口一致，写入后不可变
  versions/<v>/meta.json          单版本元数据（VersionInfo），写入后不可变
  chunks/<哈希前两位>/<哈希>        数据块对象，写入后不可变
```

### index.json 格式

```json
{
  "formatVersion": 1,
  "project": "mygame",
  "updatedAt": "2026-11-16T10:00:00Z",
  "latest": "1.1.0",
  "latestPrerelease": "1.2.0-beta.1",
  "versions": [ { "version": "1.1.0", "createdAt": "...", "prerelease": false, "fileCount": 1500, "totalSize": 0, "chunkCount": 1500, "manifestSize": 0, "baseVersion": "1.0.0", "deltaBytes": 0 } ]
}
```

- `latest` 与 `latestPrerelease` 由发布工具在每次生成索引时预先算好。客户端同步 `latest` 时默认读取 `latest` 字段，开启 `prerelease` 时读取 `latestPrerelease`。
- `versions` 数组按 SemVer 降序排列，每个元素与对应版本目录下的 `meta.json` 内容相同

### 静态块文件格式

静态目录下的 `chunks/<前两位>/<哈希>` 直接对应服务端底层存储的单个 blob 文件（不含批量流的哈希头与 `storedLen` 字段）：

```
enc     uint8      0 = 未压缩原文, 1 = raw DEFLATE
rawLen  uint32 BE  解压后的原始字节长度（上限 16 MiB）
payload            原始字节或 DEFLATE 压缩流
```

客户端 `GET` 下载该文件后读取前 5 字节头部，按需解压并校验结果长度与 SHA-256

### 静态客户端同步步骤

1. `GET {base}/index.json`，确定目标版本号
2. `GET {base}/versions/<v>/manifest.json.gz`，解压并校验清单
3. 比对本地状态、复用本地旧文件中的数据块，对剩余缺失块并发请求 `GET {base}/chunks/<前两位>/<哈希>`

配置 CDN 时只需给 `index.json` 设较短的缓存过期时间，其余 `versions/*` 与 `chunks/*` 均可设为永久缓存（`immutable`）
