# @qentimart/sdk

QentimArt 的 Node.js 下游客户端，用于把本地目录增量同步到指定素材版本。零外部依赖，要求 Node.js ≥ 18。

本地不留块缓存，同步完目录里只有素材文件和 `.qentimart/` 状态目录（通常不到总大小的 0.1%）；文件改名、移动或大文件局部改动时直接复用本地已有块，只拉缺失部分，逐块校验 SHA-256 后通过临时文件 rename 替换。

## 安装

```bash
npm install @qentimart/sdk
# 国内镜像：https://npmmirror.com/package/@qentimart/sdk
```

从本仓库源码安装：

```bash
npm install ./sdk/nodejs
```

## 快速开始

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({
  server: 'http://assets.internal:8080',
  project: 'mygame',
  token: process.env.QENTIMART_TOKEN, // 只需要 read 权限
});

const result = await client.sync('./assets', {
  onProgress: (p) => console.log(p.phase, p.done, p.total, p.bytesPerSecond),
});
console.log(result.version, result.storage.ratio); // 例如 '1.2.0' 1.0004
client.close();
```

更完整的例子：

- [examples/plugin-example.mjs](examples/plugin-example.mjs) — 检查更新、预估下载量、同步和冲突处理
- [examples/plugin-illustration-update.js](examples/plugin-illustration-update.js) — Yunzai-Bot V3 插件接入示例（参考 phi-plugin，用增量同步替代 `git pull` 更新曲绘库）

## 静态模式

上游用 `qentimart publish --static` 把资源放到对象存储、CDN 或 Cloudflare Pages 时，初始化只传 `baseUrl`，不需要 `server` 和 `token`：

```js
const client = new QentimArtClient({ baseUrl: 'https://mygame.pages.dev' });
await client.sync('./assets');
await client.sync('./assets', { version: '1.0.0' });
```

`sync`、`plan`、`status`、`verify`、`diff`、`storageReport` 用法不变。

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient({ server, project, token, concurrency=8 })` | 连接 `qentimart-server` |
| `new QentimArtClient({ baseUrl, concurrency=8 })` | 连接静态托管源（只读） |
| `listVersions()` | 获取按新到旧排列的版本列表及 `latest` 指向 |
| `getVersion(v='latest', { prerelease })` | 获取指定版本的元数据 |
| `diff(from, to='latest')` | 查询两个版本之间的文件差异 |
| `sync(dir, opts)` | 同步目录到目标版本（默认 `latest`），支持任意升降级 |
| `plan(dir, opts)` | 只计算要写入/删除的文件和预计下载量，不动磁盘 |
| `status(dir)` | 离线对比本地目录和上次同步清单，列出改动、缺失和新增文件 |
| `verify(dir)` | 重算全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storageReport(dir)` | 统计磁盘占用：`{ assetBytes, overheadBytes, ratio }` |
| `close()` | 关闭底层 HTTP 连接池 |

### `sync` 选项

| 选项 | 默认值 | 说明 |
|---|---|---|
| `version` | `'latest'` | 目标版本号，如 `'1.0.0'` |
| `localChanges` | `'overwrite'` | 本地改动处理方式：`overwrite` 把受管文件还原为服务端版本；`keep` 保留本地改动，只更新其余文件；`fail` 抛 `SyncConflictError` 并中止，不写盘 |
| `deleteUntracked` | `false` | 是否删除未跟踪文件（既不在目标版本里、也不在之前清单里的本地文件）。默认即使 `overwrite` 也不删，方便只同步目录里一部分文件；设为 `true` 才在 `overwrite` 下一并清理。清单里被删掉的受管文件始终正常清理 |
| `deleteUntrackedOnFirstSync` | `false` | 目录首次同步（还没有 `.qentimart`）时，即使开了 `deleteUntracked: true` 也默认保留未跟踪文件，防止传错路径误删整个目录；显式设为 `true` 才在首次同步时清理 |
| `prerelease` | `false` | `version` 为 `'latest'` 时允许匹配预发布版 |
| `verifyAll` | `false` | 跳过 `size + mtime` 快速检查，对所有本地文件重算哈希 |
| `verifyPaths` | `[]` | 对指定相对路径强制算哈希（以 `/` 结尾表示整个子目录），其余文件仍走快速检查 |
| `onProgress` | – | 进度回调 `({ phase, done, total, downloadedBytes, bytesPerSecond }) => void` |
| `signal` | – | 用于中途取消同步的 `AbortSignal` |

## 命令行

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
npx qentimart-node plan --dir ./assets --version 1.0.0
npx qentimart-node status --dir ./assets
npx qentimart-node report --dir ./assets
```

## 参数调优

- `concurrency`：并发批次连接数，千兆内网保持默认 `8` 就够了；跨公网高延迟或后端是 S3 时可以调到 `16`–`32`。
- 解压量很大时可以把 Node.js 的 libuv 线程池调大，比如设环境变量 `UV_THREADPOOL_SIZE=16`。
- `waveBytes` / `waveFiles`：限制单轮同时打开的临时文件总大小和数量上限。
