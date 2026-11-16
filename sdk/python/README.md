# qentimart-sdk（Python）

QentimArt 下游 SDK 的 Python 版本，只用标准库，支持 Python ≥ 3.8。功能、同步算法和本地状态格式都与 [Node.js SDK](../nodejs/README.md) 一致，两个 SDK 可以互相接管同一个目录。

- 块级增量更新，文件移动、大文件局部修改时优先复用本地数据
- 不保留本地对象缓存，稳态磁盘占用 ≈ 素材大小 + 极小的 `.qentimart/` 元数据（实测约 100.04%）
- 多线程批量拉取，每个线程保持一条长连接；每个块都做 SHA-256 校验，文件原子替换

## 安装

```bash
pip install ./sdk/python
```

## 快速开始

```python
from qentimart import QentimArtClient

with QentimArtClient("http://assets.internal:8080", "mygame", token="<read token>") as client:
    r = client.sync("./assets", on_progress=lambda p: print(p["phase"], p["done"], p["total"]))
    print(r["version"], r["storage"]["ratio"])        # 例如 1.2.0 1.0004
    client.sync("./assets", version="1.0.0")           # 切换（降级）到指定版本
```

完整示例见 [examples/plugin_example.py](examples/plugin_example.py)。

## 静态模式（无需服务端）

素材发布成静态树托管在对象存储 / CDN / Cloudflare Pages 上时，只需传 `base_url`，不需要 server/token，全程只读：

```python
from qentimart import QentimArtClient
with QentimArtClient(base_url="https://mygame.pages.dev") as client:
    client.sync("./assets")
    client.sync("./assets", version="1.0.0")
```

`sync` / `plan` / `status` / `verify` / `diff` / `storage_report` 用法与动态模式一致。

## API

| 方法 | 说明 |
|---|---|
| `QentimArtClient(server, project, token=None, concurrency=8)` | 创建动态客户端（支持 `with`） |
| `QentimArtClient(base_url=..., concurrency=8)` | 创建静态客户端（只读，无需鉴权） |
| `list_versions()` / `get_version(v="latest")` / `diff(from_, to)` | 查询服务器上的版本 |
| `sync(dir, **opts)` | 同步到指定版本（默认最新版） |
| `plan(dir, **opts)` | 预演，返回预计写入/删除的文件和下载量 |
| `status(dir)` | 离线检查本地的修改、缺失和多出的文件 |
| `verify(dir)` | 重新计算所有文件的哈希，检测静默损坏 |
| `QentimArtClient.storage_report(dir)` | `{"asset_bytes", "overhead_bytes", "ratio"}` |

### `sync` 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本 |
| `local_changes` | `"overwrite"` | `overwrite` 还原为服务器版本并删除多余文件；`keep` 保留本地改动；`fail` 发现冲突时抛出 `SyncConflictError`，不做任何修改 |
| `delete_untracked_on_first_sync` | `False` | 安全开关：从未同步过的目录不会删除不属于目标版本的文件 |
| `prerelease` | `False` | 允许 `latest` 解析到预发布版本 |
| `verify_all` | `False` | 对所有本地文件计算哈希，不信任 size + mtime |
| `verify_paths` | `None` | 对指定的重要文件强制哈希校验（即使 size+mtime 未变），普通文件仍走快速检测。每项为相对路径，以 `/` 结尾表示目录前缀 |
| `on_progress` | `None` | 回调，参数为 `{"phase", "done", "total", "downloaded_bytes", "bytes_per_second"}` |
| `cancel_event` | `None` | `threading.Event`，调用 `set()` 即可取消同步 |

## 命令行

```bash
qentimart-py sync --server http://127.0.0.1:8080 --project mygame --token $TOKEN --dir ./assets
```

```bash
qentimart-py status --dir ./assets --server http://127.0.0.1:8080 --project mygame
```

```bash
qentimart-py report --dir ./assets
```
