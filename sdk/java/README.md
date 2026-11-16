# qentimart-sdk（Java）

QentimArt 下游 SDK 的 Java 版本，只用 JDK 标准库，支持 **Java ≥ 21**。功能、同步算法和本地状态格式都与 [Node.js SDK](../nodejs/README.md)、[Python SDK](../python/README.md) 一致，三个 SDK 可以互相接管同一个目录。

- 块级增量更新，文件移动、大文件局部修改时优先复用本地数据
- 不保留本地对象缓存，稳态磁盘占用 ≈ 素材大小 + 极小的 `.qentimart/` 元数据（实测约 100.04%）
- 多线程批量拉取（`java.net.http.HttpClient` 自带连接池）；每个块都做 SHA-256 校验，文件原子替换
- 零第三方依赖

## 构建

```bash
cd sdk/java && ./gradlew build
```

产物：`build/libs/qentimart-sdk-0.1.0.jar`（可执行，内含 CLI 入口）与 `qentimart-sdk-0.1.0-sources.jar`。

坐标：`io.qentimart:qentimart-sdk:0.1.0`。

## 快速开始

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;
import qentimart.SyncResult;

try (QentimArtClient client = new QentimArtClient("http://assets.internal:8080", "mygame", "<read token>")) {
    SyncResult r = client.sync("./assets", new SyncOptions().onProgress(
            p -> System.out.println(p.phase() + " " + p.done() + "/" + p.total())));
    System.out.println(r.version + " " + r.storage.ratio);   // 例如 1.2.0 1.0004
    client.sync("./assets", new SyncOptions().version("1.0.0")); // 切换（降级）到指定版本
}
```

## 静态模式（无需服务端）

素材发布成静态树托管在对象存储 / CDN / Cloudflare Pages 上时，只需传 `baseUrl`，不需要 server/token，全程只读：

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;

try (QentimArtClient client = QentimArtClient.staticClient("https://mygame.pages.dev")) {
    client.sync("./assets");
    client.sync("./assets", new SyncOptions().version("1.0.0"));
}
```

`sync` / `plan` / `status` / `verify` / `diff` / `storageReport` 用法与动态模式一致。

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient(server, project, token[, concurrency])` | 创建动态客户端（实现 `AutoCloseable`） |
| `QentimArtClient.staticClient(baseUrl[, project, concurrency])` | 创建静态客户端（只读，无需鉴权） |
| `listVersions()` / `getVersion([v, prerelease])` / `diff(from, to)` | 查询服务器上的版本（返回 `Map<String,Object>`） |
| `sync(dir[, SyncOptions])` | 同步到指定版本（默认最新版），返回 `SyncResult` |
| `plan(dir, SyncOptions)` | 预演，返回预计写入/删除的文件和下载量 |
| `status(dir)` | 离线检查本地的修改、缺失和多出的文件 |
| `verify(dir[, onProgress])` | 重新计算所有文件的哈希，检测静默损坏 |
| `QentimArtClient.storageReport(dir)` | `StorageReport{assetBytes, overheadBytes, ratio}` |

### `SyncOptions`（链式 setter）

| 参数 | 默认 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本 |
| `localChanges` | `"overwrite"` | `overwrite` 还原为服务器版本并删除多余文件；`keep` 保留本地改动；`fail` 发现冲突时抛出 `SyncConflictException`，不做任何修改 |
| `deleteUntrackedOnFirstSync` | `false` | 安全开关：从未同步过的目录不会删除不属于目标版本的文件 |
| `prerelease` | `false` | 允许 `latest` 解析到预发布版本 |
| `verifyAll` | `false` | 对所有本地文件计算哈希，不信任 size + mtime |
| `onProgress` | `null` | 回调 `Consumer<ProgressEvent>`，字段 `phase / done / total / downloadedBytes / bytesPerSecond` |
| `cancel` | `null` | `AtomicBoolean`，调用 `set(true)` 即可取消同步 |

`SyncResult` 字段采用 Java 惯例 camelCase（`downloadedWire`、`upToDate`、`previousVersion` 等）；这是 SDK 本地结构，不影响与其他语言 SDK 共享同一目录——真正跨 SDK 兼容的是磁盘上的 `.qentimart/` 状态格式与 HTTP/静态协议。

## 命令行

```bash
java -jar build/libs/qentimart-sdk-0.1.0.jar sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
```

```bash
java -jar build/libs/qentimart-sdk-0.1.0.jar status --dir ./assets --server http://127.0.0.1:8080 --project mygame
```

```bash
java -jar build/libs/qentimart-sdk-0.1.0.jar report --dir ./assets
```

`./gradlew installDist` 后也可用 `build/install/qentimart-java/bin/qentimart-java` 启动脚本。命令：`sync` / `plan` / `status` / `verify` / `versions` / `report`；flag 与环境变量（`QENTIMART_SERVER` / `QENTIMART_PROJECT` / `QENTIMART_TOKEN` / `QENTIMART_BASE_URL`）同 Python/Node SDK。
