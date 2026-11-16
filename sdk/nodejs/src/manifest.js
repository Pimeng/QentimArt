import { gunzipSync } from 'node:zlib';
import { QentimArtError } from './http.js';

export const STATE_DIR = '.qentimart';

/**
 * 解析 gzip 压缩的 manifest。每个文件都会得到一个展开后的 `chunks` 数组，
 * 元素为 { hash, size, offset }。
 */
export function parseManifest(gz) {
  let doc;
  try {
    doc = JSON.parse(gunzipSync(gz).toString('utf8'));
  } catch (err) {
    throw new QentimArtError(`invalid manifest: ${err.message}`, { cause: err });
  }
  if (doc.formatVersion !== 1) throw new QentimArtError(`unsupported manifest formatVersion ${doc.formatVersion}`);
  const files = new Map();
  for (const f of doc.files) {
    validatePath(f.path);
    let chunks;
    if (f.chunks && f.chunks.length) {
      let off = 0;
      chunks = f.chunks.map(([hash, size]) => {
        const c = { hash, size, offset: off };
        off += size;
        return c;
      });
      if (off !== f.size) throw new QentimArtError(`manifest: chunk sizes of ${f.path} do not add up`);
    } else {
      chunks = f.size > 0 ? [{ hash: f.hash, size: f.size, offset: 0 }] : [];
    }
    files.set(f.path, { path: f.path, size: f.size, hash: f.hash, chunks });
  }
  return { version: doc.version, createdAt: doc.createdAt, files };
}

/**
 * 在客户端比较两份 manifest（静态模式没有服务端的 /diff 接口时使用）。
 * downloadBytes 为 b 中不在 a 里的块的原始字节数。
 */
export function diffManifests(a, b) {
  const added = [];
  const modified = [];
  const removed = [];
  for (const [p, f] of b.files) {
    const o = a.files.get(p);
    if (!o) added.push(p);
    else if (o.hash !== f.hash) modified.push(p);
  }
  for (const p of a.files.keys()) if (!b.files.has(p)) removed.push(p);
  const have = new Set();
  for (const f of a.files.values()) for (const c of f.chunks) have.add(c.hash);
  let downloadBytes = 0;
  let totalBytes = 0;
  const seen = new Set();
  for (const f of b.files.values()) {
    totalBytes += f.size;
    for (const c of f.chunks) {
      if (seen.has(c.hash)) continue;
      seen.add(c.hash);
      if (!have.has(c.hash)) downloadBytes += c.size;
    }
  }
  return { from: a.version, to: b.version, added: added.sort(), modified: modified.sort(), removed: removed.sort(), downloadBytes, totalBytes };
}

export function validatePath(p) {
  if (typeof p !== 'string' || !p || p.includes('\\') || p.includes('\0')) throw new QentimArtError(`unsafe path ${JSON.stringify(p)}`);
  const segs = p.split('/');
  if (segs[0].toLowerCase() === STATE_DIR) throw new QentimArtError(`unsafe path ${p}`);
  for (const s of segs) {
    if (!s || s === '.' || s === '..' || /^[a-zA-Z]:$/.test(s)) throw new QentimArtError(`unsafe path ${p}`);
  }
}
