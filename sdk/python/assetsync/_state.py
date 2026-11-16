"""<dir>/.assetsync 中的本地状态（与 Node.js SDK 格式相同）。"""
from __future__ import annotations

import json
import os
import time
from typing import Any, Callable, Dict, Optional, Tuple

from ._errors import AssetSyncError
from ._localfs import pid_alive, write_atomic
from ._wire import STATE_DIR, Manifest, parse_manifest

STATE_FILE = "state.json"
MANIFEST_FILE = "manifest.json.gz"
TMP_DIR = "tmp"
LOCK_FILE = "lock"


def read_state(root: str) -> Tuple[Optional[Dict[str, Any]], Optional[Manifest], Optional[bytes]]:
    sd = os.path.join(root, STATE_DIR)
    state = None
    manifest = None
    gz = None
    try:
        with open(os.path.join(sd, STATE_FILE), "r", encoding="utf-8") as f:
            state = json.load(f)
        if state.get("formatVersion") != 1:
            state = None
    except (OSError, ValueError):
        state = None
    if state is not None:
        try:
            with open(os.path.join(sd, MANIFEST_FILE), "rb") as f:
                gz = f.read()
            manifest = parse_manifest(gz)
            if manifest.version != state.get("version"):
                manifest = None
        except (OSError, AssetSyncError):
            manifest = None
    return state, manifest, gz


def write_state(root: str, state: Dict[str, Any], manifest_gz: bytes) -> None:
    sd = os.path.join(root, STATE_DIR)
    os.makedirs(sd, exist_ok=True)
    write_atomic(os.path.join(sd, MANIFEST_FILE), manifest_gz)
    write_atomic(os.path.join(sd, STATE_FILE), json.dumps(state, separators=(",", ":")).encode("utf-8"))


def acquire_lock(root: str) -> Callable[[], None]:
    sd = os.path.join(root, STATE_DIR)
    os.makedirs(sd, exist_ok=True)
    lock = os.path.join(sd, LOCK_FILE)

    def release() -> None:
        try:
            os.remove(lock)
        except OSError:
            pass

    for _ in range(2):
        try:
            fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
        except FileExistsError:
            stale = True
            try:
                with open(lock, "r", encoding="utf-8") as f:
                    info = json.load(f)
                pid = int(info.get("pid", 0))
                if pid and pid != os.getpid() and pid_alive(pid):
                    stale = False
            except (OSError, ValueError):
                pass
            if not stale:
                raise AssetSyncError("another sync is running on %s" % root, code="ELOCKED")
            release()
            continue
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"pid": os.getpid(), "at": int(time.time() * 1000)}, f)
        return release
    raise AssetSyncError("could not lock %s" % root, code="ELOCKED")
