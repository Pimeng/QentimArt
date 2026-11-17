# @qentimart/sdk（Node.js）

QentimArt 的 Node.js 下游客户端，用于将本地目录增量同步到指定素材版本。无第三方依赖，要求 Node.js ≥ 18。

- 按数据块增量同步，文件重命名、移动或大文件局部修改时直接复用本地已有数据
- 本地不保留额外的块缓存库，除业务文件外只有 `.qentimart/` 状态文件（通常不到素材总大小的 0.1%）
- 多连接批量并发拉取，落盘前逐块校验 SHA-256，写完临时文件后原子替换

## 安装

```bash
npm install ./sdk/nodejs        # 或发布到私有 npm 源后执行 npm install @qentimart/sdk
```

## 快速开始

```js
import { QentimArtClient } from '@qentimart/sdk';

const client = new QentimArtClient({
  server: 'http://assets.internal:8080',
  project: 'mygame',
  token: process.env.QENTIMART_TOKEN, // 只需 read 权限
});

const result = await client.sync('./assets', {
  onProgress: (p) => console.log(p.phase, p.done, p.total, p.bytesPerSecond),
});
console.log(result.version, result.storage.ratio); // 例如 '1.2.0' 1.0004
client.close();
```

包含检查更新、预估下载量、同步及冲突处理的完整流程见 [examples/plugin-example.mjs](examples/plugin-example.mjs)

Yunzai-Bot V3 机器人插件接入示例（参考 phi-plugin，用增量同步替代 `git pull` 更新“曲绘库”）见 [examples/plugin-illustration-update.js](examples/plugin-illustration-update.js)

## 静态模式

如果上游通过 `qentimart publish --static` 把素材发布到了对象存储、CDN 或 Cloudflare Pages，初始化客户端时只需传入站点地址 `baseUrl`，无需配置 `server` 与 `token`：

```js
const client = new QentimArtClient({ baseUrl: 'https://mygame.pages.dev' });
await client.sync('./assets');
await client.sync('./assets', { version: '1.0.0' });
```

`sync`、`plan`、`status`、`verify`、`diff`、`storageReport` 等方法均可照常调用

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient({ server, project, token, concurrency=8 })` | 连接 `qentimart-server` 的客户端 |
| `new QentimArtClient({ baseUrl, concurrency=8 })` | 连接静态托管源的只读客户端 |
| `listVersions()` | 获取按新到旧排列的版本列表及 `latest` 指向 |
| `getVersion(v='latest', { prerelease })` | 获取指定版本的元数据 |
| `diff(from, to='latest')` | 查询两个版本之间的文件差异 |
| `sync(dir, opts)` | 将本地目录同步到目标版本（默认 `latest`），支持任意升降级 |
| `plan(dir, opts)` | 仅计算将要写入、删除的文件及预计下载字节数，不修改磁盘 |
| `status(dir)` | 离线对比本地目录与上次同步清单，列出被改动、缺失和新增的文件 |
| `verify(dir)` | 重算目录内全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storageReport(dir)` | 统计磁盘占用：`{ assetBytes, overheadBytes, ratio }` |
| `close()` | 关闭底层 HTTP 连接池 |

### `sync` 选项

| 选项 | 默认值 | 说明 |
|---|---|---|
| `version` | `'latest'` | 目标版本号，如 `'1.0.0'` |
| `localChanges` | `'overwrite'` | 本地存在改动或多余文件时的行为：`overwrite` 覆盖还原并删除多余文件；`keep` 保留本地改动，只更新其余文件；`fail` 抛出 `SyncConflictError` 并中止，不写任何文件 |
| `deleteUntrackedOnFirstSync` | `false` | 目录首次同步（尚无 `.qentimart` 记录）时默认不删目录里原有的其他文件，防止传错路径误删数据；设为 `true` 则首次同步也清理多余文件 |
| `prerelease` | `false` | `version` 为 `'latest'` 时允许解析到预发布版本 |
| `verifyAll` | `false` | 跳过 `size + mtime` 快速判断，对本地所有文件强制算哈希 |
| `verifyPaths` | `[]` | 对列出的相对路径强制算哈希校验（以 `/` 结尾代表整个子目录），其余文件仍用 `size + mtime` 快速检查 |
| `onProgress` | – | 进度回调 `({ phase, done, total, downloadedBytes, bytesPerSecond }) => void` |
| `signal` | – | 用于中途取消同步的 `AbortSignal` |

## 命令行

```bash
npx qentimart-node sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
```

```bash
npx qentimart-node plan --dir ./assets --version 1.0.0
```

```bash
npx qentimart-node status --dir ./assets
```

```bash
npx qentimart-node report --dir ./assets
```

## 参数调优参考

- `concurrency`：并发批次连接数，千兆内网保持默认的 8 即可；跨公网高延迟或后端为 S3 时可调高到 16–32
- 解压数据量很大时，可适当调大 Node.js 的 libuv 线程池，例如设置环境变量 `UV_THREADPOOL_SIZE=16`
- `waveBytes` / `waveFiles`：限制单轮同时打开的临时文件总体积与数量上限
