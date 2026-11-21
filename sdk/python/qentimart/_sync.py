"""执行计划：复用本地块、并行批量拉取、原子替换。"""
from __future__ import annotations

import hashlib
import os
import shutil
import stat
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Set

from ._errors import QentimArtError, SyncConflictError
from ._localfs import RandomAccessFile, asset_path, check_entry, hash_file, is_link, mtime_ms, prune_empty_dirs, replace_retry, run_pool
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


def apply_writes(client: Any, root: str, plan: Plan, sources: SourceIndex, stats: Stats,
                 new_tracked: Dict[str, int], write_bytes: int, progress: Callable[..., None],
                 cancel: Optional[threading.Event]) -> None:
    tmp_dir = os.path.join(root, STATE_DIR, TMP_DIR)
    check_entry(tmp_dir, directory=True)
    if os.path.isdir(tmp_dir):
        shutil.rmtree(tmp_dir)
    os.mkdir(tmp_dir, 0o700)
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
            _apply_wave(client, root, wave, tmps, handles, sources, stats, new_tracked,
                        write_bytes, progress, cancel, delete_set)
        finally:
            for h in handles:
                if h is not None:
                    h.close()


def _apply_wave(client, root, wave, tmps, handles, sources, stats, new_tracked,
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
        if len(data) != n.size:
            raise QentimArtError("chunk length does not match manifest", code="ECORRUPT")
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
                    path = asset_path(root, p)
                    check_entry(path, directory=False)
                    readers[p] = RandomAccessFile(path, write=False)
                except (OSError, QentimArtError):
                    readers[p] = None
            return readers[p]

    remaining: List[str] = []
    rem_lock = threading.Lock()

    def try_local(item) -> None:
        h, n = item
        for s in sources.candidates(h):
            if s.size != n.size:
                continue
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
        if len(w.t.chunks) > 1 and hash_file(tmps[i]) != w.t.hash:
            raise QentimArtError("file %s failed verification" % w.t.path, code="ECORRUPT")
    for i, w in enumerate(wave):
        _ensure_parent(root, w.t.path, delete_set)
        target = asset_path(root, w.t.path)
        if w.reason == "create" and os.path.lexists(target):
            raise SyncConflictError([w.t.path])
        if w.reason == "replace-dir":
            _remove_replaced_dir(root, w.t.path, delete_set)
        try:
            replace_retry(tmps[i], target)
        except OSError as e:
            raise QentimArtError("cannot replace %s (is it open in another program?): %s" % (w.t.path, e))
        new_tracked[w.t.path] = mtime_ms(target)
        sources.replaced(w.t.path, w.t.chunks)


def _remove_replaced_dir(root: str, rel: str, delete_set: Set[str]) -> None:
    # 先检查整个子树，再仅移除规划中允许删除的文件；新出现的条目会让 rmdir 安全失败。
    files = []
    dirs = []
    pending = [rel]
    while pending:
        subdir = pending.pop()
        path = os.path.join(root, subdir)
        check_entry(path, directory=True)
        dirs.append(path)
        with os.scandir(path) as entries:
            for e in entries:
                child = subdir + "/" + e.name
                st = e.stat(follow_symlinks=False)
                if not is_link(st) and stat.S_ISDIR(st.st_mode):
                    pending.append(child)
                elif not is_link(st) and stat.S_ISREG(st.st_mode) and child in delete_set:
                    files.append(e.path)
                else:
                    raise QentimArtError("preserved entry blocks replacement: %s" % child, code="ECONFLICT")
    for path in files:
        os.unlink(path)
    for path in reversed(dirs):
        os.rmdir(path)


def _ensure_parent(root: str, rel: str, delete_set: Set[str]) -> None:
    """逐段创建父目录，绝不跟随符号链接：若某个已存在的祖先是符号链接，拒绝写入，
    以免清单路径合法但本地父目录指向 root 之外时，替换操作把文件写到 root 外（逃逸）。"""
    parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
    if not parent:
        return
    segs = parent.split("/")
    for i in range(1, len(segs) + 1):
        p = "/".join(segs[:i])
        ap = asset_path(root, p) if os.name == "nt" and "~" in p else os.path.join(root, p)
        try:
            st = os.lstat(ap)
        except FileNotFoundError:
            st = None
        if st is not None and is_link(st):
            raise QentimArtError("unsafe path %s: %r is a symlink" % (rel, p), code="EUNSAFE")
        if st is not None and not stat.S_ISDIR(st.st_mode):
            # 该位置是普通文件，但需要它是目录（例如 "a" -> "a/b"）。
            if p not in delete_set or not stat.S_ISREG(st.st_mode):
                raise QentimArtError("%s is a file but %s needs it to be a directory" % (p, rel))
            os.remove(ap)
            delete_set.discard(p)
            os.mkdir(ap)
        elif st is None:
            os.mkdir(ap)


def delete_files(root: str, paths: List[str]) -> None:
    """删除操作放在最后执行，以便被删除的文件仍可作为块来源。"""
    for p in paths:
        ap = os.path.join(root, p)
        try:
            st = os.lstat(ap)  # 不跟随符号链接
        except (FileNotFoundError, NotADirectoryError):
            continue
        asset_path(root, p)
        # 真实目录（已变为目标版本中的目录，文件 -> 目录）跳过；符号链接按条目本身删除。
        if stat.S_ISDIR(st.st_mode):
            continue
        os.remove(ap)
        prune_empty_dirs(root, p)


def now_ms() -> float:
    return time.time() * 1000
