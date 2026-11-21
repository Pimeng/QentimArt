from __future__ import annotations

from typing import List, Optional


class QentimArtError(Exception):
    """基础异常。服务端有响应时，``status`` 为 HTTP 状态码。"""

    def __init__(self, message: str, status: Optional[int] = None, code: Optional[str] = None):
        super().__init__(message)
        self.status = status
        self.code = code


class SyncConflictError(QentimArtError):
    """在 ``local_changes='fail'`` 下，本地改动阻止同步时抛出。"""

    def __init__(self, conflicts: List[str]):
        head = ", ".join(conflicts[:10]) + (", ..." if len(conflicts) > 10 else "")
        super().__init__(
            "%d locally modified/untracked path(s) block the sync: %s" % (len(conflicts), head),
            code="ECONFLICT",
        )
        self.conflicts = conflicts
