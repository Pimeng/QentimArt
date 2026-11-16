"""块帧流 + manifest 解析（参见 docs/PROTOCOL.md）。"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import struct
import zlib
from typing import Dict, Iterator, List, NamedTuple, Tuple

from ._errors import AssetSyncError

STATE_DIR = ".assetsync"
FRAME_HEADER_LEN = 41
ENC_RAW = 0
ENC_DEFLATE = 1
ENC_MISSING = 0xFF
_HDR = struct.Struct(">32sBII")
_READ_SIZE = 1 << 20


def iter_frames(resp) -> Iterator[Tuple[str, int, int, bytes]]:
    """从 HTTP 响应中逐个产出 (hash_hex, enc, raw_len, payload)。"""
    buf = bytearray()
    pos = 0
    eof = False

    def fill(n: int) -> bool:
        nonlocal buf, pos, eof
        while len(buf) - pos < n and not eof:
            if pos and pos > (1 << 22):
                del buf[:pos]
                pos = 0
            data = resp.read(max(_READ_SIZE, n - (len(buf) - pos)))
            if not data:
                eof = True
                break
            buf += data
        return len(buf) - pos >= n

    while True:
        if not fill(FRAME_HEADER_LEN):
            if len(buf) - pos:
                raise AssetSyncError("truncated chunk stream", code="ETRUNCATED")
            return
        h, enc, raw_len, stored = _HDR.unpack_from(buf, pos)
        pos += FRAME_HEADER_LEN
        if not fill(stored):
            raise AssetSyncError("truncated chunk stream", code="ETRUNCATED")
        payload = bytes(buf[pos:pos + stored])
        pos += stored
        yield h.hex(), enc, raw_len, payload


def decode_chunk(hash_hex: str, enc: int, raw_len: int, payload: bytes) -> bytes:
    if enc == ENC_RAW:
        raw = payload
    elif enc == ENC_DEFLATE:
        try:
            raw = zlib.decompress(payload, -15)
        except zlib.error as e:
            raise AssetSyncError("chunk %s: bad deflate data: %s" % (hash_hex, e), code="ECORRUPT")
    elif enc == ENC_MISSING:
        raise AssetSyncError("chunk %s is missing on the server" % hash_hex, code="EMISSING")
    else:
        raise AssetSyncError("unknown chunk encoding %d" % enc)
    if len(raw) != raw_len or hashlib.sha256(raw).hexdigest() != hash_hex:
        raise AssetSyncError("chunk %s failed verification" % hash_hex, code="ECORRUPT")
    return raw


class Chunk(NamedTuple):
    hash: str
    size: int
    offset: int


class ManifestFile(NamedTuple):
    path: str
    size: int
    hash: str
    chunks: List[Chunk]


class Manifest(NamedTuple):
    version: str
    created_at: str
    files: Dict[str, ManifestFile]


_DRIVE = re.compile(r"^[a-zA-Z]:$")


def validate_path(p: str) -> None:
    if not isinstance(p, str) or not p or "\\" in p or "\0" in p:
        raise AssetSyncError("unsafe path %r" % (p,))
    segs = p.split("/")
    if segs[0].lower() == STATE_DIR:
        raise AssetSyncError("unsafe path %r" % p)
    for s in segs:
        if not s or s in (".", "..") or _DRIVE.match(s):
            raise AssetSyncError("unsafe path %r" % p)


def parse_manifest(gz: bytes) -> Manifest:
    try:
        doc = json.loads(gzip.decompress(gz).decode("utf-8"))
    except Exception as e:
        raise AssetSyncError("invalid manifest: %s" % e)
    if doc.get("formatVersion") != 1:
        raise AssetSyncError("unsupported manifest formatVersion %r" % doc.get("formatVersion"))
    files: Dict[str, ManifestFile] = {}
    for f in doc["files"]:
        path, size, fh = f["path"], int(f["size"]), f["hash"]
        validate_path(path)
        raw_chunks = f.get("chunks")
        chunks: List[Chunk] = []
        if raw_chunks:
            off = 0
            for h, sz in raw_chunks:
                chunks.append(Chunk(h, int(sz), off))
                off += int(sz)
            if off != size:
                raise AssetSyncError("manifest: chunk sizes of %s do not add up" % path)
        elif size > 0:
            chunks.append(Chunk(fh, size, 0))
        files[path] = ManifestFile(path, size, fh, chunks)
    return Manifest(doc.get("version", ""), doc.get("createdAt", ""), files)
