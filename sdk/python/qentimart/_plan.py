"""将本地目录与目标 manifest 进行比较。"""
from __future__ import annotations

import os
import threading
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Set

from ._localfs import LocalFile, hash_file, run_pool
from ._wire import Chunk, Manifest, ManifestFile


class WriteTask(NamedTuple):
    t: ManifestFile
    reason: str  # create | update | replace-dir
    local: Optional[LocalFile]
    clean: bool


class Plan:
    def __init__(self) -> None:
        self.to_write: List[WriteTask] = []
        self.to_delete: List[str] = []
        self.unchanged: List[str] = []
        self.adopted: List[str] = []
        self.kept: List[str] = []
        self.conflicts: List[str] = []
        self.new_tracked: Dict[str, int] = {}


class PlanContext(NamedTuple):
    root: str
    policy: str
    verify_all: bool
    delete_untracked: bool
    delete_untracked_on_first_sync: bool
    files: Dict[str, LocalFile]
    dirs: Set[str]
    old: Optional[Manifest]
    tracked: Dict[str, int]
    has_state: bool
    progress: Callable[..., None]
    verify_wanted: Callable[[str], bool] = lambda p: False


def _decide_modified(plan: Plan, policy: str, t: ManifestFile, l: LocalFile, lock: threading.Lock) -> None:
    with lock:
        if policy == "overwrite":
            plan.to_write.append(WriteTask(t, "update", l, False))
        elif policy == "keep":
            plan.kept.append(t.path)
        else:
            plan.conflicts.append(t.path)


def build_plan(ctx: PlanContext, target: Manifest) -> Plan:
    """只有大小/mtime 与记录状态不一致的文件才会计算哈希。"""
    policy = ctx.policy
    old = ctx.old.files if ctx.old else {}
    plan = Plan()
    lock = threading.Lock()

    def is_clean(p: str, l: LocalFile) -> bool:
        # size+mtime 快速检测存在盲区（内容改但大小不变、mtime 被还原）；
        # verify_all 对所有文件、verify_wanted 对指定重要文件强制走哈希校验。
        o = old.get(p)
        return (not ctx.verify_all) and (not ctx.verify_wanted(p)) and o is not None \
            and ctx.tracked.get(p) == l.mtime_ms and o.size == l.size

    to_hash = []
    for t in target.files.values():
        l = ctx.files.get(t.path)
        if l is None:
            if t.path in ctx.dirs and policy != "overwrite":
                (plan.kept if policy == "keep" else plan.conflicts).append(t.path)
                continue
            plan.to_write.append(WriteTask(t, "replace-dir" if t.path in ctx.dirs else "create", None, False))
            continue
        if is_clean(t.path, l):
            if old[t.path].hash == t.hash:
                plan.unchanged.append(t.path)
                plan.new_tracked[t.path] = l.mtime_ms
            else:
                plan.to_write.append(WriteTask(t, "update", l, True))
            continue
        if l.size != t.size:
            _decide_modified(plan, policy, t, l, lock)
            continue
        to_hash.append((t, l))

    total = sum(l.size for _, l in to_hash)
    done = [0]

    def do_hash(item) -> None:
        t, l = item
        h = hash_file(os.path.join(ctx.root, t.path))
        with lock:
            done[0] += l.size
            if h == t.hash:
                plan.adopted.append(t.path)
                plan.new_tracked[t.path] = l.mtime_ms
                matched = True
            else:
                matched = False
        ctx.progress("hash", done[0], total)
        if not matched:
            _decide_modified(plan, policy, t, l, lock)

    run_pool(to_hash, 4, do_hash)

    for p, l in ctx.files.items():
        if p in target.files:
            continue
        # 受管文件（曾被同步、现已从清单移除）且本地未改动：正常清理，不受下列开关影响。
        if is_clean(p, l):
            plan.to_delete.append(p)
            continue
        # 该文件是否属于 SDK 受管范围：曾记录在状态里，或存在于旧清单中。
        managed = p in ctx.tracked or p in old
        if policy == "overwrite":
            if managed:
                # 受管但本地有改动：overwrite 语义为「以服务端为准还原」，服务端已无此文件 → 删除。
                plan.to_delete.append(p)
            else:
                # 真正未跟踪的本地文件（用户自带、从不在清单内）：默认绝不删除，避免误伤并存资源。
                # 仅当显式 delete_untracked 开启才删；首次同步（无状态）另需 delete_untracked_on_first_sync 兜底。
                may_delete = ctx.delete_untracked if ctx.has_state else (ctx.delete_untracked and ctx.delete_untracked_on_first_sync)
                (plan.to_delete if may_delete else plan.kept).append(p)
        elif policy == "keep":
            plan.kept.append(p)
        else:
            plan.conflicts.append(p)

    plan.to_write.sort(key=lambda w: w.t.path)
    plan.to_delete.sort()
    plan.kept.sort()
    plan.conflicts.sort()
    return plan


class _Source(NamedTuple):
    path: str
    offset: int
    size: int
    gen: int


class SourceIndex:
    """磁盘上（可能）存有某个块的位置。使用前始终会进行校验。"""

    def __init__(self) -> None:
        self.by_hash: Dict[str, List[_Source]] = {}
        self.gen: Dict[str, int] = {}
        self.lock = threading.Lock()

    def add(self, path: str, chunks: List[Chunk], max_size: float = float("inf")) -> None:
        g = self.gen.get(path, 0)
        for c in chunks:
            if c.offset + c.size > max_size:
                continue
            arr = self.by_hash.setdefault(c.hash, [])
            if len(arr) < 4:
                arr.append(_Source(path, c.offset, c.size, g))

    def replaced(self, path: str, chunks: List[Chunk]) -> None:
        with self.lock:
            self.gen[path] = self.gen.get(path, 0) + 1
            self.add(path, chunks)

    def candidates(self, h: str) -> List[_Source]:
        with self.lock:
            return [s for s in self.by_hash.get(h, ()) if s.gen == self.gen.get(s.path, 0)]


def build_sources(ctx: PlanContext, target: Manifest, plan: Plan) -> SourceIndex:
    src = SourceIndex()
    old = ctx.old.files if ctx.old else {}
    for p in plan.unchanged + plan.adopted:
        src.add(p, target.files[p].chunks)
    for w in plan.to_write:
        if w.local is None:
            continue
        o = old.get(w.t.path)
        if o is not None:
            src.add(w.t.path, o.chunks, w.local.size)
        if not w.clean:
            src.add(w.t.path, w.t.chunks, w.local.size)  # 推测：与目标布局相同
    for p in plan.to_delete:
        o = old.get(p)
        l = ctx.files.get(p)
        if o is not None and l is not None:
            src.add(p, o.chunks, l.size)  # 被移动/重命名的资源从本地复制
    return src


def estimate_download(plan: Plan, sources: SourceIndex) -> int:
    seen: Set[str] = set()
    n = 0
    for w in plan.to_write:
        for c in w.t.chunks:
            if c.hash in seen:
                continue
            seen.add(c.hash)
            if not sources.candidates(c.hash):
                n += c.size
    return n


