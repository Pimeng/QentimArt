"""<dir>/.qentimart 中的本地状态（与 Node.js SDK 格式相同）。"""
from __future__ import annotations

import json
import os
import threading
import time
from typing import Any, Callable, Dict, Optional, Tuple

from ._errors import QentimArtError
from ._localfs import check_entry, pid_alive, write_atomic
from ._wire import MAX_MANIFEST_GZ, MAX_MANIFEST_RAW, STATE_DIR, Manifest, parse_manifest, read_capped

STATE_FILE = "state.json"
MANIFEST_FILE = "manifest.json.gz"
TMP_DIR = "tmp"
LOCK_FILE = "lock"


def read_state(root: str) -> Tuple[Optional[Dict[str, Any]], Optional[Manifest], Optional[bytes]]:
    sd = os.path.join(root, STATE_DIR)
    check_entry(sd, directory=True)
    state = None
    manifest = None
    gz = None
    try:
        state_path = os.path.join(sd, STATE_FILE)
        check_entry(state_path, directory=False)
        with open(state_path, "rb") as f:
            state = json.loads(read_capped(f, MAX_MANIFEST_RAW).decode("utf-8"))
        if not isinstance(state, dict) or state.get("formatVersion") != 1 or not isinstance(state.get("files"), dict):
            state = None
    except (OSError, ValueError):
        state = None
    if state is not None:
        manifest_path = os.path.join(sd, MANIFEST_FILE)
        check_entry(manifest_path, directory=False)
        try:
            with open(manifest_path, "rb") as f:
                gz = read_capped(f, MAX_MANIFEST_GZ)
            manifest = parse_manifest(gz)
            if manifest.version != state.get("version"):
                manifest = None
        except (OSError, QentimArtError):
            manifest = None
    return state, manifest, gz


def write_state(root: str, state: Dict[str, Any], manifest_gz: bytes) -> None:
    sd = os.path.join(root, STATE_DIR)
    check_entry(sd, directory=True)
    os.makedirs(sd, exist_ok=True)
    write_atomic(os.path.join(sd, MANIFEST_FILE), manifest_gz)
    write_atomic(os.path.join(sd, STATE_FILE), json.dumps(state, separators=(",", ":")).encode("utf-8"))


# 同一物理目录的不同路径共享进程内互斥，跨进程互斥由磁盘上的锁文件提供。
_inproc_lock = threading.Lock()
_inproc_roots = set()


def acquire_lock(root: str) -> Callable[[], None]:
    key = os.path.normcase(os.path.realpath(root))
    with _inproc_lock:
        if key in _inproc_roots:
            raise QentimArtError("another sync is running on %s (this process)" % root, code="ELOCKED")
        _inproc_roots.add(key)

    sd = os.path.join(root, STATE_DIR)
    lock = os.path.join(sd, LOCK_FILE)

    def remove_file() -> None:
        try:
            os.remove(lock)
        except OSError:
            pass

    def release() -> None:
        remove_file()
        with _inproc_lock:
            _inproc_roots.discard(key)

    try:
        check_entry(sd, directory=True)
        os.makedirs(sd, exist_ok=True)
        for _ in range(2):
            try:
                fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
            except FileExistsError:
                check_entry(lock, directory=False)
                stale = True
                try:
                    with open(lock, "rb") as f:
                        info = json.loads(f.read(4096).decode("utf-8"))
                    pid = info.get("pid")
                    if type(pid) is not int or not 0 < pid <= 0x7FFF_FFFF:
                        raise ValueError("invalid lock PID")
                    stale = not pid_alive(pid)
                except (OSError, ValueError, AttributeError):
                    # 另一进程可能刚独占创建文件，尚未写完 PID。
                    try:
                        stale = time.time() - os.lstat(lock).st_mtime > 60
                    except FileNotFoundError:
                        continue
                if not stale:
                    raise QentimArtError("another sync is running on %s" % root, code="ELOCKED")
                remove_file()  # 只清理陈旧锁文件，保留进程内持有
                continue
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump({"pid": os.getpid(), "at": int(time.time() * 1000)}, f)
            except OSError:
                remove_file()
                raise
            return release
        raise QentimArtError("could not lock %s" % root, code="ELOCKED")
    except BaseException:
        with _inproc_lock:
            _inproc_roots.discard(key)
        raise
