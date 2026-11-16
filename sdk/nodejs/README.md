# @qentimart/sdk（Node.js）

QentimArt 下游 SDK：把游戏素材目录同步到服务器上的任意版本。依赖为零，要求 Node.js ≥ 18。

- 块级增量更新：只下载变化的块；文件移动、大文件局部修改时优先复用本地数据
- 不保留本地对象缓存：稳态磁盘占用 ≈ 素材大小 + 极小的 `.qentimart/` 元数据（通常 < 0.1%）
- 批量、多连接并行拉取，每个块都做 SHA-256 校验，文件原子替换

## 安装

```bash
npm install ./sdk/nodejs        # 或发布到私有 npm 仓库后 npm install @qentimart/sdk
```

## 快速开始

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({
  server: 'http://assets.internal:8080',
  project: 'mygame',
  token: process.env.QENTIMART_TOKEN, // 只读 token 即可
});

const result = await client.sync('./assets', {
  onProgress: (p) => console.log(p.phase, p.done, p.total, p.bytesPerSecond),
});
console.log(result.version, result.storage.ratio); // 例如 '1.2.0' 1.0004
client.close();
```

完整示例（检查更新 → 预估下载量 → 同步 → 处理冲突）见 [examples/plugin-example.mjs](examples/plugin-example.mjs)。

## 静态模式（无需服务端）

如果素材发布成了静态树并托管在对象存储 / CDN / Cloudflare Pages 上（见根目录 README 的静态模式），只需传 `baseUrl`，不需要 server/token，全程只读：

```js
const client = new QentimArtClient({ baseUrl: 'https://mygame.pages.dev' });
await client.sync('./assets');                     // 和动态模式用法完全一致
await client.sync('./assets', { version: '1.0.0' });
```

`sync` / `plan` / `status` / `verify` / `diff` / `storageReport` 用法与动态模式相同。

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient({ server, project, token, concurrency=8 })` | 创建动态客户端 |
| `new QentimArtClient({ baseUrl, concurrency=8 })` | 创建静态客户端（只读，无需鉴权） |
| `listVersions()` | 版本列表（新→旧）以及 `latest` |
| `getVersion(v='latest', { prerelease })` | 版本元数据 |
| `diff(from, to='latest')` | 服务端计算的版本差异 |
| `sync(dir, opts)` | 同步到指定版本（默认 `latest`），可升级也可降级 |
| `plan(dir, opts)` | 预演：会写入/删除哪些文件、预计下载多少 |
| `status(dir)` | 离线检查：当前版本、被修改/缺失/多出的文件 |
| `verify(dir)` | 重新计算所有文件的哈希，检测静默损坏 |
| `QentimArtClient.storageReport(dir)` | `{ assetBytes, overheadBytes, ratio }` |
| `close()` | 释放连接池 |

### `sync` 选项

| 选项 | 默认 | 说明 |
|---|---|---|
| `version` | `'latest'` | 目标版本，例如 `'1.0.0'` |
| `localChanges` | `'overwrite'` | 本地被修改/新增的文件：`overwrite` 还原为服务器版本并删除多余文件；`keep` 保留它们、其余照常更新；`fail` 发现冲突就抛出 `SyncConflictError`，不做任何修改 |
| `deleteUntrackedOnFirstSync` | `false` | 安全开关：目录从未同步过（没有 `.qentimart` 状态）时，即使是 `overwrite` 也不会删除不属于目标版本的文件，以防 `dir` 配错；设为 `true` 才删除 |
| `prerelease` | `false` | 允许 `latest` 解析到预发布版本 |
| `verifyAll` | `false` | 不信任 size+mtime，对所有本地文件计算哈希 |
| `onProgress` | – | `({ phase, done, total, downloadedBytes, bytesPerSecond }) => void` |
| `signal` | – | `AbortSignal`，用于取消 |

## 命令行

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
npx qentimart-node plan    --dir ./assets --version 1.0.0
npx qentimart-node status  --dir ./assets
npx qentimart-node report  --dir ./assets
```

## 性能调优

- `concurrency`：并行请求数，千兆内网 8 就够了，高延迟网络或 S3 后端可以调到 16–32。
- 需要大量解压时，可以调大 libuv 线程池：`UV_THREADPOOL_SIZE=16`。
- `waveBytes` / `waveFiles`：每轮最多同时打开的临时文件总大小和数量。
