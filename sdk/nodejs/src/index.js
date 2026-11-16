import path from 'node:path';
import { HttpClient, QentimArtError } from './http.js';
import { StaticTransport } from './static.js';
import { parseManifest, diffManifests, STATE_DIR } from './manifest.js';
import { parseFrames, decodeFrame } from './frames.js';
import { runSync, runStatus, runVerify, SyncConflictError } from './sync.js';
import { dirBytes, scanDir } from './localfs.js';

export { QentimArtError, SyncConflictError, STATE_DIR };

/** 从 index.json 中按请求的 version（可为 "latest"）解析出版本元数据。 */
function versionFromIndex(idx, version, prerelease) {
  let v = version;
  if (version === 'latest') v = prerelease ? idx.latestPrerelease : idx.latest;
  if (!v) throw new QentimArtError('project has no versions');
  const hit = (idx.versions || []).find((x) => x.version === v);
  if (!hit) throw new QentimArtError(`version ${v} not found`, { status: 404 });
  return hit;
}

/** 动态模式：按字节/数量分批 POST /chunks/fetch，并行拉取帧流。 */
async function apiFetchChunks(client, { hashes, sizeOf, onChunk, stats, signal }) {
  const batches = [];
  let cur = [];
  let curBytes = 0;
  for (const h of hashes) {
    cur.push(h);
    curBytes += sizeOf(h);
    if (curBytes >= client.batchBytes || cur.length >= client.batchChunks) {
      batches.push(cur);
      cur = [];
      curBytes = 0;
    }
  }
  if (cur.length) batches.push(cur);

  const runBatch = (batch) => {
    const pending = new Set(batch);
    return client.http.withRetry(async () => {
      const body = Buffer.concat([...pending].map((h) => Buffer.from(h, 'hex')));
      const res = await client.http.raw('POST', '/chunks/fetch', { body, headers: { 'content-type': 'application/octet-stream' }, signal });
      try {
        for await (const f of parseFrames(res)) {
          if (!pending.has(f.hash)) throw new QentimArtError(`server sent unexpected chunk ${f.hash}`);
          const raw = await decodeFrame(f);
          stats.downloadedWire += f.payload.length + 41;
          stats.downloadedRaw += raw.length;
          await onChunk(f.hash, raw);
          pending.delete(f.hash);
        }
      } catch (err) {
        // 出错时销毁响应流，避免未读完的 keep-alive socket 被复用（重试只补未收到的哈希）。
        res.destroy(err);
        throw err;
      }
      if (pending.size) throw new QentimArtError(`server stream ended with ${pending.size} chunk(s) missing`, { code: 'ETRUNCATED' });
    }, signal);
  };

  let i = 0;
  await Promise.all(
    Array.from({ length: Math.max(1, Math.min(client.concurrency, batches.length)) }, async () => {
      while (i < batches.length) {
        const b = batches[i++];
        await runBatch(b);
      }
    }),
  );
}

/**
 * 下游 QentimArt 客户端。两种模式：
 *   动态：new QentimArtClient({ server, project, token })  —— 走服务端 HTTP API
 *   静态：new QentimArtClient({ baseUrl })                 —— 从公开静态树只读拉取，无需鉴权
 */
export class QentimArtClient {
  constructor({
    baseUrl,
    server,
    project,
    token,
    concurrency = 8,
    batchBytes = 16 << 20,
    batchChunks = 2048,
    waveBytes = 512 << 20,
    waveFiles = 2048,
    timeoutMs = 60000,
    retries = 3,
  } = {}) {
    this.concurrency = concurrency;
    this.batchBytes = batchBytes;
    this.batchChunks = batchChunks;
    this.waveBytes = waveBytes;
    this.waveFiles = waveFiles;
    if (baseUrl) {
      this.mode = 'static';
      this.project = project ?? null;
      this.static = new StaticTransport({ baseUrl, concurrency, timeoutMs, retries });
    } else {
      if (!server || !project) throw new QentimArtError('server and project are required (or pass baseUrl for static mode)');
      this.mode = 'api';
      this.project = project;
      this.http = new HttpClient({ server, project, token, concurrency, timeoutMs, retries });
    }
  }

  /** 释放连接池。 */
  close() {
    this.http?.close();
    this.static?.close();
  }

  /** 列出版本（新到旧）以及 latest / latestPrerelease。 */
  listVersions() {
    if (this.mode === 'static') return this.static.index();
    return this.http.json('GET', '/versions');
  }

  /** 版本元数据；version 可为 "latest"。 */
  async getVersion(version = 'latest', { prerelease = false } = {}) {
    if (this.mode === 'static') return versionFromIndex(await this.static.index(), version, prerelease);
    return this.http.json('GET', `/versions/${encodeURIComponent(version)}${prerelease ? '?prerelease=1' : ''}`);
  }

  /** 两个版本之间的差异（文件列表 + 需新下载的字节）。 */
  async diff(from, to = 'latest') {
    if (this.mode === 'static') return diffManifests(await this.fetchManifest(from), await this.fetchManifest(to));
    return this.http.json('GET', `/versions/${encodeURIComponent(to)}/diff?from=${encodeURIComponent(from)}`);
  }

  /** 下载 manifest（gzip 字节）以及解析到的版本号。 */
  async fetchManifestRaw(version = 'latest', { prerelease = false } = {}) {
    if (this.mode === 'static') {
      const vi = await this.getVersion(version, { prerelease });
      const gz = await this.static.getBuffer(`versions/${vi.version}/manifest.json.gz`);
      return { version: vi.version, gz };
    }
    const { body, headers } = await this.http.buffer('GET', `/versions/${encodeURIComponent(version)}/manifest${prerelease ? '?prerelease=1' : ''}`);
    return { version: headers['x-qentimart-version'], gz: body };
  }

  /** 下载并解析 manifest。 */
  async fetchManifest(version = 'latest', opts) {
    const { gz } = await this.fetchManifestRaw(version, opts);
    return parseManifest(gz);
  }

  /** 内部：取一批块，每块解码校验后回调 onChunk(hash, raw)。 */
  fetchChunks(args) {
    if (this.mode === 'static') return this.static.fetchChunks(args);
    return apiFetchChunks(this, args);
  }

  /**
   * 把 dir 同步到某个版本。
   * @param {string} dir
   * @param {object} [opts] 见 index.d.ts 的 SyncOptions
   */
  sync(dir, opts = {}) {
    return runSync(this, dir, opts);
  }

  /** 预演：sync() 会做什么？ */
  plan(dir, opts = {}) {
    return runSync(this, dir, { ...opts, dryRun: true });
  }

  /** 相对上次同步版本的本地状态（无网络）。 */
  status(dir) {
    return runStatus(dir, this.project);
  }

  /** 重新计算当前版本每个文件的哈希。 */
  verify(dir, onProgress) {
    return runVerify(dir, onProgress);
  }

  /** 素材字节 vs. QentimArt 开销（.qentimart 目录）。 */
  static async storageReport(dir) {
    dir = path.resolve(dir);
    const { files } = await scanDir(dir);
    let assetBytes = 0;
    for (const f of files.values()) assetBytes += f.size;
    const overheadBytes = await dirBytes(path.join(dir, STATE_DIR));
    return { assetBytes, overheadBytes, ratio: assetBytes ? (assetBytes + overheadBytes) / assetBytes : 1 };
  }
}
