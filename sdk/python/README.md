# qentimart-sdk (Python)

QentimArt 的 Python 下游客户端，要求 Python ≥ 3.8，默认无第三方依赖。本地 `.qentimart/` 状态格式与 [Node.js SDK](../nodejs/README.md)、[Java SDK](../java/README.md) 通用，几个 SDK 可以混用同一个素材目录。

同步时优先从本地旧文件里复用还能用上的块，只多线程并发拉取缺失块，逐块校验 SHA-256 后通过临时文件 rename 替换；本地不留块缓存，同步后目录里只有素材文件和 `.qentimart/` 状态清单（实测总占用约 100.04%）。

## 安装

```bash
pip install qentimart-sdk
```

从本仓库源码安装：

```bash
pip install ./sdk/python
```

## 快速开始

```python
from qentimart import QentimArtClient

with QentimArtClient("http://assets.internal:8080", "mygame", token="<read token>") as client:
    r = client.sync("./assets", on_progress=lambda p: print(p["phase"], p["done"], p["total"]))
    print(r["version"], r["storage"]["ratio"])        # 例如 1.2.0 1.0004
    client.sync("./assets", version="1.0.0")          # 切到指定版本
```

更多用法见 [examples/plugin_example.py](examples/plugin_example.py)。

## 静态模式

素材通过静态模式托管在对象存储、CDN 或 Cloudflare Pages 上时，只传 `base_url` 即可，不需要 `server` 和 `token`：

```python
from qentimart import QentimArtClient

with QentimArtClient(base_url="https://mygame.pages.dev") as client:
    client.sync("./assets")
    client.sync("./assets", version="1.0.0")
```

`sync`、`plan`、`status`、`verify`、`diff`、`storage_report` 用法与连接服务端时相同。

如果静态源发送 `Content-Encoding: br`，请安装支持限制解压输出的 `Brotli`（1.2.0 或更新版本）。未压缩、gzip 和 deflate 响应由标准库处理。

## API

| 方法 | 说明 |
|---|---|
| `QentimArtClient(server, project, token=None, concurrency=8)` | 连接服务端（支持 `with` 自动关闭连接） |
| `QentimArtClient(base_url=..., concurrency=8)` | 连接静态托管源（只读） |
| `list_versions()` / `get_version(v="latest")` / `diff(from_, to)` | 查询版本列表、单版本元数据和版本间差异 |
| `sync(dir, **opts)` | 同步目录到目标版本（默认最新正式版） |
| `plan(dir, **opts)` | 只预估要更新/删除的文件和下载量，不动磁盘 |
| `status(dir)` | 离线检查本地被改动、缺失和多出的文件 |
| `verify(dir)` | 重算全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storage_report(dir)` | 返回磁盘占用统计 `{"asset_bytes", "overhead_bytes", "ratio"}` |

### `sync` 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本号 |
| `local_changes` | `"overwrite"` | `overwrite` 把受管文件还原为服务端版本；`keep` 保留本地改动和新增文件；`fail` 遇到冲突抛 `SyncConflictError`，不动磁盘 |
| `delete_untracked` | `False` | 是否删除未跟踪文件（既不在目标版本里、也不在之前清单里的本地文件）。默认即使 `overwrite` 也不删，方便只同步目录里一部分文件；设为 `True` 才在 `overwrite` 下一并清理。清单里被删掉的受管文件始终正常清理 |
| `delete_untracked_on_first_sync` | `False` | 目录首次同步（还没有 `.qentimart`）时，即使开了 `delete_untracked=True` 也默认保留未跟踪文件，防止传错路径误删整个目录；显式设为 `True` 才在首次同步时清理 |
| `prerelease` | `False` | 允许 `latest` 匹配预发布版 |
| `verify_all` | `False` | 跳过 `size + mtime` 快速检查，对所有本地文件重算哈希 |
| `verify_paths` | `None` | 对指定相对路径列表强制算哈希（以 `/` 结尾表示目录前缀），其余文件仍走快速检查 |
| `on_progress` | `None` | 进度回调，接收字典 `{"phase", "done", "total", "downloaded_bytes", "bytes_per_second"}` |
| `cancel_event` | `None` | 传入 `threading.Event`，外部调用 `set()` 可中途取消同步 |

## 命令行

```bash
qentimart-py sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
qentimart-py status --dir ./assets --server http://127.0.0.1:8080 --project mygame
qentimart-py report --dir ./assets
```
