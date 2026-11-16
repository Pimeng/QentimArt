"""静态模式的只读传输：从公开 baseUrl 按对象键 GET，不需要鉴权。"""
from __future__ import annotations

import http.client
import json
import ssl
import threading
import time
from typing import Any, Callable, Optional
from urllib.parse import urlsplit

from ._errors import QentimArtError
from ._localfs import run_pool
from ._wire import decode_blob


class StaticTransport:
    def __init__(self, base_url: str, concurrency: int = 8, timeout: float = 60.0, retries: int = 3):
        u = urlsplit(base_url)
        if u.scheme not in ("http", "https") or not u.hostname:
            raise QentimArtError("invalid base_url %r" % base_url)
        self.https = u.scheme == "https"
        self.host = u.hostname
        self.port = u.port
        self.base_path = u.path.rstrip("/")
        self.timeout = timeout
        self.retries = retries
        self.concurrency = concurrency
        self._local = threading.local()
        self._lock = threading.Lock()
        self._conns: set = set()
        self._index: Optional[dict] = None
        self._index_at = 0.0

    def _conn(self) -> http.client.HTTPConnection:
        c = getattr(self._local, "conn", None)
        if c is None:
            if self.https:
                c = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=ssl.create_default_context())
            else:
                c = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            self._local.conn = c
            with self._lock:
                self._conns.add(c)
        return c

    def _drop_conn(self) -> None:
        c = getattr(self._local, "conn", None)
        if c is not None:
            self._local.conn = None
            with self._lock:
                self._conns.discard(c)
            try:
                c.close()
            except Exception:
                pass

    def close(self) -> None:
        with self._lock:
            conns = list(self._conns)
            self._conns.clear()
        for c in conns:
            try:
                c.close()
            except Exception:
                pass

    def _get(self, rel: str) -> bytes:
        conn = self._conn()
        try:
            conn.request("GET", self.base_path + "/" + rel)
            resp = conn.getresponse()
            data = resp.read()
        except Exception:
            self._drop_conn()
            raise
        if resp.status >= 300:
            raise QentimArtError("GET %s: HTTP %d" % (rel, resp.status), status=resp.status)
        return data

    def get_buffer(self, rel: str, cancel: Optional[threading.Event] = None) -> bytes:
        last: Optional[BaseException] = None
        for attempt in range(self.retries + 1):
            if cancel is not None and cancel.is_set():
                raise QentimArtError("sync cancelled", code="ECANCELED")
            try:
                return self._get(rel)
            except QentimArtError as e:
                if e.status is not None and e.status < 500 and e.status != 429:
                    raise
                last = e
            except (OSError, http.client.HTTPException) as e:
                last = e
            self._drop_conn()
            if attempt < self.retries:
                time.sleep(0.3 * (2 ** attempt))
        raise last if isinstance(last, BaseException) else QentimArtError("GET %s failed" % rel)

    def index(self, cancel: Optional[threading.Event] = None) -> dict:
        now = time.time()
        if self._index is not None and now - self._index_at < 5.0:
            return self._index
        self._index = json.loads(self.get_buffer("index.json", cancel).decode("utf-8"))
        self._index_at = now
        return self._index

    def fetch_chunks(self, hashes, on_chunk, stats, cancel) -> None:
        def one(h: str) -> None:
            buf = self.get_buffer("chunks/%s/%s" % (h[:2], h), cancel)
            raw = decode_blob(h, buf)
            with stats.lock:
                stats.downloaded_wire += len(buf)
                stats.downloaded_raw += len(raw)
            on_chunk(h, raw)

        run_pool(hashes, self.concurrency, one)


def version_from_index(idx: dict, version: str, prerelease: bool) -> dict:
    v = version
    if version == "latest":
        v = idx.get("latestPrerelease") if prerelease else idx.get("latest")
    if not v:
        raise QentimArtError("project has no versions")
    for x in idx.get("versions", []):
        if x.get("version") == v:
            return x
    raise QentimArtError("version %s not found" % v, status=404)


# 兼容类型注解
_ = (Any, Callable)
