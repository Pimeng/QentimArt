import fs from 'node:fs/promises';
import path from 'node:path';
import { QentimArtError } from './http.js';
import { sha256hex } from './frames.js';
import { parseManifest, STATE_DIR } from './manifest.js';
import { pool, scanDir, hashFile, writeFileAtomic, renameRetry, pruneEmptyDirs } from './localfs.js';

export class SyncConflictError extends QentimArtError {
  constructor(conflicts) {
    super(`${conflicts.length} locally modified/untracked file(s) block the sync (localChanges='fail'): ${conflicts.slice(0, 10).join(', ')}${conflicts.length > 10 ? ', ...' : ''}`, { code: 'ECONFLICT' });
    this.name = 'SyncConflictError';
    this.conflicts = conflicts;
  }
}

const STATE_FILE = 'state.json';
const MANIFEST_FILE = 'manifest.json.gz';
const TMP_DIR = 'tmp';
const LOCK_FILE = 'lock';

// ---------------------------------------------------------------- 本地状态

export async function readState(dir) {
  const sd = path.join(dir, STATE_DIR);
  let state = null;
  let manifest = null;
  let manifestGz = null;
  try {
    state = JSON.parse(await fs.readFile(path.join(sd, STATE_FILE), 'utf8'));
    if (state.formatVersion !== 1) state = null;
  } catch {}
  if (state) {
    try {
      manifestGz = await fs.readFile(path.join(sd, MANIFEST_FILE));
      manifest = parseManifest(manifestGz);
      if (manifest.version !== state.version) manifest = null;
    } catch {
      manifest = null;
    }
  }
  return { state, manifest, manifestGz };
}

async function writeState(dir, state, manifestGz) {
  const sd = path.join(dir, STATE_DIR);
  await fs.mkdir(sd, { recursive: true });
  await writeFileAtomic(path.join(sd, MANIFEST_FILE), manifestGz);
  await writeFileAtomic(path.join(sd, STATE_FILE), JSON.stringify(state));
}

// 进程内已持锁的目录（绝对路径）。文件锁的陈旧抢占含 "pid≠自己" 判定，故同进程内
// 并发同步同目录时彼此会误判对方锁为陈旧并互夺；这一层在文件锁之前提供进程内互斥。
const inProcessLocks = new Set();

async function acquireLock(dir) {
  const key = path.resolve(dir);
  if (inProcessLocks.has(key)) {
    throw new QentimArtError(`another sync is running on ${dir} (this process)`, { code: 'ELOCKED' });
  }
  inProcessLocks.add(key);
  try {
    const sd = path.join(dir, STATE_DIR);
    await fs.mkdir(sd, { recursive: true });
    const lock = path.join(sd, LOCK_FILE);
    for (let i = 0; i < 2; i++) {
      try {
        const fh = await fs.open(lock, 'wx');
        await fh.writeFile(JSON.stringify({ pid: process.pid, at: Date.now() }));
        await fh.close();
        return async () => {
          inProcessLocks.delete(key);
          await fs.rm(lock, { force: true });
        };
      } catch (err) {
        if (err.code !== 'EEXIST') throw err;
        let stale = true;
        try {
          const info = JSON.parse(await fs.readFile(lock, 'utf8'));
          if (info.pid && info.pid !== process.pid) {
            try {
              process.kill(info.pid, 0);
              stale = false;
            } catch {}
          }
        } catch {}
        if (!stale) throw new QentimArtError(`another sync is running on ${dir}`, { code: 'ELOCKED' });
        await fs.rm(lock, { force: true }); // 只清理陈旧锁文件，保留进程内持有
      }
    }
    throw new QentimArtError(`could not lock ${dir}`, { code: 'ELOCKED' });
  } catch (err) {
    inProcessLocks.delete(key);
    throw err;
  }
}

// ---------------------------------------------------------------- 计划

/**
 * 将本地目录与目标 manifest 进行比较。
 * 只有 size/mtime 与已记录状态不一致的文件才会计算哈希。
 */
async function buildPlan(ctx, target) {
  const { dir, opts, local, oldManifest, tracked } = ctx;
  const policy = opts.localChanges;
  const old = oldManifest?.files ?? new Map();
  const isClean = (p, l) => {
    const o = old.get(p);
    return tracked[p] !== undefined && tracked[p] === l.mtimeMs && o && o.size === l.size && !opts.verifyAll;
  };

  const plan = { toWrite: [], toDelete: [], unchanged: [], adopted: [], kept: [], conflicts: [], newTracked: {} };
  const toHash = [];

  for (const t of target.files.values()) {
    const l = local.files.get(t.path);
    if (!l) {
      if (local.dirs.has(t.path) && policy !== 'overwrite') {
        if (policy === 'keep') plan.kept.push(t.path);
        else plan.conflicts.push(t.path);
        continue;
      }
      plan.toWrite.push({ t, reason: local.dirs.has(t.path) ? 'replace-dir' : 'create', local: null });
      continue;
    }
    if (isClean(t.path, l)) {
      const o = old.get(t.path);
      if (o.hash === t.hash) {
        plan.unchanged.push(t.path);
        plan.newTracked[t.path] = l.mtimeMs;
      } else {
        plan.toWrite.push({ t, reason: 'update', local: l, clean: true });
      }
      continue;
    }
    if (l.size !== t.size) {
      decideModified(plan, policy, t, l);
      continue;
    }
    toHash.push({ t, l });
  }

  let hashed = 0;
  const hashTotal = toHash.reduce((n, x) => n + x.l.size, 0);
  await pool(toHash, 4, async ({ t, l }) => {
    const h = await hashFile(path.join(dir, t.path));
    hashed += l.size;
    ctx.progress('hash', hashed, hashTotal);
    if (h === t.hash) {
      plan.adopted.push(t.path);
      plan.newTracked[t.path] = l.mtimeMs;
    } else {
      decideModified(plan, policy, t, l);
    }
  });

  for (const [p, l] of local.files) {
    if (target.files.has(p)) continue;
    // 安全措施：对于从未同步过的目录，未知文件只有在显式要求时才会被删除
    // （防止误传了错误的 `dir`）。
    const mayDelete = policy === 'overwrite' && (ctx.hasState || opts.deleteUntrackedOnFirstSync);
    if (isClean(p, l) || mayDelete) plan.toDelete.push(p);
    else if (policy === 'overwrite') plan.kept.push(p);
    else if (policy === 'keep') plan.kept.push(p);
    else plan.conflicts.push(p);
  }
  plan.toWrite.sort((a, b) => (a.t.path < b.t.path ? -1 : 1));
  plan.toDelete.sort();
  return plan;
}

function decideModified(plan, policy, t, l) {
  if (policy === 'overwrite') plan.toWrite.push({ t, reason: 'update', local: l, clean: false });
  else if (policy === 'keep') plan.kept.push(t.path);
  else plan.conflicts.push(t.path);
}

/**
 * 本地 chunk 来源：磁盘上所有（可能）已经包含某个 chunk 的位置。
 * 每个条目在使用前都会经过哈希校验，因此过期或推测性的条目是无害的。
 */
class SourceIndex {
  constructor() {
    this.byHash = new Map();
    this.gen = new Map();
  }
  add(p, chunks, maxSize = Infinity) {
    const g = this.gen.get(p) ?? 0;
    for (const c of chunks) {
      if (c.offset + c.size > maxSize) continue;
      let arr = this.byHash.get(c.hash);
      if (!arr) this.byHash.set(c.hash, (arr = []));
      if (arr.length < 4) arr.push({ path: p, offset: c.offset, size: c.size, gen: g });
    }
  }
  replaced(p, newChunks) {
    this.gen.set(p, (this.gen.get(p) ?? 0) + 1);
    this.add(p, newChunks);
  }
  candidates(hash) {
    const arr = this.byHash.get(hash);
    if (!arr) return [];
    return arr.filter((e) => e.gen === (this.gen.get(e.path) ?? 0));
  }
}

function buildSources(ctx, target, plan) {
  const src = new SourceIndex();
  const old = ctx.oldManifest?.files ?? new Map();
  const unchanged = new Set([...plan.unchanged, ...plan.adopted]);
  for (const p of unchanged) src.add(p, target.files.get(p).chunks);
  for (const w of plan.toWrite) {
    if (!w.local) continue;
    const o = old.get(w.t.path);
    if (o) src.add(w.t.path, o.chunks, w.local.size);
    if (!w.clean) src.add(w.t.path, w.t.chunks, w.local.size); // 推测：布局与目标相同
  }
  for (const p of plan.toDelete) {
    const o = old.get(p);
    const l = ctx.local.files.get(p);
    if (o && l) src.add(p, o.chunks, l.size); // 被移动/重命名的资产直接在本地复制
  }
  return src;
}

// ---------------------------------------------------------------- 执行

/**
 * 将 `dir` 更新到目标版本。选项参见 QentimArtClient.sync。
 */
export async function runSync(client, dir, opts) {
  const started = Date.now();
  opts = {
    version: 'latest',
    localChanges: 'overwrite',
    prerelease: false,
    verifyAll: false,
    dryRun: false,
    ...opts,
  };
  if (!['overwrite', 'keep', 'fail'].includes(opts.localChanges)) throw new QentimArtError(`invalid localChanges '${opts.localChanges}'`);
  dir = path.resolve(dir);
  await fs.mkdir(dir, { recursive: true });

  const release = opts.dryRun ? async () => {} : await acquireLock(dir);
  const stats = { written: 0, reusedLocal: 0, downloadedRaw: 0, downloadedWire: 0 };
  let lastEmit = 0;
  const progress = (phase, done, total, force = false) => {
    if (!opts.onProgress) return;
    const now = Date.now();
    if (!force && now - lastEmit < 200 && done < total) return;
    lastEmit = now;
    const secs = Math.max(0.001, (now - started) / 1000);
    opts.onProgress({ phase, done, total, downloadedBytes: stats.downloadedWire, bytesPerSecond: Math.round(stats.downloadedWire / secs) });
  };

  try {
    const { state, manifest: oldManifest, manifestGz: oldGz } = await readState(dir);
    const sameProject = state && state.project === client.project;

    // 解析目标版本并加载其 manifest（未变化时复用本地副本）。
    progress('resolve', 0, 1, true);
    let version = opts.version;
    if (version === 'latest') version = (await client.getVersion('latest', { prerelease: opts.prerelease })).version;
    let targetGz;
    if (sameProject && oldManifest && oldManifest.version === version) targetGz = oldGz;
    else targetGz = (await client.fetchManifestRaw(version)).gz;
    const target = parseManifest(targetGz);

    progress('scan', 0, 1, true);
    const local = await scanDir(dir);
    const ctx = {
      dir,
      opts,
      local,
      oldManifest: sameProject ? oldManifest : null,
      tracked: sameProject ? state.files ?? {} : {},
      hasState: Boolean(sameProject && oldManifest),
      progress,
    };
    const plan = await buildPlan(ctx, target);
    if (plan.conflicts.length && opts.localChanges === 'fail') throw new SyncConflictError(plan.conflicts);

    const sources = buildSources(ctx, target, plan);
    const writeBytes = plan.toWrite.reduce((n, w) => n + w.t.size, 0);
    const result = {
      project: client.project,
      version,
      previousVersion: sameProject ? state.version : null,
      upToDate: plan.toWrite.length === 0 && plan.toDelete.length === 0,
      dryRun: opts.dryRun,
      files: {
        total: target.files.size,
        unchanged: plan.unchanged.length,
        adopted: plan.adopted.length,
        created: plan.toWrite.filter((w) => w.reason !== 'update').length,
        updated: plan.toWrite.filter((w) => w.reason === 'update').length,
        deleted: plan.toDelete.length,
        kept: plan.kept,
      },
      bytes: { total: sumSizes(target), toWrite: writeBytes, ...stats },
      elapsedMs: 0,
      storage: null,
    };

    if (opts.dryRun) {
      result.bytes.estimatedDownload = estimateDownload(plan, sources);
      result.paths = { write: plan.toWrite.map((w) => w.t.path), delete: plan.toDelete, kept: plan.kept };
      result.elapsedMs = Date.now() - started;
      return result;
    }

    const newTracked = plan.newTracked;
    if (plan.toWrite.length) {
      await applyWrites(client, dir, opts, plan, sources, stats, newTracked, writeBytes, progress);
    }

    // 删除操作放在最后执行，这样待删除的文件仍可作为 chunk 来源。
    progress('cleanup', 0, plan.toDelete.length, true);
    for (const p of plan.toDelete) {
      // 该路径可能已经不存在，或已变成目标版本中的目录（文件 -> 目录）。
      const st = await fs.lstat(path.join(dir, p)).catch(() => null);
      if (!st || st.isDirectory()) continue;
      await fs.rm(path.join(dir, p), { force: true });
      await pruneEmptyDirs(dir, p);
    }
    await fs.rm(path.join(dir, STATE_DIR, TMP_DIR), { recursive: true, force: true });

    const newState = { formatVersion: 1, project: client.project, version, syncedAt: new Date().toISOString(), files: newTracked };
    await writeState(dir, newState, targetGz);

    Object.assign(result.bytes, stats);
    result.elapsedMs = Date.now() - started;
    result.bytesPerSecond = Math.round(stats.downloadedWire / Math.max(0.001, result.elapsedMs / 1000));
    const keptBytes = plan.kept.reduce((n, p) => n + (local.files.get(p)?.size ?? 0), 0);
    const overhead = Buffer.byteLength(JSON.stringify(newState)) + targetGz.length;
    const assetBytes = sumSizes(target) + keptBytes;
    result.storage = { assetBytes, overheadBytes: overhead, ratio: assetBytes ? (assetBytes + overhead) / assetBytes : 1 };
    progress('done', 1, 1, true);
    return result;
  } finally {
    await release();
  }
}

function sumSizes(m) {
  let n = 0;
  for (const f of m.files.values()) n += f.size;
  return n;
}

function estimateDownload(plan, sources) {
  const seen = new Set();
  let n = 0;
  for (const w of plan.toWrite) {
    for (const c of w.t.chunks) {
      if (seen.has(c.hash)) continue;
      seen.add(c.hash);
      if (!sources.candidates(c.hash).length) n += c.size;
    }
  }
  return n;
}

/** 将写入操作拆分为多个批次（wave），每批受字节数和打开文件数限制。 */
function waves(list, maxBytes, maxFiles) {
  const out = [];
  let cur = [];
  let bytes = 0;
  for (const w of list) {
    if (cur.length && (bytes + w.t.size > maxBytes || cur.length >= maxFiles)) {
      out.push(cur);
      cur = [];
      bytes = 0;
    }
    cur.push(w);
    bytes += w.t.size;
  }
  if (cur.length) out.push(cur);
  return out;
}

async function applyWrites(client, dir, opts, plan, sources, stats, newTracked, writeBytes, progress) {
  const tmpDir = path.join(dir, STATE_DIR, TMP_DIR);
  await fs.rm(tmpDir, { recursive: true, force: true });
  await fs.mkdir(tmpDir, { recursive: true });
  const deleteSet = new Set(plan.toDelete);
  let seq = 0;

  for (const wave of waves(plan.toWrite, client.waveBytes, client.waveFiles)) {
    if (opts.signal?.aborted) throw opts.signal.reason ?? new QentimArtError('sync aborted', { code: 'ABORT_ERR' });
    const tasks = wave.map((w) => ({ ...w, tmp: path.join(tmpDir, `${seq++}.part`), fh: null }));
    try {
      await applyWave(client, dir, opts, tasks, sources, stats, newTracked, writeBytes, progress, deleteSet);
    } finally {
      for (const t of tasks) await t.fh?.close().catch(() => {});
    }
  }
}

async function applyWave(client, dir, opts, tasks, sources, stats, newTracked, writeBytes, progress, deleteSet) {
  {
    await pool(tasks, 32, async (task) => {
      task.fh = await fs.open(task.tmp, 'w');
      if (task.t.size > 0) await task.fh.truncate(task.t.size);
    });

    // hash -> { size, dests: [{ fh, offset }] }
    const need = new Map();
    for (const task of tasks) {
      for (const c of task.t.chunks) {
        let n = need.get(c.hash);
        if (!n) need.set(c.hash, (n = { size: c.size, dests: [] }));
        n.dests.push({ fh: task.fh, offset: c.offset });
      }
    }
    const writeChunk = async (n, buf) => {
      await Promise.all(n.dests.map((d) => d.fh.write(buf, 0, buf.length, d.offset)));
      stats.written += buf.length * n.dests.length;
      progress('download', stats.written, writeBytes);
    };

    // 1) 复制本地已存在的 chunk
    const readers = new Map();
    const reader = async (p) => {
      let r = readers.get(p);
      if (!r) readers.set(p, (r = fs.open(path.join(dir, p), 'r').catch(() => null)));
      return r;
    };
    const remaining = [];
    await pool([...need], 16, async ([hash, n]) => {
      for (const s of sources.candidates(hash)) {
        const fh = await reader(s.path);
        if (!fh) continue;
        const buf = Buffer.allocUnsafe(s.size);
        let got = 0;
        try {
          ({ bytesRead: got } = await fh.read(buf, 0, s.size, s.offset));
        } catch {
          continue;
        }
        if (got === s.size && sha256hex(buf) === hash) {
          await writeChunk(n, buf);
          stats.reusedLocal += buf.length;
          return;
        }
      }
      remaining.push(hash);
    });
    for (const r of readers.values()) (await r)?.close().catch(() => {});

    // 2) 获取其余 chunk（动态模式批量拉帧流，静态模式逐块 GET，由 client 决定）
    if (remaining.length) {
      await client.fetchChunks({
        hashes: remaining,
        sizeOf: (h) => need.get(h).size,
        onChunk: (h, raw) => writeChunk(need.get(h), raw),
        stats,
        signal: opts.signal,
      });
    }

    // 3) 关闭文件、移动到目标位置、记录状态
    await pool(tasks, 32, async (task) => {
      await task.fh.close();
      task.fh = null;
    });
    for (const task of tasks) {
      const target = path.join(dir, task.t.path);
      await ensureParent(dir, task.t.path, deleteSet, opts.localChanges);
      if (task.reason === 'replace-dir') await fs.rm(target, { recursive: true, force: true });
      try {
        await renameRetry(task.tmp, target);
      } catch (err) {
        throw new QentimArtError(`cannot replace ${task.t.path} (is it open in another program?): ${err.message}`, { code: err.code, cause: err });
      }
      const st = await fs.stat(target);
      newTracked[task.t.path] = Math.floor(st.mtimeMs);
      sources.replaced(task.t.path, task.t.chunks);
    }
  }
}

// 逐段创建父目录，绝不跟随符号链接：若某个已存在的祖先是符号链接，拒绝写入，
// 以免清单路径合法但本地父目录指向 root 之外时，rename 把文件写到 root 外（逃逸）。
async function ensureParent(dir, rel, deleteSet, policy) {
  const parent = path.posix.dirname(rel);
  if (parent === '.') return;
  const segs = parent.split('/');
  for (let i = 1; i <= segs.length; i++) {
    const p = segs.slice(0, i).join('/');
    const abs = path.join(dir, p);
    const st = await fs.lstat(abs).catch(() => null);
    if (st && st.isSymbolicLink()) {
      throw new QentimArtError(`unsafe path ${rel}: '${p}' is a symlink`, { code: 'EUNSAFE' });
    }
    if (st && !st.isDirectory()) {
      // 该位置是普通文件，但需要它是目录（例如 "a" -> "a/b"）。
      if (!deleteSet.has(p) && policy !== 'overwrite') throw new QentimArtError(`${p} is a file but ${rel} needs it to be a directory`);
      await fs.rm(abs, { force: true });
      deleteSet.delete(p);
      await fs.mkdir(abs);
    } else if (!st) {
      await fs.mkdir(abs);
    }
  }
}

// ---------------------------------------------------------------- 状态查询

export async function runStatus(dir, project) {
  dir = path.resolve(dir);
  const { state, manifest } = await readState(dir);
  if (!state || !manifest || (project && state.project !== project)) {
    return { synced: false, project: state?.project ?? null, version: null, modified: [], missing: [], untracked: [] };
  }
  const local = await scanDir(dir);
  const modified = [];
  const missing = [];
  const untracked = [];
  const suspicious = [];
  for (const f of manifest.files.values()) {
    const l = local.files.get(f.path);
    if (!l) missing.push(f.path);
    else if (l.size !== f.size) modified.push(f.path);
    else if (state.files[f.path] !== l.mtimeMs) suspicious.push(f);
  }
  await pool(suspicious, 4, async (f) => {
    if ((await hashFile(path.join(dir, f.path))) !== f.hash) modified.push(f.path);
  });
  for (const p of local.files.keys()) if (!manifest.files.has(p)) untracked.push(p);
  modified.sort();
  untracked.sort();
  return { synced: true, project: state.project, version: state.version, syncedAt: state.syncedAt, clean: !modified.length && !missing.length && !untracked.length, modified, missing, untracked };
}

/** 按当前 manifest 对每个文件计算哈希并比对（用于发现静默损坏）。 */
export async function runVerify(dir, onProgress) {
  dir = path.resolve(dir);
  const { state, manifest } = await readState(dir);
  if (!state || !manifest) throw new QentimArtError(`${dir} has not been synced yet`);
  const bad = [];
  let done = 0;
  const files = [...manifest.files.values()];
  const total = files.reduce((n, f) => n + f.size, 0);
  await pool(files, 4, async (f) => {
    let h = null;
    try {
      h = await hashFile(path.join(dir, f.path));
    } catch {}
    if (h !== f.hash) bad.push(f.path);
    done += f.size;
    onProgress?.({ phase: 'verify', done, total });
  });
  return { version: state.version, ok: bad.length === 0, corrupted: bad.sort() };
}

