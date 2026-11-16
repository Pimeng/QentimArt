# 快速开始

本文从零搭建一套 QentimArt：启动服务端 → 在 CI 中发布素材版本 → 在下游插件或工具中同步。

- [环境要求](#环境要求)
- [1. 编译并启动服务端](#1-编译并启动服务端)
- [2. 发布版本](#2-发布版本)
- [3. 下游接入](#3-下游接入)
- [4. 运维命令](#4-运维命令)
- [5. 静态模式 / Cloudflare Pages](#5-静态模式--cloudflare-pages)
- [运行测试](#运行测试)

## 环境要求

| 组件 | 要求 |
|---|---|
| 服务端 `qentimart-server` / 客户端 `qentimart` | Go ≥ 1.23（编译用；运行时均为无依赖的单二进制） |
| Node.js SDK | Node.js ≥ 18，无第三方依赖 |
| Python SDK | Python ≥ 3.8，仅使用标准库 |
| 存储 | 本地磁盘，或任意 S3 兼容存储（AWS S3 / MinIO / 阿里云 OSS / 腾讯云 COS / Cloudflare R2） |

## 1. 编译并启动服务端

编译服务端 `qentimart-server` 和客户端 `qentimart`（会一起输出到 `bin/`）：

```bash
go build -o bin/ ./cmd/qentimart-server ./cmd/qentimart
```

为每类使用方生成一个 token（发布用、插件只读用、运维用）：

```bash
bin/qentimart-server gen-token
```

复制示例配置，把生成的 token 填进去：

```bash
cp examples/server.json server.local.json
```

启动服务端：

```bash
bin/qentimart-server serve -config server.local.json
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
docker build -t qentimart .
```

```bash
docker run -d -p 8080:8080 -v /srv/qentimart/data:/data -v /srv/qentimart/server.json:/etc/qentimart/server.json:ro qentimart
```

使用本地存储时，把配置里的 `storage.path` 设为 `/data`。

## 2. 发布版本

通常在 CI 中执行。连接参数可以通过环境变量传入：

```bash
export QENTIMART_SERVER=http://127.0.0.1:8080 QENTIMART_PROJECT=mygame QENTIMART_TOKEN=<publish token>
```

发布 `./Assets` 目录为 1.2.0 版本：

```bash
bin/qentimart publish -version 1.2.0 -exclude "*.tmp,Library/" ./Assets
```

发布端会并行分块并计算哈希，只上传服务器还没有的块，最后提交清单。

- 版本号必须是 SemVer（如 `1.2.0`、`2.0.0-beta.1`），且**不可变**：同一个版本号不能重复发布。
- 只想看看会上传多少数据、不真正提交时，可以加 `-dry-run`。
- `-concurrency` 控制并行上传连接数（默认 8），`-compress none` 可以关闭压缩。
- 默认不会扫描 `.git` 和 `.qentimart` 目录。

## 3. 下游接入

下游只需要一个 `read` 角色的 token。

### Node.js

```bash
npm install ./sdk/nodejs
```

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({ server: 'http://127.0.0.1:8080', project: 'mygame', token });
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
from qentimart import QentimArtClient

with QentimArtClient("http://127.0.0.1:8080", "mygame", token=token) as client:
    result = client.sync("./assets")
    client.sync("./assets", version="1.1.0")
```

完整 API 见 [sdk/python/README.md](../sdk/python/README.md)。

### 命令行

两个 SDK 都自带命令行工具，适合脚本或手动操作：

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

```bash
qentimart-py sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

### 本地改动的处理

同步时如果发现本地文件被手动改动、或者多出了不属于该版本的文件，处理方式可以配置：

| 策略 | 行为 |
|---|---|
| `overwrite`（默认） | 还原为服务器版本，删除多余文件 |
| `keep` | 保留本地改动和多余文件，其余照常更新 |
| `fail` | 报告冲突并中止，不修改任何文件 |

安全保护：一个目录**第一次**同步时（还没有 `.qentimart` 状态），即使是 `overwrite` 也不会删除不属于目标版本的文件，避免把目录配错时误删数据。确实需要清理时，显式打开 `deleteUntrackedOnFirstSync`（Python 为 `delete_untracked_on_first_sync`）。

## 4. 运维命令

```bash
bin/qentimart versions                            # 版本列表
```

```bash
bin/qentimart diff -from 1.1.0 -to 1.2.0          # 两个版本的差异
```

```bash
bin/qentimart delete-version -version 1.0.0       # 删除版本（需要 admin）
```

```bash
bin/qentimart gc                                  # 回收不再被任何版本引用的块（需要 admin，可加 -dry-run）
```

## 5. 静态模式 / Cloudflare Pages

静态模式**不需要运行任何服务端**：发布端把素材构建成一棵自包含的静态树（本地目录），用 `wrangler` / `rsync` / S3 sync 部署到任意静态托管；下游客户端只用一个公开的 `index.json` 地址即可差异化更新，全程只有 `GET`、默认不需要鉴权。

```
qentimart publish --static  ──▶  静态树目录  ──wrangler/rsync──▶  Cloudflare Pages / CDN  ──GET──▶  SDK (baseUrl)
```

### 构建静态树

输出目录是**持久镜像**：每次发布读取其中已有的块、只写新块、追加版本并重建 `index.json`。请在多次发布之间保留这个目录。

```bash
qentimart publish --static -o ./site -project mygame -version 1.0.0 ./Assets
```

```bash
qentimart publish --static -o ./site -project mygame -version 1.1.0 ./Assets
```

生成的目录结构：

```
site/
  _headers                        缓存策略（index.json 短 TTL，其余 immutable）
  index.json                      版本索引 + latest / latestPrerelease
  versions/<v>/manifest.json.gz
  versions/<v>/meta.json
  chunks/<哈希前两位>/<哈希>
```

### 部署到 Cloudflare Pages

```bash
npx wrangler pages deploy ./site
```

Cloudflare Pages 每次部署是整站快照，但 `wrangler` 按文件哈希去重上传，所以未变化的块不会重传。`_headers` 会被 Pages 读取，自动对不可变对象永久缓存、对 `index.json` 用短 TTL。

> **文件数上限**：Cloudflare Pages 免费版单站点最多 20,000 个文件（付费版 100,000）。每个块是一个文件，默认平均块 1 MiB；大项目叠加历史版本可能逼近上限。可用更大的平均块缓解（文件数约降 4 倍，仍远低于 25 MiB 单文件上限）：
>
> ```bash
> qentimart publish --static -o ./site -chunk-avg 4194304 -chunk-max 16777216 -version 1.0.0 ./Assets
> ```

### 下游拉取

Node.js：

```js
import { QentimArtClient } from '@qentimart/sdk';
const client = new QentimArtClient({ baseUrl: 'https://mygame.pages.dev' });
await client.sync('./assets');
```

Python：

```python
from qentimart import QentimArtClient
with QentimArtClient(base_url="https://mygame.pages.dev") as client:
    client.sync("./assets")
```

命令行：

```bash
npx qentimart-node sync --base-url https://mygame.pages.dev --dir ./assets
```

```bash
qentimart-py sync --base-url https://mygame.pages.dev --dir ./assets
```

### 静态树的运维

```bash
qentimart delete-version --static -o ./site -version 1.0.0   # 删除某版本（随后 gc 清孤块）
```

```bash
qentimart gc --static -o ./site                              # 回收不再被引用的块（可加 -dry-run）
```

清理后重新 `wrangler pages deploy ./site` 即可。

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
