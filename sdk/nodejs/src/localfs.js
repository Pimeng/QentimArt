import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import path from 'node:path';
import { STATE_DIR } from './manifest.js';
import { QentimArtError, readCapped } from './http.js';

export async function stateDirectory(dir, create = false) {
  const sd = path.join(dir, STATE_DIR);
  if (create) await fs.mkdir(sd, { recursive: true, mode: 0o700 });
  const st = await fs.lstat(sd).catch((err) => {
    if (err.code !== 'ENOENT') throw err;
  });
  if (st && !st.isDirectory()) throw new QentimArtError('unsafe state directory', { code: 'EUNSAFE' });
  return sd;
}

export async function openRegularFile(file) {
  const st = await fs.lstat(file);
  if (!st.isFile()) throw new QentimArtError('unsafe file type', { code: 'EUNSAFE' });
  const flags = constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0);
  const fh = await fs.open(file, flags);
  try {
    if (!(await fh.stat()).isFile()) throw new QentimArtError('unsafe file type', { code: 'EUNSAFE' });
    return fh;
  } catch (err) {
    await fh.close();
    throw err;
  }
}

export async function readRegularFile(file, maxBytes, encoding) {
  const fh = await openRegularFile(file);
  try {
    if ((await fh.stat()).size > maxBytes) throw new QentimArtError('state file exceeds size limit', { code: 'ETOOLARGE' });
    const data = await readCapped(fh.createReadStream(), maxBytes);
    return encoding ? data.toString(encoding) : data;
  } finally {
    await fh.close();
  }
}

/** 对 items 逐个执行 fn，同时进行中的任务最多 n 个。 */
export async function pool(items, n, fn) {
  let i = 0;
  let failed = null;
  const worker = async () => {
    while (i < items.length && !failed) {
      const idx = i++;
      try {
        await fn(items[idx], idx);
      } catch (err) {
        failed ??= err;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(n, items.length)) }, worker));
  if (failed) throw failed;
}

/**
 * 递归列出 dir 下的普通文件（跳过状态目录）。
 * 返回 Map<relPath, { size, mtimeMs, isDir }>。目录仅在其为调用方可能关心的
 * 空叶子目录时才会被报告（实际上从不报告）。
 */
export async function scanDir(dir) {
  const out = new Map();
  const dirs = new Set();
  const other = new Set();
  const walk = async (abs, rel) => {
    let entries;
    try {
      entries = await fs.readdir(abs, { withFileTypes: true });
    } catch (err) {
      if (err.code === 'ENOENT') return;
      throw err;
    }
    const files = [];
    const subdirs = [];
    for (const e of entries) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (!rel && e.name.toLowerCase() === STATE_DIR) continue;
      if (e.isDirectory()) subdirs.push([path.join(abs, e.name), r]);
      else if (e.isFile()) files.push([path.join(abs, e.name), r]);
      else other.add(r);
    }
    await pool(files, 64, async ([a, r]) => {
      const st = await fs.stat(a);
      out.set(r, { size: st.size, mtimeMs: Math.floor(st.mtimeMs) });
    });
    for (const [a, r] of subdirs) {
      dirs.add(r);
      await walk(a, r);
    }
  };
  await walk(dir, '');
  return { files: out, dirs, other };
}

/** 以流式方式计算文件的 SHA-256。 */
export async function hashFile(abs) {
  const fh = await openRegularFile(abs);
  try {
    const h = createHash('sha256');
    for await (const c of fh.createReadStream({ highWaterMark: 1 << 20 })) h.update(c);
    return h.digest('hex');
  } finally {
    await fh.close();
  }
}

export async function writeFileAtomic(target, data) {
  const tmp = `${target}.${randomUUID()}.tmp`;
  const fh = await fs.open(tmp, 'wx', 0o600);
  try {
    try {
      await fh.writeFile(data);
    } finally {
      await fh.close();
    }
    await renameRetry(tmp, target);
  } finally {
    await fs.rm(tmp, { force: true });
  }
}

/** 带重试的 rename，用于应对 Windows 上短暂的共享冲突。 */
export async function renameRetry(from, to, attempts = 8) {
  for (let i = 0; ; i++) {
    try {
      await fs.rename(from, to);
      return;
    } catch (err) {
      if (i >= attempts || !['EPERM', 'EBUSY', 'EACCES'].includes(err.code)) throw err;
      await new Promise((r) => setTimeout(r, 50 * 2 ** Math.min(i, 5)));
    }
  }
}

export async function dirBytes(abs) {
  let n = 0;
  let entries;
  try {
    entries = await fs.readdir(abs, { withFileTypes: true });
  } catch (err) {
    if (err.code === 'ENOENT') return 0;
    throw err;
  }
  for (const e of entries) {
    const p = path.join(abs, e.name);
    if (e.isDirectory()) n += await dirBytes(p);
    else if (e.isFile()) n += (await fs.stat(p)).size;
  }
  return n;
}

/** 删除 rel 的空父目录，直到 root 为止（不包括 root）。 */
export async function pruneEmptyDirs(root, rel) {
  let d = path.posix.dirname(rel);
  while (d && d !== '.') {
    try {
      await fs.rmdir(path.join(root, d));
    } catch {
      return;
    }
    d = path.posix.dirname(d);
  }
}
