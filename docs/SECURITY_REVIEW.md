# QentimArt 安全审查报告

- 审查日期：2026-11-17
- 范围：服务端（`internal/server`、`internal/storage`、`internal/wire`、`internal/manifest`、`internal/publish`）、CLI（`cmd/`）、Java 下游 SDK（`sdk/java`）
- 方法：人工源码审计（无动态测试 / 无依赖 CVE 扫描）

## 总体结论

整体架构在安全上有不少正确的设计：清单路径强校验（`manifest.ValidatePath`）、按哈希校验每个块、原子写入 + 符号链接逃逸防护（`Syncer.ensureParent`）、版本不可变、S3 SigV4 自实现签名、本地存储 key 的 `..` / 绝对路径拦截。

主要风险集中在**下游客户端（Java SDK）对“服务端 / 分发源返回的数据”缺乏大小上限**——在静态模式（从公开、无鉴权 URL 拉取）这一官方支持的部署形态下，一个被攻陷或恶意的分发源可以让客户端内存耗尽。服务端侧的问题相对次要，多为加固项。

| # | 等级 | 问题 | 位置 |
|---|------|------|------|
| 1 | 中（静态模式下偏高）✅ 已修复 | Java SDK 解压无输出上限（deflate 炸弹） | `sdk/java/.../Wire.java` |
| 2 | 中 ✅ 已修复 | Java SDK 对 manifest / 块响应体整体缓冲，无大小上限 | `Wire.java`, `HttpTransport.java`, `StaticTransport.java` |
| 3 | 低~中 ✅ 已修复 | Bearer 令牌比较非恒定时间（时序侧信道） | `internal/server/server.go` |
| 4 | 低 ✅ 已修复 | 未配置令牌时默认对所有人开放 admin | `internal/server/server.go`, `config.go` |
| 5 | 低 | S3 允许明文 HTTP + `UNSIGNED-PAYLOAD` | `internal/storage/s3.go:101` |
| 6 | 低 ✅ 已修复 | 500 错误把内部错误原文回传客户端 | `internal/server/server.go` |
| 7 | 低 | CLI 用 `-token` 传令牌会泄漏到进程列表 | `cmd/qentimart/main.go:91` |
| 8 | 低 | Java `validatePath` 弱于 Go 版（控制字符 / Windows 保留名） | `Wire.java:169` |
| 9 | 信息 | 已鉴权请求可无限增长 `projects` map | `internal/server/project.go:48` |

---

## 详细说明

> **状态：已修复（2026-11-17）。** `inflateRaw` 改为以 `rawLen` 为硬上限直接解压进固定缓冲，超出即报 `ECORRUPT`；`decodeChunk` / `readFrames` 在分配内存前校验 `rawLen`、`stored` 不超过 `MAX_CHUNK_SIZE`。

### 1.（中 / 静态模式偏高）Java SDK 解压无输出上限 —— deflate 炸弹

`sdk/java/src/main/java/qentimart/internal/Wire.java:102` `inflateRaw`：

```java
ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(rawLenHint, 64));
while (!inf.finished()) {
    int n = inf.inflate(tmp);
    ...
    out.write(tmp, 0, n);      // 没有任何输出上限
}
...
// 长度/哈希校验发生在“完全解压之后”
if (raw.length != rawLen || !sha256Hex(raw).equals(hashHex)) ...
```

`rawLen` 只被当作初始缓冲区大小提示（hint），并未作为解压输出的硬上限；真正的长度校验在整段数据解压完成之后才执行。一个几 KB 的 deflate payload 可以膨胀到数 GB，直接把客户端打到 OOM。

对比：Go 实现是安全的——`internal/wire/wire.go:123` 先 `make([]byte, rawLen)` 再 `io.ReadFull`，且 `rawLen` 在 `ReadFrameHeader` 里被限制为 `MaxChunkSize`（16 MiB）。Java 侧没有对等防护。

**威胁模型为何成立**：静态模式（`StaticTransport`）按设计从**公开 baseUrl、无鉴权**拉取块对象（`StaticTransport.java:87` → `Wire.decodeBlob` → `inflateRaw`），`rawLen` 完全由分发源控制。被攻陷的 CDN / 对象存储 / 中间人即可触发。动态模式下若连接到恶意服务端亦然。

**建议**：
- 在 `inflateRaw` 中以 `rawLen`（且 `rawLen <= MaxChunkSize`，即 16 MiB）为硬上限；写入超过上限立即中止并报 `ECORRUPT`。
- 解压前先校验 `rawLen <= 16 MiB`。

> **状态：已修复（2026-11-17）。** 新增 `Wire.readCapped`，所有远端响应体（块 blob、manifest gz、index.json / 版本目录 JSON、错误体）均按上限读取；`gunzip` 增加解压上限 `MAX_MANIFEST_RAW`；`bufferResponse`（原用 `ofByteArray` 无上限缓冲）替换为带上限的 `download`。

### 2.（中）Java SDK 对响应体整体缓冲，无大小上限

- 块下载：`StaticTransport.getBuffer` / `HttpTransport.buffer` 用 `readAllBytes()` 把整个响应读进内存，无上限。
- manifest：`HttpTransport.bufferResponse` 读全量字节后 `Wire.gunzip`（`Wire.java:277`，`GZIPInputStream.readAllBytes()`）同样无上限。

服务端对 manifest 解压有 `maxManifestBytes = 1<<30` 限制（`server.go:27`、`manifest.Decode` 的 `LimitReader`），但 **Java 客户端没有对等限制**。恶意 / 被攻陷的服务端或静态源可返回超大（或 gzip 炸弹）manifest，使客户端 OOM。

**建议**：对下载的 wire blob 和 manifest（压缩态与解压态）都设置合理上限，超限即报错。与 #1 属同一类“客户端无条件信任远端数据体积”的问题。

> **状态：已修复（2026-11-17）。** 令牌改为以 SHA-256 作为查表键（`tokenKey`），`New` 填表与 `allowed` 查表均先哈希，查表不再逐字节比较明文令牌，消除时序旁路。

### 3.（低~中）Bearer 令牌比较非恒定时间

`internal/server/server.go:113` `allowed`：

```go
if len(auth) <= len(prefix) || auth[:len(prefix)] != prefix { ... }
t, ok := s.tokens[auth[len(prefix):]]   // map 查找，非恒定时间
```

令牌直接作为 map key 查找，比较耗时与命中/未命中及前缀匹配长度相关，理论上可被远程时序分析逐步推断令牌。鉴于令牌随机且长度 ≥16，实际利用较难，但属标准加固项。

**建议**：对令牌做哈希（如 SHA-256）后再入表并比较，或用 `crypto/subtle.ConstantTimeCompare` 与候选集比对。

> **状态：已修复（2026-11-17）。** 新增 `insecureNoAuth` 配置项；无令牌且未显式设为 `true` 时 `normalize` 直接拒绝启动（不再静默 fail-open）。

### 4.（低）未配置令牌时默认对所有人放行 admin

`internal/server/server.go:67`：无令牌配置时打印 warning 并对**每个请求**授予 admin。虽有日志告警，但“fail-open”默认对于一个可写、可删除、可 GC 的资产仓库是危险默认值。

**建议**：至少提供显式开关（如 `insecureNoAuth: true`）才允许无令牌启动，否则拒绝启动。

### 5.（低）S3 允许明文 HTTP + `UNSIGNED-PAYLOAD`

`internal/storage/s3.go:101` 对所有请求使用 `UNSIGNED-PAYLOAD`，且 `endpoint` 允许 `http://`。此时对象数据明文传输、且请求体不被签名保护，存在中间人窜改 / 窃听风险（凭据本身仍受 SigV4 头签名保护）。

**建议**：生产环境强制 `https://`；或在使用 `http://` 时打印显式告警；视后端能力考虑对 PUT 签名真实 payload 哈希。

> **状态：已修复（2026-11-17）。** `writeErr` 改为 `*Server` 方法；非 `httpError`（即 500）只把详情写日志，对外返回通用 `internal server error`。

### 6.（低）500 错误回传内部错误原文

`internal/server/server.go:134` `writeErr` 对非 `httpError` 一律按 500 处理并把 `err.Error()` 原文写入响应体。存储层错误可能包含 S3 endpoint、bucket、key、内部路径等信息，造成信息泄露。

**建议**：500 对外返回通用文案，详细错误只写服务端日志。

### 7.（低）CLI `-token` 泄漏到进程列表

`cmd/qentimart/main.go:91` 支持 `-token` 命令行传参。命令行参数在多数系统上对同机其他用户可见（`ps`、`/proc`）。已同时支持 `QENTIMART_TOKEN` 环境变量（较优）。

**建议**：文档中优先推荐环境变量 / 配置文件；或支持从文件 / stdin 读取令牌。

### 8.（低）Java `validatePath` 弱于 Go 版

`Wire.java:169` 的 `validatePath` 拦截了 `..`、`.`、空段、`\`、NUL、盘符、首段 `.qentimart`、绝对路径（首段为空），已能防住路径穿越。但相比 `manifest.ValidatePath`（Go，`manifest.go:221`）缺少：控制字符（NUL 以外）、Windows 保留字符 `<>:"|?*`、Windows 设备名（CON/NUL/AUX…）、段尾 `.`/空格 的拦截。

在“对接合法服务端”场景下服务端已严格校验，但该客户端校验正是为“恶意服务端 / 静态源”准备的第二道防线，理应与服务端一致。非穿越漏洞，属健壮性。

**建议**：使 Java 侧校验与 Go 侧 `ValidatePath` 对齐。

### 9.（信息）已鉴权请求可无限增长 `projects` map

`internal/server/project.go:48` `getProject` 对任意通过正则（`^[a-z0-9][a-z0-9._-]{0,63}$`）的项目名创建并常驻 map 条目。持有 `*` 范围令牌者可用大量不同项目名使 `s.projects` 无界增长（内存）。需有效令牌，影响有限。

**建议**：如有需要，对项目数量设上限或做 LRU/淘汰。

---

## 做得好的地方（供参考，勿回退）

- `manifest.ValidatePath`：严格的可移植相对路径校验，服务端提交时强制执行。
- 每个块按 SHA-256 校验（上传、下载、本地复用三处均校验）。
- `Syncer.ensureParent`：逐段创建父目录、`NOFOLLOW_LINKS`，防止通过已存在符号链接把文件写到 root 之外。
- 本地存储 `Local.file`：拦截 `..`、前导 `/`、`\`。
- 版本不可变（提交冲突返回 409），`meta.json` 最后写入作为提交完成标记。
- 服务端对 manifest 上传 / 解压、fetch 块数、missing 请求数均设上限；`ReadHeaderTimeout` / `MaxHeaderBytes` 已设置。
- 自实现 S3 SigV4 对 host、`x-amz-*`、date 正确签名。
- 示例配置使用 `CHANGE-ME-*` 占位令牌，`.gitignore` 排除 `server.local.json`。

## 优先处理建议

1. ~~先修 #1、#2（Java SDK 下载 / 解压上限）~~ ✅ 已完成。
2. ~~再做 #3、#4、#6 的服务端加固~~ ✅ 已完成。
3. 剩余 #5（S3 明文 HTTP）、#7（CLI `-token`）、#8（Java 路径校验对齐）、#9（项目 map 上限）为文档 / 健壮性改进，可按需跟进。
