import { gunzipSync } from 'node:zlib';
import { QentimArtError } from './http.js';
import { MAX_CHUNK_SIZE, MAX_MANIFEST_GZ, MAX_MANIFEST_RAW } from './limits.js';

export const STATE_DIR = '.qentimart';

export function validateVersion(v) {
  if (typeof v !== 'string' || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(v)) {
    throw new QentimArtError('invalid release version');
  }
}

const validHash = (s) => typeof s === 'string' && s.length === 64 && /^[a-f0-9]{64}$/.test(s);
const emptyHash = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

/**
 * 解析 gzip 压缩的 manifest。每个文件都会得到一个展开后的 `chunks` 数组，
 * 元素为 { hash, size, offset }。
 */
export function parseManifest(gz) {
  if (gz.length > MAX_MANIFEST_GZ) throw new QentimArtError('compressed manifest exceeds size limit');
  let doc;
  try {
    doc = JSON.parse(gunzipSync(gz, { maxOutputLength: MAX_MANIFEST_RAW }).toString('utf8'));
  } catch (err) {
    throw new QentimArtError(`invalid manifest: ${err.message}`, { cause: err });
  }
  if (doc?.formatVersion !== 1 || !Array.isArray(doc.files)) throw new QentimArtError('invalid manifest format');
  validateVersion(doc.version);
  const files = new Map();
  const names = new Set();
  const directories = new Set();
  const chunkSizes = new Map();
  for (const f of doc.files) {
    validatePath(f?.path);
    const name = f.path.toLowerCase();
    if (names.has(name) || directories.has(name)) throw new QentimArtError('manifest has conflicting paths');
    names.add(name);
    for (let i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
      const parent = name.slice(0, i);
      if (names.has(parent)) throw new QentimArtError('manifest has conflicting paths');
      directories.add(parent);
    }
    if (!validHash(f.hash) || !Number.isSafeInteger(f.size) || f.size < 0 ||
        (f.size === 0 && f.hash !== emptyHash) || (f.chunks != null && !Array.isArray(f.chunks))) {
      throw new QentimArtError('invalid manifest file metadata');
    }
    const refs = f.chunks?.length ? f.chunks : (f.size ? [[f.hash, f.size]] : []);
    const chunks = [];
    let offset = 0;
    for (const ref of refs) {
      if (!Array.isArray(ref) || ref.length !== 2) throw new QentimArtError('invalid manifest chunk');
      const [hash, size] = ref;
      if (!validHash(hash) || !Number.isSafeInteger(size) || size <= 0 || size > MAX_CHUNK_SIZE ||
          (chunkSizes.has(hash) && chunkSizes.get(hash) !== size)) {
        throw new QentimArtError('invalid manifest chunk');
      }
      chunkSizes.set(hash, size);
      chunks.push({ hash, size, offset });
      offset += size;
      if (offset > f.size) throw new QentimArtError('manifest chunk sizes exceed file size');
    }
    if (offset !== f.size || (chunks.length === 1 && chunks[0].hash !== f.hash)) {
      throw new QentimArtError('inconsistent manifest file metadata');
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
  if (typeof p !== 'string' || !p || Buffer.byteLength(p) > 1024 || /[\\<>:"|?*\x00-\x1f]/.test(p)) throw new QentimArtError('unsafe path in manifest');
  const segs = p.split('/');
  if (segs[0].toLowerCase() === STATE_DIR) throw new QentimArtError(`unsafe path ${p}`);
  for (const s of segs) {
    if (!s || /[. ]$/.test(s) || /^(?:con|prn|aux|nul|conin\$|conout\$|com[1-9¹²³]|lpt[1-9¹²³]) *(?:\.|$)/i.test(s)) {
      throw new QentimArtError(`unsafe path ${p}`);
    }
  }
}
