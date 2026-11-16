<div align="center">

# AssetSync

**面向游戏素材的高吞吐版本分发系统**

服务器只存差异，下游不留缓存，告别 git / git-lfs 仓库体积暴涨。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Go](https://img.shields.io/badge/Go-%E2%89%A51.23-00ADD8?logo=go&logoColor=white)
![Node.js](https://img.shields.io/badge/Node.js-%E2%89%A518-339933?logo=nodedotjs&logoColor=white)
![Python](https://img.shields.io/badge/Python-%E2%89%A53.8-3776AB?logo=python&logoColor=white)
![Dependencies](https://img.shields.io/badge/dependencies-0-brightgreen)

[快速开始](docs/QUICKSTART.md) · [架构设计](docs/ARCHITECTURE.md) · [协议说明](docs/PROTOCOL.md) · [Node.js SDK](sdk/nodejs/README.md) · [Python SDK](sdk/python/README.md)

</div>

---

## 为什么需要 AssetSync

用 git 管理游戏素材，`.git` 目录会为每个版本保存一份完整副本，越用越大；git-lfs 在本地还要再缓存一份，下游磁盘占用直接翻倍。二进制素材也没法 diff，改一个像素就要重新存储、重新传输整个文件。

AssetSync 换了一种做法：

- **服务器**把文件按内容切成块，相同的块只存一份。每个游戏版本只是一份清单，新版本只增加改动过的块。
- **下游**只保留素材本身和一个很小的元数据目录。更新时只下载变化的块，文件移动、大文件局部修改时直接复用本地已有的数据。

[更多](docs/ARCHITECTURE.md)

## 特性

- **服务器只存差异**：FastCDC 内容定义分块 + SHA-256 内容寻址去重，任意历史版本都能按需拼出来。
- **下游占用 ≤ 110%**：不保留对象缓存，实测磁盘占用为素材大小的 **100.04%**。
- **高吞吐**：批量块接口、多连接并行、可压缩数据自动 DEFLATE、本地块复用；拉取管线实测 **375 MiB/s**。
- **SemVer 版本管理**：默认拉取最新正式版，也能随时切换（升级或降级）到任意版本；版本不可变。
- **安全可靠**：每个块入库和落盘前都做 SHA-256 校验；文件原子替换；中途中断不会留下半个文件。
- **本地改动策略可配置**：`overwrite`（默认）/ `keep` / `fail`，首次同步不会误删未知文件。
- **多项目 + Token 鉴权**：`read` / `publish` / `admin` 三种角色，可限定到具体项目。
- **两种分发方案**：动态服务端（鉴权 + 批量拉取），或**零服务器静态分发**——把素材构建成静态树部署到 Cloudflare Pages / CDN / 对象存储，客户端只用一个公开 `index.json` 地址即可差异化更新，默认无需鉴权。
- **零第三方依赖**：Go 单二进制服务端，存储可选本地磁盘或 S3 兼容存储（AWS S3 / MinIO / OSS / COS / R2）；Node.js 与 Python SDK 均无外部依赖。

## 工作原理

```
┌──────────────────┐  publish   ┌──────────────────────┐   sync    ┌─────────────────────┐
│ CI / 发布机       │ ─────────▶ │ assetsync-server     │ ────────▶ │ 编辑器插件 / 工具    │
│ assetsync (客户端)│  只传新块   │ 本地磁盘 或 S3        │  只拉差异  │ Node.js / Python SDK │
└──────────────────┘            └──────────────────────┘           └─────────────────────┘
```

1. 发布端扫描素材目录，并行分块并计算哈希，只上传服务器还没有的块，最后提交该版本的清单。
2. 服务器按项目存储块和清单，提供批量拉取接口。
3. 下游 SDK 对比本地状态和目标版本，先复用本地已有的块，再从服务器并行拉取缺少的块，写入临时文件后原子替换。

详见 [架构设计](docs/ARCHITECTURE.md)。

## 快速上手

```bash
go build -o bin/ ./cmd/assetsync-server ./cmd/assetsync && bin/assetsync-server serve -config examples/server.json
```

```bash
bin/assetsync publish -server http://127.0.0.1:8080 -project mygame -token <publish token> -version 1.0.0 ./Assets
```

```js
import { AssetSyncClient } from '@assetsync/sdk';

const client = new AssetSyncClient({ server: 'http://127.0.0.1:8080', project: 'mygame', token });
await client.sync('./assets');
```

完整的部署、配置、发布与接入步骤见 **[快速开始](docs/QUICKSTART.md)**。

## 性能

Windows 11，12 核，服务端与客户端在同一台机器，3 GB / 1.26 万个文件：

| 场景 | 结果 |
|---|---|
| 发布 1.1.0（改 5% 小文件、大包中间插入数据、移动或重写模型） | 只上传 17.7 MiB，服务器只增长 18.3 MiB |
| 下游 1.0.0 → 1.1.0 增量更新 | 下载 17.7 MiB，本地复用 73.9 MiB，耗时不到 1 秒 |
| 下游磁盘占用 | 素材大小的 100.04%（要求 ≤ 110%） |
| 拉取管线（网络 + 解压 + 校验，不写盘） | 375 MiB/s |
| 全新同步（含写盘） | 145–250 MiB/s，受限于测试机磁盘写入速度 |

## 文档

| 文档 | 内容 |
|---|---|
| [快速开始](docs/QUICKSTART.md) | 编译部署、配置项、发布版本、下游接入、运维命令、运行测试 |
| [架构设计](docs/ARCHITECTURE.md) | 为什么不用 git、数据流、吞吐设计、110% 存储约束、一致性 |
| [协议说明](docs/PROTOCOL.md) | HTTP 接口、块帧格式、清单格式、本地状态格式 |
| [Node.js SDK](sdk/nodejs/README.md) | API、同步选项、命令行、插件接入示例 |
| [Python SDK](sdk/python/README.md) | API、同步参数、命令行、示例 |

## 项目结构

```
cmd/assetsync-server/  服务端（serve、gen-token）
cmd/assetsync/         客户端（publish、versions、diff、gc 等）
internal/              分块、清单、存储（本地 / S3）、HTTP 服务、发布流程
sdk/nodejs/            Node.js SDK、命令行、插件接入示例
sdk/python/            Python SDK、命令行、示例
docs/                  快速开始、架构设计、协议说明
examples/              服务端配置示例
tests/                 端到端测试与吞吐基准
```

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请确认测试通过：

```bash
go test ./...
```

```bash
node tests/e2e.mjs
```

代码注释、文档请使用简体中文；新增协议字段或修改本地状态格式时，请同步更新 [协议说明](docs/PROTOCOL.md) 以及 Node.js、Python 两个 SDK。

## 开源许可

本项目基于 [MIT License](LICENSE) 开源。
