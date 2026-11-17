# 快速开始

介绍如何编译启动服务端、在 CI 中发布素材版本，以及在下游工具中接入 SDK 同步文件

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
| 服务端 `qentimart-server` / 客户端 `qentimart` | Go ≥ 1.23（仅编译需要，编译产物为无依赖单二进制） |
| Node.js SDK | Node.js ≥ 18，无第三方依赖 |
| Python SDK | Python ≥ 3.8，仅用标准库 |
| Java SDK | JDK ≥ 21，仅用标准库（Gradle 构建） |
| 存储后端 | 本地磁盘，或任意 S3 兼容存储（AWS S3 / MinIO / 阿里云 OSS / 腾讯云 COS / Cloudflare R2） |

## 1. 编译并启动服务端

编译服务端与命令行客户端，产物输出到 `bin/`：

```bash
go build -o bin/ ./cmd/qentimart-server ./cmd/qentimart
```

按角色生成几个 token（分别用于 CI 发布、下游只读拉取和日常运维）：

```bash
bin/qentimart-server gen-token
```

复制示例配置文件并填入刚生成的 token：

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
| `storage.type` | `local`（配置 `path`）或 `s3`（配置 `s3.endpoint/region/bucket/accessKey/secretKey/pathStyle/prefix/parallelism`） |
| `tokens[]` | `role` 可选 `read` / `publish` / `admin`，高权限角色自动包含低权限；`projects` 填 `["*"]` 或具体项目名列表 |
| `allowAnonymousRead` | 允许不带 token 的只读请求，默认 `false` |
| `insecureNoAuth` | 允许在未配置任何 token 时启动并对所有请求开放 admin 权限，默认 `false`，仅用于本地调试 |
| `maxFetchChunks` | 单次批量拉取允许的最大块数，默认 `4096` |
| `gcGrace` | GC 宽限期，默认 `1h`，防止误删正在上传但尚未提交清单的块 |
| `versionCacheTTL` | 版本列表缓存时间，默认 `15s`，多实例共用同一个存储桶时生效 |

未配置 `tokens` 且未显式开启 `insecureNoAuth` 时，服务端会拒绝启动

### 使用 S3 / MinIO

配置写法见 [examples/server.s3.json](../examples/server.s3.json)。自建 MinIO 一般需要设 `"pathStyle": true`；AWS S3、OSS、COS 通常保持默认的 virtual-hosted 风格（`false`）。非回环地址默认要求 `https://`，内网测试如需明文 HTTP 可在 `s3` 下加 `"allowInsecureHTTP": true`。

### 使用 Docker

```bash
docker build -t qentimart .
```

```bash
docker run -d -p 8080:8080 -v /srv/qentimart/data:/data -v /srv/qentimart/server.json:/etc/qentimart/server.json:ro qentimart
```

挂载本地目录作为存储时，记得把配置文件里的 `storage.path` 改成 `/data`

## 2. 发布版本

发布通常放在 CI 里跑，连接参数支持通过环境变量传入：

```bash
export QENTIMART_SERVER=http://127.0.0.1:8080 QENTIMART_PROJECT=mygame QENTIMART_TOKEN=<publish token>
```

把 `./Assets` 目录发布为 `1.2.0` 版本：

```bash
bin/qentimart publish -version 1.2.0 -exclude "*.tmp,Library/" ./Assets
```

客户端会并发切块并计算哈希，向服务端查询缺失列表后只上传新块，最后提交版本清单

- 版本号必须符合 SemVer（如 `1.2.0`、`2.0.0-beta.1`），发布后不可修改或覆盖同一版本号
- 加上 `-dry-run` 可以只统计待上传的数据量，不实际写入服务端
- `-concurrency` 控制并发上传连接数（默认 8），`-compress none` 可关闭压缩；也可通过 `-token-file` 从文件读取 token，避免命令行参数出现在进程列表中
- `.git` 和 `.qentimart` 目录默认跳过，无需手动排除

## 3. 下游接入

下游拉取只需要 `read` 权限的 token

### Node.js

```bash
npm install ./sdk/nodejs
```

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({ server: 'http://127.0.0.1:8080', project: 'mygame', token });
const result = await client.sync('./assets');               // 默认同步最新正式版
await client.sync('./assets', { version: '1.1.0' });        // 切换到指定版本
client.close();
```

详细接口与插件示例见 [sdk/nodejs/README.md](../sdk/nodejs/README.md)

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

详细接口见 [sdk/python/README.md](../sdk/python/README.md)

### Java

```bash
cd sdk/java && ./gradlew build
```

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;

try (QentimArtClient client = new QentimArtClient("http://127.0.0.1:8080", "mygame", token)) {
    client.sync("./assets");                                   // 默认同步最新正式版
    client.sync("./assets", new SyncOptions().version("1.1.0")); // 切换到指定版本
}
```

详细接口与构建说明见 [sdk/java/README.md](../sdk/java/README.md)

### 命令行

三个 SDK 都附带了命令行入口，方便直接在脚本里调用：

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

```bash
qentimart-py sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

```bash
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

### 本地改动的处理

同步时如果本地文件被改过，或者目录里多出了清单之外的文件，可以通过策略参数控制处理方式：

| 策略 | 行为 |
|---|---|
| `overwrite`（默认） | 恢复成目标版本内容，并删除多余文件 |
| `keep` | 保留本地修改过或新增的文件，其余文件正常更新 |
| `fail` | 检测到冲突直接报错退出，不改动任何文件 |

为了防止填错目录导致误删，从未同步过的目录（还没有 `.qentimart` 状态目录）在首次同步时，即使使用 `overwrite` 也不会删除目录里原有的其他文件。如果确实要在首次同步时清理干净，可显式开启 `deleteUntrackedOnFirstSync`（Python 为 `delete_untracked_on_first_sync`）。

### 文件校验方式

为了减少磁盘读开销，已经同步过的文件默认只检查 `size + mtime`，两者都没变就视为未修改。如果外部程序改了文件内容，但恰好没改变文件大小、又把修改时间改回了原值，快速检查就会漏掉。需要更严格的校验时可以按场景选择：

- 指定关键路径：传入 `verifyPaths`（Python 为 `verify_paths`，CLI 为 `--verify-paths a.pak,critical/`），列出的文件或目录每次同步都会重算哈希，不一致就重新拉取；其余文件照常走快速检查
- 全量校验：开启 `verifyAll`，每次同步都重算全部本地文件的哈希，安全性最高但最慢
- 离线巡检：执行 `verify` 命令，对照本地清单逐个重算文件哈希，排查磁盘静默损坏或意外篡改

```bash
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --dir ./assets --server ... --project ... --verify-paths bundles/core.pak,shaders/
```

## 4. 运维命令

```bash
bin/qentimart versions                            # 查看版本列表
```

```bash
bin/qentimart diff -from 1.1.0 -to 1.2.0          # 对比两个版本的文件差异与增量大小
```

```bash
bin/qentimart delete-version -version 1.0.0       # 删除指定版本（需 admin 权限）
```

```bash
bin/qentimart gc                                  # 清理不再被任何版本引用的块（需 admin 权限，可加 -dry-run）
```

## 5. 静态模式 / Cloudflare Pages

静态模式不需要运行 `qentimart-server`。发布命令直接把数据块、清单和版本索引写入本地目录，随后用 `wrangler`、`rsync` 或对象存储工具上传到任意静态托管服务即可；客户端只需配置站点根地址，全程只发 HTTP `GET` 请求

```
qentimart publish --static  ──▶  本地静态目录  ──wrangler/rsync──▶  Cloudflare Pages / CDN  ──GET──▶  SDK (baseUrl)
```

### 构建静态目录

请在多次发布之间保留同一个输出目录。每次发新版本时，CLI 会读取该目录里已有的数据块，只写入新增块并更新 `index.json`。

```bash
qentimart publish --static -o ./site -project mygame -version 1.0.0 ./Assets
```

```bash
qentimart publish --static -o ./site -project mygame -version 1.1.0 ./Assets
```

生成的目录结构：

```
site/
  _headers                        缓存头配置（index.json 短缓存，其余长久缓存）
  index.json                      版本列表与 latest / latestPrerelease 指向
  versions/<v>/manifest.json.gz
  versions/<v>/meta.json
  chunks/<哈希前两位>/<哈希>
```

### 部署到 Cloudflare Pages

```bash
npx wrangler pages deploy ./site
```

Cloudflare Pages 虽然按整站快照发布，但 `wrangler` 上传时会按文件哈希去重，未变动的数据块不会重复上传。目录里的 `_headers` 会被 Pages 自动识别，让不可变的数据块和历史清单长期缓存，`index.json` 保持短 TTL。前置其他 CDN（腾讯云 EdgeOne、阿里云 ESA 等）或给动态服务端挂 CDN 时的缓存配法，见 [CDN 缓存配置指南](CDN_CACHE.md)。

> **注意文件数限制**：Cloudflare Pages 免费版单站点最多允许 20,000 个文件（付费版为 100,000）。静态模式下每个数据块对应一个文件，按默认 1 MiB 平均块大小计算，大项目累积多个版本后可能撞到上限。调大平均块尺寸可以把文件数量降到原来的约四分之一，同时仍远低于 Pages 的 25 MiB 单文件限制：
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

Java：

```java
import qentimart.QentimArtClient;
try (QentimArtClient client = QentimArtClient.staticClient("https://mygame.pages.dev")) {
    client.sync("./assets");
}
```

命令行：

```bash
npx qentimart-node sync --base-url https://mygame.pages.dev --dir ./assets
```

```bash
qentimart-py sync --base-url https://mygame.pages.dev --dir ./assets
```

```bash
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --base-url https://mygame.pages.dev --dir ./assets
```

### 静态目录清理

```bash
qentimart delete-version --static -o ./site -version 1.0.0   # 删除旧版本记录
```

```bash
qentimart gc --static -o ./site                              # 清理无引用的孤立块（可加 -dry-run）
```

静态 GC 默认也会跳过最近 1 小时内写入的块（`-gc-grace`，默认 `1h`），防止和正在进行的发布任务冲突；确认没有并发发布时可以传 `-gc-grace 0` 立即清理

清理完后重新执行 `wrangler pages deploy ./site` 同步到线上即可

## 运行测试

Go 单元测试：

```bash
go test ./...
```

端到端测试（自动编译 Go 二进制、生成测试素材、发布两个版本，并依次调用各语言 SDK 验证同步、复用、冲突和校验逻辑）：

```bash
node tests/e2e.mjs
```

- 默认生成约 400 MiB 测试数据，设 `E2E_SIZE_MB=4000` 可跑更大体积的压测
- `E2E_SKIP_PYTHON=1` 跳过 Python SDK 测试
- `E2E_SKIP_JAVA=1` 跳过 Java SDK 测试；本机未检测到 JDK ≥ 21 时也会自动跳过（可通过 `JAVA_HOME` 指定路径，测试脚本直接调 `javac` 编译，不依赖本机安装 Gradle）
- 临时数据均生成在 `tests/.work/` 下（已加入 `.gitignore`），跑完可直接删除

单独测试网络拉取、解压与哈希校验管线吞吐（不落盘，用于排查瓶颈在网络还是磁盘）：

```bash
node tests/bench-fetch.mjs http://127.0.0.1:8080 mygame <read token>
```
