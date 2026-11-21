# QentimArt 安全审查记录

## 2026-11-22 项目复查

覆盖 Go 服务端、CLI、本地和 S3 存储，以及 Node.js、Python、Java SDK 的动态和静态同步。

| 修复范围 | 结果 |
|---|---|
| 鉴权和部署 | 私有制品响应使用 `private, no-store`。服务端拒绝示例令牌与重复令牌，示例部署默认绑定回环地址。 |
| 存储和并发 | 本地后端通过 Go 的 `os.Root` 约束对象访问范围。版本列表使用独立快照，避免发布或删除与查询并发时读写同一张表。发布客户端限制跨源重定向，S3 客户端禁用重定向。 |
| 数据校验 | 对响应体、压缩数据和单块解压设置字节上限，校验清单路径、版本、哈希和块大小。三个 SDK 在安装多块文件前核对完整文件的 SHA-256。 |
| 本地同步 | 校验状态目录和目标路径，限制清理范围。文件与目录互换时保留未授权删除的本地文件，来源不同的同版本清单分别处理。 |
| 构建工具链 | Go 编译基线升至 1.27.4，Docker 运行镜像升至 Alpine 3.24，Java Gradle Wrapper 升至 9.9.2 并固定发行包 SHA-256。Go 1.27.4 的 `govulncheck` 复扫未发现漏洞。 |

[Go 发布版本](https://go.dev/dl/) · [Gradle 安全公告](https://github.com/gradle/gradle/security/advisories) · [Gradle 官方校验和](https://gradle.org/release-checksums/)

旧版曾通过 CDN 提供私有资源的部署，升级时需清理历史缓存并让受保护的 API 路径回源鉴权。详见 [CDN 缓存配置](CDN_CACHE.md#动态模式qentimart-server)。

SDK 路径检查覆盖预置链接。允许其他本地进程同时写入素材目录时，路径检查与文件操作之间仍存在竞态。静态发布应由可信来源通过 HTTPS 提供。

## 2026-11-17 初次审查

- 日期：2026-11-17
- 范围：服务端（`internal/server`、`internal/storage`、`internal/wire`、`internal/manifest`、`internal/publish`）、命令行工具（`cmd/`）、Java SDK（`sdk/java`）
- 方式：人工源码审计

## 摘要

现有的主要边界基本都在：清单路径白名单校验（`manifest.ValidatePath`）、块级 SHA-256 校验、临时文件原子替换和符号链接父目录拦截（`Syncer.ensureParent`）、版本不可覆盖、本地存储路径防穿越，以及 S3 SigV4 签名。

这次查出来的主要问题是 Java 客户端没有给远端响应和解压输出设上限。静态模式下客户端直接拉公开 URL，一旦源站被篡改或遭遇中间人，恶意压缩包或超大响应体能直接把客户端打 OOM。服务端和 CLI 侧的几处风险相对低一些，顺手一并修了。

下面 9 项都在 2026-11-17 修复并补了单测：

| # | 等级 | 问题 | 位置 | 状态 |
|---|---|---|---|---|
| 1 | 中（静态模式偏高） | Java SDK 解压没设输出字节上限（deflate 炸弹） | `sdk/java/.../Wire.java` | 已修复 |
| 2 | 中 | Java SDK 读清单和块响应体没设大小上限 | `Wire.java`, `HttpTransport.java`, `StaticTransport.java` | 已修复 |
| 3 | 低~中 | Bearer token 直接用明文做 map 查找，非常量时间比较 | `internal/server/server.go` | 已修复 |
| 4 | 低 | 没配 token 时默认放行所有请求并给 admin 权限 | `internal/server/server.go`, `config.go` | 已修复 |
| 5 | 低 | S3 存储允许非回环地址走明文 HTTP | `internal/storage/s3.go` | 已修复 |
| 6 | 低 | 500 响应直接把内部错误原文返回给客户端 | `internal/server/server.go` | 已修复 |
| 7 | 低 | CLI 用 `-token` 传参时令牌会暴露在进程列表里 | `cmd/qentimart/main.go` | 已修复 |
| 8 | 低 | Java 端 `validatePath` 比 Go 服务端宽松 | `Wire.java` | 已修复 |
| 9 | 信息 | 通配权限请求能让内存 `projects` 表无限增长 | `internal/server/project.go`, `config.go` | 已修复 |

## 详情与修复

### 1. Java SDK 解压缺少输出字节上限（中 / 静态模式偏高）

原 `sdk/java/src/main/java/qentimart/internal/Wire.java:102` 的 `inflateRaw`：

```java
ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(rawLenHint, 64));
while (!inf.finished()) {
    int n = inf.inflate(tmp);
    ...
    out.write(tmp, 0, n);      // 未限制输出总字节数
}
...
// 解压全部结束后才比对长度与哈希
if (raw.length != rawLen || !sha256Hex(raw).equals(hashHex)) ...
```

`rawLen` 只拿来当初始缓冲容量，真正检查长度要等整段解压完。几 KB 的高压缩比 deflate 流就能膨到几个 GB 直接 OOM。Go 端 `internal/wire/wire.go:123` 会先查 `rawLen <= MaxChunkSize`（16 MiB）再定长 `io.ReadFull`，没有这个问题。静态模式下客户端拉无鉴权的公开站点，`rawLen` 和压缩流完全受远端控制，风险更直接。

修复：`decodeChunk` 和 `readFrames` 分配缓冲前先校验 `rawLen` 和 `stored` 不超过 `MAX_CHUNK_SIZE`（16 MiB）；`inflateRaw` 以 `rawLen` 为硬上限直接解压进定长数组，超出立刻中止报 `ECORRUPT`。

### 2. Java SDK 响应体无上限读入内存（中）

- 块拉取：`StaticTransport.getBuffer` 和 `HttpTransport.buffer` 直接调 `readAllBytes()` 读完整响应体。
- 清单拉取：`HttpTransport.bufferResponse` 全量读入后再交给 `Wire.gunzip`（里面也是 `GZIPInputStream.readAllBytes()`），压缩前后都没设上限。

Go 服务端解清单有 `maxManifestBytes = 1<<30` 限制，Java 端缺了对应保护，碰到超大响应体或 gzip 炸弹同样会 OOM。

修复：新增 `Wire.readCapped`，给块文件、压缩清单、`index.json`、版本列表 JSON 和错误响应体分别加上读取上限；`gunzip` 加 `MAX_MANIFEST_RAW` 解压上限；原 `bufferResponse`（基于无上限 `ofByteArray`）换成带流式上限检查的下载逻辑。

### 3. Bearer token 非常量时间比较（低~中）

原 `internal/server/server.go:113`：

```go
if len(auth) <= len(prefix) || auth[:len(prefix)] != prefix { ... }
t, ok := s.tokens[auth[len(prefix):]]
```

直接拿明文 token 当 Go `map` 的键查表，理论上存在时序侧信道。虽然 token 要求至少 16 字节随机串、远程利用门槛高，但改起来成本很低。

修复：服务端初始化和鉴权查表都先对 token 算 SHA-256（`tokenKey`），用定长哈希做键，不再逐字节比对明文。

### 4. 未配 token 时默认开放全部 admin 权限（低）

原 `internal/server/server.go:67` 在没配任何 token 时只打一条警告日志，然后对所有请求给 `admin` 权限（能发版、删版本、跑 GC），漏配鉴权容易出事。

修复：配置加了 `insecureNoAuth` 开关；`tokens` 为空且没显式写 `"insecureNoAuth": true` 时，`normalize` 直接报错拒绝启动。

### 5. S3 存储允许非回环地址走明文 HTTP（低）

`internal/storage/s3.go:101` 请求体用的是 `UNSIGNED-PAYLOAD`，`endpoint` 配成公网或跨网段 `http://` 时既没 TLS 也没请求体签名，容易被窃听或篡改。

修复：S3 配置加了 `allowInsecureHTTP`；`endpoint` 为 `http://` 且不是本机回环（`localhost` / `127.0.0.1` / `::1`）时，未显式开启则拒绝初始化。本机连 MinIO 调试不受影响。

### 6. 500 错误把内部报错原文回传客户端（低）

原 `internal/server/server.go:134` 的 `writeErr` 碰到非 `httpError` 直接把 `err.Error()` 写进 500 JSON，可能把磁盘路径、S3 bucket 或内网 endpoint 漏给调用方。

修复：`writeErr` 改成 `*Server` 方法，未知内部错误只记服务端日志，对外统一返回 `internal server error`。

### 7. CLI `-token` 参数暴露在进程列表里（低）

`cmd/qentimart/main.go:91` 支持 `-token` 命令行传参，在多用户机器或共享构建机上别人能通过 `ps` / `/proc` 看到。虽然本来就支持 `QENTIMART_TOKEN` 环境变量，但少了读文件的选项。

修复：新增 `-token-file`（优先级高于 `-token`），支持从文件读令牌，并在 `-token` 帮助信息里提示优先用环境变量或文件。

### 8. Java 端 `validatePath` 比 Go 服务端宽松（低）

原 `Wire.java:169` 的 `validatePath` 拦了 `..`、`.`、空段、反斜杠、NUL、盘符、前导 `/` 和 `.qentimart`，能防目录穿越；但对照 Go 端 `manifest.ValidatePath`（`manifest.go:221`），少了 1024 字节长度限制、控制字符（`< 0x20`）、Windows 保留字符 `<>:"|?*`、Windows 保留设备名（`CON`、`NUL`、`AUX` 等）以及段尾空格/句点拦截。

修复：Java 端 `validatePath` 补齐了上述所有规则，和 Go 端保持一致。

### 9. 通配令牌能让内存 `projects` 表无限增长（信息）

原 `internal/server/project.go:48` 的 `getProject` 对任何合法项目名都会创建常驻内存的状态实例，拿 `projects: ["*"]` 令牌不断刷随机项目名会持续吃内存。

修复：配置新增 `maxProjects`（默认 `100000`），到上限后对未知新项目名返回 503。

## 已有边界备忘

审查中确认这几块没问题，后面改代码别破坏：

- `manifest.ValidatePath` 在版本提交时校验跨平台相对路径
- 服务端入库、客户端下载、客户端复用本地旧文件三个地方都做 SHA-256 校验
- `Syncer.ensureParent` 逐段建目录并带 `NOFOLLOW_LINKS`，防预置软链写到工作目录外
- 本地存储 `Local.file` 拦截 `..`、前导 `/` 和反斜杠
- 版本不可覆盖（409），`meta.json` 在所有校验通过后最后写盘
- 服务端对清单请求体与解压大小、单次 fetch 块数、单次 missing 哈希数，以及 HTTP `ReadHeaderTimeout` / `MaxHeaderBytes` 都设了上限
- S3 SigV4 正确覆盖了 `host`、`x-amz-*` 和时间头签名
- 示例配置只留 `CHANGE-ME-*` 占位符，`server.local.json` 已进 `.gitignore`

## 回归测试与后续

针对上面 9 项补了测试：

- Go 单测：`internal/wire`（解压超限、帧头越界）、`internal/server`（空令牌拒启、哈希鉴权、`maxProjects` 返 503）、`internal/storage`（S3 非回环明文 HTTP 拦截）
- Java 测试：`sdk/java/tests/WireHardening.java`（deflate 炸弹、`rawLen` 越界、`validatePath` 边界用例），已挂进 `tests/e2e.mjs`

后续如果要在不受信内网开 `allowInsecureHTTP` 跑明文 S3，可以给 `PUT` 请求补上实际 payload 的 SHA-256 签名（目前还是 `UNSIGNED-PAYLOAD`）。
