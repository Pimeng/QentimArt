# 快速开始

## 环境要求

| 组件 | 要求 |
|---|---|
| 服务端 `qentimart-server` / 客户端 `qentimart` | Go ≥ 1.23（仅编译时需要，产物为单文件二进制） |
| Node.js SDK | Node.js ≥ 18，无第三方依赖 |
| Python SDK | Python ≥ 3.8，仅用标准库 |
| Java SDK | JDK ≥ 21，仅用标准库（Gradle 构建） |
| 存储后端 | 本地磁盘，或 S3 兼容存储（AWS S3 / MinIO / 阿里云 OSS / 腾讯云 COS / Cloudflare R2） |

## 1. 编译并启动服务端

编译服务端和命令行工具，输出到 `bin/`：

```bash
go build -o bin/ ./cmd/qentimart-server ./cmd/qentimart
```

生成几个不同角色的 token（发布、只读拉取、运维），填进配置文件后启动：

```bash
bin/qentimart-server gen-token
cp examples/server.json server.local.json
# 编辑 server.local.json 填入刚生成的 token
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
| `storage.type` | `local`（配 `path`）或 `s3`（配 `s3.endpoint/region/bucket/accessKey/secretKey/pathStyle/prefix/parallelism`） |
| `tokens[]` | `role` 可选 `read` / `publish` / `admin`，高权限自动包含低权限；`projects` 填 `["*"]` 或具体项目名列表 |
| `allowAnonymousRead` | 允许不带 token 的只读请求，默认 `false` |
| `insecureNoAuth` | 允许在未配任何 token 时启动并开放全部 admin 权限，默认 `false`，只用于本地调试 |
| `maxFetchChunks` | 单次批量拉取的最大块数，默认 `4096` |
| `gcGrace` | GC 宽限期，默认 `1h`，避免误删正在上传但还没提交清单的块 |
| `versionCacheTTL` | 版本列表缓存时间，默认 `15s`，多实例共用同一个存储桶时生效 |

既没配 `tokens` 也没开 `insecureNoAuth` 时，服务端会拒绝启动。

### 使用 S3 / MinIO

参考 [examples/server.s3.json](../examples/server.s3.json)。自建 MinIO 通常要设 `"pathStyle": true`；AWS S3、OSS、COS 一般保持默认的 `false`（virtual-hosted 风格）。非回环地址默认强制 `https://`，内网测试如果要走明文 HTTP，在 `s3` 下加 `"allowInsecureHTTP": true`。

### 使用 Docker

```bash
docker build -t qentimart .
docker run -d -p 8080:8080 \
  -v /srv/qentimart/data:/data \
  -v /srv/qentimart/server.json:/etc/qentimart/server.json:ro \
  qentimart
```

挂载本地目录时，配置文件里的 `storage.path` 改成 `/data`。

## 2. 发布版本

发布一般在 CI 里跑，连接参数可以通过环境变量传：

```bash
export QENTIMART_SERVER=http://127.0.0.1:8080
export QENTIMART_PROJECT=mygame
export QENTIMART_TOKEN=<publish token>

bin/qentimart publish -version 1.2.0 -exclude "*.tmp,Library/" ./Assets
```

客户端会并发切块算哈希，先问服务端缺哪些块再上传，最后提交版本清单。

- 版本号必须符合 SemVer（如 `1.2.0`、`2.0.0-beta.1`），发布后不能覆盖同一版本号。
- 加 `-dry-run` 只统计待上传的数据量，不写服务端。
- `-concurrency` 控制上传并发数（默认 8），`-compress none` 关闭压缩；用 `-token-file` 从文件读 token，避免命令行参数出现在进程列表里。
- `.git` 和 `.qentimart` 目录默认跳过。

## 3. 下游接入

下游拉取只需要 `read` 权限的 token。

### Node.js

```bash
npm install ./sdk/nodejs
```

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({ server: 'http://127.0.0.1:8080', project: 'mygame', token });
const result = await client.sync('./assets');               // 默认拉最新正式版
await client.sync('./assets', { version: '1.1.0' });        // 切到指定版本
client.close();
```

详细接口与插件示例见 [sdk/nodejs/README.md](../sdk/nodejs/README.md)。

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

详细接口见 [sdk/python/README.md](../sdk/python/README.md)。

### Java

```bash
cd sdk/java && ./gradlew build
```

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;

try (QentimArtClient client = new QentimArtClient("http://127.0.0.1:8080", "mygame", token)) {
    client.sync("./assets");
    client.sync("./assets", new SyncOptions().version("1.1.0"));
}
```

详细接口与构建说明见 [sdk/java/README.md](../sdk/java/README.md)。

### 命令行

三个 SDK 都带了 CLI 入口，方便在脚本里直接调：

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
qentimart-py sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --server http://127.0.0.1:8080 --project mygame --token <read token> --dir ./assets
```

### 本地改动的处理

本地文件被改过、或者目录里多出了清单外的文件时，用冲突策略参数控制行为：

| 策略 | 行为 |
|---|---|
| `overwrite`（默认） | 把受管文件还原成目标版本，清理清单中已移除的旧文件；未跟踪的本地文件默认保留（设 `deleteUntracked` 才一并清理） |
| `keep` | 保留本地修改过或新增的文件，其余文件正常更新 |
| `fail` | 检测到冲突直接报错退出，不改磁盘 |

另外，从未同步过的目录（还没有 `.qentimart`）首次同步时，即使开启了 `deleteUntracked` 也不会删除目录里原有的其他文件，防止传错路径把整个目录清掉；确实需要首次同步就清空时，再显式开启 `deleteUntrackedOnFirstSync`（Python 为 `delete_untracked_on_first_sync`）。

### 文件校验方式

已经同步过的文件默认只比对 `size + mtime`，都没变就跳过重算哈希。如果外部程序改了文件内容但恰好大小不变、又把修改时间还原了，快速检查会漏掉。按需要可以切到更严格的方式：

- 关键路径强制校验：传 `verifyPaths`（Python 为 `verify_paths`，CLI 为 `--verify-paths a.pak,critical/`），列出的文件或目录每次同步都重算哈希，其余照常走快速检查。
- 全量校验：开 `verifyAll`，每次同步对所有本地文件重算哈希，最慢但最稳。
- 离线巡检：跑 `verify` 命令，对照本地清单重算所有受管文件的哈希，查磁盘静默损坏或意外改动。

```bash
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --dir ./assets --server ... --project ... --verify-paths bundles/core.pak,shaders/
```

## 4. 运维命令

```bash
bin/qentimart versions                      # 查看版本列表
bin/qentimart diff -from 1.1.0 -to 1.2.0    # 对比两个版本的文件差异与增量大小
bin/qentimart delete-version -version 1.0.0 # 删除指定版本（需 admin 权限）
bin/qentimart gc                            # 清理无引用的孤立块（需 admin 权限，可加 -dry-run）
```

## 5. 静态模式 / Cloudflare Pages

静态模式不需要跑 `qentimart-server`。发布命令直接把数据块、清单和索引写到本地目录，再用 `wrangler`、`rsync` 或对象存储工具传到静态托管服务；下游只配站点根地址，全程走 HTTP `GET`。

```
qentimart publish --static  -->  本地静态目录  --wrangler/rsync-->  Cloudflare Pages / CDN  --GET-->  SDK (baseUrl)
```

### 构建静态目录

多次发布之间保留同一个输出目录，CLI 会复用目录里已有的数据块，只写新增块并更新 `index.json`：

```bash
qentimart publish --static -o ./site -project mygame -version 1.0.0 ./Assets
qentimart publish --static -o ./site -project mygame -version 1.1.0 ./Assets
```

目录结构：

```
site/
  _headers                        缓存头配置（index.json 短缓存，其余永久缓存）
  index.json                      版本列表与 latest / latestPrerelease 指向
  versions/<v>/manifest.json.gz
  versions/<v>/meta.json
  chunks/<哈希前两位>/<哈希>
```

### 部署到 Cloudflare Pages

```bash
npx wrangler pages deploy ./site
```

`wrangler` 上传时会按文件哈希去重，没变过的块不会重传。`_headers` 会被 Pages 自动识别，不可变的数据块和清单长期缓存，`index.json` 保持短 TTL。其他 CDN（EdgeOne、ESA 等）或给动态服务端挂 CDN 的配法见 [CDN 缓存配置](CDN_CACHE.md)。

> **文件数上限**：Cloudflare Pages 免费版单站点上限 20,000 个文件（付费版 100,000）。静态模式下每个块就是一个文件，默认 1 MiB 平均块大小时，大项目发多个版本后可能撞上限。把平均块尺寸调大（例如 4 MiB）可以把文件数压到四分之一左右，而且离 Pages 的 25 MiB 单文件上限还很远：
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
qentimart-py sync --base-url https://mygame.pages.dev --dir ./assets
java -jar sdk/java/build/libs/qentimart-sdk-0.1.0.jar sync --base-url https://mygame.pages.dev --dir ./assets
```

### 静态目录清理

```bash
qentimart delete-version --static -o ./site -version 1.0.0   # 删除旧版本记录
qentimart gc --static -o ./site                              # 清理无引用的孤立块（可加 -dry-run）
```

静态 GC 默认也会跳过最近 1 小时内写入的块（`-gc-grace`，默认 `1h`），确认没有并发发布时可以传 `-gc-grace 0` 立即清理。清完重新 `wrangler pages deploy ./site` 即可。

## 运行测试

Go 单元测试：

```bash
go test ./...
```

端到端测试（会自动编译 Go 二进制、生成测试素材、发两个版本，然后用各语言 SDK 跑一遍同步、复用、冲突和校验）：

```bash
node tests/e2e.mjs
```

- 默认生成约 400 MiB 测试数据，设 `E2E_SIZE_MB=4000` 可跑更大体积压测。
- `E2E_SKIP_PYTHON=1` / `E2E_SKIP_JAVA=1` 跳过对应 SDK 测试；本机没装 JDK ≥ 21 时 Java 测试会自动跳过（可通过 `JAVA_HOME` 指定，脚本直接调 `javac`，不依赖 Gradle）。
- 临时文件都在 `tests/.work/`（已 gitignore），跑完可直接删。

单独测拉取、解压和哈希校验管线吞吐（不写盘，用来判断瓶颈在网络还是磁盘）：

```bash
node tests/bench-fetch.mjs http://127.0.0.1:8080 mygame <read token>
```
