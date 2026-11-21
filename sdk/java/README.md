# qentimart-sdk (Java)

QentimArt 的 Java 下游客户端，只用 JDK 标准库，无第三方依赖，要求 Java ≥ 21。本地 `.qentimart/` 状态格式与 [Node.js SDK](../nodejs/README.md)、[Python SDK](../python/README.md) 一致，三个 SDK 可以共用同一个资源目录。

同步时优先复用本地旧文件里的块，缺失块基于 `java.net.http.HttpClient` 连接池多线程拉取，逐块校验 SHA-256 后写临时文件再 rename 替换；本地不留块缓存，同步后总占用实测约 100.04%。

## 构建

```bash
cd sdk/java && sh ./gradlew build
```

产物在 `build/libs/` 下，包含带 CLI 入口的 `qentimart-sdk-0.1.0.jar` 和源码包 `qentimart-sdk-0.1.0-sources.jar`。Maven 坐标：`io.qentimart:qentimart-sdk:0.1.0`。

## 快速开始

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;
import qentimart.SyncResult;

try (QentimArtClient client = new QentimArtClient("http://assets.internal:8080", "mygame", "<read token>")) {
    SyncResult r = client.sync("./assets", new SyncOptions().onProgress(
            p -> System.out.println(p.phase() + " " + p.done() + "/" + p.total())));
    System.out.println(r.version + " " + r.storage.ratio);      // 例如 1.2.0 1.0004
    client.sync("./assets", new SyncOptions().version("1.0.0")); // 切到指定版本
}
```

## 静态模式

素材通过静态模式托管在对象存储、CDN 或 Cloudflare Pages 上时，用 `staticClient` 传入站点根地址，不需要 `server` 和 `token`：

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;

try (QentimArtClient client = QentimArtClient.staticClient("https://mygame.pages.dev")) {
    client.sync("./assets");
    client.sync("./assets", new SyncOptions().version("1.0.0"));
}
```

`sync`、`plan`、`status`、`verify`、`diff`、`storageReport` 用法与动态客户端一致。

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient(server, project, token[, concurrency])` | 连接服务端（实现 `AutoCloseable`） |
| `QentimArtClient.staticClient(baseUrl[, project, concurrency])` | 连接静态托管源（只读） |
| `listVersions()` / `getVersion([v, prerelease])` / `diff(from, to)` | 查询版本列表、单版本元数据和差异（返回 `Map<String,Object>`） |
| `sync(dir[, SyncOptions])` | 同步目录到目标版本（默认最新正式版），返回 `SyncResult` |
| `plan(dir, SyncOptions)` | 只计算预计写入/删除的文件和下载量，不动磁盘 |
| `status(dir)` | 离线检查本地被改动、缺失和多出的文件 |
| `verify(dir[, onProgress])` | 重算全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storageReport(dir)` | 返回磁盘占用统计 `StorageReport{assetBytes, overheadBytes, ratio}` |

### `SyncOptions`（支持链式调用）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本号 |
| `localChanges` | `"overwrite"` | `overwrite` 把受管文件还原为服务端版本；`keep` 保留本地改动和新增文件；`fail` 发现冲突抛 `SyncConflictException`，不动磁盘 |
| `deleteUntracked` | `false` | 是否删除未跟踪文件（既不在目标版本里、也不在之前清单里的本地文件）。默认即使 `overwrite` 也不删，方便只同步目录里一部分文件；设为 `true` 才在 `overwrite` 下一并清理。清单里被删掉的受管文件始终正常清理 |
| `deleteUntrackedOnFirstSync` | `false` | 目录首次同步（还没有 `.qentimart`）时，即使开了 `deleteUntracked=true` 也默认保留未跟踪文件，防止传错路径误删整个目录；显式设为 `true` 才在首次同步时清理 |
| `prerelease` | `false` | 允许 `latest` 匹配预发布版 |
| `verifyAll` | `false` | 跳过 `size + mtime` 快速检查，对所有本地文件重算哈希 |
| `verifyPaths` | `[]` | 对指定相对路径强制算哈希（以 `/` 结尾表示目录前缀），其余文件仍走快速检查 |
| `onProgress` | `null` | 进度回调 `Consumer<ProgressEvent>`，含 `phase / done / total / downloadedBytes / bytesPerSecond` |
| `cancel` | `null` | 传入 `AtomicBoolean`，外部调用 `set(true)` 可中途取消同步 |

注：`SyncResult` 等返回对象按 Java 习惯用 camelCase 字段名（如 `downloadedWire`、`upToDate`、`previousVersion`），落盘的 `.qentimart/` 状态文件则使用跨语言统一字段名，不影响和其他 SDK 混用。

## 命令行

```bash
java -jar build/libs/qentimart-sdk-0.1.0.jar sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
java -jar build/libs/qentimart-sdk-0.1.0.jar status --dir ./assets --server http://127.0.0.1:8080 --project mygame
java -jar build/libs/qentimart-sdk-0.1.0.jar report --dir ./assets
```

执行 `sh ./gradlew installDist` 后也可以直接跑 `build/install/qentimart-java/bin/qentimart-java`。支持 `sync`、`plan`、`status`、`verify`、`versions`、`report`，参数和环境变量（`QENTIMART_SERVER`、`QENTIMART_PROJECT`、`QENTIMART_TOKEN`、`QENTIMART_BASE_URL`）与 Node.js、Python SDK 一致。
