from __future__ import annotations

import json
import os
import shutil
import threading
import time
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Optional
from urllib.parse import quote

from ._errors import AssetSyncError, SyncConflictError
from ._http import HttpClient
from ._localfs import dir_bytes, hash_file, run_pool, scan_dir
from ._plan import PlanContext, build_plan, build_sources, estimate_download
from ._state import TMP_DIR, acquire_lock, read_state, write_state
from ._sync import Stats, apply_writes, delete_files
from ._wire import STATE_DIR, Manifest, parse_manifest

ProgressFn = Callable[[Dict[str, Any]], None]


class AssetSyncClient:
    """AssetSync 下游客户端。

    >>> client = AssetSyncClient("http://assets:8080", "mygame", token="...")
    >>> result = client.sync("./assets")
    """

    def __init__(self, server: str, project: str, token: Optional[str] = None, concurrency: int = 8,
                 batch_bytes: int = 16 << 20, batch_chunks: int = 2048, wave_bytes: int = 512 << 20,
                 wave_files: int = 2048, timeout: float = 60.0, retries: int = 3):
        if not server or not project:
            raise AssetSyncError("server and project are required")
        self.project = project
        self.concurrency = concurrency
        self.batch_bytes = batch_bytes
        self.batch_chunks = batch_chunks
        self.wave_bytes = wave_bytes
        self.wave_files = wave_files
        self._http = HttpClient(server, project, token, timeout, retries)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "AssetSyncClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # ------------------------------------------------------------ 服务端 API

    def list_versions(self) -> Dict[str, Any]:
        return self._http.json("GET", "/versions")

    def get_version(self, version: str = "latest", prerelease: bool = False) -> Dict[str, Any]:
        return self._http.json("GET", "/versions/%s%s" % (quote(version, safe=""), "?prerelease=1" if prerelease else ""))

    def diff(self, from_: str, to: str = "latest") -> Dict[str, Any]:
        return self._http.json("GET", "/versions/%s/diff?from=%s" % (quote(to, safe=""), quote(from_, safe="")))

    def fetch_manifest_raw(self, version: str = "latest", prerelease: bool = False):
        body, headers = self._http.buffer(
            "GET", "/versions/%s/manifest%s" % (quote(version, safe=""), "?prerelease=1" if prerelease else ""))
        return headers.get("X-AssetSync-Version"), body

    def fetch_manifest(self, version: str = "latest", prerelease: bool = False) -> Manifest:
        return parse_manifest(self.fetch_manifest_raw(version, prerelease)[1])

    # ------------------------------------------------------------ 同步

    def plan(self, dir: str, **kwargs: Any) -> Dict[str, Any]:
        """sync() 的试运行（dry run）。"""
        kwargs["dry_run"] = True
        return self.sync(dir, **kwargs)

    def sync(self, dir: str, version: str = "latest", local_changes: str = "overwrite", prerelease: bool = False,
             verify_all: bool = False, dry_run: bool = False, delete_untracked_on_first_sync: bool = False,
             on_progress: Optional[ProgressFn] = None, cancel_event: Optional[threading.Event] = None) -> Dict[str, Any]:
        """将 ``dir`` 同步到 ``version``（升级或降级）。

        local_changes：'overwrite'（默认）恢复服务端文件并删除多余文件；
        'keep' 不触碰本地已修改/未跟踪的文件；'fail' 在改动任何内容之前
        抛出 SyncConflictError。
        """
        if local_changes not in ("overwrite", "keep", "fail"):
            raise AssetSyncError("invalid local_changes %r" % local_changes)
        started = time.time()
        root = os.path.abspath(dir)
        os.makedirs(root, exist_ok=True)
        stats = Stats()
        last_emit = [0.0]
        emit_lock = threading.Lock()

        def progress(phase: str, done: int, total: int, force: bool = False) -> None:
            if on_progress is None:
                return
            now = time.time()
            with emit_lock:
                if not force and now - last_emit[0] < 0.2 and done < total:
                    return
                last_emit[0] = now
            secs = max(0.001, now - started)
            on_progress({"phase": phase, "done": done, "total": total, "downloaded_bytes": stats.downloaded_wire,
                         "bytes_per_second": int(stats.downloaded_wire / secs)})

        release = (lambda: None) if dry_run else acquire_lock(root)
        try:
            state, old_manifest, old_gz = read_state(root)
            same_project = state is not None and state.get("project") == self.project

            progress("resolve", 0, 1, True)
            if version == "latest":
                version = self.get_version("latest", prerelease)["version"]
            if same_project and old_manifest is not None and old_manifest.version == version:
                target_gz = old_gz
            else:
                target_gz = self.fetch_manifest_raw(version)[1]
            target = parse_manifest(target_gz)

            progress("scan", 0, 1, True)
            files, dirs = scan_dir(root)
            ctx = PlanContext(
                root=root, policy=local_changes, verify_all=verify_all,
                delete_untracked_on_first_sync=delete_untracked_on_first_sync, files=files, dirs=dirs,
                old=old_manifest if same_project else None,
                tracked=dict(state.get("files") or {}) if same_project else {},
                has_state=bool(same_project and old_manifest is not None), progress=progress)
            plan = build_plan(ctx, target)
            if plan.conflicts and local_changes == "fail":
                raise SyncConflictError(plan.conflicts)

            sources = build_sources(ctx, target, plan)
            total_bytes = sum(f.size for f in target.files.values())
            write_bytes = sum(w.t.size for w in plan.to_write)
            result: Dict[str, Any] = {
                "project": self.project,
                "version": version,
                "previous_version": state.get("version") if same_project else None,
                "up_to_date": not plan.to_write and not plan.to_delete,
                "dry_run": dry_run,
                "files": {
                    "total": len(target.files),
                    "unchanged": len(plan.unchanged),
                    "adopted": len(plan.adopted),
                    "created": sum(1 for w in plan.to_write if w.reason != "update"),
                    "updated": sum(1 for w in plan.to_write if w.reason == "update"),
                    "deleted": len(plan.to_delete),
                    "kept": plan.kept,
                },
                "bytes": {"total": total_bytes, "to_write": write_bytes},
                "elapsed_ms": 0,
                "storage": None,
            }
            if dry_run:
                result["bytes"]["estimated_download"] = estimate_download(plan, sources)
                result["paths"] = {"write": [w.t.path for w in plan.to_write], "delete": plan.to_delete, "kept": plan.kept}
                result["elapsed_ms"] = int((time.time() - started) * 1000)
                return result

            new_tracked = plan.new_tracked
            if plan.to_write:
                apply_writes(self, root, local_changes, plan, sources, stats, new_tracked, write_bytes, progress, cancel_event)

            progress("cleanup", 0, len(plan.to_delete), True)
            delete_files(root, plan.to_delete)
            shutil.rmtree(os.path.join(root, STATE_DIR, TMP_DIR), ignore_errors=True)

            new_state = {
                "formatVersion": 1,
                "project": self.project,
                "version": version,
                "syncedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
                "files": new_tracked,
            }
            write_state(root, new_state, target_gz)

            elapsed = time.time() - started
            result["bytes"].update({"written": stats.written, "reused_local": stats.reused_local,
                                    "downloaded_raw": stats.downloaded_raw, "downloaded_wire": stats.downloaded_wire})
            result["elapsed_ms"] = int(elapsed * 1000)
            result["bytes_per_second"] = int(stats.downloaded_wire / max(0.001, elapsed))
            kept_bytes = sum(files[p].size for p in plan.kept if p in files)
            overhead = len(json.dumps(new_state, separators=(",", ":")).encode("utf-8")) + len(target_gz)
            asset_bytes = total_bytes + kept_bytes
            result["storage"] = {"asset_bytes": asset_bytes, "overhead_bytes": overhead,
                                 "ratio": (asset_bytes + overhead) / asset_bytes if asset_bytes else 1.0}
            progress("done", 1, 1, True)
            return result
        finally:
            release()

    # ------------------------------------------------------------ 仅本地

    def status(self, dir: str) -> Dict[str, Any]:
        """相对上次同步版本的本地改动（不访问网络）。"""
        root = os.path.abspath(dir)
        state, manifest, _ = read_state(root)
        if state is None or manifest is None or state.get("project") != self.project:
            return {"synced": False, "project": state.get("project") if state else None, "version": None,
                    "modified": [], "missing": [], "untracked": []}
        files, _dirs = scan_dir(root)
        tracked = state.get("files") or {}
        modified, missing, suspicious = [], [], []
        for f in manifest.files.values():
            l = files.get(f.path)
            if l is None:
                missing.append(f.path)
            elif l.size != f.size:
                modified.append(f.path)
            elif tracked.get(f.path) != l.mtime_ms:
                suspicious.append(f)
        lock = threading.Lock()

        def check(f) -> None:
            if hash_file(os.path.join(root, f.path)) != f.hash:
                with lock:
                    modified.append(f.path)

        run_pool(suspicious, 4, check)
        untracked = sorted(p for p in files if p not in manifest.files)
        modified.sort()
        return {"synced": True, "project": state["project"], "version": state["version"], "synced_at": state.get("syncedAt"),
                "clean": not (modified or missing or untracked), "modified": modified, "missing": missing, "untracked": untracked}

    def verify(self, dir: str, on_progress: Optional[ProgressFn] = None) -> Dict[str, Any]:
        """重新计算当前版本每个文件的哈希（检测静默损坏）。"""
        root = os.path.abspath(dir)
        state, manifest, _ = read_state(root)
        if state is None or manifest is None:
            raise AssetSyncError("%s has not been synced yet" % root)
        bad = []
        lock = threading.Lock()
        total = sum(f.size for f in manifest.files.values())
        done = [0]

        def check(f) -> None:
            try:
                ok = hash_file(os.path.join(root, f.path)) == f.hash
            except OSError:
                ok = False
            with lock:
                if not ok:
                    bad.append(f.path)
                done[0] += f.size
                d = done[0]
            if on_progress:
                on_progress({"phase": "verify", "done": d, "total": total})

        run_pool(list(manifest.files.values()), 4, check)
        return {"version": state["version"], "ok": not bad, "corrupted": sorted(bad)}

    @staticmethod
    def storage_report(dir: str) -> Dict[str, Any]:
        """资源字节数与 AssetSync 额外开销（.assetsync 目录）的对比。"""
        root = os.path.abspath(dir)
        files, _ = scan_dir(root)
        asset = sum(f.size for f in files.values())
        overhead = dir_bytes(os.path.join(root, STATE_DIR))
        return {"asset_bytes": asset, "overhead_bytes": overhead, "ratio": (asset + overhead) / asset if asset else 1.0}
