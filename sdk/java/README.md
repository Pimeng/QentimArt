# qentimart-sdk（Java）

QentimArt 的 Java 下游客户端，仅使用 JDK 标准库，无第三方依赖，要求 Java ≥ 21。磁盘上的 `.qentimart/` 状态格式与 [Node.js SDK](../nodejs/README.md)、[Python SDK](../python/README.md) 保持一致，三个 SDK 可以直接共用同一个资源目录。

- 按数据块增量更新，文件改名移动或大文件局部修改时直接复用本地已有数据
- 本地不保留块缓存，同步后除业务素材外只有轻量的 `.qentimart/` 元数据（实测总占用约为素材体积的 100.04%）
- 基于 `java.net.http.HttpClient` 连接池多线程批量拉取，逐块校验 SHA-256，写完临时文件后原子替换

## 构建

```bash
cd sdk/java && ./gradlew build
```

构建产物位于 `build/libs/`：包含内置 CLI 入口的可执行包 `qentimart-sdk-0.1.0.jar` 与源码包 `qentimart-sdk-0.1.0-sources.jar`

Maven 坐标：`io.qentimart:qentimart-sdk:0.1.0`

## 快速开始

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;
import qentimart.SyncResult;

try (QentimArtClient client = new QentimArtClient("http://assets.internal:8080", "mygame", "<read token>")) {
    SyncResult r = client.sync("./assets", new SyncOptions().onProgress(
            p -> System.out.println(p.phase() + " " + p.done() + "/" + p.total())));
    System.out.println(r.version + " " + r.storage.ratio);   // 例如 1.2.0 1.0004
    client.sync("./assets", new SyncOptions().version("1.0.0")); // 切换到指定版本
}
```

## 静态模式

当素材通过静态模式托管在对象存储、CDN 或 Cloudflare Pages 上时，使用 `staticClient` 传入站点根地址即可，无需 `server` 和 `token`：

```java
import qentimart.QentimArtClient;
import qentimart.SyncOptions;

try (QentimArtClient client = QentimArtClient.staticClient("https://mygame.pages.dev")) {
    client.sync("./assets");
    client.sync("./assets", new SyncOptions().version("1.0.0"));
}
```

`sync`、`plan`、`status`、`verify`、`diff`、`storageReport` 用法与动态客户端一致

## API

| 方法 | 说明 |
|---|---|
| `new QentimArtClient(server, project, token[, concurrency])` | 创建连接服务端的客户端（实现 `AutoCloseable`） |
| `QentimArtClient.staticClient(baseUrl[, project, concurrency])` | 创建连接静态托管源的只读客户端 |
| `listVersions()` / `getVersion([v, prerelease])` / `diff(from, to)` | 查询版本列表、版本元数据与差异（返回 `Map<String,Object>`） |
| `sync(dir[, SyncOptions])` | 将目录同步到目标版本（默认最新正式版），返回 `SyncResult` |
| `plan(dir, SyncOptions)` | 仅计算预计写入、删除的文件与下载字节数，不修改磁盘 |
| `status(dir)` | 离线检查本地被修改、缺失及多出的文件 |
| `verify(dir[, onProgress])` | 重算全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storageReport(dir)` | 返回磁盘占用统计 `StorageReport{assetBytes, overheadBytes, ratio}` |

### `SyncOptions`（支持链式设置）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本号 |
| `localChanges` | `"overwrite"` | `overwrite` 还原本地改动并删除多余文件；`keep` 保留本地改动与新增文件；`fail` 发现冲突抛出 `SyncConflictException`，不改动磁盘 |
| `deleteUntrackedOnFirstSync` | `false` | 从未同步过的目录首次执行 `overwrite` 时，默认不清理目录里原有的其他文件；设为 `true` 才会删除 |
| `prerelease` | `false` | 允许 `latest` 解析到预发布版本 |
| `verifyAll` | `false` | 跳过 `size + mtime` 快速检查，对所有本地文件重算哈希 |
| `verifyPaths` | `[]` | 对指定的相对路径强制重算哈希校验（以 `/` 结尾表示目录前缀），其余文件仍走快速检查 |
| `onProgress` | `null` | 进度回调 `Consumer<ProgressEvent>`，含 `phase / done / total / downloadedBytes / bytesPerSecond` |
| `cancel` | `null` | 传入 `AtomicBoolean`，外部调用 `set(true)` 可中途取消同步 |

注：`SyncResult` 等返回对象的字段名遵循 Java 命名习惯使用 camelCase（如 `downloadedWire`、`upToDate`、`previousVersion`），而落盘的 `.qentimart/` 状态文件使用跨语言统一字段名，不影响与其他 SDK 混用。

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

执行 `./gradlew installDist` 后也可直接运行生成的启动脚本 `build/install/qentimart-java/bin/qentimart-java`。支持子命令 `sync`、`plan`、`status`、`verify`、`versions`、`report`，命令行参数与环境变量（`QENTIMART_SERVER`、`QENTIMART_PROJECT`、`QENTIMART_TOKEN`、`QENTIMART_BASE_URL`）均与 Node.js 和 Python SDK 一致。
