from __future__ import annotations

import errno
import hashlib
import os
import stat
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Dict, Iterable, NamedTuple, Set, Tuple, TypeVar

from ._errors import QentimArtError
from ._wire import STATE_DIR

O_BINARY = getattr(os, "O_BINARY", 0)
T = TypeVar("T")


class LocalFile(NamedTuple):
    size: int
    mtime_ms: int


def is_link(st: os.stat_result) -> bool:
    return stat.S_ISLNK(st.st_mode) or bool(getattr(st, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def check_entry(path: str, *, directory: bool) -> None:
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return
    if is_link(st) or not (stat.S_ISDIR(st.st_mode) if directory else stat.S_ISREG(st.st_mode)):
        raise QentimArtError("unsafe filesystem entry %s" % path, code="EUNSAFE")


def asset_path(root: str, rel: str) -> str:
    """检查现有父目录，拒绝链接和 Windows 短文件名指向的其他条目。"""
    path = root
    parts = rel.split("/")
    for i, part in enumerate(parts):
        path = os.path.join(path, part)
        if i < len(parts) - 1:
            check_entry(path, directory=True)
        if os.name == "nt" and "~" in part and os.path.lexists(path):
            long_name = os.path.basename(os.path.realpath(path))
            if os.path.normcase(long_name) != os.path.normcase(part):
                raise QentimArtError("unsafe path alias %s" % rel, code="EUNSAFE")
    return path


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
                if not rel and e.name.lower() == STATE_DIR:
                    continue
                if e.is_symlink() or (os.name == "nt" and is_link(e.stat(follow_symlinks=False))):
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
    check_entry(path, directory=False)
    h = hashlib.sha256()
    fd = os.open(path, os.O_RDONLY | O_BINARY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb", buffering=0) as f:
        buf = bytearray(1 << 20)
        mv = memoryview(buf)
        while True:
            n = f.readinto(buf)
            if not n:
                break
            h.update(mv[:n])
    return h.hexdigest()


def replace_retry(src: str, dst: str, attempts: int = 8) -> None:
    """带重试的 os.replace，用于应对 Windows 上短暂的共享冲突。

    重试面与 Node（EPERM/EBUSY/EACCES）对齐：除 PermissionError 外，也重试
    Windows 共享/占用（winerror 5/32）与 EBUSY/EACCES/EPERM。"""
    for i in range(attempts + 1):
        try:
            os.replace(src, dst)
            return
        except OSError as e:
            transient = isinstance(e, PermissionError) \
                or getattr(e, "winerror", None) in (5, 32) \
                or e.errno in (errno.EACCES, errno.EBUSY, errno.EPERM)
            if not transient or i >= attempts:
                raise
            time.sleep(0.05 * (2 ** min(i, 5)))


def write_atomic(path: str, data: bytes) -> None:
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        replace_retry(tmp, path)
    finally:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass


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
        flags = (os.O_RDWR | os.O_CREAT | os.O_EXCL) if write else os.O_RDONLY
        self.fd = os.open(path, flags | O_BINARY | getattr(os, "O_NOFOLLOW", 0), 0o644)
        self.lock = threading.Lock()
        if write and size > 0:
            try:
                os.ftruncate(self.fd, size)
            except BaseException:
                self.close()
                raise

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
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        open_process = kernel32.OpenProcess
        open_process.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        open_process.restype = wintypes.HANDLE
        handle = open_process(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return ctypes.get_last_error() != 87  # ERROR_INVALID_PARAMETER: no such process
        try:
            get_exit_code = kernel32.GetExitCodeProcess
            get_exit_code.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
            code = wintypes.DWORD()
            return not get_exit_code(handle, ctypes.byref(code)) or code.value == 259  # STILL_ACTIVE
        finally:
            close_handle = kernel32.CloseHandle
            close_handle.argtypes = (wintypes.HANDLE,)
            close_handle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True
