"""执行计划：复用本地块、并行批量拉取、原子替换。"""
from __future__ import annotations

import hashlib
import os
import shutil
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Set

from ._errors import QentimArtError
from ._localfs import RandomAccessFile, mtime_ms, prune_empty_dirs, replace_retry, run_pool
from ._plan import Plan, SourceIndex, WriteTask
from ._wire import STATE_DIR
from ._state import TMP_DIR


class Stats:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.written = 0
        self.reused_local = 0
        self.downloaded_raw = 0
        self.downloaded_wire = 0


class _Need:
    __slots__ = ("size", "dests")

    def __init__(self, size: int) -> None:
        self.size = size
        self.dests: List[tuple] = []  # (RandomAccessFile, offset)


def _check_cancel(cancel: Optional[threading.Event]) -> None:
    if cancel is not None and cancel.is_set():
        raise QentimArtError("sync cancelled", code="ECANCELED")


def _waves(tasks: List[WriteTask], max_bytes: int, max_files: int) -> List[List[WriteTask]]:
    out: List[List[WriteTask]] = []
    cur: List[WriteTask] = []
    size = 0
    for w in tasks:
        if cur and (size + w.t.size > max_bytes or len(cur) >= max_files):
            out.append(cur)
            cur, size = [], 0
        cur.append(w)
        size += w.t.size
    if cur:
        out.append(cur)
    return out


def apply_writes(client: Any, root: str, policy: str, plan: Plan, sources: SourceIndex, stats: Stats,
                 new_tracked: Dict[str, int], write_bytes: int, progress: Callable[..., None],
                 cancel: Optional[threading.Event]) -> None:
    tmp_dir = os.path.join(root, STATE_DIR, TMP_DIR)
    shutil.rmtree(tmp_dir, ignore_errors=True)
    os.makedirs(tmp_dir, exist_ok=True)
    delete_set: Set[str] = set(plan.to_delete)
    seq = 0
    for wave in _waves(plan.to_write, client.wave_bytes, client.wave_files):
        _check_cancel(cancel)
        tmps = []
        for w in wave:
            tmps.append(os.path.join(tmp_dir, "%d.part" % seq))
            seq += 1
        handles: List[Optional[RandomAccessFile]] = [None] * len(wave)
        try:
            _apply_wave(client, root, policy, wave, tmps, handles, sources, stats, new_tracked,
                        write_bytes, progress, cancel, delete_set)
        finally:
            for h in handles:
                if h is not None:
                    h.close()


def _apply_wave(client, root, policy, wave, tmps, handles, sources, stats, new_tracked,
                write_bytes, progress, cancel, delete_set) -> None:
    def open_tmp(i: int) -> None:
        handles[i] = RandomAccessFile(tmps[i], write=True, size=wave[i].t.size)

    run_pool(range(len(wave)), 16, open_tmp)

    need: Dict[str, _Need] = {}
    for i, w in enumerate(wave):
        for c in w.t.chunks:
            n = need.get(c.hash)
            if n is None:
                n = need[c.hash] = _Need(c.size)
            n.dests.append((handles[i], c.offset))

    def write_chunk(n: _Need, data: bytes) -> None:
        for fh, off in n.dests:
            fh.write_at(off, data)
        with stats.lock:
            stats.written += len(data) * len(n.dests)
            done = stats.written
        progress("download", done, write_bytes)

    # 1) 复制本地已存在的块
    readers: Dict[str, Optional[RandomAccessFile]] = {}
    readers_lock = threading.Lock()

    def reader(p: str) -> Optional[RandomAccessFile]:
        with readers_lock:
            if p not in readers:
                try:
                    readers[p] = RandomAccessFile(os.path.join(root, p), write=False)
                except OSError:
                    readers[p] = None
            return readers[p]

    remaining: List[str] = []
    rem_lock = threading.Lock()

    def try_local(item) -> None:
        h, n = item
        for s in sources.candidates(h):
            fh = reader(s.path)
            if fh is None:
                continue
            try:
                data = fh.read_at(s.offset, s.size)
            except OSError:
                continue
            if len(data) == s.size and hashlib.sha256(data).hexdigest() == h:
                write_chunk(n, data)
                with stats.lock:
                    stats.reused_local += len(data)
                return
        with rem_lock:
            remaining.append(h)

    try:
        run_pool(list(need.items()), 8, try_local)
    finally:
        for r in readers.values():
            if r is not None:
                r.close()

    # 2) 获取其余的块（动态模式批量拉帧流，静态模式逐块 GET，由 client 决定）
    if remaining:
        client.fetch_chunks(
            hashes=remaining,
            size_of=lambda h: need[h].size,
            on_chunk=lambda h, raw: write_chunk(need[h], raw),
            stats=stats,
            cancel=cancel,
        )

    # 3) 关闭文件、移动到目标位置、记录状态
    for i in range(len(handles)):
        handles[i].close()
        handles[i] = None
    for i, w in enumerate(wave):
        target = os.path.join(root, w.t.path)
        _ensure_parent(root, w.t.path, delete_set, policy)
        if w.reason == "replace-dir":
            shutil.rmtree(target, ignore_errors=True)
        try:
            replace_retry(tmps[i], target)
        except OSError as e:
            raise QentimArtError("cannot replace %s (is it open in another program?): %s" % (w.t.path, e))
        new_tracked[w.t.path] = mtime_ms(target)
        sources.replaced(w.t.path, w.t.chunks)


def _ensure_parent(root: str, rel: str, delete_set: Set[str], policy: str) -> None:
    parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
    if not parent:
        return
    try:
        os.makedirs(os.path.join(root, parent), exist_ok=True)
        return
    except (FileExistsError, NotADirectoryError):
        pass
    # 需要目录的位置上存在一个文件（例如 "a" -> "a/b"）。
    segs = parent.split("/")
    for i in range(1, len(segs) + 1):
        p = "/".join(segs[:i])
        ap = os.path.join(root, p)
        if os.path.lexists(ap) and not os.path.isdir(ap):
            if p not in delete_set and policy != "overwrite":
                raise QentimArtError("%s is a file but %s needs it to be a directory" % (p, rel))
            os.remove(ap)
            delete_set.discard(p)
    os.makedirs(os.path.join(root, parent), exist_ok=True)


def delete_files(root: str, paths: List[str]) -> None:
    """删除操作放在最后执行，以便被删除的文件仍可作为块来源。"""
    for p in paths:
        ap = os.path.join(root, p)
        # 该路径可能已不存在，或已变为目标版本中的目录（文件 -> 目录）。
        if not os.path.lexists(ap) or os.path.isdir(ap):
            continue
        os.remove(ap)
        prune_empty_dirs(root, p)


def now_ms() -> float:
    return time.time() * 1000
