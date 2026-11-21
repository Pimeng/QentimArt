"""块帧流 + manifest 解析（参见 docs/PROTOCOL.md）。"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import struct
import zlib
from typing import Dict, Iterator, List, NamedTuple, Tuple

from ._errors import QentimArtError

STATE_DIR = ".qentimart"
FRAME_HEADER_LEN = 41
ENC_RAW = 0
ENC_DEFLATE = 1
ENC_MISSING = 0xFF
_HDR = struct.Struct(">32sBII")
_READ_SIZE = 1 << 20
MAX_CHUNK_SIZE = 16 << 20
MAX_STORED = MAX_CHUNK_SIZE + 1024
MAX_MANIFEST_GZ = 256 << 20
MAX_MANIFEST_RAW = 1 << 30
MAX_JSON = 64 << 20


def read_capped(stream, limit: int) -> bytes:
    out = io.BytesIO()
    while True:
        part = stream.read(min(_READ_SIZE, limit + 1 - out.tell()))
        if not part:
            return out.getvalue()
        if len(part) > limit - out.tell():
            raise QentimArtError("response exceeds %d bytes" % limit, code="ETOOBIG")
        out.write(part)


def gunzip_capped(data: bytes, limit: int) -> bytes:
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
        return read_capped(stream, limit)


def inflate_capped(data: bytes, wbits: int, limit: int) -> bytes:
    dec = zlib.decompressobj(wbits)
    out = dec.decompress(data, limit + 1)
    if len(out) > limit:
        raise QentimArtError("inflated data exceeds %d bytes" % limit, code="ETOOBIG")
    if not dec.eof or dec.unused_data:
        raise QentimArtError("invalid deflate stream", code="ECORRUPT")
    return out


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
                raise QentimArtError("truncated chunk stream", code="ETRUNCATED")
            return
        h, enc, raw_len, stored = _HDR.unpack_from(buf, pos)
        pos += FRAME_HEADER_LEN
        if raw_len > MAX_CHUNK_SIZE or stored > MAX_STORED:
            raise QentimArtError("chunk frame exceeds size limit", code="ETOOBIG")
        if not fill(stored):
            raise QentimArtError("truncated chunk stream", code="ETRUNCATED")
        payload = bytes(buf[pos:pos + stored])
        pos += stored
        yield h.hex(), enc, raw_len, payload


def decode_chunk(hash_hex: str, enc: int, raw_len: int, payload: bytes) -> bytes:
    if not 0 <= raw_len <= MAX_CHUNK_SIZE or len(payload) > MAX_STORED:
        raise QentimArtError("chunk exceeds size limit", code="ETOOBIG")
    if enc == ENC_RAW:
        raw = payload
    elif enc == ENC_DEFLATE:
        try:
            raw = inflate_capped(payload, -15, raw_len)
        except zlib.error as e:
            raise QentimArtError("chunk %s: bad deflate data: %s" % (hash_hex, e), code="ECORRUPT")
    elif enc == ENC_MISSING:
        raise QentimArtError("chunk %s is missing on the server" % hash_hex, code="EMISSING")
    else:
        raise QentimArtError("unknown chunk encoding %d" % enc)
    if len(raw) != raw_len or hashlib.sha256(raw).hexdigest() != hash_hex:
        raise QentimArtError("chunk %s failed verification" % hash_hex, code="ECORRUPT")
    return raw


def decode_blob(hash_hex: str, buf: bytes) -> bytes:
    """解码一个静态 blob 对象（enc u8 | rawLen u32be | payload），校验哈希。"""
    if len(buf) < 5:
        raise QentimArtError("chunk %s: blob too short" % hash_hex, code="ECORRUPT")
    enc = buf[0]
    raw_len = int.from_bytes(buf[1:5], "big")
    return decode_chunk(hash_hex, enc, raw_len, buf[5:])


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


_HASH = re.compile(r"[0-9a-f]{64}")
_DEVICE = re.compile(r"(?:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|(?:COM|LPT)[1-9¹²³])", re.I | re.ASCII)
_UNSAFE_CHAR = re.compile(r'[\x00-\x1f\ud800-\udfff\\<>:"|?*]')
_EMPTY_HASH = hashlib.sha256(b"").hexdigest()


def validate_path(p: str) -> None:
    if not isinstance(p, str) or not p or len(p.encode("utf-8", "surrogatepass")) > 1024 or _UNSAFE_CHAR.search(p):
        raise QentimArtError("unsafe path %r" % (p,))
    segs = p.split("/")
    if segs[0].lower() == STATE_DIR:
        raise QentimArtError("unsafe path %r" % p)
    for s in segs:
        if not s or s.endswith((".", " ")) or _DEVICE.fullmatch(s.partition(".")[0].rstrip(" ")):
            raise QentimArtError("unsafe path %r" % p)


def diff_manifests(a: "Manifest", b: "Manifest") -> dict:
    """在客户端比较两份 manifest（静态模式没有服务端 /diff 接口时使用）。"""
    added, modified, removed = [], [], []
    for p, f in b.files.items():
        o = a.files.get(p)
        if o is None:
            added.append(p)
        elif o.hash != f.hash:
            modified.append(p)
    for p in a.files:
        if p not in b.files:
            removed.append(p)
    have = set()
    for f in a.files.values():
        for c in f.chunks:
            have.add(c.hash)
    download = 0
    total = 0
    seen = set()
    for f in b.files.values():
        total += f.size
        for c in f.chunks:
            if c.hash in seen:
                continue
            seen.add(c.hash)
            if c.hash not in have:
                download += c.size
    return {"from": a.version, "to": b.version, "added": sorted(added), "modified": sorted(modified),
            "removed": sorted(removed), "downloadBytes": download, "totalBytes": total}


def parse_manifest(gz: bytes) -> Manifest:
    if len(gz) > MAX_MANIFEST_GZ:
        raise QentimArtError("compressed manifest exceeds size limit", code="ETOOBIG")
    try:
        doc = json.loads(gunzip_capped(gz, MAX_MANIFEST_RAW).decode("utf-8"))
    except (ValueError, OSError, EOFError, zlib.error, RecursionError) as e:
        raise QentimArtError("invalid manifest: %s" % e)
    if (not isinstance(doc, dict) or type(doc.get("formatVersion")) is not int or doc["formatVersion"] != 1
            or not isinstance(doc.get("files"), list) or not isinstance(doc.get("version"), str)):
        raise QentimArtError("invalid manifest header", code="ECORRUPT")
    files: Dict[str, ManifestFile] = {}
    names = set()
    dirs = set()
    sizes: Dict[str, int] = {}
    for f in doc["files"]:
        if not isinstance(f, dict):
            raise QentimArtError("invalid file record", code="ECORRUPT")
        path, size, fh = f.get("path"), f.get("size"), f.get("hash")
        validate_path(path)
        key = path.lower()
        if key in names or key in dirs:
            raise QentimArtError("conflicting manifest path %s" % path, code="ECORRUPT")
        names.add(key)
        parent = key.rpartition("/")[0]
        while parent:
            if parent in names:
                raise QentimArtError("conflicting manifest path %s" % path, code="ECORRUPT")
            dirs.add(parent)
            parent = parent.rpartition("/")[0]
        if (type(size) is not int or size < 0 or not isinstance(fh, str) or not _HASH.fullmatch(fh)
                or (size == 0 and fh != _EMPTY_HASH)):
            raise QentimArtError("invalid size or hash for %s" % path, code="ECORRUPT")
        raw_chunks = f.get("chunks")
        if raw_chunks is not None and not isinstance(raw_chunks, list):
            raise QentimArtError("invalid chunks for %s" % path, code="ECORRUPT")
        chunks: List[Chunk] = []
        off = 0
        for pair in raw_chunks or ([[fh, size]] if size else []):
            if not isinstance(pair, list) or len(pair) != 2:
                raise QentimArtError("invalid chunk reference for %s" % path, code="ECORRUPT")
            h, sz = pair
            if (not isinstance(h, str) or not _HASH.fullmatch(h) or type(sz) is not int
                    or not 0 < sz <= MAX_CHUNK_SIZE or sizes.setdefault(h, sz) != sz):
                raise QentimArtError("invalid chunk for %s" % path, code="ECORRUPT")
            chunks.append(Chunk(h, sz, off))
            off += sz
        if off != size or (len(chunks) == 1 and fh != chunks[0].hash):
            raise QentimArtError("inconsistent file record for %s" % path, code="ECORRUPT")
        files[path] = ManifestFile(path, size, fh, chunks)
    return Manifest(doc.get("version", ""), doc.get("createdAt", ""), files)
