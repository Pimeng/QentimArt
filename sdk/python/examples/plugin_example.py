"""示例：将 AssetSync 集成到 Python 工具 / 编辑器插件中。

    ASSETSYNC_SERVER=http://127.0.0.1:8080 ASSETSYNC_PROJECT=mygame \
    ASSETSYNC_TOKEN=... python examples/plugin_example.py ./assets
"""
import os
import sys
import threading

from assetsync import AssetSyncClient, AssetSyncError, SyncConflictError

target_dir = sys.argv[1] if len(sys.argv) > 1 else "./assets"

client = AssetSyncClient(
    os.environ.get("ASSETSYNC_SERVER", "http://127.0.0.1:8080"),
    os.environ.get("ASSETSYNC_PROJECT", "mygame"),
    token=os.environ.get("ASSETSYNC_TOKEN"),  # 只读 token 即可
    concurrency=8,
)

try:
    # 1) 有新版本吗？（一次小型 JSON 请求 + 本地检查，不对未改动的文件计算哈希）
    latest = client.get_version("latest")
    status = client.status(target_dir)
    print("local: %s  server latest: %s" % (status["version"] or "(never synced)", latest["version"]))

    if status["version"] == latest["version"] and status.get("clean"):
        print("already up to date")
    else:
        # 2) 可选：显示本次更新的开销。
        plan = client.plan(target_dir)
        print("update will write %d files, download ~%.1f MiB" % (
            plan["files"]["created"] + plan["files"]["updated"], plan["bytes"]["estimated_download"] / 1048576))

        # 3) 同步。local_changes='keep' 会保护本地改动，'fail' 则直接中止。
        cancel = threading.Event()  # 在“取消”按钮中调用 set()

        def on_progress(p):
            if p["phase"] == "download" and p["total"]:
                sys.stdout.write("\r%.1f%%  %.1f MiB/s   " % (p["done"] * 100.0 / p["total"], p["bytes_per_second"] / 1048576))
                sys.stdout.flush()

        result = client.sync(target_dir, local_changes="overwrite", on_progress=on_progress, cancel_event=cancel)
        print("\nsynced to %s in %.1fs" % (result["version"], result["elapsed_ms"] / 1000))
        print("downloaded %.1f MiB, reused %.1f MiB locally" % (
            result["bytes"]["downloaded_wire"] / 1048576, result["bytes"]["reused_local"] / 1048576))
        print("disk usage: %.2f%% of the asset size" % (result["storage"]["ratio"] * 100))

    # 4) 切换到其他游戏版本：client.sync(target_dir, version="1.0.0")
except SyncConflictError as e:
    print("locally modified files:", e.conflicts)
    sys.exit(1)
except AssetSyncError as e:
    print("sync failed:", e)
    sys.exit(1)
finally:
    client.close()
