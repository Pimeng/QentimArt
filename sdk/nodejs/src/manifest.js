import { gunzipSync } from 'node:zlib';
import { AssetSyncError } from './http.js';

export const STATE_DIR = '.assetsync';

/**
 * 解析 gzip 压缩的 manifest。每个文件都会得到一个展开后的 `chunks` 数组，
 * 元素为 { hash, size, offset }。
 */
export function parseManifest(gz) {
  let doc;
  try {
    doc = JSON.parse(gunzipSync(gz).toString('utf8'));
  } catch (err) {
    throw new AssetSyncError(`invalid manifest: ${err.message}`, { cause: err });
  }
  if (doc.formatVersion !== 1) throw new AssetSyncError(`unsupported manifest formatVersion ${doc.formatVersion}`);
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
      if (off !== f.size) throw new AssetSyncError(`manifest: chunk sizes of ${f.path} do not add up`);
    } else {
      chunks = f.size > 0 ? [{ hash: f.hash, size: f.size, offset: 0 }] : [];
    }
    files.set(f.path, { path: f.path, size: f.size, hash: f.hash, chunks });
  }
  return { version: doc.version, createdAt: doc.createdAt, files };
}

export function validatePath(p) {
  if (typeof p !== 'string' || !p || p.includes('\\') || p.includes('\0')) throw new AssetSyncError(`unsafe path ${JSON.stringify(p)}`);
  const segs = p.split('/');
  if (segs[0].toLowerCase() === STATE_DIR) throw new AssetSyncError(`unsafe path ${p}`);
  for (const s of segs) {
    if (!s || s === '.' || s === '..' || /^[a-zA-Z]:$/.test(s)) throw new AssetSyncError(`unsafe path ${p}`);
  }
}
