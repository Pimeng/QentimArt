from __future__ import annotations

import hashlib
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Dict, Iterable, NamedTuple, Set, Tuple, TypeVar

from ._wire import STATE_DIR

O_BINARY = getattr(os, "O_BINARY", 0)
T = TypeVar("T")


class LocalFile(NamedTuple):
    size: int
    mtime_ms: int


def run_pool(items: Iterable[T], workers: int, fn: Callable[[T], None]) -> None:
    """用线程池对 items 执行 fn；重新抛出第一个错误。"""
    items = list(items)
    if not items:
        return
    if workers <= 1 or len(items) == 1:
        for it in items:
            fn(it)
        return
    with ThreadPoolExecutor(max_workers=min(workers, len(items))) as ex:
        futures = [ex.submit(fn, it) for it in items]
        first = None
        for f in futures:
            try:
                f.result()
            except BaseException as e:  # noqa: BLE001 - 在下方重新抛出
                if first is None:
                    first = e
                    for g in futures:
                        g.cancel()
        if first is not None:
            raise first


def scan_dir(root: str) -> Tuple[Dict[str, LocalFile], Set[str]]:
    """列出 root 下的普通文件（跳过状态目录）以及所有目录。"""
    files: Dict[str, LocalFile] = {}
    dirs: Set[str] = set()
    stack = [(root, "")]
    while stack:
        abs_dir, rel = stack.pop()
        try:
            it = os.scandir(abs_dir)
        except FileNotFoundError:
            continue
        with it:
            for e in it:
                r = rel + "/" + e.name if rel else e.name
                if not rel and e.name == STATE_DIR:
                    continue
                if e.is_dir(follow_symlinks=False):
                    dirs.add(r)
                    stack.append((e.path, r))
                elif e.is_file(follow_symlinks=False):
                    st = e.stat(follow_symlinks=False)
                    files[r] = LocalFile(st.st_size, st.st_mtime_ns // 1_000_000)
    return files, dirs


def mtime_ms(path: str) -> int:
    return os.stat(path).st_mtime_ns // 1_000_000


def hash_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb", buffering=0) as f:
        buf = bytearray(1 << 20)
        mv = memoryview(buf)
        while True:
            n = f.readinto(buf)
            if not n:
                break
            h.update(mv[:n])
    return h.hexdigest()


def replace_retry(src: str, dst: str, attempts: int = 8) -> None:
    """带重试的 os.replace，用于应对 Windows 上短暂的共享冲突。"""
    for i in range(attempts + 1):
        try:
            os.replace(src, dst)
            return
        except PermissionError:
            if i >= attempts:
                raise
            time.sleep(0.05 * (2 ** min(i, 5)))


def write_atomic(path: str, data: bytes) -> None:
    tmp = "%s.%d.tmp" % (path, os.getpid())
    with open(tmp, "wb") as f:
        f.write(data)
    replace_retry(tmp, path)


def dir_bytes(path: str) -> int:
    n = 0
    for base, _dirs, names in os.walk(path):
        for name in names:
            try:
                n += os.path.getsize(os.path.join(base, name))
            except OSError:
                pass
    return n


def prune_empty_dirs(root: str, rel: str) -> None:
    d = os.path.dirname(rel.replace("/", os.sep))
    while d:
        try:
            os.rmdir(os.path.join(root, d))
        except OSError:
            return
        d = os.path.dirname(d)


class RandomAccessFile:
    """支持线程安全定位读写的文件描述符
    （可用时使用 os.pread/os.pwrite，否则（如 Windows）使用锁 + seek）。"""

    def __init__(self, path: str, write: bool, size: int = 0):
        flags = (os.O_RDWR | os.O_CREAT | os.O_TRUNC) if write else os.O_RDONLY
        self.fd = os.open(path, flags | O_BINARY, 0o644)
        self.lock = threading.Lock()
        if write and size > 0:
            os.ftruncate(self.fd, size)

    def write_at(self, offset: int, data: bytes) -> None:
        if hasattr(os, "pwrite"):
            view = memoryview(data)
            while view:
                n = os.pwrite(self.fd, view, offset)
                view = view[n:]
                offset += n
            return
        with self.lock:
            os.lseek(self.fd, offset, os.SEEK_SET)
            view = memoryview(data)
            while view:
                n = os.write(self.fd, view)
                view = view[n:]

    def read_at(self, offset: int, size: int) -> bytes:
        if hasattr(os, "pread"):
            return os.pread(self.fd, size, offset)
        with self.lock:
            os.lseek(self.fd, offset, os.SEEK_SET)
            parts = []
            left = size
            while left:
                b = os.read(self.fd, left)
                if not b:
                    break
                parts.append(b)
                left -= len(b)
            return b"".join(parts)

    def close(self) -> None:
        if self.fd >= 0:
            os.close(self.fd)
            self.fd = -1


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        code = ctypes.c_ulong()
        ok = kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
        kernel32.CloseHandle(handle)
        return bool(ok) and code.value == 259  # STILL_ACTIVE
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True
