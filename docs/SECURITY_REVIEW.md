# QentimArt 安全审查记录

- 日期：2026-11-17
- 范围：服务端（`internal/server`、`internal/storage`、`internal/wire`、`internal/manifest`、`internal/publish`）、命令行工具（`cmd/`）、Java SDK（`sdk/java`）
- 方式：人工源码审计

## 结论摘要

原有实现已经覆盖了主要的路径与数据完整性边界：清单路径白名单校验（`manifest.ValidatePath`）、块级 SHA-256 校验、临时文件原子替换与符号链接父目录拦截（`Syncer.ensureParent`）、版本不可覆盖、本地存储路径防穿越，以及自实现的 S3 SigV4 签名。

审查发现的主要隐患集中在 Java 客户端未限制远端响应与解压后的字节上限。在静态分发模式下，客户端直接拉取公开 URL，一旦分发源被篡改或遭遇中间人替换，恶意的压缩包或超大响应体会导致客户端内存耗尽（OOM）。服务端与 CLI 侧发现的几项问题风险较低，属于常规加固范畴。

以下 9 项问题均已在 2026-11-17 完成修复并补充单测：

| # | 等级 | 问题 | 涉及位置 | 状态 |
|---|------|------|----------|------|
| 1 | 中（静态模式偏高） | Java SDK 解压缺少输出字节上限（deflate 炸弹） | `sdk/java/.../Wire.java` | 已修复 |
| 2 | 中 | Java SDK 读取清单与块响应体未设大小上限 | `Wire.java`, `HttpTransport.java`, `StaticTransport.java` | 已修复 |
| 3 | 低~中 | Bearer 令牌直接用明文做 map 查找，非常量时间比较 | `internal/server/server.go` | 已修复 |
| 4 | 低 | 未配置令牌时默认放行所有请求并赋予 admin 权限 | `internal/server/server.go`, `config.go` | 已修复 |
| 5 | 低 | S3 存储允许非回环地址使用明文 HTTP | `internal/storage/s3.go` | 已修复 |
| 6 | 低 | 500 响应直接把内部错误原文返回给客户端 | `internal/server/server.go` | 已修复 |
| 7 | 低 | CLI 仅靠 `-token` 传参时令牌会暴露在系统进程列表中 | `cmd/qentimart/main.go` | 已修复 |
| 8 | 低 | Java 端 `validatePath` 过滤规则比 Go 服务端宽松 | `Wire.java` | 已修复 |
| 9 | 信息 | 持有通配权限的请求可令内存中的 `projects` 表无限增长 | `internal/server/project.go`, `config.go` | 已修复 |

---

## 问题详情与修复记录

### 1. Java SDK 解压缺少输出字节上限（中 / 静态模式偏高）

原 `sdk/java/src/main/java/qentimart/internal/Wire.java:102` 中的 `inflateRaw`：

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

`rawLen` 仅用作缓冲区初始容量提示，长度检查推迟到了整段数据解压完毕之后。几 KB 的高压缩比 deflate 数据即可膨胀到数 GB，触发客户端 OOM。相比之下，Go 端 `internal/wire/wire.go:123` 先校验 `rawLen <= MaxChunkSize`（16 MiB），再按固定长度缓冲执行 `io.ReadFull`，不存在该问题。静态模式下客户端从无鉴权的公开站点拉取数据块，`rawLen` 和压缩流完全由远端控制，风险尤为直接。

**修复情况**：`decodeChunk` 与 `readFrames` 在分配缓冲前先校验 `rawLen` 和 `stored` 不超过 `MAX_CHUNK_SIZE`（16 MiB）；`inflateRaw` 改为以 `rawLen` 作为硬上限直接解压到定长数组，仍有剩余未解压数据时立即中止并报 `ECORRUPT`。

### 2. Java SDK 对响应体无上限读入内存（中）

- 块拉取：`StaticTransport.getBuffer` 与 `HttpTransport.buffer` 直接调用 `readAllBytes()` 读取完整响应体
- 清单拉取：`HttpTransport.bufferResponse` 全量缓冲响应后再经 `Wire.gunzip`（内部同样调用 `GZIPInputStream.readAllBytes()`）解压，压缩态与解压态均无体积限制

Go 服务端解码清单时设有 `maxManifestBytes = 1<<30` 上限，而 Java 客户端缺少对应保护，异常或恶意的远端返回超大响应体或 gzip 炸弹时同样会导致 OOM。

**修复情况**：新增 `Wire.readCapped`，为块文件、压缩清单、`index.json`、版本列表 JSON 及错误响应体分别加上读取上限；`gunzip` 增加 `MAX_MANIFEST_RAW` 解压字节上限；原 `bufferResponse`（基于无上限的 `ofByteArray`）统一替换为带流式上限检查的下载逻辑。

### 3. Bearer 令牌比较非常量时间（低~中）

原 `internal/server/server.go:113` 的鉴权逻辑：

```go
if len(auth) <= len(prefix) || auth[:len(prefix)] != prefix { ... }
t, ok := s.tokens[auth[len(prefix):]]
```

直接拿明文 token 字符串作为 Go `map` 的键查找，比较耗时与前缀匹配程度有关，理论上存在时序侧信道。虽然 token 要求至少 16 字节随机字符、远程利用门槛较高，但改造成本很低。

**修复情况**：服务端初始化与鉴权查表时统一先对 token 计算 SHA-256（`tokenKey`），以定长哈希摘要作为键，避免逐字节比对明文令牌。

### 4. 未配置令牌时默认放行全部 admin 权限（低）

原 `internal/server/server.go:67` 在配置中没有任何 token 时仅打印一行日志警告，随后对所有请求授予 `admin` 权限（允许发布、删版本和触发 GC）。漏配鉴权字段时默认全开权限风险偏高。

**修复情况**：配置新增 `insecureNoAuth` 布尔字段；当 `tokens` 为空且未显式设置 `"insecureNoAuth": true` 时，`normalize` 直接报错拒绝启动。

### 5. S3 存储允许非回环地址使用明文 HTTP（低）

`internal/storage/s3.go:101` 发请求时请求体采用 `UNSIGNED-PAYLOAD`，若 `endpoint` 配成公网或跨网段的 `http://` 地址，对象内容既无传输层加密也无请求体签名校验，存在被窃听或中途篡改的风险。

**修复情况**：S3 配置新增 `allowInsecureHTTP` 开关；当 `endpoint` 为 `http://` 且目标主机不是本地回环地址（`localhost` / `127.0.0.1` / `::1`）时，未显式开启该开关会直接拒绝初始化。本机连 MinIO 调试仍可直接使用 HTTP。

### 6. 500 错误把内部报错原文回传客户端（低）

原 `internal/server/server.go:134` 的 `writeErr` 在遇到非 `httpError` 时统一按 500 处理，并把 `err.Error()` 直接写进响应 JSON，可能将底层磁盘路径、S3 bucket 或内网 endpoint 暴露给调用方。

**修复情况**：`writeErr` 改为 `*Server` 方法，内部未知错误只把完整信息写入服务端日志，对外统一返回 `internal server error`。

### 7. CLI `-token` 参数暴露在进程列表中（低）

`cmd/qentimart/main.go:91` 支持通过 `-token` 命令行参数传入令牌。在多用户机器或共享构建机上，其他用户可通过 `ps` 或 `/proc` 看到命令行参数。虽然程序原本就支持 `QENTIMART_TOKEN` 环境变量，但缺少文件读取方式。

**修复情况**：CLI 新增 `-token-file` 参数（优先级高于 `-token`），支持从受权限保护的文件读取令牌，并在 `-token` 的帮助说明中提示优先使用环境变量或文件。

### 8. Java 端 `validatePath` 校验严密程度弱于 Go 端（低）

原 `Wire.java:169` 的 `validatePath` 已经拦截了 `..`、`.`、空段、反斜杠、NUL、盘符、前导 `/` 和 `.qentimart` 目录，能够阻止目录穿越；但相比 Go 端 `manifest.ValidatePath`（`manifest.go:221`），少了路径总长限制、控制字符（`< 0x20`）、Windows 保留字符 `<>:"|?*`、Windows 保留设备名（`CON`、`NUL`、`AUX` 等）以及段尾空格或句点的拦截。作为客户端防范恶意清单的校验层，规则应与服务端严格对齐。

**修复情况**：Java 端 `validatePath` 已补齐 1024 长度限制、控制字符拦截、Windows 非法字符与保留设备名检查，以及路径段末尾句点和空格的拒绝逻辑，与 Go 实现保持一致。

### 9. 通配令牌可令内存中的 `projects` 映射无上限增长（信息）

原 `internal/server/project.go:48` 的 `getProject` 对任何符合命名正则的项目名都会创建常驻内存的状态实例。持有 `projects: ["*"]` 令牌的调用方若不断请求随机项目名，会持续消耗服务端内存。

**修复情况**：配置新增 `maxProjects` 上限（默认 `100000`），内存中的项目数达到上限后对未知新项目名返回 503，不再继续分配。

---

## 已有的安全边界

以下机制在本次审查中确认实现合理，后续迭代需注意保持：

- `manifest.ValidatePath` 在版本提交阶段强制校验跨平台相对路径合法性
- 数据块在服务端入库、客户端下载、客户端本地旧文件复用三个环节均做 SHA-256 校验
- `Syncer.ensureParent` 逐段创建目录并指定 `NOFOLLOW_LINKS`，防止利用本地预置符号链接将文件写到工作目录外
- 本地磁盘存储 `Local.file` 拦截 `..`、前导 `/` 与反斜杠
- 版本不可覆盖（冲突返回 409），且 `meta.json` 在所有校验通过后最后落盘
- 服务端已对清单请求体与解压大小、单次 fetch 块数、单次 missing 哈希数，以及 HTTP `ReadHeaderTimeout` / `MaxHeaderBytes` 设置上限
- S3 SigV4 实现正确覆盖了 `host`、`x-amz-*` 与时间头签名
- 仓库中的示例配置仅含 `CHANGE-ME-*` 占位符，`server.local.json` 已列入 `.gitignore`

## 回归测试与后续备注

针对上述 9 项修复补充的自动化测试：

- Go 单元测试：覆盖 `internal/wire`（解压超限与帧头长度越界）、`internal/server`（空令牌拒绝启动、令牌哈希鉴权、`maxProjects` 触发 503）、`internal/storage`（S3 非回环明文 HTTP 拦截）
- Java 测试：`sdk/java/tests/WireHardening.java`（deflate 炸弹拦截、`rawLen` 越界拒绝、`validatePath` 边界用例），已接入 `tests/e2e.mjs` 一并执行

后续可选改进：当前 S3 请求在启用 `allowInsecureHTTP` 走明文内网时请求体仍为 `UNSIGNED-PAYLOAD`，如果未来有在不受信内网走非 TLS 对象存储的需求，可为 `PUT` 请求补上实际 payload 的 SHA-256 签名。
