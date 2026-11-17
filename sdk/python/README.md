# qentimart-sdk（Python）

QentimArt 的 Python 下游客户端，仅使用 Python 标准库，要求 Python ≥ 3.8。本地 `.qentimart/` 状态目录格式与 [Node.js SDK](../nodejs/README.md)、[Java SDK](../java/README.md) 通用，几个 SDK 可以读写同一个素材目录。

- 按数据块增量更新，文件移动或大文件局部修改时直接从本地旧文件复用数据块
- 不保留本地块缓存，同步完成后磁盘上只有业务素材和 `.qentimart/` 元数据（实测总占用约为素材体积的 100.04%）
- 多线程复用 HTTP 长连接并发拉取，落盘前逐块校验 SHA-256，写完临时文件后原子替换

## 安装

已发布到 PyPI：[qentimart-sdk](https://pypi.org/project/qentimart-sdk/)

```bash
pip install qentimart-sdk
```

也可以从仓库源码安装：

```bash
pip install ./sdk/python
```

## 快速开始

```python
from qentimart import QentimArtClient

with QentimArtClient("http://assets.internal:8080", "mygame", token="<read token>") as client:
    r = client.sync("./assets", on_progress=lambda p: print(p["phase"], p["done"], p["total"]))
    print(r["version"], r["storage"]["ratio"])        # 例如 1.2.0 1.0004
    client.sync("./assets", version="1.0.0")           # 切换到指定版本
```

更多用法见 [examples/plugin_example.py](examples/plugin_example.py)

## 静态模式

当素材通过静态模式托管在对象存储、CDN 或 Cloudflare Pages 上时，传入站点地址 `base_url` 即可，不需要 `server` 和 `token`：

```python
from qentimart import QentimArtClient

with QentimArtClient(base_url="https://mygame.pages.dev") as client:
    client.sync("./assets")
    client.sync("./assets", version="1.0.0")
```

`sync`、`plan`、`status`、`verify`、`diff`、`storage_report` 的调用方式与连接服务端时相同

## API

| 方法 | 说明 |
|---|---|
| `QentimArtClient(server, project, token=None, concurrency=8)` | 创建连接服务端的客户端（支持 `with` 自动关闭连接） |
| `QentimArtClient(base_url=..., concurrency=8)` | 创建连接静态托管源的只读客户端 |
| `list_versions()` / `get_version(v="latest")` / `diff(from_, to)` | 查询版本列表、单版本元数据与版本间差异 |
| `sync(dir, **opts)` | 将目录同步到目标版本（默认最新正式版） |
| `plan(dir, **opts)` | 仅预估待更新、删除的文件列表及网络下载量，不改写磁盘 |
| `status(dir)` | 离线检查本地被修改、缺失以及多出的文件 |
| `verify(dir)` | 重新计算全部受管文件的 SHA-256，排查文件损坏 |
| `QentimArtClient.storage_report(dir)` | 返回磁盘占用统计 `{"asset_bytes", "overhead_bytes", "ratio"}` |

### `sync` 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `version` | `"latest"` | 目标版本号 |
| `local_changes` | `"overwrite"` | `overwrite` 把受管文件还原为服务端版本；`keep` 保留本地改动与新增文件；`fail` 遇到冲突抛出 `SyncConflictError`，不修改磁盘 |
| `delete_untracked` | `False` | 是否删除「未跟踪」文件（本地存在、不在目标版本、也从不受 SDK 管理的文件）。默认即便 `overwrite` 也绝不删除这类文件，适合「只同步某子树、其余本地文件保留」的场景；设为 `True` 才在 `overwrite` 下一并清理。清单内「被移除的受管文件」始终照常清理 |
| `delete_untracked_on_first_sync` | `False` | 安全兜底：从未同步过的目录首次执行时，即使 `delete_untracked=True` 也仍保留未跟踪文件，防止传错路径误删整个目录；设为 `True` 才允许首次同步清理 |
| `prerelease` | `False` | 允许 `latest` 匹配预发布版本 |
| `verify_all` | `False` | 忽略 `size + mtime` 快速检查，对所有本地文件重算哈希 |
| `verify_paths` | `None` | 对指定的相对路径列表强制算哈希校验（以 `/` 结尾表示目录前缀），其余文件仍按 `size + mtime` 快速判断 |
| `on_progress` | `None` | 进度回调，接收字典 `{"phase", "done", "total", "downloaded_bytes", "bytes_per_second"}` |
| `cancel_event` | `None` | 传入 `threading.Event`，外部调用 `set()` 可中途取消同步 |

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
