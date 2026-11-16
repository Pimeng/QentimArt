# AssetSync HTTP 协议 (v1)

所有接口前缀：`/api/v1/projects/{project}`，`project` 需匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`。

鉴权：`Authorization: Bearer <token>`。角色 `read` < `publish` < `admin`，高角色包含低角色权限；token 可限定项目（`"*"` 表示全部）。

错误响应统一为 `{"error": "..."}`，状态码 400 / 401 / 404 / 409 / 500。

## 版本

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/versions` | read | 版本列表（按 SemVer 降序）+ `latest` / `latestPrerelease` |
| GET | `/versions/{v}` | read | 版本元数据；`v` 可为 `latest`（`?prerelease=1` 允许预发布版） |
| GET | `/versions/{v}/manifest` | read | gzip 清单；响应头 `X-AssetSync-Version` 为实际版本号 |
| GET | `/versions/{v}/diff?from={u}` | read | 两个版本的文件差异与需新下载的字节数 |
| PUT | `/versions/{v}` | publish | 提交新版本，body 为 gzip 清单。版本不可变，重复提交返回 409 |
| DELETE | `/versions/{v}` | admin | 删除版本（之后执行 gc 回收空间） |
| POST | `/gc?dryRun=1` | admin | 删除未被任何版本引用的块（有宽限期，保护上传中未提交的块） |

`latest` 的规则：取最高的正式版；没有正式版时取最高的预发布版；`prerelease=1` 时直接取最高版本。

VersionInfo：

```json
{
  "version": "1.1.0", "createdAt": "2026-11-16T10:00:00Z", "prerelease": false,
  "fileCount": 1493, "totalSize": 315621376, "chunkCount": 1493, "manifestSize": 98765,
  "baseVersion": "1.0.0", "deltaBytes": 7444480
}
```

`deltaBytes`：本版本相对 `baseVersion`（发布时最接近的更低版本）新增的块的原始字节数，也就是服务器为这个版本额外存储的数据量（压缩前）。

## 块

| 方法 | 路径 | 角色 | Body | 响应 |
|---|---|---|---|---|
| POST | `/chunks/fetch` | read | N × 32 字节二进制哈希（N ≤ 4096） | 帧流，按请求顺序 |
| GET | `/chunks/{hex}` | read | – | 解压后的原始块（可走 CDN 缓存） |
| POST | `/chunks/missing` | publish | N × 32 字节哈希（N ≤ 65536） | 服务器缺少的哈希（同格式） |
| POST | `/chunks` | publish | 帧流 | `{"stored":n,"skipped":n,"bytes":n}` |

### 帧格式

```
hash      32 字节   原始块的 SHA-256
enc       u8        0 = 原始, 1 = raw DEFLATE (RFC 1951), 0xFF = 服务器不存在该块
rawLen    u32 BE    原始长度
storedLen u32 BE    后续 payload 长度
payload   storedLen 字节
```

客户端必须对每个块解码后校验 SHA-256。服务器在存储故障时会直接中断连接，客户端应只重试尚未收到的哈希。

## 清单 (manifest)

gzip 压缩的 JSON，文件按路径排序：

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

- `hash`：整个文件的 SHA-256。
- `chunks`：`[块哈希, 块长度]` 列表，块在文件中的偏移量按顺序累加得到。只有一个块（此时块哈希等于文件哈希）或文件为空时省略。
- 路径使用 `/` 分隔，禁止 `..`、绝对路径、Windows 非法字符、仅大小写不同的重复路径，以及保留目录 `.assetsync`。

## 分块

发布端使用 FastCDC（gear 表固定，normalized chunking level 2），默认 min 256 KiB / avg 1 MiB / max 4 MiB，单块上限 16 MiB。只有发布端需要分块，下游 SDK 只需要做 SHA-256 校验。

## 下游本地状态

```
<asset dir>/.assetsync/state.json        {"formatVersion":1,"project","version","syncedAt","files":{path: mtimeMs}}
<asset dir>/.assetsync/manifest.json.gz  当前版本清单原文（用于本地块复用与离线 status）
<asset dir>/.assetsync/tmp/              同步过程中的临时文件，完成后删除
<asset dir>/.assetsync/lock              防止并发同步
```

`mtimeMs` 为整数毫秒（`floor(mtime)`）。Node 和 Python SDK 的状态格式相同，可以互相接管同一个目录。
