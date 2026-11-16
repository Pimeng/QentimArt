# 快速开始

本文从零搭建一套 AssetSync：启动服务端 → 在 CI 中发布素材版本 → 在下游插件或工具中同步。

- [环境要求](#环境要求)
- [1. 编译并启动服务端](#1-编译并启动服务端)
- [2. 发布版本](#2-发布版本)
- [3. 下游接入](#3-下游接入)
- [4. 运维命令](#4-运维命令)
- [运行测试](#运行测试)

## 环境要求

| 组件 | 要求 |
|---|---|
| 服务端 `assetsync-server` / 客户端 `assetsync` | Go ≥ 1.23（编译用；运行时均为无依赖的单二进制） |
| Node.js SDK | Node.js ≥ 18，无第三方依赖 |
| Python SDK | Python ≥ 3.8，仅使用标准库 |
| 存储 | 本地磁盘，或任意 S3 兼容存储（AWS S3 / MinIO / 阿里云 OSS / 腾讯云 COS / Cloudflare R2） |

## 1. 编译并启动服务端

编译服务端 `assetsync-server` 和客户端 `assetsync`（会一起输出到 `bin/`）：

```bash
go build -o bin/ ./cmd/assetsync-server ./cmd/assetsync
```

为每类使用方生成一个 token（发布用、插件只读用、运维用）：

```bash
bin/assetsync-server gen-token
```

复制示例配置，把生成的 token 填进去：

```bash
cp examples/server.json server.local.json
```

启动服务端：

```bash
bin/assetsync-server serve -config server.local.json
```

### 配置项

```json
{
  "listen": ":8080",
  "storage": { "type": "local", "path": "./data" },
  "tokens": [
    { "name": "ci-publisher", "token": "<token>", "role": "publish", "projects": ["*"] },
    { "name": "plugins",      "token": "<token>", "role": "read",    "projects": ["mygame"] },
    { "name": "ops",          "token": "<token>", "role": "admin",   "projects": ["*"] }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `listen` | 监听地址，默认 `:8080` |
| `storage.type` | `local`（使用 `path`）或 `s3`（使用 `s3.endpoint/region/bucket/accessKey/secretKey/pathStyle/prefix/parallelism`） |
| `tokens[]` | `role` 为 `read` / `publish` / `admin`，高角色包含低角色的权限；`projects` 为 `["*"]` 或具体项目名 |
| `allowAnonymousRead` | 允许不带 token 的只读访问，默认 `false` |
| `maxFetchChunks` | 单次批量拉取最多的块数，默认 `4096` |
| `gcGrace` | GC 宽限期，默认 `1h`，保护正在发布、尚未提交的块 |
| `versionCacheTTL` | 版本列表缓存时间，默认 `15s`（多实例共享同一个 bucket 时生效） |

不配置任何 token 时，所有请求都拥有 admin 权限，只适合本地试用。

### 使用 S3 / MinIO

参考 [examples/server.s3.json](../examples/server.s3.json)。MinIO 需要 `"pathStyle": true`；AWS S3、OSS、COS 通常使用 virtual-hosted 风格（`false`）。

### 使用 Docker

```bash
docker build -t assetsync .
```

```bash
docker run -d -p 8080:8080 -v /srv/assetsync/data:/data -v /srv/assetsync/server.json:/etc/assetsync/server.json:ro assetsync
```

使用本地存储时，把配置里的 `storage.path` 设为 `/data`。

## 2. 发布版本

通常在 CI 中执行。连接参数可以通过环境变量传入：

```bash
export ASSETSYNC_SERVER=http://127.0.0.1:8080 ASSETSYNC_PROJECT=mygame ASSETSYNC_TOKEN=<publish token>
```

发布 `./Assets` 目录为 1.2.0 版本：

```bash
bin/assetsync publish -version 1.2.0 -exclude "*.tmp,Library/" ./Assets
```

发布端会并行分块并计算哈希，只上传服务器还没有的块，最后提交清单。

- 版本号必须是 SemVer（如 `1.2.0`、`2.0.0-beta.1`），且**不可变**：同一个版本号不能重复发布。
- 只想看看会上传多少数据、不真正提交时，可以加 `-dry-run`。
- `-concurrency` 控制并行上传连接数（默认 8），`-compress none` 可以关闭压缩。
- 默认不会扫描 `.git` 和 `.assetsync` 目录。

## 3. 下游接入

下游只需要一个 `read` 角色的 token。

### Node.js

```bash
npm install ./sdk/nodejs
```

```js
import { AssetSyncClient } from '@assetsync/sdk';

const client = new AssetSyncClient({ server: 'http://127.0.0.1:8080', project: 'mygame', token });
const result = await client.sync('./assets');               // 默认同步到最新正式版
await client.sync('./assets', { version: '1.1.0' });        // 切换（升级或降级）到指定版本
client.close();
```

完整 API 与插件接入示例见 [sdk/nodejs/README.md](../sdk/nodejs/README.md)。

### Python

```bash
pip install ./sdk/python
```

```python
from assetsync import AssetSyncClient

with AssetSyncClient("http://127.0.0.1:8080", "mygame", token=token) as client:
    result = client.sync("./assets")
    client.sync("./assets", version="1.1.0")
```

完整 API 见 [sdk/python/README.md](../sdk/python/README.md)。

### 命令行

两个 SDK 都自带命令行工具，适合脚本或手动操作：

```bash
npx assetsync-node sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

```bash
assetsync-py sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

### 本地改动的处理

同步时如果发现本地文件被手动改动、或者多出了不属于该版本的文件，处理方式可以配置：

| 策略 | 行为 |
|---|---|
| `overwrite`（默认） | 还原为服务器版本，删除多余文件 |
| `keep` | 保留本地改动和多余文件，其余照常更新 |
| `fail` | 报告冲突并中止，不修改任何文件 |

安全保护：一个目录**第一次**同步时（还没有 `.assetsync` 状态），即使是 `overwrite` 也不会删除不属于目标版本的文件，避免把目录配错时误删数据。确实需要清理时，显式打开 `deleteUntrackedOnFirstSync`（Python 为 `delete_untracked_on_first_sync`）。

## 4. 运维命令

```bash
bin/assetsync versions                            # 版本列表
```

```bash
bin/assetsync diff -from 1.1.0 -to 1.2.0          # 两个版本的差异
```

```bash
bin/assetsync delete-version -version 1.0.0       # 删除版本（需要 admin）
```

```bash
bin/assetsync gc                                  # 回收不再被任何版本引用的块（需要 admin，可加 -dry-run）
```

## 运行测试

单元测试：

```bash
go test ./...
```

端到端测试（自动编译服务端、生成素材、发布两个版本，再用两个 SDK 做全部检查）：

```bash
node tests/e2e.mjs
```

- 默认生成约 400 MiB 素材，`E2E_SIZE_MB=4000` 可以做更大规模的压测。
- `E2E_SKIP_PYTHON=1` 跳过 Python SDK 的检查。
- 测试数据写在 `tests/.work/`（已在 `.gitignore` 中忽略），跑完可以删除。

只测网络 + 解码 + 校验、不写磁盘的吞吐（用来区分网络瓶颈和磁盘瓶颈）：

```bash
node tests/bench-fetch.mjs http://127.0.0.1:8080 mygame <read token>
```
