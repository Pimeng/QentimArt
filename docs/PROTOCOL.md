# QentimArt HTTP 协议 (v1)

所有动态接口都在 `/api/v1/projects/{project}` 下，`project` 需匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`。

鉴权使用请求头 `Authorization: Bearer <token>`。角色分三级：`read` < `publish` < `admin`，高权限包含低权限；每个 token 绑定允许访问的项目列表（`"*"` 表示全部项目）。

错误统一返回 `{"error": "..."}`，常见状态码为 400、401、404、409、500、503。

## 版本接口

| 方法 | 路径 | 所需角色 | 说明 |
|---|---|---|---|
| GET | `/versions` | read | 按 SemVer 降序返回版本列表，以及 `latest` / `latestPrerelease` |
| GET | `/versions/{v}` | read | 版本元数据；`v` 可填 `latest`（带 `?prerelease=1` 时包含预发布版） |
| GET | `/versions/{v}/manifest` | read | gzip 压缩清单；响应头 `X-QentimArt-Version` 给出解析后的具体版本号 |
| GET | `/versions/{v}/diff?from={u}` | read | 两个版本之间的文件增删改列表与需新下载字节数 |
| PUT | `/versions/{v}` | publish | 提交新版本，请求体为 gzip 清单；不可覆盖，重复提交返回 409 |
| DELETE | `/versions/{v}` | admin | 删除版本元数据和清单（释放块空间需再调 GC） |
| POST | `/gc?dryRun=1` | admin | 清理无引用的孤立块，受宽限期保护 |

`latest` 解析规则：优先取最高的正式版；没有任何正式版时回退到最高的预发布版；带上 `prerelease=1` 时直接在全部版本里取最高。

版本元数据（VersionInfo）示例：

```json
{
  "version": "1.1.0", "createdAt": "2026-11-16T10:00:00Z", "prerelease": false,
  "fileCount": 1493, "totalSize": 315621376, "chunkCount": 1493, "manifestSize": 98765,
  "baseVersion": "1.0.0", "deltaBytes": 7444480
}
```

`deltaBytes` 是相比 `baseVersion`（发布时已有的最接近低版本）新增数据块的未压缩字节总和。

## 数据块接口

| 方法 | 路径 | 所需角色 | 请求体 | 响应 |
|---|---|---|---|---|
| POST | `/chunks/fetch` | read | N × 32 字节原始哈希（N ≤ 4096） | 二进制帧流，顺序与请求一致 |
| GET | `/chunks/{hex}` | read | 无 | 解压后的原始数据块（可挂 CDN 缓存） |
| POST | `/chunks/missing` | publish | N × 32 字节原始哈希（N ≤ 65536） | 服务端不存在的哈希列表（32 字节拼接） |
| POST | `/chunks` | publish | 二进制帧流 | `{"stored":n,"skipped":n,"bytes":n}` |

### 二进制帧格式

批量上传（`POST /chunks`）和批量拉取（`POST /chunks/fetch`）用同一套流式帧编码：

```
hash      32 字节   原始数据块的 SHA-256
enc       u8        0 = 未压缩原文, 1 = raw DEFLATE (RFC 1951), 0xFF = 服务端不存在该块
rawLen    u32 BE    解压后的原始字节长度（上限 16 MiB）
storedLen u32 BE    紧随其后的 payload 字节长度
payload   storedLen 字节
```

客户端每收完一帧并解压后，必须重算 SHA-256 与 `hash` 比对。服务端读底层存储出错时会直接断开连接，客户端重试只需补请求还没收齐的哈希。

## 版本清单 (manifest)

gzip 压缩的 UTF-8 JSON，`files` 按 `path` 字典序升序排列：

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

- `hash`：完整文件的 SHA-256 十六进制字符串。
- `chunks`：按顺序排列的 `[块哈希, 块原始长度]` 数组，各块写入偏移由前序块长度累加得到。空文件、或者整个文件只切出一块（此时块哈希等于文件哈希）时省略该字段。
- `path`：`/` 分隔的相对路径，最长 1024 字节；禁止空段、`.`、`..`、前导 `/`、反斜杠、控制字符、Windows 保留字符（`<>:"|?*`）和设备名、段尾空格或句点、保留顶层目录 `.qentimart`，且同一份清单里不能有仅大小写不同的两个路径。

## 切块参数

发布端用 FastCDC（固定 gear 表，normalized chunking level 2），默认最小 256 KiB、平均 1 MiB、最大 4 MiB，协议允许的单块硬上限为 16 MiB。切块只在发布时跑，下游 SDK 直接按清单里的块列表校验拼装，不用实现 FastCDC。

## 下游本地状态目录

```
<asset dir>/.qentimart/state.json        {"formatVersion":1,"project","version","syncedAt","files":{path: mtimeMs}}
<asset dir>/.qentimart/manifest.json.gz  当前已同步版本的原始压缩清单，供本地块复用与离线 status 读取
<asset dir>/.qentimart/tmp/              同步期间存放拼装中的临时文件，同步结束后清空
<asset dir>/.qentimart/lock              并发同步互斥锁文件
```

`mtimeMs` 存文件修改时间的整数毫秒值（向下取整）。Node.js、Python、Java 三个 SDK 读写的状态结构完全一致，可以在同一个目录上混用。

### 锁与中断恢复

- `.qentimart/lock` 记录持锁进程的 `{pid, at}`。跨进程时如果锁文件里的 PID 还在跑就报 `ELOCKED` 退出；进程已经不在了则当作上次崩溃留下的陈旧锁直接接管。各 SDK 在进程内还会维护一份活动目录集合，避免同进程多线程因 PID 相同误判抢锁。
- 待更新文件全部先写到 `.qentimart/tmp/`，校验通过再 `rename` 覆盖目标路径，中途崩溃不会写坏原文件；`state.json` 在所有替换和清理完成后最后落盘。下次启动同步会先清掉残留的 `tmp/`，再结合已有状态和磁盘的 `size + mtime` 继续完成。（`rename` 只保证进程层面的原子替换，不做抗断电的 `fsync`。）

### 下游路径校验

下游 SDK 把远端清单当成不可信输入，解析时执行和服务端一致的路径合法性检查。创建父目录时逐级检查且不跟随符号链接，一旦某级父目录是指向工作目录外的符号链接，报 `unsafe path ... is a symlink` 拒绝写入。

## 静态分发目录结构

静态模式把单个项目导出成纯静态目录，可以直接挂到 Cloudflare Pages、S3 静态站点、Nginx 或任意 CDN，客户端全程走 HTTP `GET`：

```
<root>/
  _headers                        Cloudflare Pages 缓存规则（index.json 短缓存，其余 immutable）
  index.json                      版本索引文件（唯一会随新版本更新的文件）
  versions/<v>/manifest.json.gz   版本清单，格式与动态接口一致，写入后不可变
  versions/<v>/meta.json          单版本元数据（VersionInfo），写入后不可变
  chunks/<哈希前两位>/<哈希>        数据块对象，写入后不可变
```

### index.json

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

- `latest` 和 `latestPrerelease` 由发布工具生成索引时算好；客户端同步 `latest` 默认读 `latest`，开 `prerelease` 时读 `latestPrerelease`。
- `versions` 按 SemVer 降序排列，每个元素内容与对应版本的 `meta.json` 相同。

### 静态块文件格式

`chunks/<前两位>/<哈希>` 就是服务端底层存储的单块 blob 文件（不含批量流的哈希头和 `storedLen`）：

```
enc     uint8      0 = 未压缩原文, 1 = raw DEFLATE
rawLen  uint32 BE  解压后的原始字节长度（上限 16 MiB）
payload            原始字节或 DEFLATE 压缩流
```

客户端 `GET` 下来后读前 5 字节头，按需解压并校验长度和 SHA-256。

### 静态客户端同步步骤

1. `GET {base}/index.json`，确定目标版本号
2. `GET {base}/versions/<v>/manifest.json.gz`，解压并校验清单
3. 对比本地状态、复用旧文件里已有的块，剩下的缺失块并发 `GET {base}/chunks/<前两位>/<哈希>`

挂 CDN 时只给 `index.json` 设短过期时间，其余 `versions/*` 和 `chunks/*` 都可以永久缓存（`immutable`）。
