from __future__ import annotations

import http.client
import json
import ssl
import threading
import time
from typing import Any, Callable, Dict, Optional, Tuple
from urllib.parse import quote, urlsplit

from ._errors import QentimArtError


class HttpClient:
    """线程安全的 HTTP 客户端：每个线程各自保持一个持久连接。"""

    def __init__(self, server: str, project: str, token: Optional[str], timeout: float = 60.0, retries: int = 3):
        u = urlsplit(server)
        if u.scheme not in ("http", "https") or not u.hostname:
            raise QentimArtError("invalid server URL %r" % server)
        self.https = u.scheme == "https"
        self.host = u.hostname
        self.port = u.port
        self.prefix = u.path.rstrip("/") + "/api/v1/projects/" + quote(project, safe="")
        self.token = token
        self.timeout = timeout
        self.retries = retries
        self._local = threading.local()
        self._lock = threading.Lock()
        self._conns = set()

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

    def drop_conn(self) -> None:
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

    def request(self, method: str, path: str, body: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None) -> http.client.HTTPResponse:
        """发送请求；返回尚未读取的响应（status < 300）。"""
        h = dict(headers or {})
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        conn = self._conn()
        try:
            conn.request(method, self.prefix + path, body=body, headers=h)
            resp = conn.getresponse()
        except Exception:
            self.drop_conn()
            raise
        if resp.status >= 300:
            data = resp.read()
            msg = data.decode("utf-8", "replace")
            try:
                msg = json.loads(msg).get("error", msg)
            except Exception:
                pass
            raise QentimArtError("%s %s: HTTP %d: %s" % (method, path, resp.status, msg), status=resp.status)
        return resp

    def buffer(self, method: str, path: str, body: Optional[bytes] = None) -> Tuple[bytes, Any]:
        def go(_attempt: int) -> Tuple[bytes, Any]:
            resp = self.request(method, path, body)
            try:
                return resp.read(), resp.headers
            except Exception:
                self.drop_conn()
                raise

        return self.with_retry(go)

    def json(self, method: str, path: str) -> Any:
        data, _ = self.buffer(method, path)
        return json.loads(data.decode("utf-8"))

    def with_retry(self, fn: Callable[[int], Any], cancel: Optional[threading.Event] = None) -> Any:
        """对网络错误、被截断的流以及 5xx 响应进行重试。"""
        last: Optional[BaseException] = None
        for attempt in range(self.retries + 1):
            if cancel is not None and cancel.is_set():
                raise QentimArtError("sync cancelled", code="ECANCELED")
            try:
                return fn(attempt)
            except QentimArtError as e:
                if (e.status is not None and e.status < 500) or e.code in ("ECANCELED", "ECONFLICT"):
                    raise
                last = e
            except (OSError, http.client.HTTPException) as e:
                last = e
            self.drop_conn()
            if attempt < self.retries:
                time.sleep(0.3 * (2 ** attempt))
        if isinstance(last, QentimArtError):
            raise last
        raise QentimArtError("request failed after %d attempts: %s" % (self.retries + 1, last), code="ENETWORK")
